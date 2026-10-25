#!/usr/bin/env bash
# ЛР6: Read Scaling Impact — Single Primary vs Primary + Replica.
# Кеш вимкнено (CACHE_ENABLED=false), щоб читання справді доходили до СУБД.
set -uo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."
OUT=results/lab06; mkdir -p "$OUT"
source scripts/lib.sh
VUS_LIST=${VUS_LIST:-"50 100 200"}
SCENARIOS=${SCENARIOS:-"A C"}
TOPOLOGIES=${TOPOLOGIES:-"single replica"}

run_topology() { # name replica_enabled
  echo "== Топологія $1 (REPLICA_ENABLED=$2, CACHE_ENABLED=false)"
  scripts/reset-data.sh >/dev/null
  REPLICA_ENABLED=$2 CACHE_ENABLED=false restart_apps 180s
  for s in $SCENARIOS; do
    $K6 -e SCENARIO=$s -e VUS=20 -e DURATION=15s load-tests/scenarios.js >/dev/null 2>&1
    for v in $VUS_LIST; do measure "$1-$s-$v" load-tests/scenarios.js -e SCENARIO=$s -e VUS=$v -e DURATION=30s; done
  done
}

[[ " $TOPOLOGIES " == *" single "* ]] && run_topology single false
[[ " $TOPOLOGIES " == *" replica "* ]] && run_topology replica true
echo "== Повернення до штатної конфігурації (replica + cache)"
REPLICA_ENABLED=true restart_apps 60s
echo done
