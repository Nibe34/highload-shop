package ua.highload.shop.shard;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Фізичний шард замовлень: окремий вузол PostgreSQL зі своїм пулом з'єднань і менеджером транзакцій. */
public record Shard(String name, int index, NamedParameterJdbcTemplate jdbc, TransactionTemplate tx) {
}
