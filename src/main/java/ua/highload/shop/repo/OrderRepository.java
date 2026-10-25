package ua.highload.shop.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ua.highload.shop.domain.Order;
import ua.highload.shop.domain.OrderItem;
import ua.highload.shop.shard.Shard;

/** Доступ до замовлень на конкретному шарді. Шард обирає {@link ua.highload.shop.shard.ShardRouter}. */
@Repository
public class OrderRepository {

    private static final String COLUMNS = "id, user_id, status, total_cents, created_at";

    public Order insert(Shard shard, long userId, String status, long totalCents, List<OrderItem> items) {
        return insert(shard.jdbc(), null, userId, status, totalCents, null, items);
    }

    /** Вставка з явним id/created_at — для перенесення замовлень між шардами (resharding). */
    public Order insert(NamedParameterJdbcTemplate jdbc, Long id, long userId, String status, long totalCents,
                        java.time.Instant createdAt, List<OrderItem> items) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("status", status)
                .addValue("total", totalCents)
                .addValue("createdAt", createdAt == null ? null : java.sql.Timestamp.from(createdAt));
        var order = jdbc.queryForObject("""
                INSERT INTO orders (id, user_id, status, total_cents, created_at)
                VALUES (COALESCE(:id, nextval('order_id_seq')), :userId, :status, :total, COALESCE(:createdAt, now()))
                RETURNING""" + " " + COLUMNS, params, OrderRepository::map);
        var batch = items.stream().map(it -> new MapSqlParameterSource()
                .addValue("orderId", order.id())
                .addValue("productId", it.productId())
                .addValue("qty", it.quantity())
                .addValue("price", it.priceCents())).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO order_items (order_id, product_id, quantity, price_cents)
                VALUES (:orderId, :productId, :qty, :price)
                """, batch);
        return order.withItems(items);
    }

    public Optional<Order> findById(Shard shard, long id, long userId) {
        return shard.jdbc().query("SELECT " + COLUMNS + " FROM orders WHERE id = :id AND user_id = :userId",
                new MapSqlParameterSource().addValue("id", id).addValue("userId", userId),
                OrderRepository::map).stream().findFirst();
    }

    public List<Order> findByUser(Shard shard, long userId, int limit) {
        return shard.jdbc().query("SELECT " + COLUMNS + """
                 FROM orders
                WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("limit", limit),
                OrderRepository::map);
    }

    public List<OrderItem> findItems(Shard shard, long orderId) {
        return findItems(shard.jdbc(), orderId);
    }

    /**
     * Позиції для кількох замовлень одним запитом ({@code order_id = ANY(?)}) замість N окремих (усунення N+1).
     * Порядок позицій у межах замовлення — за product_id, як і в {@link #findItems(Shard, long)}.
     */
    public Map<Long, List<OrderItem>> findItems(Shard shard, List<Long> orderIds) {
        Map<Long, List<OrderItem>> result = new java.util.HashMap<>();
        if (orderIds.isEmpty()) {
            return result;
        }
        shard.jdbc().query("""
                SELECT order_id, product_id, quantity, price_cents FROM order_items
                WHERE order_id = ANY(:ids) ORDER BY order_id, product_id
                """, new MapSqlParameterSource("ids", orderIds.toArray(Long[]::new)), rs -> {
            result.computeIfAbsent(rs.getLong(1), k -> new java.util.ArrayList<>())
                    .add(new OrderItem(rs.getLong(2), rs.getInt(3), rs.getLong(4)));
        });
        return result;
    }

    public List<OrderItem> findItems(NamedParameterJdbcTemplate jdbc, long orderId) {
        return jdbc.query("SELECT product_id, quantity, price_cents FROM order_items WHERE order_id = :id ORDER BY product_id",
                new MapSqlParameterSource("id", orderId),
                (rs, i) -> new OrderItem(rs.getLong("product_id"), rs.getInt("quantity"), rs.getLong("price_cents")));
    }

    /** Часткова агрегація продажів на одному шарді (фаза scatter для top-products). */
    public List<ProductSales> salesByProduct(Shard shard) {
        return shard.jdbc().query("""
                SELECT product_id, sum(quantity) AS units, sum(quantity * price_cents) AS revenue
                FROM order_items GROUP BY product_id
                """, Map.of(), (rs, i) -> new ProductSales(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
    }

    /** Агрегати замовлень за період на одному шарді. */
    public SalesSummary summary(Shard shard, java.time.Instant from, java.time.Instant to) {
        return shard.jdbc().queryForObject("""
                SELECT count(*), COALESCE(sum(total_cents), 0), count(DISTINCT user_id)
                FROM orders WHERE created_at >= :from AND created_at < :to
                """, new MapSqlParameterSource()
                .addValue("from", java.sql.Timestamp.from(from))
                .addValue("to", java.sql.Timestamp.from(to)),
                (rs, i) -> new SalesSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
    }

    public static Order map(ResultSet rs, int rowNum) throws SQLException {
        return new Order(rs.getLong("id"), rs.getLong("user_id"), rs.getString("status"),
                rs.getLong("total_cents"), rs.getTimestamp("created_at").toInstant(), List.of());
    }

    public record ProductSales(long productId, long units, long revenueCents) {
    }

    public record SalesSummary(long orders, long revenueCents, long customers) {
    }
}
