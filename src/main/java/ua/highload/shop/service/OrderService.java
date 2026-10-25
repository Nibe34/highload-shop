package ua.highload.shop.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ua.highload.shop.cache.CacheClient;
import ua.highload.shop.cache.CacheKeys;
import ua.highload.shop.domain.ConflictException;
import ua.highload.shop.domain.NotFoundException;
import ua.highload.shop.domain.Order;
import ua.highload.shop.domain.OrderItem;
import ua.highload.shop.domain.Product;
import ua.highload.shop.repo.OrderRepository;
import ua.highload.shop.repo.ProductRepository;
import ua.highload.shop.shard.Shard;
import ua.highload.shop.shard.ShardRouter;

/**
 * Замовлення шардовані за user_id; склад (products.stock) — у БД каталогу.
 * Checkout зачіпає дві різні БД, тому одна ACID-транзакція неможлива — використовується Saga:
 * <ol>
 *   <li>T1 (каталог): блокування рядків товарів, перевірка й резервування (списання) залишку — COMMIT;</li>
 *   <li>T2 (шард користувача): запис замовлення та позицій — COMMIT;</li>
 *   <li>C1 (компенсація T1): якщо T2 не вдалася — повернення залишку на склад.</li>
 * </ol>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final ProductRepository products;
    private final StatsService stats;
    private final ProductService productService;
    private final CacheClient cache;
    private final ShardRouter router;
    private final TransactionTemplate catalogTx;
    private final Duration fenceTtl;

    public OrderService(OrderRepository orders, ProductRepository products, StatsService stats,
                        ProductService productService, CacheClient cache, ShardRouter router,
                        TransactionTemplate catalogTx, @Value("${shop.replica.fence-ttl:5s}") Duration fenceTtl) {
        this.orders = orders;
        this.products = products;
        this.stats = stats;
        this.productService = productService;
        this.cache = cache;
        this.router = router;
        this.catalogTx = catalogTx;
        this.fenceTtl = fenceTtl;
    }

    public Order checkout(long userId, Map<Long, Integer> requested) {
        if (requested.isEmpty()) {
            throw new ConflictException("Order has no items");
        }
        var quantities = new TreeMap<>(requested);
        Shard shard = router.forUser(userId);

        // T1: резервування складу в каталозі (блокування в детермінованому порядку — без deadlock'ів)
        List<OrderItem> items = catalogTx.execute(status -> reserveStock(quantities));
        long total = items.stream().mapToLong(i -> i.priceCents() * i.quantity()).sum();
        afterStockChanged(quantities);

        // T2: запис замовлення на шард користувача
        Order order;
        try {
            order = shard.tx().execute(status -> orders.insert(shard, userId, "CREATED", total, items));
        } catch (RuntimeException e) {
            // C1: компенсуюча транзакція — повертаємо зарезервований залишок
            log.warn("Saga: order insert on shard {} failed, compensating stock reservation: {}", shard.name(), e.getMessage());
            catalogTx.executeWithoutResult(status -> quantities.forEach(products::incrementStock));
            afterStockChanged(quantities);
            throw e;
        }
        cache.fence(CacheKeys.userWriteFence(userId), fenceTtl);
        stats.orderCreated();
        return order;
    }

    private List<OrderItem> reserveStock(TreeMap<Long, Integer> quantities) {
        Map<Long, Product> locked = products.lockByIds(quantities.keySet()).stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));
        var items = new ArrayList<OrderItem>(quantities.size());
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
        }
        return items;
    }

    private void afterStockChanged(Map<Long, Integer> quantities) {
        productService.evictProducts(quantities.keySet());
    }

    /** Деталі замовлення: shard-local запит на шард власника (id замовлення + user_id). */
    public Order get(long userId, long id) {
        Shard shard = router.forUser(userId);
        Order order = orders.findById(shard, id, userId)
                .orElseThrow(() -> new NotFoundException("Order " + id + " not found"));
        return order.withItems(orders.findItems(shard, id));
    }

    /** Історія замовлень — shard-local (усі замовлення користувача на одному шарді). */
    public List<Order> byUser(long userId, int limit) {
        Shard shard = router.forUser(userId);
        return orders.findByUser(shard, userId, limit).stream()
                .map(o -> o.withItems(orders.findItems(shard, o.id())))
                .toList();
    }
}
