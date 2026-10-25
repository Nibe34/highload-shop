package ua.highload.shop.cache;

import java.time.Duration;
import java.util.Collection;
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

    /** Момент, до якого кеш вважається недоступним. Ephemeral-стан: втрата при рестарті безпечна. */
    private volatile long openUntil;

    public CacheClient(StringRedisTemplate redis, ObjectMapper json, MeterRegistry meters,
                       @Value("${shop.cache.enabled:true}") boolean enabled,
                       @Value("${shop.cache.breaker-open:5s}") Duration breakerOpen) {
        this.redis = redis;
        this.json = json;
        this.enabled = enabled;
        this.breakerOpenMs = breakerOpen.toMillis();
        this.hits = meters.counter("shop.cache", "result", "hit");
        this.misses = meters.counter("shop.cache", "result", "miss");
        this.errors = meters.counter("shop.cache", "result", "error");
        this.invalidations = meters.counter("shop.cache", "result", "invalidation");
    }

    public <T> Cached<T> getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        return getOrLoad(key, json.constructType(type), ttl, loader);
    }

    public <T> Cached<T> getOrLoad(String key, JavaType type, Duration ttl, Supplier<T> loader) {
        if (!available()) {
            return new Cached<>(loader.get(), CacheStatus.BYPASS);
        }
        String raw;
        try {
            raw = redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            trip(e);
            return new Cached<>(loader.get(), CacheStatus.BYPASS);
        }
        if (raw != null) {
            try {
                T value = json.readValue(raw, type);
                hits.increment();
                return new Cached<>(value, CacheStatus.HIT);
            } catch (JsonProcessingException e) {
                log.warn("Corrupted cache entry {}, reloading", key);
            }
        }
        misses.increment();
        T value = loader.get();
        put(key, value, ttl);
        return new Cached<>(value, CacheStatus.MISS);
    }

    public void put(String key, Object value, Duration ttl) {
        if (!available()) {
            return;
        }
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
        } catch (DataAccessException e) {
            trip(e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize cache value for " + key, e);
        }
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

    public boolean available() {
        return enabled && System.currentTimeMillis() >= openUntil;
    }

    private void trip(DataAccessException e) {
        errors.increment();
        openUntil = System.currentTimeMillis() + breakerOpenMs;
        log.warn("Redis unavailable, cache bypassed for {} ms: {}", breakerOpenMs, e.getMessage());
    }
}
