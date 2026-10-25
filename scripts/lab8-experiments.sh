#!/usr/bin/env bash
# ЛР8: Cache Stampede / Thundering Herd, Cache Avalanche (TTL jitter), Cache Penetration. Результати: results/lab08/
set -uo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."
OUT=results/lab08; mkdir -p "$OUT"
source scripts/lib.sh
STEPS=${STEPS:-"stampede avalanche penetration"}
has() { [[ " $STEPS " == *" $1 "* ]]; }
REPLICA=shop_postgres-replica_1
SHARDS="shop_postgres-shard-a_1 shop_postgres-shard-b_1"

flags() { curl -s -XPUT localhost:8080/api/admin/flags -H 'Content-Type: application/json' -d "$1" >/dev/null; sleep 2; }
# виконання агрегації top-products на шардах (scatter-запит) — сума по всіх шардах
shard_agg_calls() { local t=0; for s in $SHARDS; do
  t=$(( t + $($ENGINE exec $s psql -U shop -d shop -tAc "SELECT coalesce(sum(calls),0) FROM pg_stat_statements WHERE query LIKE '%GROUP BY product_id%' AND query NOT LIKE '%pg_stat%'") )); done; echo $t; }
# читання карток товарів на репліці
replica_product_calls() { $ENGINE exec $REPLICA psql -U shop -d shop -tAc \
  "SELECT coalesce(sum(calls),0) FROM pg_stat_statements WHERE query LIKE 'SELECT id, category_id%FROM products WHERE id = \$1'"; }
# лічильник фактичних перерахунків (loader) на всіх інстансах
app_loads() { local t=0; for c in shop_app-1_1 shop_app-2_1 shop_app-3_1; do
  v=$($ENGINE exec $c curl -s localhost:8080/actuator/metrics/shop.cache.loads | sed -E 's/.*"value":([0-9.]+).*/\1/'); t=$(python3 -c "print(int($t + ${v:-0}))"); done; echo $t; }

if has stampede; then
  echo "== Cache Stampede: 500 одночасних запитів до холодного top-products"
  warmup 60s
  $K6 -e VUS=50 load-tests/lab8/stampede.js >/dev/null 2>&1   # прогрів шляху top-products
  : > "$OUT/stampede.csv"; echo "mode,run,shard_queries,app_loads,rps,p50,p95,p99,max,errors,hit,miss,coalesced" > "$OUT/stampede.csv"
  for mode in NONE SINGLE_FLIGHT LOCK LOCK_SINGLE_FLIGHT; do
    flags "{\"stampede\":\"$mode\"}"
    for run in 1 2 3; do
      $ENGINE exec shop_redis_1 redis-cli DEL shop:stats:top-products:v1:limit10 >/dev/null
      q0=$(shard_agg_calls); l0=$(app_loads)
      $K6 -e VUS=500 --summary-export "$OUT/stampede-$mode-$run.json" load-tests/lab8/stampede.js > "$OUT/stampede-$mode-$run.txt" 2>&1
      q1=$(shard_agg_calls); l1=$(app_loads)
      python3 - "$OUT/stampede-$mode-$run.json" "$mode" "$run" $(( (q1 - q0) / 2 )) $(( l1 - l0 )) >> "$OUT/stampede.csv" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))["metrics"]; d = m["http_req_duration"]
c = lambda s: m.get(f"cache_status{{status:{s}}}", {}).get("count", 0)
print(f"{sys.argv[2]},{sys.argv[3]},{sys.argv[4]},{sys.argv[5]},{m['http_reqs']['rate']:.0f},{d['med']:.1f},{d['p(95)']:.1f},"
      f"{d['p(99)']:.1f},{d['max']:.1f},{m['http_req_failed']['value']*100:.2f},{c('HIT')},{c('MISS')},{c('COALESCED')}")
PY
      tail -1 "$OUT/stampede.csv"; sleep 3
    done
  done
  flags '{"stampede":"LOCK_SINGLE_FLIGHT"}'
fi

avalanche_run() { # name jitter
  scripts/reset-data.sh >/dev/null
  flags "{\"productTtlSeconds\":\"20\",\"ttlJitter\":\"$2\"}"
  $K6 -e MODE=warm -e KEYS=3000 load-tests/lab8/products.js >/dev/null 2>&1    # усі 3000 ключів за ~1-2 с
  ( prev=$(replica_product_calls); echo "t,db_calls" > "$OUT/$1-timeline.csv"
    for t in $(seq 1 60); do sleep 1; cur=$(replica_product_calls); echo "$t,$(( cur - prev ))" >> "$OUT/$1-timeline.csv"; prev=$cur; done ) &
  measure "$1" load-tests/lab8/products.js -e MODE=steady -e KEYS=3000 -e RATE=1500 -e DURATION=60s
  wait
}

if has avalanche; then
  echo "== Cache Avalanche: TTL=20 с для 3000 ключів, записаних одночасно"
  avalanche_run avalanche-sync false
  avalanche_run avalanche-jitter true
  flags '{"productTtlSeconds":"0","ttlJitter":"true"}'
fi

if has penetration; then
  echo "== Cache Penetration: запити до неіснуючих id (50 VUs, 30 с)"
  for pattern in repeat unique; do
    for mode in none negative bloom; do
      case $mode in none) f='{"negative":"false","bloom":"false"}';; negative) f='{"negative":"true","bloom":"false"}';; bloom) f='{"negative":"false","bloom":"true"}';; esac
      flags "$f"; $ENGINE exec shop_redis_1 sh -c "redis-cli --scan --pattern 'shop:product:*' | xargs -r -n 500 redis-cli DEL >/dev/null"
      q0=$(replica_product_calls)
      measure "pen-$pattern-$mode" load-tests/lab8/products.js -e MODE=penetration -e PATTERN=$pattern -e VUS=50 -e DURATION=30s
      q1=$(replica_product_calls); echo "replica_product_queries $(( q1 - q0 ))" >> "$OUT/pen-$pattern-$mode.db"
    done
  done
  flags '{"negative":"true","bloom":"true"}'
fi
echo done
