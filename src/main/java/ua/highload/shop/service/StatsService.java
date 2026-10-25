package ua.highload.shop.service;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ua.highload.shop.cache.CacheClient;
import ua.highload.shop.cache.CacheKeys;
import ua.highload.shop.cache.Cached;
import ua.highload.shop.db.DbRouting;
import ua.highload.shop.domain.TopProduct;
import ua.highload.shop.repo.OrderRepository;

@Service
public class StatsService {

    /** Глобальний лічильник оформлених замовлень (Redis INCR — атомарний для всіх інстансів). */
    private static final String ORDERS_CREATED = "stats:orders-created";

    private final OrderRepository orders;
    private final StringRedisTemplate redis;
    private final CacheClient cache;
    private final JavaType topList;
    private final Duration topTtl;

    public StatsService(OrderRepository orders, StringRedisTemplate redis, CacheClient cache, ObjectMapper json,
                        @Value("${shop.cache.ttl.top-products:30s}") Duration topTtl) {
        this.orders = orders;
        this.redis = redis;
        this.cache = cache;
        this.topList = json.getTypeFactory().constructCollectionType(List.class, TopProduct.class);
        this.topTtl = topTtl;
    }

    /** Вимірювальний лічильник: недоступність Redis не повинна ламати вже закомічене замовлення. */
    public void orderCreated() {
        try {
            redis.opsForValue().increment(ORDERS_CREATED);
        } catch (org.springframework.dao.DataAccessException e) {
            // лічильник пропускає інкремент — допустима втрата для статистики
        }
    }

    public long ordersCreated() {
        String value = redis.opsForValue().get(ORDERS_CREATED);
        return value == null ? 0 : Long.parseLong(value);
    }

    /** Аналітика допускає застарівання до TTL (30 с), тому явна інвалідація при нових замовленнях не робиться. */
    public Cached<List<TopProduct>> topProducts(int limit) {
        return cache.getOrLoad(CacheKeys.topProducts(limit), topList, topTtl, () -> DbRouting.replica(() -> orders.topProducts(limit)));
    }
}
