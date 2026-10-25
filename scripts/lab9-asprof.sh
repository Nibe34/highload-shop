#!/usr/bin/env bash
# ЛР9: async-profiler у форматі JFR під навантаженням сценарію C (100 VUs):
#   app-1: CPU (itimer) + allocation profiling одночасно; app-2: wall-clock (усі потоки, включно з очікуванням I/O)
# Потім jfrconv → HTML flame graphs + collapsed stacks + текстовий топ (scripts/flame_top.py).
set -uo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."
PHASE=${1:?phase}
PROF=profiling; mkdir -p "$PROF"
source scripts/lib.sh
AP=/profiling-tools/async-profiler-4.6-linux-x64/bin/asprof
CONV=profiling/tools/async-profiler-4.6-linux-x64/bin/jfrconv

scripts/reset-data.sh >/dev/null
( sleep 10
  $ENGINE exec shop_app-1_1 $AP -e itimer --alloc 512k -d 25 -f /tmp/cpu-alloc.jfr 1 >/dev/null 2>&1 &
  $ENGINE exec shop_app-2_1 $AP -e wall -d 25 -f /tmp/wall.jfr 1 >/dev/null 2>&1 &
  wait ) &
$K6 -e SCENARIO=C -e VUS=100 -e DURATION=40s load-tests/scenarios.js >/dev/null 2>&1
wait
$ENGINE cp shop_app-1_1:/tmp/cpu-alloc.jfr "$PROF/$PHASE-cpu-alloc.jfr"
$ENGINE cp shop_app-2_1:/tmp/wall.jfr "$PROF/$PHASE-wall.jfr"
$CONV --cpu --simple "$PROF/$PHASE-cpu-alloc.jfr" "$PROF/$PHASE-cpu.html"
$CONV --alloc --simple "$PROF/$PHASE-cpu-alloc.jfr" "$PROF/$PHASE-alloc.html"
$CONV --wall --simple "$PROF/$PHASE-wall.jfr" "$PROF/$PHASE-wall.html"
$CONV --cpu --simple -o collapsed "$PROF/$PHASE-cpu-alloc.jfr" "$PROF/$PHASE-cpu.collapsed"
$CONV --alloc --simple --total -o collapsed "$PROF/$PHASE-cpu-alloc.jfr" "$PROF/$PHASE-alloc.collapsed"
$CONV --wall --simple -t -o collapsed "$PROF/$PHASE-wall.jfr" "$PROF/$PHASE-wall.collapsed"
python3 scripts/flame_top.py "$PROF/$PHASE" > "$PROF/$PHASE-hotspots.txt"
cat "$PROF/$PHASE-hotspots.txt"
