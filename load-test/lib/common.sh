#!/usr/bin/env bash
# Shared helpers for the load-test scenarios. Source it, do not run it:  source "$(dirname "$0")/../lib/common.sh"
# Every knob is an environment variable with a default; see load-test/README.md.

LT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
REPO_DIR=$(cd "$LT_DIR/.." && pwd)

PROJECT=${PROJECT:-hosload}        # Compose project name; containers are named <PROJECT>-app-1 etc.
APP_PORT=${APP_PORT:-18080}        # host port of the service (the base compose file defaults to 8080)
DB_PORT=${DB_PORT:-15432}          # host port of PostgreSQL
export PROJECT APP_PORT DB_PORT
BASE=http://localhost:$APP_PORT
SIM_NAME=$PROJECT-sim

if [ -z "${DC:-}" ]; then
  if docker compose version >/dev/null 2>&1; then DC="docker compose"
  elif command -v docker-compose >/dev/null 2>&1; then DC="docker-compose"
  else echo "setup error: neither 'docker compose' nor 'docker-compose' works (set DC=...)" >&2; exit 2; fi
fi
read -r -a DC_CMD <<< "$DC"        # DC must not contain paths with spaces
if command -v python3 >/dev/null 2>&1; then PY=python3; else PY=python; fi

dc() {
  "${DC_CMD[@]}" -p "$PROJECT" --project-directory "$REPO_DIR" \
    -f "$REPO_DIR/docker-compose.yml" -f "$LT_DIR/docker-compose.load.yml" "$@"
}

# Results folder: set by run.sh, or created here when a scenario is run directly.
if [ -z "${RUN_DIR:-}" ]; then
  RUN_DIR=${OUT_DIR:-$LT_DIR/results}/$(date +%Y%m%d-%H%M%S)-${SCENARIO:-adhoc}
fi
mkdir -p "$RUN_DIR"
export RUN_DIR

now() { date +%s; }

# "10m" -> 600, "90s" -> 90, "2h" -> 7200, "45" -> 45
to_seconds() {
  case $1 in
    *h) echo $(( ${1%h} * 3600 )) ;;
    *m) echo $(( ${1%m} * 60 )) ;;
    *s) echo "${1%s}" ;;
    *) echo "$1" ;;
  esac
}

wait_for_health() {  # wait_for_health [timeout_s]
  local limit=${1:-${HEALTH_TIMEOUT_S:-300}} t0
  t0=$(now)
  until curl -sf "$BASE/actuator/health" >/dev/null; do
    [ $(( $(now) - t0 )) -ge "$limit" ] && return 1
    sleep 2
  done
}

# metric <name> [tag]  -> the metric's value as an integer; empty if the service does not answer or the
# metric does not exist yet (a counter that was never incremented). Tag-less queries sum over all tags.
metric() {
  local url="$BASE/actuator/metrics/$1"
  [ -n "${2:-}" ] && url="$url?tag=$2"
  curl -s "$url" | grep -o '"value":[0-9.eE+-]*' | head -1 | cut -d: -f2 | awk '{printf "%.0f", $1}'
}

METRICS="messages.received messages.dropped messages.rejected rows.written batches.failed queue.depth"
snapshot() {  # snapshot <file>: one "<name> <value>" line per metric
  local m v
  for m in $METRICS; do v=$(metric "telemetry.$m"); echo "$m ${v:-0}"; done > "$1"
}
val() { awk -v k="$2" '$1==k {print ($2==""?0:$2)}' "$1"; }          # val <snapshot> <name>
delta() { echo $(( $(val "$RUN_DIR/after.txt" "$1") - $(val "$RUN_DIR/before.txt" "$1") )); }

db_scalar() { dc exec -T db psql -U telemetry -d telemetry -Atc "$1"; }

# ---- simulator and sampler -------------------------------------------------------------------------
SAMPLER_PID=""
start_sampler() { "$PY" -I "$LT_DIR/lib/sample.py" "${1:-5}" > "$RUN_DIR/sampler.csv" & SAMPLER_PID=$!; }
stop_sampler() { [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" 2>/dev/null; SAMPLER_PID=""; }
sim_kill() { docker kill -s "${1:-KILL}" "$SIM_NAME" >/dev/null 2>&1; }
# sim_cmd <simulator args...>: the simulator container runs on the Compose network, named $SIM_NAME.
sim_cmd() { dc --profile sim run --rm --name "$SIM_NAME" simulator "$@"; }
sim_field() { grep 'summary:' "$RUN_DIR/sim.txt" | tail -1 | grep -o "$1=[0-9]*" | head -1 | cut -d= -f2; }

cleanup() { stop_sampler; sim_kill; }
trap cleanup EXIT

# ---- results ---------------------------------------------------------------------------------------
FAILS=0
verdict() {  # verdict <PASS|FAIL> <text>
  printf '%-5s %s\n' "$1" "$2"
  [ "$1" = FAIL ] && FAILS=$(( FAILS + 1 ))
  return 0
}
assert_eq() {  # assert_eq <label> <actual> <expected>
  if [ "$2" = "$3" ]; then verdict PASS "$1: $2 (expected $3)"; else verdict FAIL "$1: $2 (expected $3)"; fi
}
assert_le() {  # assert_le <label> <actual> <max> [unit]
  if [ -n "$2" ] && [ "$2" -le "$3" ]; then verdict PASS "$1: $2${4:-} (limit $3${4:-})"
  else verdict FAIL "$1: ${2:-never}${4:-} (limit $3${4:-})"; fi
}
info() { printf 'INFO  %s\n' "$*"; }

# finish: print peaks and the overall result; exit 0 on PASS, 1 on FAIL.
finish() {
  stop_sampler
  if [ -s "$RUN_DIR/sampler.csv" ]; then
    echo "---- sampler peaks (sampler.csv, 5 s resolution; docker stats CPU 100 % = 1 core) ----"
    "$PY" -I "$LT_DIR/lib/csv-peaks.py" "$RUN_DIR/sampler.csv"
  fi
  if [ "$FAILS" -eq 0 ]; then echo "RESULT: PASS ($SCENARIO)"; else echo "RESULT: FAIL ($SCENARIO, $FAILS criteria failed)"; fi
  echo "files: $RUN_DIR"
  [ "$FAILS" -eq 0 ]
  exit $?
}
