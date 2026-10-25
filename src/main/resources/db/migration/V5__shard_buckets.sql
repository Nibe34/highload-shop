-- Virtual Buckets (ЛР7): 1024 логічних бакети, bucket = hash(user_id) mod 1024.
-- Таблиця bucket → shard — єдине джерело правди для маршрутизації; при resharding
-- змінюється лише відображення частини бакетів, а не формула хешування.
CREATE TABLE shard_buckets (
    bucket INTEGER PRIMARY KEY CHECK (bucket >= 0 AND bucket < 1024),
    shard  VARCHAR(32) NOT NULL
);

INSERT INTO shard_buckets (bucket, shard)
SELECT b, CASE WHEN b < 512 THEN 'a' ELSE 'b' END FROM generate_series(0, 1023) b;
