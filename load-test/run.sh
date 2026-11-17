#!/usr/bin/env bash
# Load-test runner: clean stack up -> wait for health -> scenario -> PASS/FAIL summary -> stack down -v.
# Usage: load-test/run.sh <scenario> [args]      scenarios: see load-test/scenarios/ and load-test/README.md
# Exit code: 0 = PASS, 1 = FAIL, 2 = setup error (Docker not running, build failed, no health, unknown scenario).
# KEEP_STACK=1 leaves the stack running afterwards. All knobs are environment variables (see README.md).
set -u
LT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SCENARIO=${1:-}
if [ -z "$SCENARIO" ] || [ ! -f "$LT_DIR/scenarios/$SCENARIO.sh" ]; then
  echo "usage: load-test/run.sh <scenario> [args]; scenarios:" >&2
  ls "$LT_DIR/scenarios" | sed 's/\.sh$//;s/^/  /' >&2
  exit 2
fi
shift
export SCENARIO

# shellcheck source=lib/common.sh
source "$LT_DIR/lib/common.sh"
trap - EXIT   # the runner has no simulator or sampler of its own; each scenario cleans up after itself

docker info >/dev/null 2>&1 || { echo "setup error: Docker is not running" >&2; exit 2; }
echo "scenario=$SCENARIO args='$*' results=$RUN_DIR"

teardown() { [ "${KEEP_STACK:-0}" = 1 ] && echo "KEEP_STACK=1: stack left running (cleanup: $DC -p $PROJECT down -v)" || dc down -v >/dev/null 2>&1; }

# A cancelled job (Ctrl+C, pipeline abort) must not leave the stack or the simulator running.
trap 'sim_kill; teardown; exit 2' INT TERM

dc down -v >/dev/null 2>&1
if ! dc --profile sim build > "$RUN_DIR/build.log" 2>&1 || ! dc up -d db app >> "$RUN_DIR/build.log" 2>&1; then
  echo "setup error: build or start failed, see $RUN_DIR/build.log" >&2; teardown; exit 2
fi
if ! wait_for_health; then
  echo "setup error: $BASE/actuator/health not UP after ${HEALTH_TIMEOUT_S:-300} s" >&2
  dc logs --tail 40 app >&2; teardown; exit 2
fi

started=$(now)
bash "$LT_DIR/scenarios/$SCENARIO.sh" "$@" 2>&1 | tee "$RUN_DIR/summary.txt"
status=${PIPESTATUS[0]}
echo "scenario wall time: $(( $(now) - started )) s, exit $status" | tee -a "$RUN_DIR/summary.txt"
dc logs --no-color app > "$RUN_DIR/app.log" 2>&1
teardown
exit "$status"
