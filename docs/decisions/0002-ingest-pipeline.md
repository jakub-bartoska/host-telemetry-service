# 0002 — Ingest: validate per message, bounded queue, single batch writer

Status: accepted

Correction (2026-11-12): the Disconnect row of the failure table first claimed a Tomcat idle timeout of
60 s. That was wrong: no idle timeout was configured. Since then the service sets one (90 s,
`telemetry.ingest.session-idle-timeout`); the Disconnect and Silent device rows describe that.

## Context
Peak load at 10 000 devices is 1 000 msg/s = 6 000 rows/s (ADR 0001); a message is ~250 B of JSON,
so ~250 KB/s of network input. The protocol has no acknowledgement. The assignment leaves malformed
messages, disconnects, silent devices, late/out-of-order data and duplicates to us.

## Decision
- **Endpoint** `ws://…/ws/telemetry`, Spring `TextWebSocketHandler`, plain JSON, no STOMP.
  The handler only parses, validates and enqueues — it never touches the database.
- **Validation** (unit tested). A message is rejected whole if: invalid JSON, `type != "stats"`,
  `deviceId` not `^[A-Za-z0-9._-]{1,64}$`, `timestamp` not an ISO-8601 instant, timestamp more than
  **5 min in the future** or **older than 7 days**, or `stats` empty / more than 32 keys. Inside
  `stats`, a single entry with a bad key (`^[A-Za-z][A-Za-z0-9_]{0,63}$`) or a non-finite / non-numeric
  / `null` value is dropped and the rest is kept (one broken sensor must not hide CPU). No per-metric
  range checks — the map is generic. On rejection we send `{"type":"error","reason":…}` and keep
  the connection open; a counter is incremented. Rejections and discarded entries are also recorded
  per device in the `ingest_rejections` table (ADR 0008).
- **Bounded queue** of 10 000 messages (≈10 s at 10k devices, a few MB). `offer()` never blocks;
  when full the message is **dropped and counted** (`telemetry.dropped`) — load shedding instead of
  blocking Tomcat threads.
- **One writer thread** drains the queue and flushes every 1 000 messages (6 000 rows) **or** 500 ms,
  as one JDBC batch with `reWriteBatchedInserts=true` and
  `INSERT … ON CONFLICT DO NOTHING` on the `(device_id, metric, ts)` unique key (Exposed
  `batchInsert(ignore = true)` since ADR 0007; first `JdbcTemplate.batchUpdate`). Transient DB errors: retry the same
  batch with backoff (queue fills, then sheds). Non-transient errors: log, count, drop that batch.
- **Graceful shutdown** (`SmartLifecycle`): stop accepting, drain the queue, flush, then exit.
- **Timestamps**: the device timestamp is the measurement time and is what we store.
- **Micrometer counters** via Actuator: received, rejected, dropped, written, batch latency, queue depth.

| Situation | What we do |
|---|---|
| Malformed message | Reject, error frame, counter; connection stays open. Device, reason and a sample go to `ingest_rejections` once a minute (ADR 0008) |
| Bad entry in `stats` | Entry discarded, rest of the message stored, counter; no frame. Device and discarded keys go to `ingest_rejections` (ADR 0008) |
| Duplicate message (resend) | Unique key + `ON CONFLICT DO NOTHING`; exact resends are no-ops |
| Late / out-of-order | Accepted up to 7 days; raw query sees it at once, aggregate on next refresh (invalidation) |
| Device clock in the future | > 5 min ahead rejected; a clock that is *behind* is indistinguishable from late data — accepted (documented) |
| Disconnect | Nothing to clean up — no per-device state. A session that receives nothing for `session-idle-timeout` (90 s, 9 missed messages) is closed by the server (Tomcat checks about every 10 s), so a dead or half-open socket frees its memory; a live device reconnects and loses nothing. No server-side ping |
| Silent device | Nothing is written; buckets become `null` gaps; its connection is closed after the idle timeout. No liveness alerting (skipped) |
| Overload / DB down | Queue fills, then drop + count |
| Service crash (kill -9, OOM) | **Lost:** queue + in-flight batch, at most ~10 s of data. Accepted for this scope |

## Alternatives considered
- **Block when full** (TCP back-pressure): no loss, but holds container threads and stalls every
  device behind a slow DB. **Application ack + device resend**: closes the crash-loss window, but needs
  a protocol change and buffering on the device; idempotent inserts already make it possible later.
- **`COPY`**: fastest bulk path, but no `ON CONFLICT`; needs a staging table. Not needed at 6 000 rows/s.
- **Insert per message**: 1 000 transactions/s at 10k devices — avoidable cost.

## Consequences
Data loss on crash or overload is possible by design and visible in counters. Writer throughput
headroom is an estimate until the load test measures it.
