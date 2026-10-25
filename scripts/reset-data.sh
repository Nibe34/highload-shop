#!/usr/bin/env bash
# Повертає дані до seed-стану перед серією вимірювань (детермінований обсяг таблиць):
# видаляє замовлення / товари / користувачів, створені тестами, відновлює залишки, чистить кеш.
# Замовлення живуть на шардах (ЛР7); legacy-таблиця orders у каталозі після міграції порожня.
set -euo pipefail
ENGINE=${ENGINE:-podman}
PG=${PG:-shop_postgres_1}
REDIS=${REDIS:-shop_redis_1}
SHARDS=${SHARDS_CONTAINERS:-$($ENGINE ps --format '{{.Names}}' | grep '^shop_postgres-shard-' | sort)}

for shard in $SHARDS; do
  $ENGINE exec -i "$shard" psql -U shop -d shop -v ON_ERROR_STOP=1 -q <<'SQL'
DELETE FROM orders WHERE id > 50000 OR user_id > 2000;
DELETE FROM order_items WHERE product_id > 10000;
VACUUM ANALYZE;
SQL
done
$ENGINE exec -i "$PG" psql -U shop -d shop -v ON_ERROR_STOP=1 -q <<'SQL'
DELETE FROM orders WHERE id > 50000 OR user_id > 2000;
DELETE FROM order_items WHERE product_id > 10000;
DELETE FROM products WHERE id > 10000;
DELETE FROM users WHERE id > 2000;
UPDATE products SET stock = 1000000 WHERE stock <> 1000000;
VACUUM ANALYZE;
SQL
# кеш і кошики чистимо; прапорці (shop:flags) і Bloom filter (shop:bloom:*) — це конфігурація, а не дані тесту
$ENGINE exec "$REDIS" sh -c "redis-cli --scan --pattern 'shop:*' | grep -vE '^shop:(flags|bloom:)' | xargs -r -n 500 redis-cli DEL >/dev/null; redis-cli --scan --pattern 'cart:*' | xargs -r -n 500 redis-cli DEL >/dev/null"
for shard in $SHARDS; do
  echo "$shard: $($ENGINE exec "$shard" psql -U shop -d shop -tAc "SELECT 'orders=' || count(*) FROM orders")"
done
echo "catalog: $($ENGINE exec "$PG" psql -U shop -d shop -tAc "SELECT 'legacy orders=' || count(*) FROM orders")"
