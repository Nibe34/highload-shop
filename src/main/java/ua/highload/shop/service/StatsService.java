package ua.highload.shop.service;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ua.highload.shop.domain.TopProduct;
import ua.highload.shop.repo.OrderRepository;

@Service
public class StatsService {

    /** Глобальний лічильник оформлених замовлень (Redis INCR — атомарний для всіх інстансів). */
    private static final String ORDERS_CREATED = "stats:orders-created";

    private final OrderRepository orders;
    private final StringRedisTemplate redis;

    public StatsService(OrderRepository orders, StringRedisTemplate redis) {
        this.orders = orders;
        this.redis = redis;
    }

    public void orderCreated() {
        redis.opsForValue().increment(ORDERS_CREATED);
    }

    public long ordersCreated() {
        String value = redis.opsForValue().get(ORDERS_CREATED);
        return value == null ? 0 : Long.parseLong(value);
    }

    public List<TopProduct> topProducts(int limit) {
        return orders.topProducts(limit);
    }
}
