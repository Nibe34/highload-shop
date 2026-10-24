#!/usr/bin/env bash
# ЛР2: аварійна зупинка (podman/docker kill) app-1 під час серії операцій.
# Клієнт чергує інстанси; якщо інстанс недоступний — повторює запит на іншому (роль майбутнього LB).
set -uo pipefail
A=${A:-http://localhost:8081}
B=${B:-http://localhost:8082}
ENGINE=${ENGINE:-podman}
VICTIM=${VICTIM:-shop_app-1_1}
N=${N:-40}
J='Content-Type: application/json'

EMAIL="loss$(date +%s%N)@shop.test"
curl -s -XPOST "$A/api/users" -H "$J" -d "{\"email\":\"$EMAIL\",\"fullName\":\"Loss\",\"region\":\"Kyiv\",\"password\":\"secret123\"}" >/dev/null
LOGIN=$(curl -s -XPOST "$A/api/auth/login" -H "$J" -d "{\"email\":\"$EMAIL\",\"password\":\"secret123\"}")
TOKEN=$(sed -E 's/.*"token":"([^"]+)".*/\1/' <<<"$LOGIN")
USER_ID=$(sed -E 's/.*"userId":([0-9]+).*/\1/' <<<"$LOGIN")
echo "user=$USER_ID, сесію створено на app-1"
curl -s -XPOST "$A/api/cart/items" -H "Authorization: Bearer $TOKEN" -H "$J" -d '{"productId":5,"quantity":1}' >/dev/null
echo "кошик наповнено через app-1"

ok=0; failover=0; errors=0
for i in $(seq 1 "$N"); do
  [[ $i -eq $((N / 2)) ]] && { echo ">>> $ENGINE kill $VICTIM"; $ENGINE kill "$VICTIM" >/dev/null; }
  primary=$A; secondary=$B; (( i % 2 == 0 )) && { primary=$B; secondary=$A; }
  body="{\"items\":[{\"productId\":$((10 + i)),\"quantity\":1}]}"
  out=$(curl -s -m 3 -o /dev/null -w '%{http_code} %header{x-instance-id}' -XPOST "$primary/api/orders" \
        -H "Authorization: Bearer $TOKEN" -H "$J" -d "$body")
  if [[ ${out%% *} != 201 ]]; then
    out=$(curl -s -m 3 -o /dev/null -w '%{http_code} %header{x-instance-id}' -XPOST "$secondary/api/orders" \
          -H "Authorization: Bearer $TOKEN" -H "$J" -d "$body"); failover=$((failover + 1))
  fi
  [[ ${out%% *} == 201 ]] && ok=$((ok + 1)) || errors=$((errors + 1))
  printf '#%02d %s\n' "$i" "$out"
done
echo "--- кошик після аварії (через app-2):"
curl -s "$B/api/cart" -H "Authorization: Bearer $TOKEN"; echo
db=$($ENGINE exec shop_postgres_1 psql -U shop -tAc "SELECT count(*) FROM orders WHERE user_id=$USER_ID")
echo "Успішних відповідей клієнту: $ok, повторів на інший інстанс: $failover, помилок: $errors"
echo "Замовлень у БД для user=$USER_ID: $db"
