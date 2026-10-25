#!/usr/bin/env bash
# ЛР4: Cache HIT/MISS, інвалідація, cold vs hot latency, fallback при падінні Redis.
set -uo pipefail
export LC_ALL=C
BASE=${BASE:-http://localhost:8080}
ENGINE=${ENGINE:-podman}
REDIS=${REDIS:-shop_redis_1}
J='Content-Type: application/json'
PID=${PID:-42}

show() { curl -s -o /tmp/lab4.b -D /tmp/lab4.h -w '%{time_total}' "$@" >/tmp/lab4.t
  printf '%-7s X-Cache=%-6s %s  t=%.1f ms  %s\n' "$(head -1 /tmp/lab4.h | cut -d' ' -f2)" \
    "$(grep -i '^x-cache' /tmp/lab4.h | tr -d '\r' | cut -d' ' -f2)" \
    "$(grep -i '^x-instance-id' /tmp/lab4.h | tr -d '\r' | cut -d' ' -f2)" \
    "$(echo "$(cat /tmp/lab4.t) * 1000" | bc -l)" "$(head -c 90 /tmp/lab4.b)"; }

echo "### 1. MISS → HIT (картка товару $PID, ключ shop:product:v1:$PID)"
$ENGINE exec "$REDIS" redis-cli DEL "shop:product:v1:$PID" >/dev/null
show "$BASE/api/products/$PID"
show "$BASE/api/products/$PID"
show "$BASE/api/products/$PID"
echo "TTL ключа: $($ENGINE exec "$REDIS" redis-cli TTL "shop:product:v1:$PID") с"

echo; echo "### 2. Інвалідація при PUT"
BODY=$(curl -s "$BASE/api/products/$PID" | python3 -c 'import json,sys;p=json.load(sys.stdin);print(json.dumps({"categoryId":p["categoryId"],"name":p["name"],"description":p["description"],"priceCents":p["priceCents"]+1,"stock":p["stock"]}))')
curl -s -o /dev/null -w "PUT -> %{http_code}\n" -XPUT "$BASE/api/products/$PID" -H "$J" -d "$BODY"
show "$BASE/api/products/$PID"
show "$BASE/api/products/$PID"

echo; echo "### 3. Інвалідація сторінок каталогу через версію категорії"
CAT=$(python3 -c "import json;print(json.loads('''$BODY''')['categoryId'])")
show "$BASE/api/products?categoryId=$CAT&size=5"
show "$BASE/api/products?categoryId=$CAT&size=5"
curl -s -o /dev/null -w "PUT -> %{http_code}\n" -XPUT "$BASE/api/products/$PID" -H "$J" -d "$BODY"
echo "версія каталогу категорії $CAT: $($ENGINE exec "$REDIS" redis-cli GET "shop:catalog:ver:$CAT")"
show "$BASE/api/products?categoryId=$CAT&size=5"

echo; echo "### 4. Cold vs Hot: top-products (агрегація ~150k рядків), 20 вимірювань кожного"
cold=(); hot=()
for i in $(seq 20); do
  $ENGINE exec "$REDIS" redis-cli DEL "shop:stats:top-products:v1:limit10" >/dev/null
  cold+=("$(curl -s -o /dev/null -w '%{time_total}' "$BASE/api/stats/top-products")")
  hot+=("$(curl -s -o /dev/null -w '%{time_total}' "$BASE/api/stats/top-products")")
done
python3 - "${cold[*]}" "${hot[*]}" <<'PY'
import statistics, sys
for name, raw in (("cold (MISS)", sys.argv[1]), ("hot  (HIT) ", sys.argv[2])):
    v = sorted(float(x) * 1000 for x in raw.split())
    print(f"{name}: median={statistics.median(v):.2f} ms  min={v[0]:.2f}  max={v[-1]:.2f}")
PY

echo; echo "### 5. Fallback: $ENGINE stop $REDIS"
$ENGINE stop -t 1 "$REDIS" >/dev/null
show "$BASE/api/products/$PID"
show "$BASE/api/products/$PID"
show "$BASE/api/products?categoryId=$CAT&size=5"
show "$BASE/api/stats/top-products"
show "$BASE/health"
show -XPOST "$BASE/api/auth/login" -H "$J" -d '{"email":"user1@shop.test","password":"password"}'
echo "### $ENGINE start $REDIS — очікуємо автоматичного перепідключення (Lettuce reconnect + breaker 5 с)"
$ENGINE start "$REDIS" >/dev/null; t0=$(date +%s)
until curl -s -D - -o /dev/null "$BASE/api/products/$PID" | grep -qiE 'x-cache: (HIT|MISS)'; do sleep 1; done
echo "кеш знову активний через $(( $(date +%s) - t0 )) с"
show "$BASE/api/products/$PID"
show "$BASE/api/products/$PID"
