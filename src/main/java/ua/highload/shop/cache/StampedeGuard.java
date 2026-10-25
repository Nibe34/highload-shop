package ua.highload.shop.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Координація перерахунку при Cache Miss — захист від Cache Stampede / Thundering Herd.
 * <ul>
 *   <li><b>Single-flight</b>: у межах інстансу всі паралельні MISS одного ключа чекають на один Future.</li>
 *   <li><b>Distributed lock</b>: {@code SET lock:<key> <token> NX PX lockTtl}. Власник перераховує й пише в кеш,
 *       інші опитують кеш з backoff до {@code waitTimeout}. Зняття блокування — Lua compare-and-delete
 *       (знімає лише власник: якщо lock вже вигас і його взяв інший — чужий lock не видаляється).</li>
 * </ul>
 * Захист від deadlock: lock завжди має TTL; якщо власник упав — lock вигасає. Якщо очікування вичерпано —
 * запит перераховує сам (деградація до незахищеного режиму, а не помилка).
 */
@Component
public class StampedeGuard {

    private static final Logger log = LoggerFactory.getLogger(StampedeGuard.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redis;
    private final FeatureFlags flags;
    private final Duration lockTtl;
    private final Duration waitTimeout;
    private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    private final Counter loads;
    private final Counter coalesced;
    private final Counter lockTimeouts;

    public StampedeGuard(StringRedisTemplate redis, FeatureFlags flags, MeterRegistry meters,
                         @Value("${shop.cache.lock-ttl:5s}") Duration lockTtl,
                         @Value("${shop.cache.lock-wait:3s}") Duration waitTimeout) {
        this.redis = redis;
        this.flags = flags;
        this.lockTtl = lockTtl;
        this.waitTimeout = waitTimeout;
        this.loads = meters.counter("shop.cache.loads");
        this.coalesced = meters.counter("shop.cache.coalesced");
        this.lockTimeouts = meters.counter("shop.cache.lock.timeouts");
    }

    /**
     * @param recheck   повторне читання кешу (значення або null)
     * @param loadAndPut перерахунок із БД і запис у кеш
     */
    @SuppressWarnings("unchecked")
    public <T> Result<T> load(String key, Supplier<T> recheck, Supplier<T> loadAndPut) {
        StampedeMode mode = flags.stampedeMode();
        boolean singleFlight = mode == StampedeMode.SINGLE_FLIGHT || mode == StampedeMode.LOCK_SINGLE_FLIGHT;
        boolean lock = mode == StampedeMode.LOCK || mode == StampedeMode.LOCK_SINGLE_FLIGHT;
        if (!singleFlight) {
            return lock ? withLock(key, recheck, loadAndPut) : new Result<>(counted(loadAndPut), false);
        }
        var mine = new CompletableFuture<Object>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) {
            coalesced.increment();
            try {
                return new Result<>((T) existing.join(), true);
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException re ? re : e;
            }
        }
        try {
            Result<T> r = lock ? withLock(key, recheck, loadAndPut) : new Result<>(counted(loadAndPut), false);
            mine.complete(r.value());
            return r;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private <T> Result<T> withLock(String key, Supplier<T> recheck, Supplier<T> loadAndPut) {
        String lockKey = "lock:" + key;
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(lockKey, token, lockTtl);
        } catch (DataAccessException e) {
            return new Result<>(counted(loadAndPut), false); // Redis недоступний — без координації
        }
        if (Boolean.TRUE.equals(acquired)) {
            try {
                T fresh = recheck.get(); // поки брали lock, інший власник міг уже заповнити кеш
                return fresh != null ? new Result<>(fresh, true) : new Result<>(counted(loadAndPut), false);
            } finally {
                try {
                    redis.execute(RELEASE, List.of(lockKey), token);
                } catch (DataAccessException e) {
                    // lock вигасне сам за lockTtl
                }
            }
        }
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        long sleep = 10;
        while (System.nanoTime() < deadline) {
            sleepQuietly(sleep);
            T value = recheck.get();
            if (value != null) {
                coalesced.increment();
                return new Result<>(value, true);
            }
            sleep = Math.min(sleep * 2, 100);
        }
        lockTimeouts.increment();
        log.warn("Stampede lock wait timeout for {} — loading without coordination", key);
        return new Result<>(counted(loadAndPut), false);
    }

    private <T> T counted(Supplier<T> loadAndPut) {
        loads.increment();
        return loadAndPut.get();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** value + чи було отримано значення, обчислене іншим запитом (coalesced). */
    public record Result<T>(T value, boolean coalesced) {
    }
}
