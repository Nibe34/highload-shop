#!/usr/bin/env bash
# ЛР3: повна серія експериментів балансування. Результати: results/lab03/
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=results/lab03; mkdir -p "$OUT"
source scripts/lib.sh
C=podman-compose

echo "== 0. Прогрів усіх інстансів"
scripts/lb-switch.sh round-robin; warmup 60s

echo "== 1. Розподіл для кожного алгоритму (20 VUs, 20 s)"
for algo in round-robin least-conn weighted ip-hash; do
  scripts/lb-switch.sh "$algo"; sleep 1; warmup 10s
  measure "algo-$algo" load-tests/lab3/balance.js -e VUS=20 -e DURATION=20s
done

echo "== 2. Асиметричне навантаження: app-3 +100 мс затримки"
APP3_DELAY_MS=100 $C up -d app-3 >/dev/null 2>&1; sleep 15; wait_up; warmup 45s
for algo in round-robin least-conn; do
  scripts/lb-switch.sh "$algo"; sleep 1; warmup 10s
  measure "slow-$algo" load-tests/lab3/balance.js -e VUS=50 -e DURATION=30s
done
$C up -d app-3 >/dev/null 2>&1; sleep 15; wait_up

echo "== 3. Відмова вузла під навантаженням: kill app-2 на 10-й секунді (round-robin)"
scripts/lb-switch.sh round-robin; warmup 45s
( sleep 10; $ENGINE kill shop_app-2_1 >/dev/null; echo "   killed app-2 at $(date +%T)" ) &
measure failover-kill load-tests/lab3/balance.js -e VUS=50 -e DURATION=30s
wait
$ENGINE start shop_app-2_1 >/dev/null; sleep 15; wait_up

echo "== 4. Scale-out — див. scripts/lab3-scale.sh"; exec scripts/lab3-scale.sh
