# Scaling: limits, levers and several instances

What the service can carry today, what runs out first, and what I would change in what order. Every
number has a source: *measured* values come from [`docs/load-test.md`](load-test.md) (scenario number in
brackets), *derived* values are arithmetic on measured ones, *estimate* values come from the ADR
calculations and have not been measured. All measurements are from one laptop where the service, the
database and the simulator share CPU and the Docker VM ([ADR 0004](decisions/0004-deployment-docker-compose.md)),
so they show where the bottleneck is, not production capacity.

## 1. Where we are

| Quantity | Value | Source |
|---|---|---|
| Target: 10 000 devices, one message per 10 s | 1 000 msg/s = 6 000 rows/s | ADR 0001, 0002 (calculation) |
| Ingest, no drops | up to 5 000 msg/s; drops from 7 500 msg/s (not bisected) | measured [1] |
| One writer sustains | about 5 850 msg/s (1 000 messages / 0.171 s per batch) | derived [1] |
| Headroom at 10 000 devices | about 5.9× on ingest | derived [1] |
| Live, 10 000 devices, 5 min | 0 dropped, queue peak 144 of 10 000, app CPU peak 38 % of one core, app memory 1 812 MiB, 10 023 open fds, DB CPU peak 23 % | measured [3] |
| Live, 1 000 devices | app memory 554 MiB, 1 025 fds | measured [3] |
| Chart latency, sequential client | `week` p99 20 ms warm, 46 ms on a restarted DB across 200 devices | measured [4] |
| Rows read by a week chart | at most 2 016, independent of fleet size | [ADR 0001](decisions/0001-storage-timescaledb-narrow-hypertable.md) / [0003](decisions/0003-charts-api.md) |
| Disk, 1 000 devices × 1 week at a 120 s interval | raw 463 MB compressed, aggregate 1 408 MB (measured before aggregate compression after 1 day was added, not re-measured) | measured [2] |
| Restart of the service with 10 000 devices connected | health UP after 7 s, all connections back after 15 s (5 s resolution), 0 dropped; 212 of 622 484 sent messages (0.034 %) not in the DB | measured [6] |

## 2. Limits of one service instance

In the order I expect them to bind:

1. **Connections and memory per connection.** Every device holds one WebSocket. Tomcat
   `max-connections` is 20 000 (`application.yml`, ADR 0004), so that is the configured cap per
   instance; 10 000 were accepted [3]. Memory of the service process was 554 MiB at 1 000 devices and
   1 812 MiB at 10 000, which is roughly 140 KiB (slope) to 185 KiB (average) per device (derived [3]);
   the figure includes the JVM baseline and uncollected garbage, so it is an upper bound, and ADR 0005's
   guess of "tens of KB" was too low. Extrapolating to 50 000 devices gives about 7–9 GiB (estimate).
   Open file descriptors were one per device (10 023 at 10 000 devices [3]); I did not check the container's
   `ulimit`.
2. **The writer.** One writer thread with one DB connection caps ingest at about 5 850 msg/s (derived [1]).
   At that ceiling the DB used only 1–1.4 cores [1], so the limit is the design of the writer, not the
   database. It does not need more instances; more writers in the same instance lift it.
3. **CPU of the service is not a limit in the measured range.** Peak 38 % of one core at 10 000 devices [3].
   The reason to run more instances would be connection count and memory, not computation.

## 3. Levers by reaction time

- **Minutes (configuration, no build).** `TELEMETRY_INGEST_QUEUE_CAPACITY`, `_BATCH_SIZE`,
  `_FLUSH_INTERVAL`; `server.tomcat.max-connections`; JVM heap and container limits; PostgreSQL memory
  settings (the measured run used whatever the image chose, `shared_buffers=3988MB` [machine table]).
  The ~8 h hot window of ~22 GB at 10 000 devices is an ADR 0001 estimate, so tuning is untested;
  shorter raw retention or compression interval with one `ALTER` (raw retention 8 → 2 days roughly
  halves disk, ADR 0005 item 8, calculation).
- **Hours (small change inside the design).** Several writers in parallel (`ON CONFLICT DO NOTHING`
  makes concurrent inserts safe; assign a writer by `hash(device_id)` so two concurrent batches with the
  same keys cannot deadlock and retry, and per-device order is kept) or `COPY` into a staging table
  (ADR 0002). [Interview notes, "What I would do next" 1](../INTERVIEW_NOTES.md#what-i-would-do-next). The continuous aggregate, measured at 3× the compressed
  raw data [2], is now compressed after 1 day (not re-measured at that scale).
- **Days (architecture).** More instances (section 4), a bigger / replicated / sharded database
  (section 5), a broker between ingest and writer, an acknowledgement or back-pressure protocol
  ([interview notes, "Where I drop data on purpose"](../INTERVIEW_NOTES.md#where-i-drop-data-on-purpose); [ADR 0005](decisions/0005-critique-and-scope.md) item 4 and the scope guard).

## 4. Running several instances behind a load balancer

Not tested; this is reasoning from the code and the ADRs. Compose is not the tool for it (ADR 0004).

- **Why it works without code changes.** The service is stateless per message: each message carries
  `deviceId` and `timestamp`, the handler keeps nothing beyond the connection, and the insert is
  idempotent on `(device_id, metric, ts)` (ADR 0002). No sticky sessions are needed, a resend after a
  reconnect is a no-op, and ordering across instances does not matter.
- **L4 or L7.** A TCP (L4) balancer is enough. An HTTP (L7) one must pass the `Upgrade` header and its
  idle timeout must be longer than the 10 s message interval, otherwise quiet devices are cut and
  reconnect. The service itself closes a session that receives nothing for 90 s
  (`telemetry.ingest.session-idle-timeout`), so dead and half-open sockets are freed; it sends no
  WebSocket ping (ADR 0002).
- **Long connections do not rebalance.** The balancer decides at connect time. A new instance stays
  empty until devices reconnect, so one needs a maximum connection lifetime or gradual closing from the
  server side; neither exists.
- **A restart moves 1/N of the devices at once** onto the others. Scenario 6 measured the single-instance
  version of that burst: 10 000 devices back in 15 s, queue depth 0 at 1 s polling, 0 drops [6]. It is
  a connection-setup burst, not a message burst. The jitter in the simulator's reconnect is a
  simulator feature; real devices would need the same.
- **Metrics are per instance** (`telemetry.messages.dropped`, `telemetry.queue.depth`, counters reset
  with the process [6]). Without a central collector (Prometheus is skipped) the sum has to be taken
  by hand.
- **The database is shared**, and every instance adds a writer and a connection pool to it (section 5).
  `/actuator/health` is exposed; I did not test what it returns when the DB is down, so I do not rely
  on it for load-balancer behaviour. An outage with all instances unhealthy would make devices
  reconnect in cycles, which is an argument for a broker or a local buffer (both skipped).

## 5. The database as the shared bottleneck

Order of moves: (1) vertically: CPU, NVMe, RAM for the hot window; (2) a read replica for charts;
(3) sharding by `device_id` in the application. Timescale multi-node is not an option: its release
notes for 2.14.0 (2024-02-08) say it is no longer supported, and the image pinned here is 2.31.0
(ADR 0006); I checked this on 2026-11-11. The alternative from ADR 0001 is ClickHouse. Compression,
continuous aggregates and retention need a Timescale-licensed server and are not on RDS or Azure
Database for PostgreSQL (ADR 0005). Sizes at 10 000 devices: raw ~47 GB compressed per week (ADR 0001
estimate; cross-check: 463 MB × 12 (10 s instead of 120 s interval) × 10 (10 000 devices) ≈ 56 GB per
week, derived from [2] assuming the same compression ratio, so the orders of magnitude agree); the
aggregate has the same number of rows at any sample interval (1 000 devices × 6 metrics ×
2 016 buckets = 12 096 000 [2]), so about 14 GB uncompressed (derived [2]; ADR 0001 estimate 19 GB).

## 6. Signals that it is time

`telemetry.queue.depth` staying high (capacity 10 000), `telemetry.messages.dropped` above 0,
`telemetry.batch.latency` rising from 0.17 s per batch [1], DB CPU. Today there is no export and no
alert; someone has to look at `/actuator/metrics`.

## 7. What we do not know

A week of data at the 10 s interval (12× the measured volume); concurrent chart clients; charts under
5 000+ live devices; more than one instance; a DB outage during ingest; the ceiling between 5 000 and
7 500 msg/s; where the 212 lost messages in [6] went; cold-disk behaviour; the `ulimit` of the
container. The full list is "Not measured" in [`docs/load-test.md`](load-test.md#not-measured).
