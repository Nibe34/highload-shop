#!/usr/bin/env bash
# ЛР5: Performance Baseline. Результати: results/lab05/ (≈50 хв).
#   1) сценарії A/B/C × 10/25/50/100/200 VUs (окремі прогони constant-vus по 30 с)
#   2) ступеневий ramp 10→25→50→100→200 (сценарій C) для графіка в часі (сирий CSV у results/raw, у git — агрегат)
#   3) Scaling Impact: 1 vs 3 інстанси (A, C)
#   4) Caching Impact: Cache Disabled vs Enabled (A), Cold vs Warm
#   5) Coordinated Omission: closed vs open model (constant-arrival-rate)
set -uo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."
OUT=${OUT:-results/lab05}; RAW=results/raw/lab05; mkdir -p "$OUT" "$RAW"
source scripts/lib.sh
LEVELS=${LEVELS:-"10 25 50 100 200"}
STEPS=${STEPS:-"env levels ramp scaling caching co"}
has() { [[ " $STEPS " == *" $1 "* ]]; }

if has env; then
{
  echo "# Baseline Environment Specification"
  echo; echo "Дата: $(date -Iseconds)"
  echo "Хост: $(lscpu | grep 'Model name' | sed 's/.*: *//'), $(nproc) потоків, RAM $(free -g | awk '/Mem/{print $2}') GB, $(uname -r)"
  echo "Контейнери: $(podman --version), $(podman-compose version 2>/dev/null | tail -1)"
  echo "Load generator: $(k6 version | head -1), прив'язаний до CPU 12-15 (taskset)"
  echo; echo "| Контейнер | CPU limit | Memory limit | Образ |"; echo "|---|---|---|---|"
  for c in $(podman ps --format '{{.Names}}' | grep '^shop_' | sort); do
    podman inspect "$c" --format "| {{.Name}} | {{.HostConfig.NanoCpus}} | {{.HostConfig.Memory}} | {{.ImageName}} |" \
      | awk -F'|' '{printf "|%s| %.1f | %d MB |%s|\n", $2, $3/1e9, $4/1048576, $5}'
  done
  echo; echo "JVM: $(podman exec shop_app-1_1 java -version 2>&1 | head -1); JAVA_OPTS=-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
  echo "Tomcat max threads: 200; HikariCP maximumPoolSize: 10 на інстанс (30 сумарно)"
  echo "PostgreSQL: $(podman exec "$PG" postgres --version), max_connections=200, shared_buffers=256MB"
  echo "Redis: $(podman exec shop_redis_1 redis-server --version | awk '{print $3}'), AOF everysec"
  echo "nginx: Round Robin, keepalive 64; backend instances: 3"
  echo "Дані: $(podman exec "$PG" psql -U shop -tAc "SELECT 'products='||(SELECT count(*) FROM products)||', users='||(SELECT count(*) FROM users)||', orders='||(SELECT count(*) FROM orders)||', order_items='||(SELECT count(*) FROM order_items)")"
} > "$OUT/environment.md"
cat "$OUT/environment.md"
fi

if has levels; then
  echo "== Прогрів (180 с)"; scripts/lb-switch.sh round-robin; warmup 180s
  for s in A B C; do
    echo "== Сценарій $s"
    $K6 -e SCENARIO=$s -e VUS=20 -e DURATION=20s load-tests/scenarios.js >/dev/null 2>&1
    for v in $LEVELS; do measure "$s-$v" load-tests/scenarios.js -e SCENARIO=$s -e VUS=$v -e DURATION=30s; done
  done
fi

if has ramp; then
  for s in ${RAMP_SCENARIOS:-C}; do
    echo "== Ramp $s"
    measure "ramp-$s" load-tests/scenarios.js -e SCENARIO=$s -e PROFILE=ramp --out "csv=$RAW/ramp-$s.csv.gz"
    python3 scripts/k6_timeseries.py "$RAW/ramp-$s.csv.gz" > "$OUT/ramp-$s-timeseries.csv"
  done
fi

if has scaling; then
  echo "== Scaling Impact: 1 інстанс"
  scripts/lb-switch.sh scale-1; warmup 20s
  for s in A C; do for v in 50 100 200; do measure "scale1-$s-$v" load-tests/scenarios.js -e SCENARIO=$s -e VUS=$v -e DURATION=30s; done; done
  scripts/lb-switch.sh round-robin
fi

if has caching; then
  echo "== Caching Impact: Cache Disabled"
  CACHE_ENABLED=false restart_apps 180s
  for v in $LEVELS; do measure "nocache-A-$v" load-tests/scenarios.js -e SCENARIO=A -e VUS=$v -e DURATION=30s; done
  echo "== Cache Enabled, Cold → Warm"
  restart_apps 180s
  podman exec shop_redis_1 sh -c "redis-cli --scan --pattern 'shop:*' | xargs -r -n 500 redis-cli DEL" >/dev/null
  measure "cold-A-100" load-tests/scenarios.js -e SCENARIO=A -e VUS=100 -e DURATION=10s
  measure "warm-A-100" load-tests/scenarios.js -e SCENARIO=A -e VUS=100 -e DURATION=10s
fi

if has co; then
  echo "== Coordinated Omission: 1 інстанс, closed (200 VUs) vs open (arrival rate)"
  scripts/lb-switch.sh scale-1; warmup 20s
  measure "co-closed" load-tests/scenarios.js -e SCENARIO=A -e VUS=200 -e DURATION=30s
  RATE=$(python3 -c "import json;print(int(json.load(open('$OUT/co-closed.json'))['metrics']['http_reqs']['rate']*1.2))")
  echo "   open model rate=$RATE/s (120 % від closed-model throughput)"
  measure "co-open" load-tests/open-model.js -e RATE=$RATE -e DURATION=30s
  scripts/lb-switch.sh round-robin
fi
echo done
