#!/usr/bin/env bash
# Scenario 4: Charts API latency (sequential client) on a freshly seeded week of data.
# Usage: chart-latency.sh [count]   (or COUNT, SEED_DEVICES, DAYS, INTERVAL, RATE, LIVE_DEVICES, P99_MAX_MS)
# Seeds SEED_DEVICES devices (default 100) with DAYS days (7) of history, then measures hour / day / week
# for one device and for COUNT distinct devices. LIVE_DEVICES > 0 keeps that many live devices running during the
# measurement (latency during ingest). PASS: no failed request and p99 <= P99_MAX_MS (default 200) in every row.
set -u
SCENARIO=chart-latency
source "$(dirname "${BASH_SOURCE[0]}")/../lib/common.sh"
COUNT=${1:-${COUNT:-200}}; SEED_DEVICES=${SEED_DEVICES:-100}; DAYS=${DAYS:-7}; INTERVAL=${INTERVAL:-120s}
RATE=${RATE:-5000}; LIVE_DEVICES=${LIVE_DEVICES:-0}; P99_MAX_MS=${P99_MAX_MS:-200}
command -v node >/dev/null 2>&1 || { echo "setup error: node is required for latency.mjs" >&2; exit 2; }
[ "$COUNT" -gt "$SEED_DEVICES" ] && COUNT=$SEED_DEVICES   # 'spread' needs one seeded device per request

echo "== chart-latency: seed $SEED_DEVICES devices x $DAYS days, then $COUNT requests per row, live devices $LIVE_DEVICES =="
if ! sim_cmd --mode backfill --devices "$SEED_DEVICES" --days "$DAYS" --interval "$INTERVAL" --rate "$RATE" > "$RUN_DIR/seed.txt" 2>&1; then
  echo "setup error: seeding failed, see $RUN_DIR/seed.txt" >&2; exit 2
fi
sleep 15
start_sampler 5
if [ "$LIVE_DEVICES" -gt 0 ]; then
  sim_cmd --mode live --devices "$LIVE_DEVICES" --interval 10s --duration 1h > "$RUN_DIR/sim.txt" 2>&1 &
  sleep 60
fi

echo "---- verdict (ms) ----"
for range in hour day week; do
  for target in host-0001 spread; do
    out=$(node "$LT_DIR/lib/latency.mjs" "$range" "$COUNT" "$target")
    echo "$out" >> "$RUN_DIR/latency.jsonl"
    p99=$(echo "$out" | grep -o '"p99":"[0-9.]*"' | cut -d'"' -f4)
    fails=$(echo "$out" | grep -o '"failures":[0-9]*' | cut -d: -f2)
    info "$range $target: $out"
    assert_eq "$range $target failed requests" "${fails:-?}" 0
    assert_le "$range $target p99" "${p99%.*}" "$P99_MAX_MS" " ms"
  done
done
sim_kill TERM
finish
