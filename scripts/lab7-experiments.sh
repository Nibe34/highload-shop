#!/usr/bin/env bash
# ЛР7: розподіл даних, Hot Shard, shard-local vs fan-out, 1 вузол vs 2 шарди. Результати: results/lab07/
# Перед КОЖНИМ виміром дані скидаються до seed-стану (50k замовлень), інакше прогони запису
# роздувають таблиці і наступні вимірювання читання порівнюють різні обсяги даних.
set -uo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."
OUT=results/lab07; mkdir -p "$OUT"
source scripts/lib.sh
STEPS=${STEPS:-"hot queries benchmark"}
has() { [[ " $STEPS " == *" $1 "* ]]; }
SA=shop_postgres-shard-a_1; SB=shop_postgres-shard-b_1

shard_counts() { for s in $SA $SB; do printf '%s=%s ' "${s#shop_postgres-shard-}" "$($ENGINE exec $s psql -U shop -d shop -tAc 'SELECT count(*) FROM orders')"; done; }
admin() { curl -s -XPOST "localhost:8080/api/admin/shards/$1"; echo; }
reset() { scripts/reset-data.sh >/dev/null; }
warm_paths() { # JIT-прогрів шляхів запису та shard-local читання (не лише read-шляху з warmup)
  $K6 -e SCENARIO=B -e VUS=50 -e DURATION=45s load-tests/scenarios.js >/dev/null 2>&1
  $K6 -e MODE=local -e VUS=20 -e DURATION=20s -e USERS=200 load-tests/lab7/queries.js >/dev/null 2>&1; }

write_test() { # name — частка записів на кожен шард
  reset; local before after
  before=$(shard_counts)
  measure "$1" load-tests/scenarios.js -e SCENARIO=B -e VUS=100 -e DURATION=30s
  after=$(shard_counts)
  printf 'before: %s\nafter:  %s\n' "$before" "$after" | tee "$OUT/$1.shards"; }

set_buckets() { $ENGINE exec "$PG" psql -U shop -d shop -qc "UPDATE shard_buckets SET shard = $1"; sleep 7; }

hot_series() { # strategy label
  echo "== Hot Shard: стратегія $1"
  reset; SHARD_STRATEGY=$1 restart_apps 120s
  admin rebalance; reset
  curl -s localhost:8080/api/admin/shards | tee "$OUT/$2-status.json"; echo
  warm_paths
  write_test "$2-write"
  reset; measure "$2-read" load-tests/lab7/queries.js -e MODE=local -e VUS=50 -e DURATION=30s
}

if has hot; then
  hot_series REGION hot-region
  hot_series BUCKETS hot-buckets
  curl -s localhost:8080/api/admin/shards/matrix > "$OUT/matrix.json"
fi

if has queries; then
  echo "== Shard-Local vs Cross-Shard (20 VUs), sharded 512/512"
  for m in local fanout sales; do reset; measure "query-$m" load-tests/lab7/queries.js -e MODE=$m -e VUS=20 -e DURATION=30s; done
fi

if has benchmark; then
  for topo in single sharded; do
    if [[ $topo == single ]]; then
      echo "== Single Node: усі 1024 бакети → shard a (resharding через таблицю бакетів)"; set_buckets "'a'"
    else
      echo "== Sharded: 512 / 512 бакетів"; set_buckets "CASE WHEN bucket < 512 THEN 'a' ELSE 'b' END"
    fi
    admin rebalance; reset; echo "   $(shard_counts)"
    warm_paths
    for s in B C; do for v in 50 100 200; do reset; measure "$topo-$s-$v" load-tests/scenarios.js -e SCENARIO=$s -e VUS=$v -e DURATION=30s; done; done
    for m in local fanout; do reset; measure "$topo-query-$m" load-tests/lab7/queries.js -e MODE=$m -e VUS=20 -e DURATION=30s; done
  done
fi
echo done
