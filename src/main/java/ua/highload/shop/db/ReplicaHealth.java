package ua.highload.shop.db;

import java.time.Duration;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Стан Read-Replica: доступність і поточний лаг реплікації.
 * Опитується за розкладом ({@link #probe()}); між опитуваннями миттєво позначається DOWN при помилці з'єднання.
 * Ephemeral-стан інстансу (як circuit breaker) — втрата при рестарті безпечна.
 */
public class ReplicaHealth {

    private static final Logger log = LoggerFactory.getLogger(ReplicaHealth.class);

    private final JdbcTemplate replicaJdbc;
    private final double maxLagSeconds;
    private volatile boolean up = true;
    private volatile double lagSeconds;
    private volatile String lastError;

    public ReplicaHealth(DataSource replica, Duration maxLag, MeterRegistry meters) {
        this.replicaJdbc = new JdbcTemplate(replica);
        this.maxLagSeconds = maxLag.toMillis() / 1000.0;
        meters.gauge("shop.replica.lag.seconds", this, h -> h.lagSeconds);
        meters.gauge("shop.replica.up", this, h -> h.up ? 1 : 0);
    }

    /** Replica придатна для читання: доступна і не відстає більше ніж на maxLag. */
    public boolean usable() {
        return up && lagSeconds <= maxLagSeconds;
    }

    public void markDown(String reason) {
        if (up) {
            log.warn("Replica marked DOWN: {}", reason);
        }
        up = false;
        lastError = reason;
    }

    /**
     * Лаг = час з моменту останньої застосованої транзакції, але лише якщо є незастосований WAL
     * (інакше простій Primary виглядав би як лаг).
     */
    public void probe() {
        try {
            Double lag = replicaJdbc.queryForObject("""
                    SELECT CASE WHEN pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn() THEN 0
                                ELSE COALESCE(EXTRACT(EPOCH FROM now() - pg_last_xact_replay_timestamp()), 0) END
                    """, Double.class);
            lagSeconds = lag == null ? 0 : lag;
            if (!up) {
                log.info("Replica is UP again (lag {} s)", lagSeconds);
            }
            up = true;
            lastError = null;
        } catch (RuntimeException e) {
            markDown(e.getMessage());
        }
    }

    public boolean up() {
        return up;
    }

    public double lagSeconds() {
        return lagSeconds;
    }

    public String lastError() {
        return lastError;
    }
}
