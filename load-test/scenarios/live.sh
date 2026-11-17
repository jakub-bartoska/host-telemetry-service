#!/usr/bin/env bash
# Scenario 3: N live devices on the running stack for DURATION.
# Usage: live.sh [devices] [duration]   (or DEVICES / DURATION / INTERVAL / DRAIN_S)
# PASS: simulator exit 0 and errorFrames=0; dropped, rejected and batches.failed did not grow;
#       received delta == messages the simulator sent; rows.written delta == 6 x received delta.
set -u
SCENARIO=live
source "$(dirname "${BASH_SOURCE[0]}")/../lib/common.sh"
DEVICES=${1:-${DEVICES:-1000}}; DURATION=${2:-${DURATION:-5m}}
INTERVAL=${INTERVAL:-10s}; DRAIN_S=${DRAIN_S:-15}

echo "== live: $DEVICES devices, interval $INTERVAL, duration $DURATION =="
snapshot "$RUN_DIR/before.txt"
start_sampler 5
sim_cmd --mode live --devices "$DEVICES" --interval "$INTERVAL" --duration "$DURATION" > "$RUN_DIR/sim.txt" 2>&1
SIM_EXIT=$?
sleep "$DRAIN_S"   # let the writer drain the queue
snapshot "$RUN_DIR/after.txt"
stop_sampler

SENT=$(sim_field sent); ERR=$(sim_field errorFrames)
RECV=$(delta messages.received); ROWS=$(delta rows.written)
echo "simulator: $(grep 'summary:' "$RUN_DIR/sim.txt" | tail -1)"
echo "deltas: received=$RECV dropped=$(delta messages.dropped) rejected=$(delta messages.rejected) rows.written=$ROWS batches.failed=$(delta batches.failed)"
echo "---- verdict ----"
assert_eq "simulator exit code" "$SIM_EXIT" 0
assert_eq "simulator errorFrames" "${ERR:-?}" 0
assert_eq "dropped delta" "$(delta messages.dropped)" 0
assert_eq "rejected delta" "$(delta messages.rejected)" 0
assert_eq "batches.failed delta" "$(delta batches.failed)" 0
assert_eq "received delta == simulator sent" "$RECV" "${SENT:-?}"
assert_eq "rows.written delta == 6 x received" "$ROWS" $(( 6 * RECV ))
finish
