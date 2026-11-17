# 0004 — Deployment: multi-stage Docker image, Docker Compose for evaluation

Status: accepted

## Context
The evaluator must start everything with one or two commands and should need nothing but Docker.
Compose v2.12 compatibility is required (no `include`, no `develop`).

## Decision
- **One multi-stage `Dockerfile`**: build stage `eclipse-temurin:25-jdk` runs the Gradle wrapper
  (toolchain pinned to 25 in the build), runtime stage `eclipse-temurin:25-jre`
  (originally JDK 21; versions superseded by ADR 0006). Two targets:
  `app` and `simulator` (the simulator is its own Gradle module and its own jar).
- **`docker-compose.yml`**:
  - `db`: `timescale/timescaledb` with a **pinned** tag (`2.31.0-pg18`; originally `2.x-pg16`, superseded by ADR 0006), named volume, `pg_isready` healthcheck.
  - `app`: `depends_on: db: condition: service_healthy`; Flyway migrates on startup; port 8080.
  - `simulator`: under `profiles: ["sim"]`, so it only starts on request; configurable device
    count, interval and mode (`live` / `backfill`).
- Commands: `docker compose up --build` and
  `docker compose --profile sim run simulator --devices 1000` (exact flags fixed at implementation).
- Integration tests use **Testcontainers** with the same TimescaleDB image, not Compose.
- `server.tomcat.max-connections` raised from the default 8 192 to 20 000 — the default would refuse
  device #8 193.

## Alternatives considered
- **Run locally with Gradle + a Compose-only database:** faster dev loop, but the evaluator then needs
  JDK 25. Kept as a documented dev option, not the main path.
- **Jib / Spring Boot buildpacks:** no Dockerfile, but less transparent and harder to explain.
- **Kubernetes / Helm:** out of scope (team rule).

## Consequences
- Load-test numbers from Compose are numbers **for one laptop** where simulator, service and DB
  share CPU, RAM and (on Windows/macOS) the Docker Desktop VM. They show relative behaviour and
  bottlenecks, not production capacity; the README must say so with the machine spec.
- Compose gives no restart orchestration beyond `restart:` and no resource isolation unless we set
  limits; we set none to keep it simple.
