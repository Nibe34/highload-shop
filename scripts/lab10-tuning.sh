#!/usr/bin/env bash
# ЛР10: фінальна оптимізація — обмеження конкурентності (Tomcat threads на 1-CPU інстанс) та алгоритм балансування.
set -uo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."
OUT=results/lab10/tuning; mkdir -p "$OUT"
source scripts/lib.sh
for threads in ${THREADS:-200 64 32 16}; do
  echo "== Tomcat max threads = $threads"
  scripts/reset-data.sh >/dev/null
  TOMCAT_MAX_THREADS=$threads restart_apps 30s; warmup_all
  for s in A C; do scripts/reset-data.sh >/dev/null; measure "t$threads-$s-100" load-tests/scenarios.js -e SCENARIO=$s -e VUS=100 -e DURATION=30s; done
done
echo done
