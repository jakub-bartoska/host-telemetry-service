# Host telemetry aggregator

A Kotlin backend that receives telemetry from thousands of devices over WebSocket (one message every
~10 s, six numeric stats each) and serves chart data over HTTP: one metric of one device for the last
hour, day or week. A simulator generates the devices, live or as backfilled history. Everything runs
with Docker Compose.

```
device ──WebSocket /ws/telemetry──▶ validate ──▶ bounded queue ──▶ one batch writer ──▶ TimescaleDB
                                       │ error frame            (10 000 msgs)   (1 000 msgs / 500 ms)   hypertable + 5-min
                                       ▼                                                                continuous aggregate
GET /api/v1/devices/{id}/metrics/{metric}/series?range=hour|day|week  ◀────────────────────────────────────┘
```

> The author's notes for the assignment and the interview — why these technologies, the trade-offs,
> how it was verified, what was left out and what comes next — are in
> [`INTERVIEW_NOTES.md`](INTERVIEW_NOTES.md). This README describes the service itself.

## Run it

Needs Docker with Compose v2 (tested with 2.12). Ports 5432 and 8080 must be free on the host.

```
docker compose up --build            # database + service; first build takes a few minutes
curl localhost:8080/actuator/health  # {"groups":["liveness","readiness"],"status":"UP"}
```

Start 100 simulated live devices (one message per device every 10 s):

```
docker compose --profile sim up --build
SIM_DEVICES=1000 docker compose --profile sim up --build     # more devices
```

Load a day of history (the first command is 864 000 messages, so at least 7 minutes at the default
`--rate 2000`; the second takes a few seconds):

```
docker compose --profile sim run --rm simulator --mode backfill --devices 100 --days 1
docker compose --profile sim run --rm simulator --mode backfill --devices 10 --days 1 --interval 60s
```

Stop and delete the data: `docker compose down -v`.

## API

### Ingest: `WebSocket /ws/telemetry`

A device sends one text frame per sample:

```json
{"type": "stats", "deviceId": "host-0001", "timestamp": "2026-11-11T17:53:30Z",
 "stats": {"cpuUtilPct": 45.7, "memUtilPct": 61.2, "rxBps": 18234, "txBps": 9120, "powerW": 41.5, "temperatureC": 58.0}}
```

A message is rejected with an error frame such as `{"type": "error", "reason": "timestamp_too_old"}`,
and the connection stays open, when it is not valid JSON, the device id is not 1–64 characters of
`A-Z a-z 0-9 . _ -`, it has no stats or more than 32, or the timestamp is more than 5 minutes in the
future or more than 7 days old. A single stat with an invalid key or a non-numeric or non-finite value
is dropped and the rest of the message is kept. Both cases are recorded per device (see
[Operations](#operations)). A connection without a message for 90 s is closed. Resending a message is harmless: a
`(ts, device_id, metric)` that is already stored is ignored.

### Charts: `GET /api/v1/devices/{deviceId}/metrics/{metric}/series?range=hour|day|week`

Device ids from the simulator are `host-0001`, `host-0002`, …; metrics are `cpuUtilPct`, `memUtilPct`,
`rxBps`, `txBps`, `powerW`, `temperatureC`.

```
curl 'localhost:8080/api/v1/devices/host-0001/metrics/cpuUtilPct/series?range=hour'
```

```json
{
  "deviceId": "host-0001", "metric": "cpuUtilPct", "range": "hour", "bucketSeconds": 30,
  "from": "2026-11-11T16:53:30Z", "to": "2026-11-11T17:53:30Z",
  "points": [
    {"ts": "2026-11-11T16:53:30Z", "min": 45.7, "max": 45.7, "avg": 45.7},
    {"ts": "2026-11-11T16:54:00Z", "min": null, "max": null, "avg": null},
    ...
  ]
}
```

| `range` | Bucket | Source |
|---|---|---|
| `hour` | 30 s | raw table |
| `day` | 5 min | continuous aggregate |
| `week` | 30 min | continuous aggregate, re-bucketed in the service |

Buckets are aligned to UTC. Every bucket carries `min`, `max` and `avg`; a bucket without data is
`null` (the service never interpolates). An invalid or missing `range` returns `400`, and so does an
API version other than `v1`. An unknown device or metric returns `200` with all-`null` points.

### Operations

- `/actuator/health` (liveness and readiness groups).
- `/actuator/metrics`: `telemetry.messages.received|rejected|dropped`, `telemetry.stats.discarded`,
  `telemetry.rejections.unrecorded`, `telemetry.rows.written`, `telemetry.batch.latency`,
  `telemetry.queue.depth`, and the Spring Boot defaults.

The metrics count rejected messages per reason, not per device. Which devices send bad data is in
the `ingest_rejections` table, written once a minute. Open the database with
`docker compose exec db psql -U telemetry telemetry` and run:

```sql
SELECT device_id, reason, sum(message_count) AS messages, max(window_end) AS last_seen
FROM ingest_rejections
WHERE window_start > now() - INTERVAL '1 day'
GROUP BY device_id, reason
ORDER BY messages DESC
LIMIT 20;
```

`reason` is the code from the error frame, or `discarded_stats` for a message that was accepted
without some of its stats entries; `discarded_keys` then lists them and `sample` holds the last such
message. `device_id` is `NULL` when the message carried no valid id.

## Architecture

- **Storage** ([ADR 0001](docs/decisions/0001-storage-timescaledb-narrow-hypertable.md)). TimescaleDB
  hypertable with narrow rows `(ts, device_id, metric, value)`, so a new stat key needs no migration.
  Raw chunks are compressed (`segmentby device_id, metric`); a 5-minute continuous aggregate (`min`,
  `max`, `sum`, `count`) serves `day` and `week` and is itself compressed after 1 day. Retention drops
  data older than 8 days. These features need the Timescale License, so managed PostgreSQL such as RDS
  is not a supported target.
- **Ingest pipeline** ([ADR 0002](docs/decisions/0002-ingest-pipeline.md)). The WebSocket handler only
  validates and enqueues into a bounded queue (10 000 messages). One writer thread inserts 1 000
  messages or 500 ms at a time with `ON CONFLICT DO NOTHING`. When the queue is full, new messages are
  dropped and counted in `telemetry.messages.dropped` instead of blocking Tomcat threads.
- **Rejections per device** ([ADR 0008](docs/decisions/0008-ingest-rejections.md)). Rejected messages
  and discarded stats entries are summed in memory per device and reason and written to the
  `ingest_rejections` table once a minute, so a misconfigured fleet costs one row per device a
  minute, not one per message. A failed write loses that minute and is counted.
- **Charts** ([ADR 0003](docs/decisions/0003-charts-api.md)). Three fixed ranges; a week query reads
  about 2 000 rows regardless of fleet size. The 30-minute week buckets are built from 5-minute rows in
  `model/charts/SeriesWindow.kt`: min of mins, max of maxes, `sum(sum)/sum(count)`.
- **Web layer.** Spring Boot 4.1 on JDK 25 ([ADR 0006](docs/decisions/0006-dependency-versions.md)),
  Spring MVC on Tomcat. Idle sockets cost memory, not threads; `max-connections` is raised to 20 000.
- **Data access** ([ADR 0007](docs/decisions/0007-exposed-query-layer.md)). Exposed DSL for queries
  over table objects (`TelemetryTable`, `Telemetry5mView`), Flyway SQL migrations for the schema, no JPA.
- **Simulator.** A separate module and jar that speaks the real WebSocket protocol, also for backfill,
  so history goes through the same validation and write path as live data.

## Delivery guarantees

The protocol has no acknowledgement, so **data can be lost**:

- a crash or restart loses what is in the queue (about 10 s of traffic at most);
- when the queue is full, new messages are dropped. A drop is visible only in
  `telemetry.messages.dropped`; the device is not told.

Inserts are idempotent, so a resend after reconnect never duplicates data.

## Known limitations

- No authentication, device identity or TLS: any client can send as any device id.
- The Compose setup is an evaluation harness for one machine, not a production deployment: service,
  database and simulator share one host, the database password is in `docker-compose.yml` and
  `application.yml`, and port 5432 is published to the host
  ([ADR 0004](docs/decisions/0004-deployment-docker-compose.md)).
- One service instance; running several behind a load balancer is possible without code changes but
  has not been tested ([`docs/scaling.md`](docs/scaling.md#4-running-several-instances-behind-a-load-balancer)).
- Only the three fixed ranges; no device list or registry; no alerting for silent devices.
- Metrics are exposed only through Actuator (no Prometheus or Grafana).

The full scope list with reasons is in [ADR 0005](docs/decisions/0005-critique-and-scope.md).

## Simulator options

Flags go after the service name (`docker compose --profile sim run --rm simulator --flag value`). Each
flag can also be set as an environment variable `SIM_<FLAG>`; Compose uses this for its defaults
(`SIM_MODE`, `SIM_DEVICES`, `SIM_INTERVAL` are read from your shell). A flag overrides the variable.

| Flag | Default | Meaning |
|---|---|---|
| `--mode` | `live` | `live`: one WebSocket per device, current timestamps. `backfill`: send history up to now, then exit |
| `--devices` | `100` | Number of devices, `host-0001`… (1–100 000) |
| `--interval` | `10s` | Spacing of one device's samples in both modes (`500ms`, `10s`, `5m`, `1h`; at least `1s`). In live mode keep it under the service's 90 s idle timeout, otherwise every device is disconnected and reconnects between samples |
| `--url` | `ws://localhost:8080/ws/telemetry` | WebSocket endpoint (Compose sets `ws://app:8080/ws/telemetry`) |
| `--duration` | run until Ctrl+C | Live only: stop after this long |
| `--days` | `7` | Backfill only: days of history, **1–7** (the service rejects timestamps older than 7 days) |
| `--rate` | `2000` | Backfill only: maximum messages per second over all connections |
| `--seed` | `1` | Random seed; the same seed produces the same data |

Backfill always uses 8 parallel connections (not configurable). Run `--help` for the usage text. The
simulator reconnects and resends after a lost connection and gives up after 60 s without one.

## Build and test

```
./gradlew build
```

Runs unit and integration tests of both modules. The integration tests use Testcontainers, so Docker
must be running; Gradle downloads JDK 25 itself through the toolchain.

## Performance and scaling

Measured on one laptop (AMD Ryzen 9 5900X, Docker Desktop on WSL2), where the service, database and
simulator share the machine, so the numbers show where the bottleneck is, not production capacity.
One instance carried 10 000 live devices (1 000 msg/s) with 0 drops; the single writer sustains about
5 850 msg/s. Method and results: [`docs/load-test.md`](docs/load-test.md). Limits and how to lift them:
[`docs/scaling.md`](docs/scaling.md).

The load tests are scripts outside the service codebase, in [`load-test/`](load-test/README.md):
`./load-test/run.sh <scenario>` starts a clean stack, runs the scenario, prints PASS/FAIL per criterion
and tears the stack down (exit code 0 / 1 / 2), so it can run in a pipeline without a person.

## Repository layout

```
service/            Spring Boot service, packaged by layer: config/, web/ (controller: WebSocket handler and
                    series API; converter: request parameters; exception: error responses),
                    core/ (ingest: validation, queue, writer, rejection log; charts: series service), model/,
                    repository/ (ingest: telemetry and rejection writes; charts: series reads; table: Exposed
                    table mappings); Flyway migrations in
                    src/main/resources/db/migration
simulator/          standalone simulator: cli/ (options), device/ (data generator), transport/ (WebSocket, JSON),
                    mode/ (live, backfill), report/ (counters, status line)
docs/decisions/     ADRs 0001–0008
docs/load-test.md   load test results
docs/scaling.md     scaling limits and levers
docs/assignment.md  the original assignment
load-test/          load test runner, scenarios and README (`load-test/run.sh <scenario>`)
Dockerfile          multi-stage build, targets `app` and `simulator`
docker-compose.yml  database, service and (profile `sim`) simulator
INTERVIEW_NOTES.md  author's notes for the assignment and the interview
```
