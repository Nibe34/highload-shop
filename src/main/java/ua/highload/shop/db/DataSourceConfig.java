package ua.highload.shop.db;

import java.time.Duration;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Два пули з'єднань (Primary, Replica) за {@link RoutingDataSource}.
 * Якщо {@code shop.replica.enabled=false} — Single Primary Topology: replica = primary.
 */
@Configuration
@EnableScheduling
public class DataSourceConfig {

    @Bean(destroyMethod = "close")
    @Qualifier("primaryPool")
    HikariDataSource primaryPool(@Value("${spring.datasource.url}") String url,
                                 @Value("${spring.datasource.username}") String user,
                                 @Value("${spring.datasource.password}") String password,
                                 @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize) {
        return pool("primary", url, user, password, poolSize);
    }

    @Bean
    @Qualifier("replicaPool")
    DataSource replicaPool(@Qualifier("primaryPool") HikariDataSource primary,
                           @Value("${shop.replica.enabled:false}") boolean enabled,
                           @Value("${shop.replica.url:}") String url,
                           @Value("${spring.datasource.username}") String user,
                           @Value("${spring.datasource.password}") String password,
                           @Value("${shop.replica.pool-size:10}") int poolSize) {
        return enabled ? pool("replica", url, user, password, poolSize) : primary;
    }

    @Bean
    ReplicaHealth replicaHealth(@Qualifier("replicaPool") DataSource replica,
                                @Value("${shop.replica.max-lag:10s}") Duration maxLag, MeterRegistry meters) {
        return new ReplicaHealth(replica, maxLag, meters);
    }

    @Bean
    @Primary
    DataSource dataSource(@Qualifier("primaryPool") HikariDataSource primary,
                          @Qualifier("replicaPool") DataSource replica, ReplicaHealth health) {
        return new RoutingDataSource(primary, replica, health);
    }

    @Bean
    ReplicaProbe replicaProbe(ReplicaHealth health, @Value("${shop.replica.enabled:false}") boolean enabled) {
        return new ReplicaProbe(health, enabled);
    }

    private static HikariDataSource pool(String name, String url, String user, String password, int size) {
        var cfg = new HikariConfig();
        cfg.setPoolName(name);
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(size);
        cfg.setConnectionTimeout(1500);
        cfg.setValidationTimeout(1000);
        // Без цього пул не стартує, поки вузол недоступний; так застосунок піднімається і при впалій Replica.
        cfg.setInitializationFailTimeout(-1);
        return new HikariDataSource(cfg);
    }

    /** Періодична перевірка Replica (кожну секунду). */
    static class ReplicaProbe {

        private final ReplicaHealth health;
        private final boolean enabled;

        ReplicaProbe(ReplicaHealth health, boolean enabled) {
            this.health = health;
            this.enabled = enabled;
        }

        @Scheduled(fixedDelay = 1000)
        void probe() {
            if (enabled) {
                health.probe();
            }
        }
    }
}
