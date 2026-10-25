package ua.highload.shop.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;
import ua.highload.shop.db.DbRouting;
import ua.highload.shop.db.ReplicaHealth;

/** Діагностика топології реплікації (для демонстрації на захисті). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final JdbcTemplate jdbc;
    private final ReplicaHealth replica;

    public AdminController(JdbcTemplate jdbc, ReplicaHealth replica) {
        this.jdbc = jdbc;
        this.replica = replica;
    }

    @GetMapping("/replication")
    public Map<String, Object> replication() {
        var result = new LinkedHashMap<String, Object>();
        result.put("primary", safe(() -> DbRouting.primary(() -> jdbc.queryForList("""
                SELECT application_name, client_addr::text, state, sync_state,
                       pg_current_wal_lsn()::text AS primary_lsn, replay_lsn::text,
                       pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) AS replay_lag_bytes,
                       EXTRACT(EPOCH FROM replay_lag) AS replay_lag_seconds
                FROM pg_stat_replication
                """))));
        result.put("replica", safe(() -> DbRouting.replica(() -> jdbc.queryForList("""
                SELECT pg_is_in_recovery() AS in_recovery,
                       pg_last_wal_receive_lsn()::text AS receive_lsn,
                       pg_last_wal_replay_lsn()::text AS replay_lsn,
                       pg_last_xact_replay_timestamp() AS last_replayed_tx,
                       current_setting('recovery_min_apply_delay') AS apply_delay
                """))));
        result.put("routing", Map.of("replicaUp", replica.up(), "replicaUsable", replica.usable(),
                "lagSeconds", replica.lagSeconds(), "lastError", String.valueOf(replica.lastError()),
                "servedBy", String.valueOf(DbRouting.used())));
        return result;
    }

    private static Object safe(java.util.function.Supplier<List<Map<String, Object>>> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }
}
