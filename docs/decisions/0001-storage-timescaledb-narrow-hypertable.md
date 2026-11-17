# 0001 — Storage: TimescaleDB narrow hypertable + 5-minute continuous aggregate

Status: accepted

## Context
Thousands of devices send one message every ~10 s, each with a generic `stats` map (6 keys today,
other keys later). We must keep one week queryable and answer "one metric of one device for the last
hour/day/week" fast, and the data must survive restarts.

Capacity (calculation; 6 metrics/message; bytes per row are an **estimate** from PostgreSQL tuple
layout: 24 B header + 8 B ts + ~10 B device id + ~9 B metric + 8 B value + 4 B line pointer ≈ 68 B
heap, plus ~60 B for the unique B-tree entry at ~70 % leaf fill ≈ **130 B/row**; compression ratio
**10× is an estimate** based on Timescale's published 90 %+ figures and must be measured with
`hypertable_compression_stats()` during the load test):

| Devices | msg/s | rows/s | rows/day | rows/week | week uncompressed | week compressed | on disk (8 h hot + rest compressed) |
|---|---|---|---|---|---|---|---|
| 1 000 | 100 | 600 | 51.8 M | 0.36 B | 47 GB | 4.7 GB | ~6.7 GB |
| 5 000 | 500 | 3 000 | 259 M | 1.81 B | 236 GB | 24 GB | ~34 GB |
| 10 000 | 1 000 | 6 000 | 518 M | 3.63 B | 472 GB | 47 GB | ~67 GB |

## Decision
- **Raw table** `telemetry(ts timestamptz, device_id text, metric text, value double precision)`,
  hypertable on `ts`, `chunk_time_interval = 2 h` (≈0.56 GB/chunk at 1k devices, 5.6 GB at 10k),
  `create_default_indexes => false`, one unique index `(device_id, metric, ts)` — it is both the
  idempotency key (ADR 0002) and the only index the queries need.
- **Compression** with `segmentby = device_id, metric`, `orderby = ts`, policy `compress_after = 6 h`.
  Late data younger than 6 h lands in uncompressed chunks; older data still inserts (slower path).
- **Continuous aggregate** `telemetry_5m(bucket, device_id, metric, min, max, sum, count)`, created
  `WITH NO DATA` (so it runs inside Flyway's transaction), real-time aggregation on
  (`materialized_only = false`), refresh policy every 5 min over `[now − 8 d, now − 5 min]`.
  We store `sum` and `count`, not `avg`, so 5-minute rows can be re-aggregated into 30-minute buckets
  correctly. Size: N × 6 × 2016 rows/week = 12 M (1k) … 121 M (10k), ≈1.9 … 19 GB uncompressed (estimate).
  The load test measured the uncompressed aggregate as the largest object, so aggregate chunks are
  compressed after 1 day (`segmentby device_id, metric`, `orderby bucket`). The day chart
  stays on uncompressed chunks; late data still reaches compressed ones through the refresh window
  (integration test).
- **Retention**: drop raw chunks older than 8 days, aggregate rows older than 8 days (week + 1 day margin).
- Schema and policies live in Flyway migrations; tuning numbers are in one place.

## Alternatives considered
- **Plain PostgreSQL, declarative daily partitions + own rollup job.** Works, but we would hand-write
  the rollup scheduler, late-data invalidation and partition maintenance — the exact features
  Timescale gives in ~10 lines of SQL. And with no compression a week at 10k devices is ~470 GB.
- **Wide row** (one row per message, one column per stat): ~150 B/message vs ~780 B (6 × 130),
  i.e. ~5× smaller uncompressed, but every new stat key is a schema migration — contradicts the
  "generic map" requirement. **JSONB row**: generic and ~2–3× smaller than narrow uncompressed, but the
  aggregate must unpack JSON per metric and columnar compression works poorly on it.
- **ClickHouse** (MergeTree ordered by device, metric, ts, TTL): better compression and aggregation
  speed at this scale; lost on team familiarity, eventual dedup (`ReplacingMergeTree`) and time budget.

## Consequences
- Week chart reads 2 016 aggregate rows instead of 60 480 raw rows scattered across ~60 k heap pages.
- Hot uncompressed window (~8 h) dominates RAM needs: ~2 GB at 1k devices, ~22 GB at 10k (estimate).
- Compression, continuous aggregates and retention are under the Timescale License: fine in the
  `timescale/timescaledb` image, **not available on AWS RDS / Azure Postgres** (see ADR 0005).
