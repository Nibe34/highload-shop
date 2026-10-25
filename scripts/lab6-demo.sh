#!/usr/bin/env bash
# ЛР6: реплікація, Stale Read, Read-Your-Own-Writes, відмова Replica / Primary.
set -uo pipefail
export LC_ALL=C
BASE=${BASE:-http://localhost:8080}
ENGINE=${ENGINE:-podman}
PRIMARY=${PRIMARY:-shop_postgres_1}
REPLICA=${REPLICA:-shop_postgres-replica_1}
J='Content-Type: application/json'
DELAY=${DELAY:-3s}

hdr() { grep -i "^$1:" /tmp/lab6.h | tr -d '\r' | cut -d' ' -f2; }
call() { curl -s -o /tmp/lab6.b -D /tmp/lab6.h -w '%{http_code} %{time_total}' "$@"; }
orders_count() { python3 -c 'import json;print(len(json.load(open("/tmp/lab6.b"))))' 2>/dev/null || echo "-"; }
psql_on() { $ENGINE exec "$1" psql -U shop -d shop -tAc "$2"; }

echo "### 1. Реплікація: запис через API → Primary, перевірка наявності на Replica"
call -XPOST "$BASE/api/products" -H "$J" -d '{"categoryId":1,"name":"Replicated product","description":"","priceCents":100,"stock":1}' >/dev/null
PID=$(python3 -c 'import json;print(json.load(open("/tmp/lab6.b"))["id"])')
sleep 0.5
echo "primary: $(psql_on "$PRIMARY" "SELECT id, name FROM products WHERE id=$PID")"
echo "replica: $(psql_on "$REPLICA" "SELECT id, name FROM products WHERE id=$PID")"
echo "replica pg_is_in_recovery: $(psql_on "$REPLICA" "SELECT pg_is_in_recovery()")"
echo "запис на replica: $(psql_on "$REPLICA" "UPDATE products SET stock=0 WHERE id=$PID" 2>&1 | tail -1)"
echo "pg_stat_replication на primary:"
$ENGINE exec "$PRIMARY" psql -U shop -d shop -c "SELECT application_name, state, sync_state, sent_lsn, replay_lsn, replay_lag FROM pg_stat_replication"

echo; echo "### 2. Stale Read: штучний лаг recovery_min_apply_delay=$DELAY на Replica"
psql_on "$REPLICA" "ALTER SYSTEM SET recovery_min_apply_delay = '$DELAY'" >/dev/null
psql_on "$REPLICA" "SELECT pg_reload_conf()" >/dev/null; sleep 1
EMAIL="lag$(date +%s%N)@shop.test"
curl -s -o /dev/null -XPOST "$BASE/api/users" -H "$J" -d "{\"email\":\"$EMAIL\",\"fullName\":\"Lag\",\"region\":\"Lviv\",\"password\":\"secret123\"}"
LOGIN=$(curl -s -XPOST "$BASE/api/auth/login" -H "$J" -d "{\"email\":\"$EMAIL\",\"password\":\"secret123\"}")
TOKEN=$(sed -E 's/.*"token":"([^"]+)".*/\1/' <<<"$LOGIN"); AUTH="Authorization: Bearer $TOKEN"
call "$BASE/api/orders/my?limit=100&consistency=STRONG" -H "$AUTH" >/dev/null; before=$(orders_count)
echo "замовлень до запису (primary): $before"
call -XPOST "$BASE/api/orders" -H "$AUTH" -H "$J" -d '{"items":[{"productId":7,"quantity":1}]}' >/dev/null
echo "POST /api/orders → $(head -1 /tmp/lab6.h | cut -d' ' -f2), X-DB-Node=$(hdr x-db-node)"
for mode in EVENTUAL RYW; do
  call "$BASE/api/orders/my?limit=100&consistency=$mode" -H "$AUTH" >/dev/null
  printf '  GET /orders/my consistency=%-8s → %s замовлень, X-DB-Node=%s\n' "$mode" "$(orders_count)" "$(hdr x-db-node)"
done
echo "  (очікується: EVENTUAL = $before — Stale Read; RYW = $((before + 1)) з Primary завдяки write-fence)"
sleep 6
call "$BASE/api/orders/my?limit=100&consistency=RYW" -H "$AUTH" >/dev/null
echo "  через 6 с (fence вигас, replica наздогнала): RYW → $(orders_count) замовлень, X-DB-Node=$(hdr x-db-node)"
psql_on "$REPLICA" "ALTER SYSTEM RESET recovery_min_apply_delay" >/dev/null; psql_on "$REPLICA" "SELECT pg_reload_conf()" >/dev/null

echo; echo "### 3. Втрата Replica: $ENGINE stop $REPLICA"
$ENGINE stop -t 2 "$REPLICA" >/dev/null; sleep 2
call "$BASE/api/orders/my?limit=5&consistency=EVENTUAL" -H "$AUTH" >/dev/null
echo "  GET /orders/my (EVENTUAL) → $(head -1 /tmp/lab6.h | cut -d' ' -f2), X-DB-Node=$(hdr x-db-node)"
call "$BASE/api/users/5" >/dev/null
echo "  GET /users/5 → $(head -1 /tmp/lab6.h | cut -d' ' -f2), X-DB-Node=$(hdr x-db-node)"
call -XPOST "$BASE/api/orders" -H "$AUTH" -H "$J" -d '{"items":[{"productId":8,"quantity":1}]}' >/dev/null
echo "  POST /orders → $(head -1 /tmp/lab6.h | cut -d' ' -f2)"
call "$BASE/health" >/dev/null; echo "  /health → $(head -1 /tmp/lab6.h | cut -d' ' -f2) $(head -c 200 /tmp/lab6.b)"
$ENGINE start "$REPLICA" >/dev/null
t0=$(date +%s); ok=0
# повернення в ротацію: усі три інстанси мають знову читати з replica
until [[ $ok -ge 3 ]]; do call "$BASE/api/users/5" >/dev/null
  [[ $(hdr x-db-node) == replica ]] && ok=$((ok + 1)) || ok=0; sleep 0.5; done
echo "  replica повернулась у ротацію на всіх інстансах через $(( $(date +%s) - t0 )) с після старту контейнера"
for i in 1 2 3; do call "$BASE/api/users/5" >/dev/null
  echo "  GET /users/5 → X-Instance-ID=$(hdr x-instance-id) X-DB-Node=$(hdr x-db-node)"; done

echo; echo "### 4. Втрата Primary: $ENGINE stop $PRIMARY"
$ENGINE stop -t 2 "$PRIMARY" >/dev/null; sleep 2
call -XPOST "$BASE/api/orders" -H "$AUTH" -H "$J" -d '{"items":[{"productId":9,"quantity":1}]}' >/dev/null
echo "  POST /orders → $(head -1 /tmp/lab6.h | cut -d' ' -f2) $(head -c 160 /tmp/lab6.b)"
call "$BASE/api/orders/my?limit=5&consistency=EVENTUAL" -H "$AUTH" >/dev/null
echo "  GET /orders/my (EVENTUAL) → $(head -1 /tmp/lab6.h | cut -d' ' -f2), X-DB-Node=$(hdr x-db-node)"
call "$BASE/api/users/5" >/dev/null
echo "  GET /users/5 → $(head -1 /tmp/lab6.h | cut -d' ' -f2), X-DB-Node=$(hdr x-db-node)"
call "$BASE/api/products/123" >/dev/null
echo "  GET /products/123 → $(head -1 /tmp/lab6.h | cut -d' ' -f2), X-Cache=$(hdr x-cache)"
call "$BASE/api/orders/1" -H "$AUTH" >/dev/null
echo "  GET /orders/1 (strict, primary) → $(head -1 /tmp/lab6.h | cut -d' ' -f2)"
$ENGINE start "$PRIMARY" >/dev/null
t0=$(date +%s)
for i in $(seq 30); do
  call -XPOST "$BASE/api/orders" -H "$AUTH" -H "$J" -d '{"items":[{"productId":9,"quantity":1}]}' >/dev/null
  code=$(head -1 /tmp/lab6.h | cut -d' ' -f2); [[ $code == 201 ]] && break; sleep 1
done
echo "  primary повернувся: POST /orders → $code (через $(( $(date +%s) - t0 )) с після старту контейнера)"
