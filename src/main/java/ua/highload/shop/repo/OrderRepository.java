package ua.highload.shop.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ua.highload.shop.domain.Order;
import ua.highload.shop.domain.OrderItem;
import ua.highload.shop.domain.TopProduct;

@Repository
public class OrderRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public OrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Order insert(long userId, String status, long totalCents, List<OrderItem> items) {
        var order = jdbc.queryForObject("""
                INSERT INTO orders (user_id, status, total_cents) VALUES (:userId, :status, :total)
                RETURNING id, user_id, status, total_cents, created_at
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("status", status)
                .addValue("total", totalCents), OrderRepository::map);
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

    public Optional<Order> findById(long id) {
        return jdbc.query("SELECT id, user_id, status, total_cents, created_at FROM orders WHERE id = :id",
                new MapSqlParameterSource("id", id), OrderRepository::map).stream().findFirst();
    }

    public List<Order> findByUser(long userId, int limit) {
        return jdbc.query("""
                SELECT id, user_id, status, total_cents, created_at FROM orders
                WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("limit", limit),
                OrderRepository::map);
    }

    public List<OrderItem> findItems(long orderId) {
        return jdbc.query("SELECT product_id, quantity, price_cents FROM order_items WHERE order_id = :id ORDER BY product_id",
                new MapSqlParameterSource("id", orderId),
                (rs, i) -> new OrderItem(rs.getLong("product_id"), rs.getInt("quantity"), rs.getLong("price_cents")));
    }

    /** Агрегація по всій таблиці order_items — найдорожчий read-запит системи. */
    public List<TopProduct> topProducts(int limit) {
        return jdbc.query("""
                SELECT p.id, p.name, s.units, s.revenue
                FROM (SELECT product_id, sum(quantity) AS units, sum(quantity * price_cents) AS revenue
                      FROM order_items GROUP BY product_id
                      ORDER BY units DESC, product_id LIMIT :limit) s
                JOIN products p ON p.id = s.product_id
                ORDER BY s.units DESC, p.id
                """, new MapSqlParameterSource("limit", limit),
                (rs, i) -> new TopProduct(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4)));
    }

    static Order map(ResultSet rs, int rowNum) throws SQLException {
        return new Order(rs.getLong("id"), rs.getLong("user_id"), rs.getString("status"),
                rs.getLong("total_cents"), rs.getTimestamp("created_at").toInstant(), List.of());
    }
}
