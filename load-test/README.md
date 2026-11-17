# Load tests

Automated, configurable load-test scenarios for the telemetry service. Each run starts a clean stack
(PostgreSQL + TimescaleDB and the service), waits for `/actuator/health`, runs one scenario with the
simulator, evaluates the metrics, prints a table of numbers and `PASS`/`FAIL` per criterion, and removes the
stack again. No JUnit, no manual steps. Results from this machine: [`docs/load-test.md`](../docs/load-test.md).

## Requirements

- bash, `curl`, `awk`, `sed`
- Docker with Compose v2 (`docker compose`, or the older `docker-compose` binary; v2.12 is enough)
- Python 3 (`python3` or `python`) for the sampler and the helper scripts
- Node 18+ (only the `chart-latency` scenario)
- Free host ports `APP_PORT` (18080) and `DB_PORT` (15432)

## Run

```
./load-test/run.sh restart-storm                          # defaults: DEVICES=10000 DURATION=10m
DEVICES=1000 DURATION=2m ./load-test/run.sh live          # small smoke run
./load-test/run.sh live 5000 5m                           # positional arguments override the variables
RATE=7500 ./load-test/run.sh ingest-ceiling
```

Exit code: `0` PASS, `1` FAIL, `2` setup error (Docker not running, build or start failed, health never UP,
unknown scenario). That is all a pipeline step needs; see below.

## Scenarios

| scenario | arguments (env) | what it does | PASS when |
|---|---|---|---|
| `ingest-ceiling` | `[rate]` (`RATE`=5000, `DEVICES`=1000, `DAYS`=1, `INTERVAL`=120s) | backfill at a fixed message rate | dropped, rejected, `batches.failed` = 0; received = simulator sent; `rows.written` = 6 × (received − dropped) = `count(*)`. Raise `RATE` until it fails to find the ceiling |
| `week-backfill` | `[devices]` (`DEVICES`=1000, `DAYS`=7, `INTERVAL`=120s, `RATE`=5000) | backfill a week, then compress, report sizes | dropped, rejected, `batches.failed` = 0; received = sent; `count(*)` = 6 × received. Sizes and times are printed, not asserted |
| `live` | `[devices] [duration]` (`DEVICES`=1000, `DURATION`=5m, `INTERVAL`=10s) | live devices over WebSocket | dropped, rejected, `batches.failed` = 0; `errorFrames` = 0; received = sent; `rows.written` = 6 × received |
| `chart-latency` | `[count]` (`COUNT`=200, `SEED_DEVICES`=100, `DAYS`=7, `LIVE_DEVICES`=0, `P99_MAX_MS`=200) | seeds a week, then measures `hour`/`day`/`week` for one device and for `COUNT` distinct devices; `LIVE_DEVICES`>0 adds live ingest | no failed request, p99 ≤ `P99_MAX_MS` in every row |
| `restart-storm` | `[devices] [duration]` (`DEVICES`=10000, `DURATION`=10m, `RECONNECT_TIMEOUT_S`=120, `CONNECT_TIMEOUT_S`=180) | connects the devices, restarts the service, measures the recovery, runs steady traffic | dropped and `batches.failed` did not grow; all connections back within `RECONNECT_TIMEOUT_S`; `rows.written` = 6 × received. Also printed, not asserted: health-UP time, max queue depth, `null` buckets in the hour chart of `host-0001`, received vs expected |

Verification status: `live` (10 000 devices, 10 min) and `restart-storm` (10 000 devices, 10 min) were run end to end;
`ingest-ceiling`, `week-backfill` and `chart-latency` have only been syntax-checked (`bash -n`) in this runner, so
treat their first run as a check of the script too.

Notes on `restart-storm`: the "connections back" time is read from the simulator's status line, which is
printed every 5 s, so its resolution is 5 s. The 120 s limit is a variable on purpose.

## Other knobs

| variable | default | meaning |
|---|---|---|
| `KEEP_STACK` | `0` | `1` leaves the stack running after the run (clean it with `docker compose -p hosload ... down -v`) |
| `OUT_DIR` | `load-test/results` | base folder for results |
| `DC` | `docker compose` (else `docker-compose`) | Compose command; must not contain spaces in paths |
| `PROJECT` | `hosload` | Compose project name (containers are `<PROJECT>-app-1`, `<PROJECT>-db-1`) |
| `APP_PORT` / `DB_PORT` | `18080` / `15432` | published host ports (the base compose file uses 8080 / 5432) |
| `QUEUE_CAPACITY`, `BATCH_SIZE`, `FLUSH_INTERVAL` | 10000, 1000, 500ms | passed to the service as `TELEMETRY_INGEST_*` ([`docker-compose.load.yml`](docker-compose.load.yml)) |
| `HEALTH_TIMEOUT_S` | `300` | how long to wait for the first healthy state |

## Results

Every run writes `load-test/results/<timestamp>-<scenario>/` (git-ignored): `summary.txt` (the printed
output), `before.txt` / `after.txt` (metric snapshots), `sampler.csv` (service metrics plus `docker stats`
every 5 s), `sim.txt` (simulator output), `app.log`, `build.log`, and scenario files (`series.json`,
`nulls.txt`, `latency.jsonl`).

## In a pipeline

```
DEVICES=1000 DURATION=2m ./load-test/run.sh live   # fails the job on exit 1 (FAIL) or 2 (setup error)
```

Archive `load-test/results/` as the job artifact. The runner needs a Docker daemon (a Docker-in-Docker
or a VM runner); the first run builds the service and simulator images, so allow a few minutes.

## Caveats

- The service, database, simulator, sampler and the measuring client all share one machine. The numbers
  show relative behaviour and where the bottleneck is, not production capacity.
- The service has no acknowledgement protocol, so the simulator cannot see drops; only the service's
  `dropped` counter shows them, and that is what the scenarios assert on.
- `rows.written` counts rows handed to the database, including duplicates ignored by `ON CONFLICT`. On a clean
  stack with live devices it must equal 6 × received exactly; re-running a backfill over existing data would
  break that equality by design.
- `run.sh` starts from `down -v` every time, so it deletes the data of the `PROJECT` stack it uses.
