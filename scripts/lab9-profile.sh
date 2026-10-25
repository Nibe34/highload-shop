#!/usr/bin/env bash
# ЛР9: інструментальне профілювання однієї фази (baseline | index | batch).
#   - навантаження: сценарій C, 100 VUs, 60 с (+ shard-local історія 50 VUs і запис B 100 VUs для побічних ефектів)
#   - async-profiler: wall-clock (app-1) та CPU/itimer (app-2), 20 с під навантаженням → flame graphs
#   - HikariCP: active/pending для пулів шардів щосекунди (app-1)
#   - PostgreSQL: pg_stat_statements (top), EXPLAIN (ANALYZE, BUFFERS) для запиту історії
set -uo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."
PHASE=${1:?baseline|index|batch}
OUT=results/lab09; PROF=profiling; mkdir -p "$OUT" "$PROF"
source scripts/lib.sh
SHARDS="shop_postgres-shard-a_1 shop_postgres-shard-b_1"
AP=/profiling-tools/async-profiler-4.6-linux-x64/bin/asprof

metric() { $ENGINE exec shop_app-1_1 curl -s "localhost:8080/actuator/metrics/$1?tag=pool:$2" | sed -E 's/.*"value":([0-9.]+).*/\1/'; }

scripts/reset-data.sh >/dev/null
warmup_all
scripts/reset-data.sh >/dev/null
for s in $SHARDS; do $ENGINE exec $s psql -U shop -d shop -qtAc "SELECT pg_stat_statements_reset()" >/dev/null; done

(   sleep 15
    $ENGINE exec shop_app-1_1 $AP -e wall -t -d 20 -f /tmp/wall.html 1 >/dev/null 2>&1 &
    $ENGINE exec shop_app-2_1 $AP -e itimer -d 20 -f /tmp/cpu.html 1 >/dev/null 2>&1 &
    echo "t,shard_a_active,shard_a_pending,shard_b_active,shard_b_pending" > "$OUT/$PHASE-hikari.csv"
    for t in $(seq 1 30); do
      echo "$t,$(metric hikaricp.connections.active shard-a),$(metric hikaricp.connections.pending shard-a),$(metric hikaricp.connections.active shard-b),$(metric hikaricp.connections.pending shard-b)" >> "$OUT/$PHASE-hikari.csv"
      sleep 1
    done
    wait ) &
measure "$PHASE-C" load-tests/scenarios.js -e SCENARIO=C -e VUS=100 -e DURATION=60s
wait
$ENGINE cp shop_app-1_1:/tmp/wall.html "$PROF/$PHASE-wall-flamegraph.html"
$ENGINE cp shop_app-2_1:/tmp/cpu.html "$PROF/$PHASE-cpu-flamegraph.html"

{ for s in $SHARDS; do
    echo "### $s"
    $ENGINE exec $s psql -U shop -d shop -c "SELECT calls, round(total_exec_time::numeric) AS total_ms,
        round(mean_exec_time::numeric, 3) AS mean_ms, rows, round((100 * total_exec_time / sum(total_exec_time) OVER ())::numeric, 1) AS pct,
        left(regexp_replace(query, '\s+', ' ', 'g'), 110) AS query
      FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'shop')
      ORDER BY total_exec_time DESC LIMIT 6"
  done; } > "$PROF/$PHASE-pg_stat_statements.txt"

USER_ID=$(curl -s "localhost:8080/api/admin/shards/route?userId=42" | sed -E 's/.*"shard":"([a-z])".*/\1/')
{ echo "-- user 42 → shard $USER_ID"
  $ENGINE exec shop_postgres-shard-${USER_ID}_1 psql -U shop -d shop -c "EXPLAIN (ANALYZE, BUFFERS)
    SELECT id, user_id, status, total_cents, created_at FROM orders WHERE user_id = 42 ORDER BY created_at DESC LIMIT 10"
  ORDER_ID=$($ENGINE exec shop_postgres-shard-${USER_ID}_1 psql -U shop -d shop -tAc "SELECT id FROM orders WHERE user_id = 42 LIMIT 1")
  $ENGINE exec shop_postgres-shard-${USER_ID}_1 psql -U shop -d shop -c "EXPLAIN (ANALYZE, BUFFERS)
    SELECT product_id, quantity, price_cents FROM order_items WHERE order_id = $ORDER_ID ORDER BY product_id"
  $ENGINE exec shop_postgres-shard-${USER_ID}_1 psql -U shop -d shop -c "\di+"
} > "$PROF/$PHASE-explain.txt" 2>&1

scripts/reset-data.sh >/dev/null
measure "$PHASE-history" load-tests/lab7/queries.js -e MODE=local -e VUS=50 -e DURATION=30s
scripts/reset-data.sh >/dev/null
measure "$PHASE-B" load-tests/scenarios.js -e SCENARIO=B -e VUS=100 -e DURATION=30s
echo "phase $PHASE done"
