package ua.highload.shop.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ua.highload.shop.cache.CacheClient;
import ua.highload.shop.cache.CacheKeys;
import ua.highload.shop.cache.Cached;
import ua.highload.shop.db.DbRouting;
import ua.highload.shop.domain.Product;
import ua.highload.shop.domain.TopProduct;
import ua.highload.shop.repo.OrderRepository;
import ua.highload.shop.repo.OrderRepository.ProductSales;
import ua.highload.shop.repo.OrderRepository.SalesSummary;
import ua.highload.shop.repo.ProductRepository;
import ua.highload.shop.shard.Shard;
import ua.highload.shop.shard.ShardRegistry;

@Service
public class StatsService {

    /** Глобальний лічильник оформлених замовлень (Redis INCR — атомарний для всіх інстансів). */
    private static final String ORDERS_CREATED = "stats:orders-created";

    private final OrderRepository orders;
    private final ProductRepository products;
    private final ShardRegistry shards;
    private final StringRedisTemplate redis;
    private final CacheClient cache;
    private final JavaType topList;
    private final Duration topTtl;
    /** Паралельний scatter-запит на всі шарди (віртуальні потоки — дешеві для I/O-очікування). */
    private final ExecutorService fanOut = Executors.newVirtualThreadPerTaskExecutor();

    public StatsService(OrderRepository orders, ProductRepository products, ShardRegistry shards,
                        StringRedisTemplate redis, CacheClient cache, ObjectMapper json,
                        @Value("${shop.cache.ttl.top-products:30s}") Duration topTtl) {
        this.orders = orders;
        this.products = products;
        this.shards = shards;
        this.redis = redis;
        this.cache = cache;
        this.topList = json.getTypeFactory().constructCollectionType(List.class, TopProduct.class);
        this.topTtl = topTtl;
    }

    /** Вимірювальний лічильник: недоступність Redis не повинна ламати вже закомічене замовлення. */
    public void orderCreated() {
        try {
            redis.opsForValue().increment(ORDERS_CREATED);
        } catch (DataAccessException e) {
            // лічильник пропускає інкремент — допустима втрата для статистики
        }
    }

    public long ordersCreated() {
        String value = redis.opsForValue().get(ORDERS_CREATED);
        return value == null ? 0 : Long.parseLong(value);
    }

    /** Аналітика допускає застарівання до TTL (30 с), тому явна інвалідація при нових замовленнях не робиться. */
    public Cached<List<TopProduct>> topProducts(int limit) {
        return cache.getOrLoad(CacheKeys.topProducts(limit), topList, topTtl, () -> computeTopProducts(limit));
    }

    /**
     * Cross-shard Scatter-Gather: часткові агрегати з кожного шарду паралельно (scatter),
     * злиття сум по product_id у застосунку (gather), назви товарів — з каталогу (Replica).
     */
    public List<TopProduct> computeTopProducts(int limit) {
        Map<Long, long[]> merged = new HashMap<>();
        for (List<ProductSales> part : scatter(orders::salesByProduct).values()) {
            for (ProductSales s : part) {
                long[] acc = merged.computeIfAbsent(s.productId(), k -> new long[2]);
                acc[0] += s.units();
                acc[1] += s.revenueCents();
            }
        }
        List<Map.Entry<Long, long[]>> top = merged.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Long, long[]>>comparingLong(e -> -e.getValue()[0])
                        .thenComparingLong(Map.Entry::getKey))
                .limit(limit)
                .toList();
        Map<Long, String> names = DbRouting.replica(() -> products.findByIds(top.stream().map(Map.Entry::getKey).toList()))
                .stream().collect(Collectors.toMap(Product::id, Product::name));
        return top.stream()
                .map(e -> new TopProduct(e.getKey(), names.getOrDefault(e.getKey(), "(deleted)"), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    /** Звіт продажів за період: fan-out на всі шарди і сумування. Повертає також внесок кожного шарду. */
    public Map<String, Object> sales(Instant from, Instant to) {
        Map<String, SalesSummary> parts = scatter(s -> orders.summary(s, from, to));
        long count = 0, revenue = 0, customers = 0;
        for (SalesSummary p : parts.values()) {
            count += p.orders();
            revenue += p.revenueCents();
            customers += p.customers(); // ключ шардування = user_id, тож множини покупців на шардах не перетинаються
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("orders", count);
        result.put("revenueCents", revenue);
        result.put("customers", customers);
        result.put("byShard", parts);
        return result;
    }

    private <T> Map<String, T> scatter(Function<Shard, T> query) {
        Map<String, CompletableFuture<T>> futures = new LinkedHashMap<>();
        for (Shard shard : shards.all()) {
            futures.put(shard.name(), CompletableFuture.supplyAsync(() -> query.apply(shard), fanOut));
        }
        Map<String, T> results = new LinkedHashMap<>();
        futures.forEach((name, f) -> results.put(name, f.join()));
        return results;
    }

    @PreDestroy
    void shutdown() {
        fanOut.shutdown();
    }
}
