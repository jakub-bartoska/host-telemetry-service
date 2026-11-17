#!/usr/bin/env bash
# Scenario 2: backfill a week of history, then compress it and report the table sizes.
# Usage: week-backfill.sh [devices]   (or DEVICES / DAYS / INTERVAL / RATE; defaults 1000, 7, 120s, 5000 msg/s)
# PASS: dropped == 0, rejected == 0, batches.failed == 0; received == simulator sent; count(*) == 6 x received.
# Sizes before/after compression are reported (INFO), not asserted.
set -u
SCENARIO=week-backfill
source "$(dirname "${BASH_SOURCE[0]}")/../lib/common.sh"
DEVICES=${1:-${DEVICES:-1000}}; DAYS=${DAYS:-7}; INTERVAL=${INTERVAL:-120s}; RATE=${RATE:-5000}

sizes() {  # sizes <label>: raw table and continuous aggregate sizes in MB
  local raw cagg
  raw=$(db_scalar "select round(table_bytes/1e6)||' MB table, '||round(index_bytes/1e6)||' MB indexes, '||round(total_bytes/1e6)||' MB total' from hypertable_detailed_size('telemetry')" | tr -d '\r')
  cagg=$(db_scalar "select round(sum(s.total_bytes)/1e6) from timescaledb_information.continuous_aggregates c, lateral hypertable_detailed_size(format('%I.%I', c.materialization_hypertable_schema, c.materialization_hypertable_name)::regclass) s" | tr -d '\r')
  info "$1: telemetry $raw; continuous aggregate ${cagg:-?} MB"
}

echo "== week-backfill: $DEVICES devices x $DAYS days, interval $INTERVAL, rate $RATE msg/s =="
snapshot "$RUN_DIR/before.txt"
start_sampler 10
START=$(now)
sim_cmd --mode backfill --devices "$DEVICES" --days "$DAYS" --interval "$INTERVAL" --rate "$RATE" > "$RUN_DIR/sim.txt" 2>&1
SIM_EXIT=$?
BACKFILL_S=$(( $(now) - START ))
sleep 15
snapshot "$RUN_DIR/after.txt"
stop_sampler

SENT=$(sim_field sent); RECV=$(delta messages.received)
COUNT=$(db_scalar "select count(*) from telemetry" | tr -d '\r')
echo "simulator: $(grep 'summary:' "$RUN_DIR/sim.txt" | tail -1)"
info "backfill wall time $BACKFILL_S s; chunks: $(db_scalar "select count(*) from show_chunks('telemetry')" | tr -d '\r')"
sizes "before compression"
START=$(now)
# The policy job is looked up by name; CALL cannot take a subquery, so psql \gexec runs the generated statement.
echo "select format('call run_job(%s)', job_id) from timescaledb_information.jobs where proc_name = 'policy_compression' \gexec" \
  | dc exec -T db psql -U telemetry -d telemetry > "$RUN_DIR/compression.txt" 2>&1
info "compression job: $(( $(now) - START )) s; compressed chunks: $(db_scalar "select count(*) from timescaledb_information.chunks where hypertable_name = 'telemetry' and is_compressed" | tr -d '\r')"
sizes "after compression"
info "divide the sizes by $DEVICES for per-device numbers (sample interval $INTERVAL, $DAYS days)"
echo "---- verdict ----"
assert_eq "simulator exit code" "$SIM_EXIT" 0
assert_eq "dropped delta" "$(delta messages.dropped)" 0
assert_eq "rejected delta" "$(delta messages.rejected)" 0
assert_eq "batches.failed delta" "$(delta batches.failed)" 0
assert_eq "received delta == simulator sent" "$RECV" "${SENT:-?}"
assert_eq "count(*) == 6 x received" "$COUNT" $(( 6 * RECV ))
finish
