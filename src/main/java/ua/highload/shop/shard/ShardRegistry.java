package ua.highload.shop.shard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ua.highload.shop.domain.NotFoundException;

/**
 * Пули з'єднань до шардів замовлень. Конфігурація: SHARDS="a=jdbc:postgresql://host-a/shop,b=jdbc:...".
 * Схема кожного шарду мігрується Flyway з classpath:db/shard.
 */
@Component
public class ShardRegistry {

    private final Map<String, Shard> shards = new LinkedHashMap<>();
    private final List<HikariDataSource> pools = new ArrayList<>();

    public ShardRegistry(@Value("${shop.shards.urls}") String urls,
                         @Value("${spring.datasource.username}") String user,
                         @Value("${spring.datasource.password}") String password,
                         @Value("${shop.shards.pool-size:10}") int poolSize) {
        int index = 0;
        for (String entry : urls.split(",")) {
            String[] kv = entry.trim().split("=", 2);
            var cfg = new HikariConfig();
            cfg.setPoolName("shard-" + kv[0]);
            cfg.setJdbcUrl(kv[1]);
            cfg.setUsername(user);
            cfg.setPassword(password);
            cfg.setMaximumPoolSize(poolSize);
            cfg.setConnectionTimeout(1500);
            var ds = new HikariDataSource(cfg);
            pools.add(ds);
            Flyway.configure().dataSource(ds).locations("classpath:db/shard")
                    .placeholders(Map.of("orderIdStart", Long.toString(1_000_000L + index)))
                    .load().migrate();
            var tm = new DataSourceTransactionManager(ds);
            shards.put(kv[0], new Shard(kv[0], index, new NamedParameterJdbcTemplate(ds), new TransactionTemplate(tm)));
            index++;
        }
    }

    public Shard get(String name) {
        Shard shard = shards.get(name);
        if (shard == null) {
            throw new NotFoundException("Unknown shard " + name);
        }
        return shard;
    }

    public List<String> names() {
        return List.copyOf(shards.keySet());
    }

    public List<Shard> all() {
        return List.copyOf(shards.values());
    }

    @PreDestroy
    void close() {
        pools.forEach(HikariDataSource::close);
    }
}
