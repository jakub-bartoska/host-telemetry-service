# Load test

All numbers below come from runs I executed on the machine described next, on 2026-11-11, with the
commands shown. Anything not measured is listed under [Not measured](#not-measured). Nothing here is
an estimate presented as a measurement; where a number is derived by arithmetic from measured ones, it
says so.

The numbers of scenarios 1–5 were measured with the earlier versions of the scripts (commit `994e952`,
run by hand with a remapped copy of the compose file). The scripts have since moved to
[`load-test/`](../load-test/README.md) with a runner (`load-test/run.sh`) that starts the stack, evaluates
PASS/FAIL and tears the stack down; the measuring logic is the same. I did not re-measure scenarios 1–5;
as a check that the move broke nothing, [one short smoke run](#smoke-run-of-the-new-runner) of the `live`
scenario is included below. Scenario 6 was measured with the new runner.

## Machine and versions

| | |
|---|---|
| CPU | AMD Ryzen 9 5900X, 12 cores / 24 threads |
| RAM | 31.9 GB host; Docker VM sees 24 CPUs and 15.58 GiB |
| Disk | Samsung SSD 980 PRO 1 TB |
| OS | Windows 11 Pro 10.0.26200, Docker on WSL2 (kernel 6.6.87.2) |
| Docker | Docker Desktop 4.13.0, engine 20.10.20, Compose v2.12.0 |
| Database | `timescale/timescaledb:2.31.0-pg18` (PostgreSQL 18.6; `max_connections=100`, `shared_buffers=3988MB`, defaults) |
| Service | Spring Boot 4.1.2, Kotlin 2.4.21, runtime `eclipse-temurin:25-jre` (OpenJDK 25.0.5), built from this branch (HOS-9 head `bf2067c`) |
| Service tuning | defaults from `application.yml`: queue 10 000, batch 1 000 messages (6 rows each), flush 500 ms, Tomcat `max-connections` 20 000 |

**Service, database, simulator, the load-test client and `docker stats` all share this one machine and
the one Docker VM** (ADR 0004). In addition, 21 unrelated containers (other projects' databases and web
apps, mostly idle) were running in the same Docker VM. The numbers show relative behaviour and where the
bottleneck is, not production capacity.

Compose: `docker compose` as a subcommand did not resolve on this machine, so I ran the plugin binary
directly (`docker-compose.exe` from Docker Desktop's `cli-plugins` folder, v2.12.0, same version), via
`DC=<path to that binary>`. Scenarios 1–5 (measured earlier) used a copy of `docker-compose.yml` with the host
ports remapped (`15432:5432`, `18080:8080`) because 5432/8080 were taken by other projects, and project name
`hos10`; below, `DC` in their commands abbreviates `docker-compose.exe -p hos10 --project-directory . -f
<copy of docker-compose.yml>`. The new runner reads the ports from `APP_PORT` / `DB_PORT` (the base compose file
now takes them from these variables, defaults unchanged), project name `hosload`.

Helper scripts (in `load-test/`, see its README): `run.sh` (runner), `scenarios/*.sh`, `lib/sample.py`
(samples `/actuator/metrics` and `docker stats` every 5 s), `lib/latency.mjs` (client-side latency, Node 24,
sequential requests, `fetch`). Knobs are environment variables (`DC`, `OUT_DIR`, `DEVICES`, `DURATION`, ...).

## Scenario 1 — ingest ceiling

Clean DB before every run (`DC down -v`, `DC up -d db app`), then:

```
# measured with the earlier script (`ingest-run.sh <rate>`); today: ./load-test/run.sh ingest-ceiling <rate>
# = DC --profile sim run --rm simulator --mode backfill --devices 1000 --days 1 --interval 120s --rate <rate>
```

718 000 messages per run (1 000 devices × 718 samples), 4 308 000 rows expected. I used `--interval 120s`
instead of the suggested 60 s so that four runs fit the time budget. `--rate 1000000` is the CLI maximum
(effectively unlimited).

| `--rate` | sent msg/s (sim summary) | sim elapsed | `messages.received` | `messages.dropped` | `rows.written` = `count(*)` | expected rows | peak queue depth (sampled) | peak app CPU / db CPU (`docker stats`, 100 % = 1 core) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 2 000 | 1 997 | 359.5 s | 718 000 | **0** | 4 308 000 | 4 308 000 | 330 | 42 % / 141 % |
| 5 000 | 4 981 | 144.1 s | 718 000 | **0** | 4 308 000 | 4 308 000 | 858 | 54 % / 102 % |
| 7 500 | 7 458 | 96.3 s | 718 000 | 150 409 | 3 405 546 | 4 308 000 | 10 000 (full) | 92 % / 111 % |
| 10 000 | 9 927 | 72.3 s | 718 000 | 295 292 | 2 536 248 | 4 308 000 | 10 000 (full) | 112 % / 116 % |
| 1 000 000 | 155 613 | 4.6 s | 718 000 | 692 000 | 156 000 | 4 308 000 | 10 000 (full) | 2 % / 92 % |

- In every run `rows.written / 6 + dropped = 718 000`, i.e. every message is accounted for as either
  written or dropped; `count(*)` equals `rows.written` exactly. `messages.rejected` was never non-zero,
  `batches.failed` stayed 0.
- **No drops up to 5 000 msg/s; the first rate with drops is 7 500 msg/s.** I did not bisect between
  5 000 and 7 500, so the ceiling on this machine is somewhere in that interval.
- Writer batch latency (`telemetry.batch.latency`, mean = TOTAL_TIME / COUNT): 0.170 s (2 000/s),
  0.171 s (5 000/s) per 1 000-message batch (6 000 rows), max 0.25–0.26 s. Derived: 1 000 messages / 0.171 s
  ≈ 5 850 msg/s is what a single writer sustains, and in the 5 000/s run the writer was busy about
  719 × 0.171 s / 144 s ≈ 85 % of the time. This matches the observed ceiling.
- The simulator reported `errorFrames=0` in the dropping runs too: the service has no acknowledgement
  (ADR 0002), so the sender cannot see drops; only the service's `dropped` counter shows them.
- Peak service memory 402–450 MiB in runs 2 000–10 000 and 738 MiB in the unlimited run.

**Observation (bottleneck):** the single writer / one DB connection doing 6 000-row batch inserts. DB CPU
sits at ~1–1.4 cores (92–141 % in the table), app CPU is far from saturated at the ceiling, and the queue fills only when the offered
rate exceeds the writer's rate. Not the simulator (it reached 155 000 msg/s) and not the WebSocket layer.

## Scenario 2 — one week of history

Estimate before the run: messages = devices × 7 × 86 400 / interval = 1 000 × 604 800 / 120 =
5 040 000 (the simulator planned 5 038 000), rows = 30.2 M; at 5 000 msg/s ≈ 1 008 s. I chose 5 000 msg/s
(highest rate with 0 drops in scenario 1) and 1 000 devices at a 120 s interval. A 10 s interval would be
12× more data; that was not run.

```
DC down -v && DC up -d db app
DC --profile sim run --rm simulator --mode backfill --devices 1000 --days 7 --interval 120s --rate 5000
```

| | |
|---|---|
| Duration | 1 008.2 s (avg 4 997 msg/s), 0 error frames |
| `messages.received` / `dropped` | 5 038 000 / 0 |
| `count(*)` from `telemetry` | 30 228 000 = 5 038 000 × 6 |
| Chunks | 85 (2 h chunk interval), 0 compressed after the run |
| `telemetry_5m` rows | 12 096 000 (1 000 devices × 6 metrics × 2 016 buckets) |

Sizes (`hypertable_detailed_size`; the `telemetry_5m` size is that of its materialization hypertable
`_materialized_hypertable_2`, which had been refreshed by the policy job):

| | before compression | after compression |
|---|---:|---:|
| `telemetry` table | 1 895 MB | 366 MB |
| `telemetry` indexes | 1 592 MB | 96 MB |
| `telemetry` toast | 688 kB | 1 336 kB |
| **`telemetry` total** | **3 488 MB** | **463 MB** |
| `telemetry_5m` total (not compressed) | 1 408 MB | 1 408 MB (same) |

Compression was triggered manually: the compression policy job, looked up by name instead of by id (see Reproduce) — 80.8 s. 81 of 85 chunks were compressed (the 4 youngest are inside the 6 h `compress_after`
window). `hypertable_compression_stats('telemetry')`: 3 338 MB → 312 MB, **ratio 10.69** for the
compressed chunks. Per device and week at this 120 s sample interval (derived by dividing by 1 000
devices): raw 3.49 MB, compressed 0.46 MB, plus 1.41 MB for `telemetry_5m`.

**Observation:** the 5-minute continuous aggregate (1.41 GB) is three times larger than the whole
compressed raw table and is not compressed itself; after compression it dominates storage. See
recommendations.

## Scenario 3 — live load on top of the week of data

Same stack as scenario 2 (no `down -v`), after compression. Each run is 5 minutes at the default 10 s
interval; the same device ids `host-0001…` as the history.

```
# measured with the earlier script (`live-run.sh <N> 5m`) on the stack of scenario 2; today: DURATION=5m ./load-test/run.sh live <N>
# (the runner starts from a clean stack) = DC --profile sim run --rm simulator --mode live --devices <N> --interval 10s --duration 5m
```

| devices | messages sent (sim) | `received` Δ | `dropped` Δ | `rows.written` Δ | steady msg/s (sampler, mid-run) | peak queue depth | peak open fds (app) | peak app CPU / mem | peak db CPU |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 000 | 30 475 | 30 475 | **0** | 182 850 (= 6 ×) | 100 | 0 | 1 025 | 15 % / 554 MiB | 5 % |
| 5 000 | 155 869 | 155 869 | **0** | 935 214 (= 6 ×) | 496 | 1 | 5 023 | 54 % / 1 052 MiB | 18 % |
| 10 000 | 324 995 | 324 995 | **0** | 1 949 970 (= 6 ×) | 991 | 144 | 10 023 | 38 % / 1 812 MiB | 23 % |

- 10 000 simultaneous WebSocket connections were accepted with no connection errors and no error frames
  (above Tomcat's default `max-connections` of 8 192, so the ADR 0004 change to 20 000 is exercised).
  The simulator needed 351 s of wall time for a 300 s run (connection ramp-up).
- The service's DB pool is not per-device, so PostgreSQL `max_connections=100` was not a factor; I did not
  sample the DB connection count.
- The DB container's memory reading (~4.2 GiB) is the shared-buffer/page-cache footprint, not a leak.

## Scenario 4 — Charts API latency

Client: `node load-test/lib/latency.mjs <range> 200 <device>`; sequential requests (concurrency 1) from
the same machine, time measured around `fetch` + JSON parse. Device `host-0001` (a full week: 336 non-null
points on `week`). Metric `cpuUtilPct`. All values in ms.

| condition | range | p50 | p95 | p99 | max |
|---|---|---:|---:|---:|---:|
| Warm, no ingest | hour | 2.6 | 4.5 | 8.0 | 151.6 |
| | day | 3.8 | 5.2 | 11.2 | 62.3 |
| | week | 9.9 | 14.7 | 20.4 | 68.8 |
| **During live ingest, 1 000 devices** (requests issued ~75 s into the run) | hour | 1.8 | 3.2 | 9.6 | 137.4 |
| | day | 3.0 | 5.4 | 9.2 | 49.2 |
| | week | 9.1 | 12.1 | 19.4 | 67.9 |
| Cold, same device repeated (first request after `restart db`: week 141.4, day 55.4, hour 48.9; then 200 requests) | hour | 4.6 | 7.0 | 10.7 | 155.9 |
| | day | 3.5 | 5.5 | 10.3 | 61.4 |
| | week | 8.1 | 10.1 | 11.2 | 103.9 |
| **Cold, 200 distinct devices** (`latency.mjs <range> 200 spread`, right after `restart db`) | hour | 2.3 | 3.7 | 8.4 | 154.9 |
| | day | 3.4 | 5.6 | 7.5 | 44.2 |
| | week | 13.6 | 35.8 | 46.0 | 108.4 |
| Warm, same 200 distinct devices, second pass | hour | 2.1 | 3.3 | 4.5 | 45.1 |
| | day | 2.9 | 4.0 | 4.6 | 46.5 |
| | week | 10.0 | 12.0 | 13.3 | 50.1 |

0 failed requests in all runs. "Cold" means PostgreSQL was restarted (its shared buffers are empty); the
Docker VM's OS page cache stayed warm, so this is not a cold-disk measurement.

**Observations:**
- `week` is the slowest range (p50 ≈ 9–10 ms warm), as expected for 336 buckets built from the
  materialized aggregate plus a real-time tail; it is still ~10× below any interactive limit, and live
  ingest from 1 000 devices did not visibly change it (the `week` and `hour` differences between rows
  are within run-to-run noise; I did not repeat each row).
- The worst case I measured is `week` on a cold DB across distinct devices: p99 46 ms.
- The week series stayed correct while the real-time tail was non-empty (336/336 non-null buckets), but
  I did not isolate the cost of the real-time tail from the materialized part.
- Concurrency was 1. Latency under many concurrent chart clients was not measured.

## Scenario 5 — persistence across an app restart

After scenario 3 (no live devices): saved `GET …/host-0001/metrics/cpuUtilPct/series?range=week`, ran
`DC restart app`, waited for `/actuator/health`, fetched again; `count(*)` in the DB was 33 296 034 and
unchanged.

Result: both responses have 336 points with identical timestamps and 336/336 non-null `avg`; min and max
are identical in all buckets. In 8 of 336 buckets `avg` differs in the last floating-point digits
(for example 77.11999999999999 vs 77.12000000000002), which is the order of summation in the aggregate,
not missing data. The data survived the restart.

## Scenario 6 — reconnect storm after a service restart, then 10 minutes of steady traffic

Machine and versions as above (the other-project containers were still in the Docker VM, mostly idle; I did not
recount them). Service image built from the repository at this branch (service code unchanged, same Dockerfile).
Clean stack with an empty database (`run.sh` starts from `down -v`).

```
DC=<docker-compose plugin binary> ./load-test/run.sh restart-storm      # DEVICES=10000 DURATION=10m, defaults
```

What it does: 10 000 live devices (interval 10 s) connect without a ramp; once the simulator reports 10 000
connections the scenario takes a metrics snapshot ("old process"), runs `DC restart app`, and measures until the
simulator reports 10 000 connections again. The simulator prints its status line every 5 s, so **the
connection-return time has a resolution of 5 s**; health UP and the queue depth are polled every second. Traffic
then runs until 10 minutes after the restart, the simulator is stopped with SIGTERM, and the final snapshot
("new process") is taken after the devices are gone and the queue is drained. Run directory:
`load-test/results/20261111-232929-restart-storm/`.

Result of the run (10 000 devices, 10 min after the restart, wall time 679 s):

| | value |
|---|---:|
| Initial connect of 10 000 devices (no ramp) | 58 s |
| `restart app` → `/actuator/health` UP | 7 s |
| `restart app` → simulator reports 10 000 connections again (5 s resolution) | 15 s |
| Max `telemetry.queue.depth` during the return (1 s polling) | 0 |
| Max queue depth over the whole run (sampler, 5 s samples) | 96 |
| `dropped`, old / new process | 0 / 0 |
| `rejected`, old / new process | 0 / 0 |
| `batches.failed`, old / new process | 0 / 0 |
| `received` old process (snapshot just before the restart) / new process (final) | 30 684 / 590 987 |
| `rows.written` old / new process | 181 014 / 3 545 922 (= 6 × 590 987) |
| Messages sent by the simulator (summary) | 622 484 (`elapsed=658.2s`, `errorFrames=0`, `connectionErrors=30000`) |
| Rows in the database (`count(*)`) | 3 733 632 = 622 272 messages |
| Sent but not in the database | 212 messages (1 272 rows, 0.034 %) |
| Samples skipped while disconnected (estimate N × 15 s / 10 s) | about 15 000 (estimate, not asserted) |
| `received` by the new process (restart → SIGTERM, 600 s) vs N × 600 s / 10 s − skipped samples = 600 000 − 15 000 | 590 987 vs 585 000; difference +5 987 (recomputed, see the note below; not asserted) |
| `null` buckets in the hour chart of `host-0001` inside the run window (30 s buckets) | 1: `22:41:00Z` |
| Peak app CPU / memory (sampler; 100 % = 1 core) | 398 % in the sample right after the restart (JVM start), 149 % in the next, then 13–25 %; 1.70 GiB |
| Peak db CPU / memory | 38 % / 670 MiB |

Note on the received-vs-expected row: the script printed a different comparison in this run (it added the 15 s to the
expectation instead of subtracting it and compared old + new process against it, i.e. two different windows;
reviewer finding). I recomputed the row above from the saved snapshots of the same run (`after.txt`,
`sim.txt`); the script now prints this comparison. Likewise, the script detects the connection return from
status lines counted only after `restart` has returned; the measured run used the earlier check (any status line
after the restart command started, once health was UP), whose false positive would have shown up as a value equal to the
health time (7 s), not 15 s. I did not repeat the 10-minute run after these two script changes.

PASS/FAIL printed by the script:

```
PASS  dropped (old + new process): 0 (expected 0)
PASS  batches.failed (old + new process): 0 (expected 0)
PASS  all connections back after restart: 15 s (limit 120 s)
PASS  rows.written == 6 x received (new process): 3545922 (expected 3545922)
RESULT: PASS (restart-storm)
```

The only `null` bucket (`22:41:00Z`) is after the end of the traffic (the simulator was stopped at the end of the
10 minutes, the window runs to the end of the run), not at the restart: the restart is at 22:30:45Z and the
reconnect took at most 15 s, shorter than a 30 s bucket, so with a 10 s sample interval the bucket still has
data for `host-0001` and no gap shows. A gap at the hour chart resolution would need an outage longer than
about 30 s.

**What it means.**
- The reconnect storm has no ramp: all 10 000 devices retry at the same moment, and the simulator backoff
  retried each device three times while the service was down (`connectionErrors=30000` = 3 × 10 000). The service
  accepted all of them within 15 s of the restart and queued nothing (max queue depth 0 at 1 s polling), because
  the devices send only every 10 s: the storm is a connection-setup burst, not a message burst. Whether Tomcat
  `accept-count` (default 100) overflowed during the burst is not visible here: refused attempts are retried by
  the simulator, and the retries are what `connectionErrors` counts.
- No data was dropped by the service and no batch failed, in either process. The graceful shutdown (writer
  drain, `shutdown-timeout` 10 s) had an empty queue to drain in this run, so it was not stressed.
- **Counters are per process.** The Micrometer counters start at 0 with the new process, so deltas across a
  restart are meaningless; the first evaluation in this task made exactly that mistake (see below).
- **End to end, 212 of 622 484 sent messages (0.034 %) are not in the database.** The service counters cannot
  show this, and with no acknowledgement protocol the sender cannot either. This run does not
  tell where they were lost (messages in flight when the old process stopped are the obvious candidate;
  I did not verify it).
- The 15 s is a single number at 5 s resolution (runs 2 and 3 below gave the same 7 s / 15 s); it is not a
  distribution.

Runs I made for this scenario, all on this machine, in order (the numbers above come from the last one):
1. `restart-storm` 10 000: **FAIL in the connect phase.** Only 7 995 of 10 000 connections after the 180 s limit;
   the simulator output stopped at 7 995 and the sampler got no metrics from the service after about 60 s (its
   `docker stats` calls took ~30 s each). I did not find the cause (the next two runs connected all 10 000
   in 58 s). I did not repeat with 5 000 because 10 000 passed this phase on the retry. This is one
   observation of a stall, not an explanation.
2. `restart-storm` 10 000, first complete run: health UP 7 s, connections back 15 s, max queue 6, dropped 0. The
   script reported FAIL on `rows.written == 6 × received`: it subtracted before/after counters across a process
   restart (the counters reset) and read the final counters while traffic was still running. That was a
   measurement error of the script, not of the service; fixed in the commit "Evaluate restart-storm counters per
   process", and the final snapshot is now taken after the simulator stopped. The criteria themselves were not changed.
3. The run reported above.

### Smoke run of the new runner

`DEVICES=1000 DURATION=2m ./load-test/run.sh live` (same scenario as scenario 3, 1 000 devices, 2 min), exit 0:

```
simulator: [live] summary: elapsed=125.3s sent=12468 avg=100/s errorFrames=0 connectionErrors=0
deltas: received=12468 dropped=0 rejected=0 rows.written=74808 batches.failed=0
PASS  simulator exit code / errorFrames / dropped / rejected / batches.failed delta: all 0
PASS  received delta == simulator sent: 12468 (expected 12468)
PASS  rows.written delta == 6 x received: 74808 (expected 74808)
sampler peaks: queue depth 3, open fds 1024, app CPU 58 %, app memory 526 MiB, db CPU 13 %, db memory 186 MiB
RESULT: PASS (live)       scenario wall time: 150 s
```

The figures match scenario 3 for 1 000 devices (100 msg/s, 6 rows per message, 0 drops).

## Not measured

- Charts latency while 5 000 / 10 000 devices are live, and with concurrent clients.
- A week of history at the 10 s interval (12× the data of scenario 2; ~60 M messages).
- Per-device size numbers at the 10 s interval; the figures above are for a 120 s sample interval and I
  did not extrapolate them.
- The exact ingest ceiling between 5 000 and 7 500 msg/s.
- A short DB outage while ingesting, a silent device producing a gap in a chart, and a
  short spike being visible in the weekly `max`. (The reconnect storm after a restart is scenario 6.)
- Retention policy behaviour and the continuous-aggregate refresh under concurrent ingest (only the
  refresh job's success was seen, not timed).
- Cold-disk behaviour (only PostgreSQL's cache was cleared).
- `rejected` and malformed-message paths under load.

## Findings and recommendations (only what the numbers support)

1. **Ingest ceiling is the single writer, about 5.9 k msg/s (35 k rows/s) on this machine**, with drops
   first seen at 7 500 msg/s. 1 000 devices need 100 msg/s and 10 000 devices need 1 000 msg/s, so
   the target scale has ~5× headroom here, and the tested live runs used ≤ 23 % of the DB core.
   "What next" if the ceiling matters: more than one writer (parallel batches), or `COPY` instead of
   batched multi-row inserts.
2. **Silent drops.** Without an acknowledgement protocol, the sender sees `errorFrames=0` while 295 292
   messages were dropped (10 000 msg/s run). The `dropped` counter is the only signal. An ack or
   back-pressure protocol would be needed if devices must know.
3. **Storage: the continuous aggregate is the largest object after compression** (1.41 GB vs 463 MB for
   compressed raw data, for 1 000 devices × 1 week). If storage matters, compress `telemetry_5m` too or
   use a coarser bucket for the week range. Raw compression ratio is 10.7× (including indexes 1 592 MB →
   96 MB).
4. **Chart latency is not a concern at this scale**: p99 ≤ 46 ms on all ranges, including cold and during
   ingest from 1 000 devices (sequential client).
5. No service or simulator bugs were found in scenarios 1–5; no code was changed.
6. **Restart under 10 000 connected devices recovers in 15 s with no drops** (scenario 6), but 212 of 622 484
   sent messages (0.034 %) never reached the database, invisible to every counter. If that matters, it
   is the same recommendation as 2: an acknowledgement and resend protocol (inserts are already
   idempotent). The scenario has no ramp on reconnect (out of scope); a jittered
   reconnect on the device side would spread the burst and is worth specifying. I did not change the service or its
   configuration (`accept-count`, shutdown timeout).
7. **One of the three attempts to connect 10 000 devices stalled at 7 995 connections** and the sampler
   got no metrics from the service afterwards; the other two connected in 58 s. Not explained; it needs a
   repeat run with the app logs and the Docker VM watched before anything is concluded.

## Reproduce

Everything goes through the runner (it builds, starts a clean stack, evaluates and runs `down -v`; see
[`load-test/README.md`](../load-test/README.md)):

```
./load-test/run.sh ingest-ceiling 5000      # scenario 1 (per rate)
./load-test/run.sh week-backfill            # scenario 2, including compression and sizes
DURATION=5m ./load-test/run.sh live 1000    # scenario 3
./load-test/run.sh chart-latency            # scenario 4 (seeds 100 devices; the measured run used 1 000)
./load-test/run.sh restart-storm            # scenario 6
```

`KEEP_STACK=1` leaves the stack running afterwards; clean it up with `down -v` of project `hosload`
(`DC -p hosload --project-directory . -f docker-compose.yml -f load-test/docker-compose.load.yml down -v`).
The scenario-1–5 numbers above come from the earlier hand-run scripts; the scenario-4 seeding in the runner is
smaller (100 devices) than the 1 000-device week those numbers were measured on.
