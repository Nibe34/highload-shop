package ua.highload.shop.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/** Кошик зберігається в пам'яті процесу (MVP). */
@Service
public class CartService {

    private final Map<Long, Map<Long, Integer>> carts = new ConcurrentHashMap<>();
    private final ProductService products;

    public CartService(ProductService products) {
        this.products = products;
    }

    public Map<Long, Integer> get(long userId) {
        return Map.copyOf(carts.getOrDefault(userId, Map.of()));
    }

    public Map<Long, Integer> add(long userId, long productId, int quantity) {
        products.get(productId);
        carts.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).merge(productId, quantity, Integer::sum);
        return get(userId);
    }

    public Map<Long, Integer> remove(long userId, long productId) {
        carts.computeIfPresent(userId, (k, cart) -> {
            cart.remove(productId);
            return cart;
        });
        return get(userId);
    }

    public void clear(long userId) {
        carts.remove(userId);
    }
}
