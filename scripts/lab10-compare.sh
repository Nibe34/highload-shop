#!/usr/bin/env bash
# ЛР10: Baseline (код ЛР5) vs Final на тотожному середовищі та профілі навантаження ЛР5:
# сценарії A/B/C × 10/25/50/100/200 VUs по 30 с, дані скидаються до seed-стану перед кожним виміром.
#   CONFIG=baseline — git worktree з тегу lab5, compose-проєкт shopbase (окремі томи), образ shop-app:lab5
#   CONFIG=final    — поточний стек (compose-проєкт shop)
set -uo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."
CONFIG=${CONFIG:?baseline|final}
OUT=results/lab10/$CONFIG; mkdir -p "$OUT"
ROOT=$(pwd)
LEVELS=${LEVELS:-"10 25 50 100 200"}

if [[ $CONFIG == baseline ]]; then
  WT=${WT:-$ROOT/../shop-lab5-baseline}
  [[ -d $WT ]] || git worktree add -q "$WT" lab5
  podman build -q -t localhost/shop-app:lab5 "$WT" >/dev/null
  podman-compose -p shop stop >/dev/null 2>&1
  (cd "$WT" && podman-compose -p shopbase -f compose.yaml -f "$ROOT/compose.baseline-override.yaml" up -d >/dev/null 2>&1)
  export STACK=shopbase PG=shopbase_postgres_1 REDIS=shopbase_redis_1 LB=shopbase_lb_1 SHARDS_CONTAINERS=" "
else
  export STACK=shop PG=shop_postgres_1 REDIS=shop_redis_1 LB=shop_lb_1
fi
source scripts/lib.sh
sleep 20; wait_up
scripts/reset-data.sh >/dev/null
echo "== $CONFIG: прогрів"
warmup_all
for s in A B C; do
  for v in $LEVELS; do
    scripts/reset-data.sh >/dev/null
    measure "$s-$v" load-tests/scenarios.js -e SCENARIO=$s -e VUS=$v -e DURATION=30s
  done
done
if [[ $CONFIG == baseline ]]; then
  (cd "$WT" && podman-compose -p shopbase -f compose.yaml -f "$ROOT/compose.baseline-override.yaml" down >/dev/null 2>&1)
  podman-compose -p shop start >/dev/null 2>&1
fi
echo done
