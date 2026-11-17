# 0003 — Charts API: fixed ranges, fixed bucket sizes, min/max/avg, null for gaps

Status: accepted

## Context
A chart is a few hundred pixels wide. The operator asks "was the CPU pegged?" — an average alone
hides spikes. Charts must "tell the truth", so missing data must look missing.

## Decision
`GET /api/v1/devices/{deviceId}/metrics/{metric}/series?range=hour|day|week`

| range | bucket | points | source | rows read per query |
|---|---|---|---|---|
| hour | 30 s | 120 | raw hypertable, `time_bucket` | ~360 |
| day | 5 min | 288 | `telemetry_5m` | 288 (+ real-time tail) |
| week | 30 min | 336 | `telemetry_5m` re-aggregated | 2 016 |

(Row counts are a calculation for one series at one message per 10 s.)

```json
{
  "deviceId": "host-0042", "metric": "cpuUtilPct", "range": "day",
  "bucketSeconds": 300, "from": "2026-10-25T14:05:00Z", "to": "2026-10-26T14:05:00Z",
  "points": [
    {"ts": "2026-10-25T14:05:00Z", "min": 12.0, "max": 97.5, "avg": 41.3},
    {"ts": "2026-10-25T14:10:00Z", "min": null, "max": null, "avg": null}
  ]
}
```
- Buckets are aligned to UTC (`time_bucket` default origin); `to` is the end of the current bucket,
  so the last point may be partial. The service generates every bucket in `[from, to)` and fills
  missing ones with `null` — **never interpolates**.
- Week avg = `sum(sum) / sum(count)` over six 5-minute rows; min/max of mins/maxes.
- Unknown device or metric: `200` with all-null points (no device registry to check against;
  costs nothing extra). Invalid `range`: `400`.
- The version is a path segment, resolved by Spring's API versioning (`spring.mvc.apiversion`): it is
  visible in logs and usable from a browser or `curl`. A breaking change to the response becomes a
  second controller method for `v2` while `v1` keeps working; an unserved version is `400`.

## Alternatives considered
- **Hour at 10 s buckets (360 points):** device jitter makes some buckets empty and others double,
  producing fake gaps. 30 s smooths that.
- **Arbitrary `from`/`to`/`points` parameters (LTTB downsampling):** more flexible, but needs choosing
  the aggregation level at runtime and more tests. The assignment asks for three ranges.
- **Avg only / `[ts, value]` pairs:** matches the brief literally but hides peaks; `avg` is the
  value, `min`/`max` are the band.

## Consequences
- Every query is bounded to ≤ ~2 000 rows read regardless of fleet size.
- A device reporting less often than every 30 s shows alternating gaps on the hour chart (documented).
- Fresh data appears in the hour chart immediately, in day/week after the next aggregate refresh
  (real-time aggregation covers the not-yet-materialized tail).
