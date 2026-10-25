package ua.highload.shop.cache;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Динамічна конфігурація механізмів стійкості кешу (Redis hash {@code shop:flags}), яку всі інстанси
 * перечитують щосекунди. Дозволяє перемикати захист під навантаженням без рестарту (і без втрати JIT-прогріву).
 * Значення за замовчуванням — з application.yml; при недоступності Redis лишаються останні відомі.
 */
@Component
public class FeatureFlags {

    public static final String KEY = "shop:flags";
    private static final Logger log = LoggerFactory.getLogger(FeatureFlags.class);

    private final StringRedisTemplate redis;
    private final StampedeMode defaultMode;
    private final boolean defaultJitter;
    private final boolean defaultNegative;
    private final boolean defaultBloom;

    private volatile StampedeMode stampedeMode;
    private volatile boolean ttlJitter;
    private volatile boolean negativeCache;
    private volatile boolean bloom;
    private volatile long productTtlSeconds;
    private volatile boolean historyBatch;

    public FeatureFlags(StringRedisTemplate redis,
                        @Value("${shop.cache.stampede:LOCK_SINGLE_FLIGHT}") StampedeMode defaultMode,
                        @Value("${shop.cache.ttl-jitter:true}") boolean defaultJitter,
                        @Value("${shop.cache.negative:true}") boolean defaultNegative,
                        @Value("${shop.cache.bloom:true}") boolean defaultBloom) {
        this.redis = redis;
        this.defaultMode = defaultMode;
        this.defaultJitter = defaultJitter;
        this.defaultNegative = defaultNegative;
        this.defaultBloom = defaultBloom;
        apply(Map.of());
    }

    @Scheduled(fixedDelay = 1000)
    public void refresh() {
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(KEY);
            apply(raw);
        } catch (DataAccessException e) {
            // залишаємо останні відомі значення
        }
    }

    private void apply(Map<Object, Object> raw) {
        StampedeMode mode = parse(raw.get("stampede"), defaultMode);
        if (mode != stampedeMode && stampedeMode != null) {
            log.info("Stampede protection: {} -> {}", stampedeMode, mode);
        }
        stampedeMode = mode;
        ttlJitter = bool(raw.get("ttlJitter"), defaultJitter);
        negativeCache = bool(raw.get("negative"), defaultNegative);
        bloom = bool(raw.get("bloom"), defaultBloom);
        productTtlSeconds = raw.get("productTtlSeconds") == null ? 0 : Long.parseLong(raw.get("productTtlSeconds").toString());
        historyBatch = bool(raw.get("historyBatch"), true);
    }

    private static StampedeMode parse(Object v, StampedeMode def) {
        try {
            return v == null ? def : StampedeMode.valueOf(v.toString());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    private static boolean bool(Object v, boolean def) {
        return v == null ? def : Boolean.parseBoolean(v.toString());
    }

    public StampedeMode stampedeMode() {
        return stampedeMode;
    }

    public boolean ttlJitter() {
        return ttlJitter;
    }

    public boolean negativeCache() {
        return negativeCache;
    }

    public boolean bloom() {
        return bloom;
    }

    /** ЛР9: історія замовлень — позиції одним batch-запитом (true) або N+1 (false, для A/B-порівняння). */
    public boolean historyBatch() {
        return historyBatch;
    }

    /** Перевизначення TTL карток товарів (для експерименту з Cache Avalanche); 0 — значення з конфігурації. */
    public long productTtlSeconds() {
        return productTtlSeconds;
    }
}
