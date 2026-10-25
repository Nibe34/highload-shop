# Спільні функції для скриптів експериментів (source scripts/lib.sh).
K6=${K6:-"taskset -c 12-15 k6 run -q"}
ENGINE=${ENGINE:-podman}

wait_up() { for _ in $(seq 90); do curl -sf localhost:8080/health >/dev/null && return 0; sleep 1; done; echo "health timeout" >&2; }

# JIT-прогрів: при 1 CPU на інстанс C2-компіляція займає десятки секунд — без прогріву результати занижені в рази.
warmup() { $K6 -e VUS=50 -e DURATION=${1:-45s} load-tests/lab3/balance.js >/dev/null 2>&1; }

# Сумарний CPU (секунди) контейнерів shop_* з cgroup (podman stats --no-stream показує середнє за весь час життя).
cpu_snapshot() { for c in $($ENGINE ps --format '{{.Names}}' | grep -E "^${STACK:-shop}_" | sort); do
  printf '%s %s\n' "$c" "$($ENGINE exec "$c" sh -c 'grep usage_usec /sys/fs/cgroup/cpu.stat' 2>/dev/null | cut -d' ' -f2)"; done; }

cpu_delta() { # before_file after_file seconds
  join "$1" "$2" | awk -v s="$3" '{ printf "%s %.2f\n", $1, ($3 - $2) / 1e6 / s }'; }

PG=${PG:-shop_postgres_1}
# Кількість SQL-викликів (pg_stat_statements) — міра навантаження на БД.
db_calls() { $ENGINE exec "$PG" psql -U shop -d shop -tAc \
  "SELECT coalesce(sum(calls),0)::bigint FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname='shop')" 2>/dev/null || echo 0; }

# Перезапуск інстансів застосунку з довгим прогрівом JIT (при 1 CPU C2-компіляція триває хвилини).
restart_apps() { podman-compose up -d --force-recreate --no-deps app-1 app-2 app-3 >/dev/null 2>&1
  sleep 10; wait_up; warmup "${1:-180s}"; }

# measure NAME SCRIPT [k6 args...] — запуск з експортом summary і CPU-утилізації контейнерів (ядер).
measure() { local name=$1 script=$2; shift 2
  cpu_snapshot > "$OUT/.cpu0"; local t0=$(date +%s) q0=$(db_calls)
  $K6 "$@" --summary-export "$OUT/$name.json" "$script" > "$OUT/$name.txt" 2>&1
  local q1=$(db_calls) t1=$(date +%s)
  cpu_snapshot > "$OUT/.cpu1"; cpu_delta "$OUT/.cpu0" "$OUT/.cpu1" $(( t1 - t0 )) > "$OUT/$name.cpu"
  echo "db_calls $(( q1 - q0 )) per_sec $(( (q1 - q0) / (t1 - t0) ))" > "$OUT/$name.db"
  rm -f "$OUT/.cpu0" "$OUT/.cpu1"; echo "   $name: $(grep -E 'http_reqs\.' "$OUT/$name.txt" | awk '{print $3}') rps, db $(( (q1 - q0) / (t1 - t0) ))/s"; }

# Прогрів УСІХ кодових шляхів (читання, запис, workflow, історія): при 1 CPU на інстанс C2 компілює
# кожен шлях окремо, і непрогрітий шлях на одному інстансі гальмує весь пул при Round Robin.
warmup_all() {
  $K6 -e VUS=50 -e DURATION=60s load-tests/lab3/balance.js >/dev/null 2>&1
  $K6 -e SCENARIO=B -e VUS=50 -e DURATION=45s load-tests/scenarios.js >/dev/null 2>&1
  $K6 -e SCENARIO=C -e VUS=50 -e DURATION=60s load-tests/scenarios.js >/dev/null 2>&1
  $K6 -e MODE=local -e VUS=20 -e DURATION=30s -e USERS=200 load-tests/lab7/queries.js >/dev/null 2>&1
}
