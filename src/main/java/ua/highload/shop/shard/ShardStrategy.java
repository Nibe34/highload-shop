package ua.highload.shop.shard;

/** Функція вибору шарду за ключем user_id. */
public enum ShardStrategy {
    /** user_id mod N — «наївний» шардинг: зміна N переміщує майже всі ключі. */
    MODULO,
    /** hash(user_id) mod N — рівномірніше для послідовних id, але та сама проблема зі зміною N. */
    HASH,
    /** hash(user_id) mod 1024 → bucket → shard (таблиця shard_buckets). Resharding = перенесення частини бакетів. */
    BUCKETS,
    /** region користувача → shard: ключ з низькою кардинальністю (3 значення) — демонстрація Hot Shard. */
    REGION
}
