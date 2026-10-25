package ua.highload.shop.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Readiness-перевірка для балансувальника: 200 — інстанс може обслуговувати трафік,
 * 503 — критична залежність (БД) недоступна і вузол треба вивести з пулу.
 * Недоступний Redis — це DEGRADED (200): каталог працює напряму з БД, тому вузол лишається в пулі.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final String instanceId;

    public HealthController(JdbcTemplate jdbc, StringRedisTemplate redis, @Value("${shop.instance-id}") String instanceId) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.instanceId = instanceId;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        var checks = new LinkedHashMap<String, Object>();
        boolean dbUp = check(checks, "database", () -> jdbc.queryForObject("SELECT 1", Integer.class));
        boolean redisUp = check(checks, "redis", () -> redis.execute((RedisCallback<String>) RedisConnection::ping));
        boolean up = dbUp;
        var body = new LinkedHashMap<String, Object>();
        body.put("status", !dbUp ? "DOWN" : redisUp ? "UP" : "DEGRADED");
        body.put("instance", instanceId);
        body.put("checks", checks);
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private static boolean check(Map<String, Object> checks, String name, Runnable probe) {
        long start = System.nanoTime();
        try {
            probe.run();
            checks.put(name, Map.of("status", "UP", "latencyMs", (System.nanoTime() - start) / 1_000_000.0));
            return true;
        } catch (RuntimeException e) {
            checks.put(name, Map.of("status", "DOWN", "error", String.valueOf(e.getMessage())));
            return false;
        }
    }
}
