# Interview notes

How to run the service is in [`README.md`](README.md), details of each decision in
[`docs/decisions/`](docs/decisions/).

## Why these technologies

- **Spring Boot** – I'm used to it from Java and I understand it, so for an interview it's the most
  comfortable choice.
- **Exposed** – a Kotlin library that reminds me of the persistence frameworks from Java. The project
  doesn't need it at this stage, but I added it because it takes care of things the programmer would
  otherwise have to watch, and it doesn't add problems.
- **TimescaleDB** – solves the problem of this assignment well, and I have experience with it.

## Data model

One narrow table `telemetry (ts, device_id, metric, value)`: the stats map is generic, so a new stat
key needs no schema change, and a chart always reads one metric of one device. I considered lookup
tables with numeric ids for devices and metrics and left them out. Ingest would have to create ids
for unknown keys on the write path, rows would not be readable without joins, and compression
already stores `device_id` and `metric` once per segment. I would add them together with a device
registry.

## How I know it works

Unit and Testcontainers integration tests, plus a load test on my laptop: 10 000 live devices with
0 drops, one week of history for 1 000 devices backfilled without loss. Numbers and caveats in
[`docs/load-test.md`](docs/load-test.md).

## Where I drop data on purpose

The current version can lose data: messages that arrive when the in-memory queue is full, a batch
the database refuses with a non-transient error, and whatever is still in the queue when the
service crashes or does not finish shutting down in time. Devices get no acknowledgement. I chose
this for a simpler flow, because I assume telemetry from devices is not data that has to be
available 100 % of the time: a missing point is a gap in the chart.

The loss is visible: dropped messages and failed batches are counted in Actuator metrics
(`telemetry.messages.dropped`, `telemetry.batches.failed`), and a failed batch or a shutdown that
leaves messages behind is logged. In production I would set alerts on these. A crash is not counted,
because the queue only lives in memory. If the loss were a problem, I would put Kafka between ingest
and the database.

## What I skipped

Authentication and TLS, arbitrary time ranges, high availability. See
[ADR 0005](docs/decisions/0005-critique-and-scope.md).

**Device clocks.** A timestamp more than 5 minutes ahead is rejected, but one up to 7 days old is
accepted, so a device whose clock or time zone is set behind stores shifted data and nothing reports
it. I left it open because I do not know the business side of what devices send. If only real-time
data is expected, anything older than about a minute should be invalid, and a wrong clock would show
up as rejections. If devices may resend history after an outage, a shifted clock looks the same as
late data.

## What I would do next

1. More writers in parallel – the single writer is the ingest ceiling (~5 850 msg/s,
   [`docs/scaling.md`](docs/scaling.md)).
2. Alerts on the drop metrics, and Kafka if the loss turns out to matter.
3. Keep history past the 8 days, which today is simply deleted: an hourly aggregate on top of
   `telemetry_5m` with a long retention for trends, or an export of raw chunks to object storage
   before they are dropped if someone needs the original values.
4. A production deployment. Docker Compose is here to run and evaluate the assignment on one
   machine: no failover, no rolling update, the database password in the file. I would move to an
   orchestrator such as Kubernetes with several instances behind a load balancer, and run the
   database separately on a TimescaleDB that has compression and continuous aggregates.
5. CI: build and tests on every push, and a small load test scenario as a manual job. For an
   assignment it would be overkill, so the repository has none.
