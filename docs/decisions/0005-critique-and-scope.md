# 0005 — Critique of the proposed approach, and scope guard

Status: accepted

## Context
The stack (Spring Boot MVC, TimescaleDB narrow table, Docker Compose) was chosen up front, partly for
familiarity. This record names its weak points so the choice is made knowingly.

## Critique of the proposed approach
1. **TimescaleDB is not overkill — it is less code.** Compression, rollups and retention are ~10 lines
   of SQL each; plain Postgres would need a rollup job, invalidation of late data and partition
   management (ADR 0001). *Real risks:* (a) these features are under the Timescale License and are
   **not available on AWS RDS / Azure Database for PostgreSQL**, so "it's just Postgres" is only half
   true in production; (b) continuous-aggregate and compression semantics (refresh windows, inserts into
   compressed chunks, `WITH NO DATA` in Flyway) are things the author must be able to explain.
2. **The narrow table is the expensive schema.** Uncompressed it costs ~130 B per value vs ~25 B in a
   wide row — **5×**. At 10k devices a week is 472 GB narrow vs ~91 GB wide (calculation, ADR 0001).
   It only wins because compression (`segmentby device_id, metric`) brings it to ~47 GB (estimate).
   **Where I disagree with a blanket "narrow is right":** without Timescale compression (e.g. on RDS)
   I would pick JSONB or a wide row with a migration per new key.
3. **MVC + virtual threads vs WebFlux for thousands of sockets.** Tomcat NIO does not hold a thread
   per idle WebSocket; a thread is used only for the microseconds it takes to parse and enqueue a
   message. 1 000 msg/s is small. The real limits are `max-connections` (default **8 192**), file
   descriptors and per-session buffer memory (estimate tens of KB × 10k sessions — to be measured;
   the load test later measured
   140 KiB (slope) to 185 KiB (average, including the JVM baseline) per device, see
   [`docs/scaling.md`](../scaling.md)).
   **Disagreement:** virtual threads buy nothing here and should not be presented as the scaling
   story; we leave them off. WebFlux/Netty would use less memory per connection but forces reactive
   style (and R2DBC or blocking bridges) on a team new to Kotlin — not worth it at this load.
   **Ktor + coroutines** is the most idiomatic-Kotlin option and an interviewer may ask why not; it
   loses on the author's Spring depth, which matters more in a 4-hour defended solution.
4. **No acknowledgement means silent loss.** Crash = up to ~10 s of data lost; overload = shedding
   (ADR 0002). It is honest and counted, but a production system would add per-message ack + resend.
5. **Compose as a test deployment** measures a laptop: simulator, app and DB compete for the same
   CPU; Docker Desktop's VM limits RAM. Results are not production capacity and must be labelled so.
   10k simulated sockets from one container also stress the simulator itself.
6. **Spring + Kotlin risk:** Spring code written by a Java expert tends to look like Java. Mitigation:
   the team's Kotlin rules (data classes, no `!!`, constructor injection) and review. The Spring
   version is Boot 4 / Framework 7, whose JSpecify annotations give Kotlin real nullability (ADR 0006).
7. **Four hours is tight** for Timescale + simulator + Testcontainers + a load test. The scope guard
   below is not optional.
8. **Raw retention of 8 days may be more than needed:** charts older than one hour read only the
   aggregate. Keeping raw for 2 days would cut disk at 10k devices from ~67 GB to ~34 GB (calculation).
   Kept at 8 days to match "a week of history" literally — user decision.

## Scope guard — deliberately skipped
Authentication/device identity, TLS, Kafka or any broker, Kubernetes, frontend/Grafana, device
registry and "list devices" endpoint, liveness/alerting for silent devices, per-message ack and
resend, arbitrary time ranges, per-metric range validation, high availability / replication,
multi-node Timescale, Prometheus/Grafana stack (Actuator metrics only). Compressing the continuous
aggregate was first on this list; it was added after the load test. Each goes to "What I skipped" in [`INTERVIEW_NOTES.md`](../../INTERVIEW_NOTES.md).

## Consequences
The decisions in ADRs 0001–0004 stand; items 2, 3 and 8 are explicitly flagged for user approval.
