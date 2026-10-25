package ua.highload.shop.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ua.highload.shop.db.DbRouting;
import ua.highload.shop.shard.ShardRouter;

/**
 * Bloom Filter ідентифікаторів товарів у Redis-бітмапі (спільний для всіх інстансів):
 * m = 2^20 біт (128 KB), k = 5 хеш-функцій. Для 10k товарів ймовірність хибнопозитивної відповіді ≈ 2·10⁻⁷.
 * «Ні» — гарантовано немає (запит не йде ні в кеш, ні в БД); «можливо так» — звичайний шлях.
 */
@Component
public class ProductBloomFilter {

    public static final String KEY = "shop:bloom:products:v1";
    private static final Logger log = LoggerFactory.getLogger(ProductBloomFilter.class);
    private static final long M = 1L << 20;
    private static final int K = 5;
    /** Повертає 1, якщо всі k біт встановлені; 0 — якщо хоч один нульовий; -1 — фільтр ще не побудований. */
    private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
            for i = 1, #ARGV do
              if redis.call('GETBIT', KEYS[1], ARGV[i]) == 0 then return 0 end
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public ProductBloomFilter(StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    /**
     * false — товару гарантовано не існує. При будь-якій проблемі (Redis недоступний, фільтр не побудований) — true
     * (fail-open: фільтр лише оптимізація, він не має права відхилити існуючий товар).
     * Якщо бітмапу втрачено (рестарт Redis без AOF, FLUSH) — запускається фонова перебудова.
     */
    public boolean mightContain(long productId) {
        try {
            Long r = redis.execute(CHECK, List.of(KEY), (Object[]) positions(productId));
            if (r != null && r == -1) {
                CompletableFuture.runAsync(this::ensureBuilt);
            }
            return r == null || r != 0;
        } catch (DataAccessException e) {
            return true;
        }
    }

    /** Будує фільтр, якщо його немає. Будує лише один інстанс (SET NX lock), решта пропускають. */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureBuilt() {
        try {
            if (Boolean.TRUE.equals(redis.hasKey(KEY))
                    || !Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("lock:bloom-build", "1", Duration.ofSeconds(30)))) {
                return;
            }
            try {
                rebuild(DbRouting.replica(() -> jdbc.queryForList("SELECT id FROM products", Long.class)));
            } finally {
                redis.delete("lock:bloom-build");
            }
        } catch (RuntimeException e) {
            log.warn("Bloom filter build skipped: {}", e.getMessage());
        }
    }

    public void add(long productId) {
        try {
            redis.executePipelined((RedisCallback<Object>) c -> {
                for (String p : positions(productId)) {
                    ((StringRedisConnection) c).setBit(KEY, Long.parseLong(p), true);
                }
                return null;
            });
        } catch (DataAccessException e) {
            log.warn("Bloom add failed for {}: {}", productId, e.getMessage());
        }
    }

    /** Повна перебудова з переліку всіх id (видалені товари з фільтра прибрати неможливо — лише перебудовою). */
    public void rebuild(List<Long> ids) {
        String tmp = KEY + ":building";
        redis.delete(tmp);
        for (int from = 0; from < ids.size(); from += 2000) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + 2000));
            redis.executePipelined((RedisCallback<Object>) c -> {
                StringRedisConnection sc = (StringRedisConnection) c;
                for (long id : chunk) {
                    for (String p : positions(id)) {
                        sc.setBit(tmp, Long.parseLong(p), true);
                    }
                }
                return null;
            });
        }
        redis.rename(tmp, KEY);
        log.info("Bloom filter rebuilt: {} products", ids.size());
    }

    private static String[] positions(long id) {
        // double hashing: h_i = h1 + i·h2 (Kirsch–Mitzenmacher)
        long h1 = ShardRouter.mix(id);
        long h2 = ShardRouter.mix(h1 ^ 0x9E3779B97F4A7C15L) | 1;
        List<String> result = new ArrayList<>(K);
        for (int i = 0; i < K; i++) {
            result.add(Long.toString(Long.remainderUnsigned(h1 + i * h2, M)));
        }
        return result.toArray(String[]::new);
    }
}
