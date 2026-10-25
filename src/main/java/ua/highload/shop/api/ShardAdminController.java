package ua.highload.shop.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.highload.shop.db.DbRouting;
import ua.highload.shop.shard.Shard;
import ua.highload.shop.shard.ShardMigrationService;
import ua.highload.shop.shard.ShardRegistry;
import ua.highload.shop.shard.ShardRouter;
import ua.highload.shop.shard.ShardStrategy;

/** Діагностика та обслуговування шардів (ЛР7). */
@RestController
@RequestMapping("/api/admin/shards")
public class ShardAdminController {

    private final ShardRegistry registry;
    private final ShardRouter router;
    private final ShardMigrationService migration;
    private final NamedParameterJdbcTemplate catalog;

    public ShardAdminController(ShardRegistry registry, ShardRouter router, ShardMigrationService migration,
                                NamedParameterJdbcTemplate catalog) {
        this.registry = registry;
        this.router = router;
        this.migration = migration;
        this.catalog = catalog;
    }

    /** Фактичний розподіл даних по шардах. */
    @GetMapping
    public Map<String, Object> status() {
        var shards = new LinkedHashMap<String, Object>();
        for (Shard s : registry.all()) {
            shards.put(s.name(), s.jdbc().queryForMap(
                    "SELECT count(*) AS orders, count(DISTINCT user_id) AS users, "
                            + "(SELECT count(*) FROM order_items) AS items FROM orders", Map.of()));
        }
        return Map.of("strategy", router.strategy(), "bucketsPerShard", router.bucketsPerShard(), "shards", shards);
    }

    /** Куди потрапив би кожен користувач за кожною стратегією (Data Distribution Matrix для всіх стратегій). */
    @GetMapping("/matrix")
    public Map<String, Map<String, Integer>> matrix() {
        List<Long> users = DbRouting.replica(() -> catalog.getJdbcTemplate().queryForList("SELECT id FROM users", Long.class));
        var result = new LinkedHashMap<String, Map<String, Integer>>();
        for (ShardStrategy s : ShardStrategy.values()) {
            var counts = new LinkedHashMap<String, Integer>();
            registry.names().forEach(n -> counts.put(n, 0));
            users.forEach(u -> counts.merge(router.shardName(u, s), 1, Integer::sum));
            result.put(s.name(), counts);
        }
        return result;
    }

    /** Куди маршрутизується конкретний користувач. */
    @GetMapping("/route")
    public Map<String, Object> route(@RequestParam long userId) {
        return Map.of("userId", userId, "bucket", ShardRouter.bucket(userId), "strategy", router.strategy(),
                "shard", router.shardName(userId, router.strategy()));
    }

    @PostMapping("/migrate-legacy")
    public Map<String, Long> migrateLegacy() {
        return migration.migrateLegacy();
    }

    @PostMapping("/rebalance")
    public Map<String, Long> rebalance() {
        return migration.rebalance();
    }
}
