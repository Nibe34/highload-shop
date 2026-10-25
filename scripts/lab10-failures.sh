#!/usr/bin/env bash
# ЛР10: Failure Scenarios Review під навантаженням (сценарій C, 50 VUs, 60 с):
# відмова інжектується на 20-й секунді, відновлення — на 40-й. Посекундний ряд: RPS, p99, помилки.
set -uo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."
OUT=results/lab10/failures; RAW=results/raw/lab10; mkdir -p "$OUT" "$RAW"
source scripts/lib.sh
FAILS=${FAILS:-"app redis replica shard"}

inject() { # name
  case $1 in
    app)     sleep 20; $ENGINE kill shop_app-2_1 >/dev/null;              sleep 20; $ENGINE start shop_app-2_1 >/dev/null ;;
    redis)   sleep 20; $ENGINE stop -t 0 shop_redis_1 >/dev/null;         sleep 20; $ENGINE start shop_redis_1 >/dev/null ;;
    replica) sleep 20; $ENGINE stop -t 0 shop_postgres-replica_1 >/dev/null; sleep 20; $ENGINE start shop_postgres-replica_1 >/dev/null ;;
    shard)   sleep 20; $ENGINE stop -t 0 shop_postgres-shard-b_1 >/dev/null; sleep 20; $ENGINE start shop_postgres-shard-b_1 >/dev/null ;;
  esac
}

scripts/reset-data.sh >/dev/null; warmup_all
for f in $FAILS; do
  echo "== failure: $f"
  scripts/reset-data.sh >/dev/null
  ( sleep 3; inject $f ) &      # +3 с — setup() k6 (логін 1000 користувачів)
  measure "fail-$f" load-tests/scenarios.js -e SCENARIO=C -e VUS=50 -e DURATION=60s --out "csv=$RAW/fail-$f.csv.gz"
  wait
  python3 scripts/k6_timeseries.py "$RAW/fail-$f.csv.gz" > "$OUT/fail-$f-timeseries.csv"
  sleep 15; wait_up
  warmup 30s
done
echo done
