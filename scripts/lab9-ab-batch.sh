#!/usr/bin/env bash
# ЛР9: A/B для усунення N+1 (feature flag historyBatch) на тих самих прогрітих JVM; індекс увімкнено в обох варіантах.
set -uo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."
OUT=results/lab09/ab; mkdir -p "$OUT"
source scripts/lib.sh
flag() { curl -s -XPUT localhost:8080/api/admin/flags -H 'Content-Type: application/json' -d "{\"historyBatch\":\"$1\"}" >/dev/null; sleep 2; }
SHARDS="shop_postgres-shard-a_1 shop_postgres-shard-b_1"
calls() { local t=0; for s in $SHARDS; do t=$(( t + $($ENGINE exec $s psql -U shop -d shop -tAc "SELECT coalesce(sum(calls),0) FROM pg_stat_statements WHERE query LIKE '%FROM order_items%WHERE order_id%'") )); done; echo $t; }

scripts/reset-data.sh >/dev/null; warmup_all
for rep in 1 2 3; do
  for state in false true; do
    flag $state; name=$([[ $state == true ]] && echo batch || echo nplus1)
    scripts/reset-data.sh >/dev/null; q0=$(calls)
    measure "$name-history-$rep" load-tests/lab7/queries.js -e MODE=local -e VUS=50 -e DURATION=20s
    echo "items_queries $(( $(calls) - q0 ))" >> "$OUT/$name-history-$rep.db"
    scripts/reset-data.sh >/dev/null; measure "$name-C30-$rep" load-tests/scenarios.js -e SCENARIO=C -e VUS=30 -e DURATION=30s
  done
done
flag true
echo done
