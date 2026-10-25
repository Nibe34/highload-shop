#!/usr/bin/env bash
# ЛР9: контрольований A/B експеримент для індексу. Індекс перемикається в БД (DROP/CREATE) на тих самих
# прогрітих JVM — так різниця не змішується з шумом JIT-прогріву між розгортаннями. Порядок ABAB, 3 повтори.
set -uo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."
OUT=results/lab09/ab; mkdir -p "$OUT"
source scripts/lib.sh
SHARDS="shop_postgres-shard-a_1 shop_postgres-shard-b_1"
set_index() { for s in $SHARDS; do
  if [[ $1 == on ]]; then $ENGINE exec $s psql -U shop -d shop -qc "CREATE INDEX IF NOT EXISTS idx_orders_user_created ON orders (user_id, created_at DESC)"
  else $ENGINE exec $s psql -U shop -d shop -qc "DROP INDEX IF EXISTS idx_orders_user_created"; fi; done; }

scripts/reset-data.sh >/dev/null; warmup_all
for rep in 1 2 3; do
  for state in off on; do
    set_index $state
    scripts/reset-data.sh >/dev/null; measure "$state-C-$rep" load-tests/scenarios.js -e SCENARIO=C -e VUS=100 -e DURATION=30s
    scripts/reset-data.sh >/dev/null; measure "$state-history-$rep" load-tests/lab7/queries.js -e MODE=local -e VUS=50 -e DURATION=20s
    scripts/reset-data.sh >/dev/null; measure "$state-B-$rep" load-tests/scenarios.js -e SCENARIO=B -e VUS=100 -e DURATION=20s
  done
done
set_index on
echo done
