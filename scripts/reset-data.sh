#!/usr/bin/env bash
# Повертає дані до seed-стану перед серією вимірювань (детермінований обсяг таблиць):
# видаляє замовлення / товари / користувачів, створені тестами, відновлює залишки, чистить кеш.
set -euo pipefail
ENGINE=${ENGINE:-podman}
PG=${PG:-shop_postgres_1}
REDIS=${REDIS:-shop_redis_1}
$ENGINE exec -i "$PG" psql -U shop -d shop -v ON_ERROR_STOP=1 -q <<'SQL'
DELETE FROM orders WHERE id > 50000;
DELETE FROM orders WHERE user_id > 2000;
DELETE FROM order_items WHERE product_id > 10000;
DELETE FROM products WHERE id > 10000;
DELETE FROM users WHERE id > 2000;
UPDATE products SET stock = 1000000 WHERE stock <> 1000000;
VACUUM ANALYZE;
SQL
$ENGINE exec "$REDIS" sh -c "redis-cli --scan --pattern 'shop:*' | xargs -r -n 500 redis-cli DEL >/dev/null; redis-cli --scan --pattern 'cart:*' | xargs -r -n 500 redis-cli DEL >/dev/null"
$ENGINE exec "$PG" psql -U shop -d shop -tAc "SELECT 'orders=' || count(*) FROM orders"
