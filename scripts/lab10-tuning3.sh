#!/usr/bin/env bash
# ЛР10: вибір розміру пулу потоків з урахуванням усіх трьох профілів (CPU-bound A/C vs fsync-bound B), Least Connections.
set -uo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."
OUT=results/lab10/tuning3; mkdir -p "$OUT"
source scripts/lib.sh
for threads in ${THREADS:-16 32 64 200}; do
  scripts/reset-data.sh >/dev/null
  TOMCAT_MAX_THREADS=$threads restart_apps 30s; warmup_all
  for s in A B C; do scripts/reset-data.sh >/dev/null; measure "t$threads-$s" load-tests/scenarios.js -e SCENARIO=$s -e VUS=100 -e DURATION=30s; done
done
echo done
