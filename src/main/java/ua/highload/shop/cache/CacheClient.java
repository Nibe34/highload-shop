package ua.highload.shop.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cache-Aside поверх Redis з graceful degradation.
 * <p>
 * Будь-яка помилка Redis не ламає запит: дані читаються напряму з БД (BYPASS), а кеш
 * «відкривається» (simple circuit breaker) на {@code shop.cache.breaker-open} — щоб не платити
 * таймаут Redis на кожному запиті, поки він недоступний.
 */
@Component
public class CacheClient {

    private static final Logger log = LoggerFactory.getLogger(CacheClient.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean enabled;
    private final long breakerOpenMs;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;
    private final Counter invalidations;
    private final Counter negativeHits;
    private final StampedeGuard guard;
    private final FeatureFlags flags;
    private final Duration negativeTtl;

    /** Маркер «сутності не існує» для Negative Caching. */
    static final String NULL_MARKER = "__null__";
    private static final double JITTER = 0.2;

    /** Момент, до якого кеш вважається недоступним. Ephemeral-стан: втрата при рестарті безпечна. */
    private volatile long openUntil;

    public CacheClient(StringRedisTemplate redis, ObjectMapper json, MeterRegistry meters,
                       StampedeGuard guard, FeatureFlags flags,
                       @Value("${shop.cache.enabled:true}") boolean enabled,
                       @Value("${shop.cache.breaker-open:5s}") Duration breakerOpen,
                       @Value("${shop.cache.negative-ttl:30s}") Duration negativeTtl) {
        this.redis = redis;
        this.json = json;
        this.enabled = enabled;
        this.breakerOpenMs = breakerOpen.toMillis();
        this.hits = meters.counter("shop.cache", "result", "hit");
        this.misses = meters.counter("shop.cache", "result", "miss");
        this.errors = meters.counter("shop.cache", "result", "error");
        this.invalidations = meters.counter("shop.cache", "result", "invalidation");
        this.negativeHits = meters.counter("shop.cache", "result", "negative-hit");
        this.guard = guard;
        this.flags = flags;
        this.negativeTtl = negativeTtl;
    }

    public <T> Cached<T> getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        return getOrLoad(key, json.constructType(type), ttl, loader);
    }

    /**
     * Cache-Aside із захистом від Stampede (координація перерахунку — {@link StampedeGuard}).
     * Loader може повернути {@code null} — «сутності не існує»; тоді (якщо увімкнено negative caching)
     * у кеш пишеться маркер на короткий TTL, і результат повертається як {@code Cached(null, …)}.
     */
    public <T> Cached<T> getOrLoad(String key, JavaType type, Duration ttl, Supplier<T> loader) {
        if (!available()) {
            return new Cached<>(loader.get(), CacheStatus.BYPASS);
        }
        Optional<T> cached;
        try {
            cached = read(key, type);
        } catch (DataAccessException e) {
            trip(e);
            return new Cached<>(loader.get(), CacheStatus.BYPASS);
        }
        if (cached != null) {
            hits.increment();
            return new Cached<>(cached.orElse(null), CacheStatus.HIT);
        }
        misses.increment();
        StampedeGuard.Result<Optional<T>> r = guard.load(key, () -> readQuietly(key, type), () -> {
            T value = loader.get();
            if (value != null) {
                put(key, value, ttl);
            } else if (flags.negativeCache()) {
                putRaw(key, NULL_MARKER, negativeTtl);
            }
            return Optional.ofNullable(value);
        });
        return new Cached<>(r.value().orElse(null), r.coalesced() ? CacheStatus.COALESCED : CacheStatus.MISS);
    }

    /** null — ключа немає; Optional.empty() — negative-маркер; Optional.of(v) — значення. */
    private <T> Optional<T> read(String key, JavaType type) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            return null;
        }
        if (NULL_MARKER.equals(raw)) {
            negativeHits.increment();
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(raw, type));
        } catch (JsonProcessingException e) {
            log.warn("Corrupted cache entry {}, reloading", key);
            return null;
        }
    }

    private <T> Optional<T> readQuietly(String key, JavaType type) {
        try {
            return read(key, type);
        } catch (DataAccessException e) {
            return null;
        }
    }

    public void put(String key, Object value, Duration ttl) {
        try {
            putRaw(key, json.writeValueAsString(value), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize cache value for " + key, e);
        }
    }

    private void putRaw(String key, String raw, Duration ttl) {
        if (!available()) {
            return;
        }
        try {
            redis.opsForValue().set(key, raw, jittered(ttl));
        } catch (DataAccessException e) {
            trip(e);
        }
    }

    /** TTL Jitter: TTL = base × (1 ± 20 %) — ключі, записані одночасно, вигасають у різний час (захист від Avalanche). */
    Duration jittered(Duration ttl) {
        if (!flags.ttlJitter()) {
            return ttl;
        }
        double factor = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * JITTER;
        return Duration.ofMillis(Math.max(1, (long) (ttl.toMillis() * factor)));
    }

    public void evict(Collection<String> keys) {
        if (keys.isEmpty() || !enabled) {
            return;
        }
        try {
            redis.delete(keys);
            invalidations.increment(keys.size());
        } catch (DataAccessException e) {
            // Не вдалося інвалідувати: запис застаріє не довше за TTL. Логуємо як ризик консистентності.
            log.warn("Cache eviction failed for {}: {}", keys, e.getMessage());
            trip(e);
        }
    }

    /** Атомарний INCR версії простору ключів (для інвалідації групи ключів, напр. сторінок каталогу). */
    public long bumpVersion(String versionKey) {
        if (!available()) {
            return 0;
        }
        try {
            Long v = redis.opsForValue().increment(versionKey);
            invalidations.increment();
            return v == null ? 0 : v;
        } catch (DataAccessException e) {
            trip(e);
            return 0;
        }
    }

    public long version(String versionKey) {
        if (!available()) {
            return 0;
        }
        try {
            String v = redis.opsForValue().get(versionKey);
            return v == null ? 0 : Long.parseLong(v);
        } catch (DataAccessException e) {
            trip(e);
            return 0;
        }
    }

    /**
     * «Write fence»: позначка, що дані нещодавно змінено на Primary і Replica може ще їх не мати.
     * Поки позначка жива (TTL ≥ очікуваного лагу реплікації), читання маршрутизуються на Primary.
     */
    public void fence(String key, Duration ttl) {
        try {
            redis.opsForValue().set(key, "1", ttl);
        } catch (DataAccessException e) {
            trip(e);
        }
    }

    /**
     * Чи діє fence. Якщо Redis недоступний — вважаємо, що діє (безпечніше читати з Primary).
     * Fence — механізм консистентності, а не кешування, тому від shop.cache.enabled не залежить.
     */
    public boolean fenced(String key) {
        if (System.currentTimeMillis() < openUntil) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(key));
        } catch (DataAccessException e) {
            trip(e);
            return true;
        }
    }

    public boolean available() {
        return enabled && System.currentTimeMillis() >= openUntil;
    }

    private void trip(DataAccessException e) {
        errors.increment();
        openUntil = System.currentTimeMillis() + breakerOpenMs;
        log.warn("Redis unavailable, cache bypassed for {} ms: {}", breakerOpenMs, e.getMessage());
    }
}
