package ua.highload.shop.shard;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ua.highload.shop.db.DbRouting;

/**
 * Перенесення замовлень між вузлами: (1) з монолітної БД каталогу на шарди, (2) між шардами
 * при зміні стратегії / відображення бакетів (resharding).
 * <p>
 * Кожна сторінка переноситься як «копія на ціль (ідемпотентно, ON CONFLICT DO NOTHING) → видалення з джерела»:
 * збій посередині лишає дубль, який повторний запуск безпечно прибирає, але не втрачає даних.
 */
@Service
public class ShardMigrationService {

    private static final Logger log = LoggerFactory.getLogger(ShardMigrationService.class);
    private static final int PAGE = 2000;

    private final ShardRegistry registry;
    private final ShardRouter router;
    private final NamedParameterJdbcTemplate catalog;

    public ShardMigrationService(ShardRegistry registry, ShardRouter router, NamedParameterJdbcTemplate catalog) {
        this.registry = registry;
        this.router = router;
        this.catalog = catalog;
    }

    /** Монолітна таблиця orders у БД каталогу → шарди за поточною стратегією. */
    public Map<String, Long> migrateLegacy() {
        return DbRouting.primary(() -> {
            Map<String, Long> moved = move(catalog, null);
            log.info("Legacy orders migrated to shards: {}", moved);
            return moved;
        });
    }

    /** Перерозподіл: кожне замовлення, що лежить не на «своєму» шарді, переноситься на правильний. */
    public Map<String, Long> rebalance() {
        Map<String, Long> total = new LinkedHashMap<>();
        for (Shard source : registry.all()) {
            move(source.jdbc(), source.name()).forEach((k, v) -> total.merge(source.name() + "->" + k, v, Long::sum));
        }
        log.info("Rebalance finished: {}", total);
        return total;
    }

    private Map<String, Long> move(NamedParameterJdbcTemplate source, String sourceShard) {
        Map<String, Long> moved = new LinkedHashMap<>();
        Map<Long, String> targetCache = new HashMap<>();
        long lastId = 0;
        while (true) {
            List<Row> page = source.query("""
                    SELECT id, user_id, status, total_cents, created_at FROM orders
                    WHERE id > :last ORDER BY id LIMIT :limit
                    """, new MapSqlParameterSource().addValue("last", lastId).addValue("limit", PAGE),
                    (rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getTimestamp(5)));
            if (page.isEmpty()) {
                break;
            }
            lastId = page.get(page.size() - 1).id();
            Map<String, List<Row>> byTarget = new LinkedHashMap<>();
            for (Row r : page) {
                String target = targetCache.computeIfAbsent(r.userId(), u -> router.shardName(u, router.strategy()));
                if (!target.equals(sourceShard)) {
                    byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(r);
                }
            }
            for (var e : byTarget.entrySet()) {
                copy(source, registry.get(e.getKey()), e.getValue());
                List<Long> ids = e.getValue().stream().map(Row::id).toList();
                source.update("DELETE FROM orders WHERE id IN (:ids)", new MapSqlParameterSource("ids", ids));
                moved.merge(e.getKey(), (long) ids.size(), Long::sum);
            }
        }
        return moved;
    }

    private void copy(NamedParameterJdbcTemplate source, Shard target, List<Row> rows) {
        List<Long> ids = rows.stream().map(Row::id).toList();
        var items = source.queryForList("""
                SELECT order_id, product_id, quantity, price_cents FROM order_items WHERE order_id IN (:ids)
                """, new MapSqlParameterSource("ids", ids));
        target.tx().executeWithoutResult(status -> {
            target.jdbc().batchUpdate("""
                    INSERT INTO orders (id, user_id, status, total_cents, created_at)
                    VALUES (:id, :userId, :status, :total, :createdAt) ON CONFLICT (id) DO NOTHING
                    """, rows.stream().map(r -> new MapSqlParameterSource()
                    .addValue("id", r.id()).addValue("userId", r.userId()).addValue("status", r.status())
                    .addValue("total", r.totalCents()).addValue("createdAt", r.createdAt()))
                    .toArray(MapSqlParameterSource[]::new));
            target.jdbc().batchUpdate("""
                    INSERT INTO order_items (order_id, product_id, quantity, price_cents)
                    VALUES (:order_id, :product_id, :quantity, :price_cents) ON CONFLICT DO NOTHING
                    """, items.stream().map(MapSqlParameterSource::new).toArray(MapSqlParameterSource[]::new));
        });
    }

    private record Row(long id, long userId, String status, long totalCents, Timestamp createdAt) {
    }
}
