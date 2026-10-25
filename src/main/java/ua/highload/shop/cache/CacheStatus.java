package ua.highload.shop.cache;

/** Джерело відповіді — повертається клієнту в заголовку X-Cache. */
public enum CacheStatus {
    HIT, MISS, BYPASS,
    /** Значення обчислив інший запит (single-flight / distributed lock), цей лише дочекався. */
    COALESCED
}
