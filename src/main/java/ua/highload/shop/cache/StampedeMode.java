package ua.highload.shop.cache;

/** Механізм координації перерахунку значення при Cache Miss (ЛР8). */
public enum StampedeMode {
    /** Без захисту: кожен запит, що отримав MISS, сам іде в БД (Cache Stampede). */
    NONE,
    /** Request Coalescing у межах інстансу: паралельні MISS одного ключа чекають на один спільний Future. */
    SINGLE_FLIGHT,
    /** Distributed Mutex (Redis SET NX PX): перераховує лише власник блокування, решта чекають на кеш. */
    LOCK,
    /** Обидва рівні: single-flight всередині інстансу + distributed lock між інстансами. */
    LOCK_SINGLE_FLIGHT
}
