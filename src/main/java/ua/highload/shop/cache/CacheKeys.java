package ua.highload.shop.cache;

/**
 * Схема ключів: {@code shop:<entity>:v<schema>:<id/params>}.
 * Версія схеми (v1) змінюється при зміні формату значення — старі ключі просто вигасають за TTL.
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    public static String product(long id) {
        return "shop:product:v1:" + id;
    }

    /** Лічильник версії сторінок каталогу категорії; INCR інвалідує всі сторінки разом. */
    public static String catalogVersion(Long categoryId) {
        return "shop:catalog:ver:" + (categoryId == null ? "all" : categoryId);
    }

    public static String catalogPage(Long categoryId, long version, int page, int size) {
        return "shop:catalog:v1:" + (categoryId == null ? "all" : categoryId)
                + ":ver" + version + ":p" + page + ":s" + size;
    }

    /** Fence після зміни товару (stock/ціна): поки діє — картку перечитуємо з Primary. */
    public static String productFence(long id) {
        return "shop:fence:product:" + id;
    }

    public static String catalogFence(Long categoryId) {
        return "shop:fence:catalog:" + (categoryId == null ? "all" : categoryId);
    }

    /** Fence read-your-own-writes: користувач щойно щось записав. */
    public static String userWriteFence(long userId) {
        return "shop:fence:user:" + userId;
    }

    public static String categories() {
        return "shop:categories:v1";
    }

    public static String topProducts(int limit) {
        return "shop:stats:top-products:v1:limit" + limit;
    }
}
