package ua.highload.shop.shard;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ua.highload.shop.cache.CacheClient;
import ua.highload.shop.db.DbRouting;
import ua.highload.shop.domain.NotFoundException;
import ua.highload.shop.repo.UserRepository;

/**
 * Детермінований Shard Router (Application-Level Sharding). Ключ шардингу — user_id:
 * усі запити одного користувача (історія, деталі замовлення, checkout) потрапляють на один шард.
 */
@Component
public class ShardRouter {

    public static final int BUCKETS = 1024;
    private static final Logger log = LoggerFactory.getLogger(ShardRouter.class);

    private final ShardRegistry registry;
    private final ShardStrategy strategy;
    private final JdbcTemplate catalog;
    private final UserRepository users;
    private final CacheClient cache;
    /** Кеш відображення bucket → shard; оновлюється з БД каталогу кожні 5 с (не бізнес-стан, а копія конфігурації). */
    private volatile String[] bucketMap;

    public ShardRouter(ShardRegistry registry, @Value("${shop.shards.strategy:BUCKETS}") ShardStrategy strategy,
                       JdbcTemplate catalog, UserRepository users, CacheClient cache) {
        this.registry = registry;
        this.strategy = strategy;
        this.catalog = catalog;
        this.users = users;
        this.cache = cache;
        log.info("Shard strategy: {}, shards: {}", strategy, registry.names());
    }

    public ShardStrategy strategy() {
        return strategy;
    }

    public Shard forUser(long userId) {
        return registry.get(shardName(userId, strategy));
    }

    public String shardName(long userId, ShardStrategy s) {
        List<String> names = registry.names();
        return switch (s) {
            case MODULO -> names.get((int) (userId % names.size()));
            case HASH -> names.get((int) Long.remainderUnsigned(mix(userId), names.size()));
            case BUCKETS -> bucketMap()[bucket(userId)];
            case REGION -> regionShard(userId, names);
        };
    }

    public static int bucket(long userId) {
        return (int) Long.remainderUnsigned(mix(userId), BUCKETS);
    }

    /** MurmurHash3 fmix64 — дешевий хеш з лавинним ефектом: сусідні id потрапляють у різні бакети. */
    public static long mix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    private String regionShard(long userId, List<String> names) {
        String region = cache.getOrLoad("shop:user-region:v1:" + userId, String.class, Duration.ofHours(1),
                () -> DbRouting.replica(() -> users.findById(userId))
                        .orElseThrow(() -> new NotFoundException("User " + userId + " not found")).region()).value();
        // Kyiv — 80 % користувачів → перший шард стає «гарячим»
        return switch (region) {
            case "Kyiv" -> names.get(0);
            default -> names.get(names.size() > 1 ? 1 : 0);
        };
    }

    private String[] bucketMap() {
        String[] map = bucketMap;
        if (map == null) {
            refreshBuckets();
            map = bucketMap;
        }
        return map;
    }

    @Scheduled(fixedDelay = 5000)
    public void refreshBuckets() {
        String[] map = new String[BUCKETS];
        DbRouting.primary(() -> {
            catalog.query("SELECT bucket, shard FROM shard_buckets", rs -> {
                map[rs.getInt(1)] = rs.getString(2);
            });
            return null;
        });
        for (int b = 0; b < BUCKETS; b++) {
            if (map[b] == null || !registry.names().contains(map[b])) {
                // бакет вказує на неіснуючий шард — фолбек на перший, щоб не втратити запис; логуємо як помилку конфігурації
                log.error("Bucket {} mapped to unknown shard '{}'", b, map[b]);
                map[b] = registry.names().get(0);
            }
        }
        bucketMap = map;
    }

    /** Кількість бакетів на кожному шарді (для діагностики). */
    public Map<String, Integer> bucketsPerShard() {
        var result = new LinkedHashMap<String, Integer>();
        registry.names().forEach(n -> result.put(n, 0));
        for (String s : bucketMap()) {
            result.merge(s, 1, Integer::sum);
        }
        return result;
    }
}
