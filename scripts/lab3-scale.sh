#!/usr/bin/env bash
# ЛР3 (крок 4): scale-out 1 / 2 / 3 інстанси. Усі JVM попередньо прогріті; кількість вузлів у пулі
# задається upstream-конфігом nginx (без рестарту контейнерів — інакше вимірюється JIT-прогрів, а не масштабування).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=results/lab03; mkdir -p "$OUT"
source scripts/lib.sh
scripts/lb-switch.sh round-robin; warmup 180s
for n in 1 2 3; do
  if [[ $n == 3 ]]; then scripts/lb-switch.sh round-robin; else scripts/lb-switch.sh "scale-$n"; fi
  sleep 1; warmup 10s
  measure "scale-$n-read" load-tests/lab3/balance.js -e VUS=100 -e DURATION=30s
  measure "scale-$n-mixed" load-tests/lab3/scale.js -e VUS=100 -e DURATION=30s
done
scripts/lb-switch.sh round-robin
echo done
