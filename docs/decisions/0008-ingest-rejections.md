# 0008 — Ingest rejections: a table per device and reason, summed per window

Status: accepted

## Context
A rejected message increments `telemetry.messages.rejected{reason}` and the device gets an error frame
(ADR 0002). A stats entry discarded from an accepted message increments `telemetry.stats.discarded`
and nothing else: no frame, no log line. Neither says **which device**. If 10 of 1 000 devices send
bad data, an operator sees "100 rejections a minute for `timestamp_in_future`" and cannot name one of
them; a temperature sent as a string is an empty chart with no trace of the cause. The handler's log
line is DEBUG and carries the session id, not the device id.

## Decision
- **Table `ingest_rejections`** (migration V3), one row per flush window, device and reason:
  `window_start`, `window_end`, `device_id` (`NULL` when the message carried no valid id), `reason`
  (the error-frame code, or `discarded_stats`), `message_count`, `discarded_keys text[]` and `sample`
  (the last such message of the window, first 1 000 characters). A hypertable with 1-day chunks and
  the same 8-day retention as the telemetry.
- **`RejectionLog`** sums rejections in memory per (device, reason) and writes the window every
  `rejection-flush-interval` (60 s) from its own thread. The WebSocket handler still never touches
  the database, and a clean message never takes the log's lock.
- **Validation reports the sender.** A rejection carries the device id once the id itself has passed
  validation (every reason from `invalid_timestamp` on); an accepted message carries the keys of its
  discarded entries.
- **Loss is accepted and counted** in `telemetry.rejections.unrecorded`: a window whose write fails is
  dropped, not retried; a window holds at most `rejection-max-entries` (10 000) device and reason
  pairs, further pairs are only counted. Shutdown writes the last window; a crash loses the open one
  (at most 60 s).
- **Read with SQL** (query in the README). No HTTP endpoint.

## Alternatives considered
- **A `deviceId` label on the metrics.** One time series per device and reason: tens of thousands of
  series at 10 000 devices, held in the service's memory and in every system that scrapes it. The
  metrics stay per reason.
- **A rate-limited WARN log with the device id.** The cheapest option, but "who sends bad data" then
  needs a log aggregator to group by device, and this deployment has none. Can be added next to the
  table.
- **One row per rejected message.** Exact timestamps and every payload, but the volume is set by the
  faulty devices: 1 000 misconfigured devices write 100 rows/s of about 1 KB each, 10 000 write as
  many rows as the telemetry itself has messages. Summed per window the same fleet is 1 000 rows per
  minute, whatever its message rate.
- **Sending rejections through the telemetry queue and writer.** Diagnostics would compete with data
  for the one writer whose ceiling is measured (ADR 0002).

## Consequences
- Time resolution is the window: the table says a device failed 6 times between 14:03 and 14:04, not
  when exactly. One sample per row, not every payload.
- When `telemetry.rejections.unrecorded` grew during a window, its counts are a lower bound.
- Memory at the limit: 10 000 pairs, each with a sample of up to 1 000 characters and up to 32 keys of
  up to 64 characters, so tens of MB in the worst case (calculation, not measured).
- A device is still not told about a discarded entry; only the operator can see it.
- The protocol has no device type or agent version, so the table answers "which devices", not "which
  model"; that needs a device registry (out of scope, ADR 0005).
- Not load tested: the load test sends only valid messages.
