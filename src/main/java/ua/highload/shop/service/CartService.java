package ua.highload.shop.service;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Кошик — Redis hash {@code cart:<userId>} (productId → quantity).
 * Зміни атомарні на боці Redis (HINCRBY/HDEL), тож паралельні запити через різні інстанси не втрачають оновлень.
 */
@Service
public class CartService {

    private static final String PREFIX = "cart:";

    private final StringRedisTemplate redis;
    private final ProductService products;
    private final Duration ttl;

    public CartService(StringRedisTemplate redis, ProductService products,
                       @Value("${shop.cart-ttl:7d}") Duration ttl) {
        this.redis = redis;
        this.products = products;
        this.ttl = ttl;
    }

    public Map<Long, Integer> get(long userId) {
        var result = new TreeMap<Long, Integer>();
        redis.<String, String>opsForHash().entries(PREFIX + userId)
                .forEach((k, v) -> result.put(Long.parseLong(k), Integer.parseInt(v)));
        return result;
    }

    public Map<Long, Integer> add(long userId, long productId, int quantity) {
        products.get(productId);
        String key = PREFIX + userId;
        redis.opsForHash().increment(key, Long.toString(productId), quantity);
        redis.expire(key, ttl);
        return get(userId);
    }

    public Map<Long, Integer> remove(long userId, long productId) {
        redis.opsForHash().delete(PREFIX + userId, Long.toString(productId));
        return get(userId);
    }

    public void clear(long userId) {
        redis.delete(PREFIX + userId);
    }
}
