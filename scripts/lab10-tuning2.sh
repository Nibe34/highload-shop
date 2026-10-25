#!/usr/bin/env bash
# ЛР10: підтвердження — Tomcat threads 200 vs 16 та LB Round Robin vs Least Connections (ABAB на тих самих JVM).
set -uo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."
OUT=results/lab10/tuning; mkdir -p "$OUT"
source scripts/lib.sh
for threads in 16 200; do
  scripts/reset-data.sh >/dev/null
  TOMCAT_MAX_THREADS=$threads restart_apps 30s; warmup_all
  for rep in 1 2; do
    for algo in round-robin least-conn; do
      scripts/lb-switch.sh $algo >/dev/null; sleep 1
      for s in A C; do scripts/reset-data.sh >/dev/null; measure "t$threads-$algo-$s-$rep" load-tests/scenarios.js -e SCENARIO=$s -e VUS=100 -e DURATION=30s; done
    done
  done
done
scripts/lb-switch.sh round-robin
echo done
