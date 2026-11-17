#!/usr/bin/env bash
# Scenario 1: one backfill run at a fixed message rate on a clean stack (run.sh provides the clean stack).
# Usage: ingest-ceiling.sh [rate]   (or RATE / DEVICES / DAYS / INTERVAL; defaults 5000 msg/s, 1000 devices, 1 day, 120s)
# PASS: dropped == 0, rejected == 0, batches.failed == 0; received == simulator sent;
#       rows.written == 6 x (received - dropped) == count(*) in the DB.
# Raise RATE until it FAILs to find the ceiling (the single writer, see docs/load-test.md).
set -u
SCENARIO=ingest-ceiling
source "$(dirname "${BASH_SOURCE[0]}")/../lib/common.sh"
RATE=${1:-${RATE:-5000}}; DEVICES=${DEVICES:-1000}; DAYS=${DAYS:-1}; INTERVAL=${INTERVAL:-120s}

echo "== ingest-ceiling: backfill $DEVICES devices x $DAYS day(s), interval $INTERVAL, rate $RATE msg/s =="
snapshot "$RUN_DIR/before.txt"
start_sampler 5
sim_cmd --mode backfill --devices "$DEVICES" --days "$DAYS" --interval "$INTERVAL" --rate "$RATE" > "$RUN_DIR/sim.txt" 2>&1
SIM_EXIT=$?
sleep 10   # let the writer drain the queue
snapshot "$RUN_DIR/after.txt"
stop_sampler

SENT=$(sim_field sent); RECV=$(delta messages.received); DROP=$(delta messages.dropped); ROWS=$(delta rows.written)
COUNT=$(db_scalar "select count(*) from telemetry" | tr -d '\r')
echo "simulator: $(grep 'summary:' "$RUN_DIR/sim.txt" | tail -1)"
echo "deltas: received=$RECV dropped=$DROP rejected=$(delta messages.rejected) rows.written=$ROWS count(*)=$COUNT"
echo "---- verdict ----"
assert_eq "simulator exit code" "$SIM_EXIT" 0
assert_eq "simulator errorFrames" "$(sim_field errorFrames)" 0
assert_eq "dropped delta" "$DROP" 0
assert_eq "rejected delta" "$(delta messages.rejected)" 0
assert_eq "batches.failed delta" "$(delta batches.failed)" 0
assert_eq "received delta == simulator sent" "$RECV" "${SENT:-?}"
assert_eq "rows.written == 6 x (received - dropped)" "$ROWS" $(( 6 * (RECV - DROP) ))
assert_eq "count(*) == rows.written" "$COUNT" "$ROWS"
finish
