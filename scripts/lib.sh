# Спільні функції для скриптів експериментів (source scripts/lib.sh).
K6=${K6:-"taskset -c 12-15 k6 run -q"}
ENGINE=${ENGINE:-podman}

wait_up() { for _ in $(seq 90); do curl -sf localhost:8080/health >/dev/null && return 0; sleep 1; done; echo "health timeout" >&2; }

# JIT-прогрів: при 1 CPU на інстанс C2-компіляція займає десятки секунд — без прогріву результати занижені в рази.
warmup() { $K6 -e VUS=50 -e DURATION=${1:-45s} load-tests/lab3/balance.js >/dev/null 2>&1; }

# Сумарний CPU (секунди) контейнерів shop_* з cgroup (podman stats --no-stream показує середнє за весь час життя).
cpu_snapshot() { for c in $($ENGINE ps --format '{{.Names}}' | grep '^shop_' | sort); do
  printf '%s %s\n' "$c" "$($ENGINE exec "$c" sh -c 'grep usage_usec /sys/fs/cgroup/cpu.stat' 2>/dev/null | cut -d' ' -f2)"; done; }

cpu_delta() { # before_file after_file seconds
  join "$1" "$2" | awk -v s="$3" '{ printf "%s %.2f\n", $1, ($3 - $2) / 1e6 / s }'; }

# measure NAME SCRIPT [k6 args...] — запуск з експортом summary і CPU-утилізації контейнерів (ядер).
measure() { local name=$1 script=$2; shift 2
  cpu_snapshot > "$OUT/.cpu0"; local t0=$(date +%s)
  $K6 "$@" --summary-export "$OUT/$name.json" "$script" > "$OUT/$name.txt" 2>&1
  cpu_snapshot > "$OUT/.cpu1"; cpu_delta "$OUT/.cpu0" "$OUT/.cpu1" $(( $(date +%s) - t0 )) > "$OUT/$name.cpu"
  rm -f "$OUT/.cpu0" "$OUT/.cpu1"; echo "   $name: $(grep -E 'http_reqs\.' "$OUT/$name.txt" | awk '{print $3}') rps"; }
