#!/usr/bin/env bash
# Scenario 6: reconnect storm after a service restart, then steady traffic.
# Usage: restart-storm.sh [devices] [duration]
#   env: DEVICES (10000), DURATION (10m, measured from the restart), RECONNECT_TIMEOUT_S (120),
#        CONNECT_TIMEOUT_S (180, initial connect), RESTART_WATCH_S (600, how long to wait for the return)
# Steps: connect DEVICES live devices -> snapshot -> `restart app` -> measure health UP time, time until the
# simulator reports all connections again (its status line has a 5 s period, so 5 s resolution), max queue
# depth (polled every second) -> steady traffic until DURATION after the restart -> snapshot -> stop the simulator.
# PASS: dropped and batches.failed are 0 in the old and the new process (counters restart at 0 with the process);
#       rows.written == 6 x received in the new process. The received-vs-expected difference is printed, not asserted.
set -u
SCENARIO=restart-storm
source "$(dirname "${BASH_SOURCE[0]}")/../lib/common.sh"
DEVICES=${1:-${DEVICES:-10000}}; DURATION=${2:-${DURATION:-10m}}
RECONNECT_TIMEOUT_S=${RECONNECT_TIMEOUT_S:-120}; CONNECT_TIMEOUT_S=${CONNECT_TIMEOUT_S:-180}
RESTART_WATCH_S=${RESTART_WATCH_S:-600}
N=$DEVICES; DUR_S=$(to_seconds "$DURATION")

conns() { grep -o 'connections=[0-9]*' "$RUN_DIR/sim.txt" | tail -1 | cut -d= -f2; }
lines() { grep -c 'connections=' "$RUN_DIR/sim.txt"; }

echo "== restart-storm: $N devices, interval 10s, $DURATION of traffic after the restart =="
: > "$RUN_DIR/sim.txt"
start_sampler 5
# --duration is far longer than any run; the simulator is stopped with SIGTERM below.
sim_cmd --mode live --devices "$N" --interval 10s --duration 2h >> "$RUN_DIR/sim.txt" 2>&1 &
RUN_START=$(now)

# Phase 1: wait until the simulator reports N connections
t0=$(now)
until [ "$(conns)" = "$N" ]; do
  if [ $(( $(now) - t0 )) -ge "$CONNECT_TIMEOUT_S" ]; then
    verdict FAIL "initial connect: only $(conns) of $N connections after ${CONNECT_TIMEOUT_S} s"
    finish
  fi
  sleep 1
done
info "initial connect: $N connections after $(( $(now) - t0 )) s"
snapshot "$RUN_DIR/before.txt"

# Phase 2: restart and watch the return
RESTART_AT=$(now)
dc restart app >/dev/null 2>&1
# Count status lines only from here: a line printed by the old process while `restart` was still blocking
# could show N connections and would be taken for the return.
LINES0=$(lines)
HEALTH_S=""; BACK_S=""; MAXQ=0; DIPPED=0
while :; do
  el=$(( $(now) - RESTART_AT ))
  if [ -z "$HEALTH_S" ] && curl -sf "$BASE/actuator/health" >/dev/null; then HEALTH_S=$el; fi
  if [ -n "$HEALTH_S" ]; then
    q=$(metric telemetry.queue.depth); [ "${q:-0}" -gt "$MAXQ" ] && MAXQ=$q
  fi
  c=$(conns); [ "${c:-0}" -lt "$N" ] && DIPPED=1
  # a status line printed after the restart that shows N connections again
  if [ "$(lines)" -gt "$LINES0" ] && [ "${c:-0}" = "$N" ] && { [ $DIPPED = 1 ] || [ -n "$HEALTH_S" ]; }; then BACK_S=$el; break; fi
  [ "$el" -ge "$RESTART_WATCH_S" ] && break
  sleep 1
done
info "restart: health UP after ${HEALTH_S:-never} s; all $N connections back after ${BACK_S:-never} s (5 s resolution); max queue depth during return $MAXQ"

# Phase 3: steady traffic until DURATION after the restart, then stop the simulator and take the final snapshot.
# The snapshot is taken after the devices are gone and the queue is drained: with traffic still running, the
# counters are read a few tens of milliseconds apart and received/rows.written would not match exactly.
while [ "$(now)" -lt $(( RESTART_AT + DUR_S )) ]; do sleep 5; done
sim_kill TERM
sleep 15   # devices disconnect, the writer drains the queue
snapshot "$RUN_DIR/after.txt"; AFTER_AT=$(now)
stop_sampler
STOP_AT=$(now)
info "simulator summary: $(grep 'summary:' "$RUN_DIR/sim.txt" | tail -1)"

# Phase 4: gaps in the hour chart of host-0001 inside the run window (30 s buckets)
curl -s "$BASE/api/v1/devices/host-0001/metrics/cpuUtilPct/series?range=hour" > "$RUN_DIR/series.json"
"$PY" -I "$LT_DIR/lib/null-buckets.py" "$RUN_START" "$STOP_AT" < "$RUN_DIR/series.json" > "$RUN_DIR/nulls.txt"
info "hour chart of host-0001 inside the run: $(head -1 "$RUN_DIR/nulls.txt"); restart at $(date -u -d "@$RESTART_AT" +%H:%M:%SZ 2>/dev/null)"
tail -n +2 "$RUN_DIR/nulls.txt" | sed 's/^/        null bucket /'

# Phase 5: verdict. The service's counters live in memory and restart at 0 with the process, so before.txt is the
# old process and after.txt the new one: they are compared as absolute values, never subtracted.
RECV=$(val "$RUN_DIR/after.txt" messages.received); ROWS=$(val "$RUN_DIR/after.txt" rows.written)
DROP_OLD=$(val "$RUN_DIR/before.txt" messages.dropped); DROP_NEW=$(val "$RUN_DIR/after.txt" messages.dropped)
REJ_OLD=$(val "$RUN_DIR/before.txt" messages.rejected); REJ_NEW=$(val "$RUN_DIR/after.txt" messages.rejected)
FAIL_OLD=$(val "$RUN_DIR/before.txt" batches.failed); FAIL_NEW=$(val "$RUN_DIR/after.txt" batches.failed)
RECV_OLD=$(val "$RUN_DIR/before.txt" messages.received)
DB_ROWS=$(db_scalar "select count(*) from telemetry" | tr -d '\r'); SENT=$(sim_field sent)
echo "old process (snapshot just before the restart): received=$RECV_OLD dropped=$DROP_OLD rejected=$REJ_OLD batches.failed=$FAIL_OLD"
echo "new process (counters start at 0, final snapshot): received=$RECV dropped=$DROP_NEW rejected=$REJ_NEW batches.failed=$FAIL_NEW rows.written=$ROWS"
echo "---- verdict ($N devices, $DURATION after restart) ----"
assert_eq "dropped (old + new process)" $(( DROP_OLD + DROP_NEW )) 0
assert_eq "batches.failed (old + new process)" $(( FAIL_OLD + FAIL_NEW )) 0
assert_le "all connections back after restart" "$BACK_S" "$RECONNECT_TIMEOUT_S" " s"
assert_eq "rows.written == 6 x received (new process)" "$ROWS" $(( 6 * RECV ))
# Same window on both sides: the new process counted from the restart until SIGTERM (about DUR_S seconds).
SKIPPED=$(( N * ${BACK_S:-0} / 10 ))
EXPECTED=$(( N * DUR_S / 10 - SKIPPED ))
info "received by the new process $RECV vs expected N x $DUR_S s / 10 s - skipped samples = $(( N * DUR_S / 10 )) - $SKIPPED = $EXPECTED: difference $(( RECV - EXPECTED )) (skipped-sample estimate N x ${BACK_S:-0} s / 10 s; not asserted)"
info "end to end: rows in the database $DB_ROWS vs 6 x simulator sent $(( 6 * ${SENT:-0} )): difference $(( 6 * ${SENT:-0} - DB_ROWS )) rows (not asserted; messages in flight when the old process stopped, or sent before the snapshot gap, show up here)"
info "rejected old + new: $(( REJ_OLD + REJ_NEW )) (not asserted)"
finish
