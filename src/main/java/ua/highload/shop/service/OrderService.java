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

    public OrderService(OrderRepository orders, ProductRepository products, StatsService stats,
                        ProductService productService) {
        this.orders = orders;
        this.products = products;
        this.stats = stats;
        this.productService = productService;
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
                productService.evictProducts(quantities.keySet());
                stats.orderCreated();
            }
        });
        return order;
    }

    public Order get(long id) {
        Order order = orders.findById(id).orElseThrow(() -> new NotFoundException("Order " + id + " not found"));
        return order.withItems(orders.findItems(id));
    }

    public List<Order> byUser(long userId, int limit) {
        return orders.findByUser(userId, limit).stream()
                .map(o -> o.withItems(orders.findItems(o.id())))
                .toList();
    }
}
