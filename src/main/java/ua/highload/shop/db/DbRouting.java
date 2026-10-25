package ua.highload.shop.db;

import java.util.function.Supplier;

/**
 * Явна маршрутизація читань у Data Access Layer.
 * <p>
 * За замовчуванням усе йде на Primary (безпечно). Читання, що допускають Eventual Consistency,
 * обгортаються в {@link #replica(Supplier)}. Транзакції ({@code @Transactional}) завжди отримують
 * з'єднання з Primary, бо з'єднання береться на початку транзакції, коли контекст = PRIMARY.
 */
public final class DbRouting {

    private static final ThreadLocal<DbNode> TARGET = new ThreadLocal<>();
    /** Фактичний вузол останнього запиту в БД у межах HTTP-запиту (для заголовка X-DB-Node). */
    private static final ThreadLocal<String> USED = new ThreadLocal<>();

    private DbRouting() {
    }

    public static <T> T replica(Supplier<T> action) {
        return on(DbNode.REPLICA, action);
    }

    public static <T> T primary(Supplier<T> action) {
        return on(DbNode.PRIMARY, action);
    }

    public static <T> T on(DbNode node, Supplier<T> action) {
        DbNode previous = TARGET.get();
        TARGET.set(node);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                TARGET.remove();
            } else {
                TARGET.set(previous);
            }
        }
    }

    static DbNode target() {
        DbNode node = TARGET.get();
        return node == null ? DbNode.PRIMARY : node;
    }

    static void markUsed(String node) {
        USED.set(node);
    }

    public static String used() {
        return USED.get();
    }

    public static void clearUsed() {
        USED.remove();
    }
}
