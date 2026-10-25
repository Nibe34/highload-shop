package ua.highload.shop.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Read/Write Separation: Dynamic DataSource Routing між пулами Primary та Replica.
 * <p>
 * Primary Fallback: якщо Replica позначена недоступною (health checker або помилка з'єднання)
 * або її лаг перевищує поріг — read-запит виконується на Primary.
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(RoutingDataSource.class);

    private final DataSource primary;
    private final DataSource replica;
    private final ReplicaHealth health;

    public RoutingDataSource(DataSource primary, DataSource replica, ReplicaHealth health) {
        this.primary = primary;
        this.replica = replica;
        this.health = health;
        setTargetDataSources(Map.of(DbNode.PRIMARY, primary, DbNode.REPLICA, replica));
        setDefaultTargetDataSource(primary);
        afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return DbRouting.target();
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (DbRouting.target() == DbNode.REPLICA && replica != primary) {
            if (health.usable()) {
                try {
                    Connection c = replica.getConnection();
                    DbRouting.markUsed("replica");
                    return c;
                } catch (SQLException e) {
                    log.warn("Replica connection failed, falling back to primary: {}", e.getMessage());
                    health.markDown(e.getMessage());
                }
            }
            DbRouting.markUsed("primary-fallback");
            return primary.getConnection();
        }
        DbRouting.markUsed("primary");
        return primary.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }
}
