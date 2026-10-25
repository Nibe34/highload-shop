package ua.highload.shop.service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ua.highload.shop.cache.CacheClient;
import ua.highload.shop.cache.CacheKeys;
import ua.highload.shop.db.DbNode;
import ua.highload.shop.db.DbRouting;
import ua.highload.shop.domain.ConflictException;
import ua.highload.shop.domain.NotFoundException;
import ua.highload.shop.domain.Order;
import ua.highload.shop.domain.OrderItem;
import ua.highload.shop.domain.Product;
import ua.highload.shop.repo.OrderRepository;
import ua.highload.shop.repo.ProductRepository;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final ProductRepository products;
    private final StatsService stats;
    private final ProductService productService;
    private final CacheClient cache;
    private final java.time.Duration fenceTtl;

    public OrderService(OrderRepository orders, ProductRepository products, StatsService stats,
                        ProductService productService, CacheClient cache,
                        @org.springframework.beans.factory.annotation.Value("${shop.replica.fence-ttl:5s}")
                        java.time.Duration fenceTtl) {
        this.orders = orders;
        this.products = products;
        this.stats = stats;
        this.productService = productService;
        this.cache = cache;
        this.fenceTtl = fenceTtl;
    }

    /** Режим читання історії замовлень. */
    public enum ReadConsistency {
        /** Read-Your-Own-Writes: Replica, але Primary протягом fence-ttl після власного запису. */
        RYW,
        /** Завжди Replica (може повернути застарілі дані) — для демонстрації Stale Read. */
        EVENTUAL,
        /** Завжди Primary. */
        STRONG
    }

    /**
     * Оформлення замовлення: блокування рядків товарів, перевірка залишків,
     * списання складу та запис замовлення — в одній транзакції.
     */
    @Transactional
    public Order checkout(long userId, Map<Long, Integer> requested) {
        if (requested.isEmpty()) {
            throw new ConflictException("Order has no items");
        }
        var quantities = new TreeMap<>(requested);
        Map<Long, Product> locked = products.lockByIds(quantities.keySet()).stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));

        long total = 0;
        var items = new java.util.ArrayList<OrderItem>(quantities.size());
        for (var entry : quantities.entrySet()) {
            Product p = locked.get(entry.getKey());
            if (p == null) {
                throw new NotFoundException("Product " + entry.getKey() + " not found");
            }
            int qty = entry.getValue();
            if (p.stock() < qty) {
                throw new ConflictException("Insufficient stock for product " + p.id());
            }
            products.decrementStock(p.id(), qty);
            items.add(new OrderItem(p.id(), qty, p.priceCents()));
            total += p.priceCents() * qty;
        }
        Order order = orders.insert(userId, "CREATED", total, items);
        // Інвалідація кешу карток товарів (змінився stock) — лише після успішного COMMIT.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.fence(CacheKeys.userWriteFence(userId), fenceTtl);
                productService.evictProducts(quantities.keySet());
                stats.orderCreated();
            }
        });
        return order;
    }

    /** Підтвердження замовлення — Explicit Primary Routing (Strictly Consistent Read). */
    public Order get(long id) {
        return DbRouting.primary(() -> {
            Order order = orders.findById(id).orElseThrow(() -> new NotFoundException("Order " + id + " not found"));
            return order.withItems(orders.findItems(id));
        });
    }

    public List<Order> byUser(long userId, int limit, ReadConsistency consistency) {
        DbNode node = switch (consistency) {
            case STRONG -> DbNode.PRIMARY;
            case EVENTUAL -> DbNode.REPLICA;
            case RYW -> cache.fenced(CacheKeys.userWriteFence(userId)) ? DbNode.PRIMARY : DbNode.REPLICA;
        };
        return DbRouting.on(node, () -> orders.findByUser(userId, limit).stream()
                .map(o -> o.withItems(orders.findItems(o.id())))
                .toList());
    }
}
