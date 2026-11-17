# 0007 — Query layer: Exposed DSL, no JPA/Hibernate

Status: accepted

## Context
The two repositories first held their SQL as Kotlin string constants run through `JdbcTemplate`.
All values were bound as `?` parameters, so there was no SQL injection, but every new query or column
would be another hand-written string: column names are not checked by the compiler, and someone
extending the service could concatenate a request value into one. The question was whether to move to
an ORM.

The queries are not CRUD on entities. Ingest is a bulk insert with `ON CONFLICT DO NOTHING`
(~6 000 rows/s at 10 000 devices, ADR 0002); charts use TimescaleDB's `time_bucket` and read a
continuous aggregate (ADR 0001, 0003).

## Decision
- **Exposed 1.6 (JetBrains) DSL** for the queries in `ExposedTelemetryRepository` and `SeriesRepository`.
  Tables are Kotlin objects (`TelemetryTable`, `Telemetry5mView`); values are always bound parameters.
- Ingest: `batchInsert(ignore = true, shouldReturnGeneratedValues = false)`, which on PostgreSQL is
  `INSERT … ON CONFLICT DO NOTHING`, sent as one JDBC batch, so `reWriteBatchedInserts` still applies.
- `time_bucket` is one small custom expression (`TimeBucket`); its width comes from `ChartRange`,
  never from a request.
- **Flyway keeps the schema** in SQL migrations. Hypertables, compression, continuous aggregates and
  policies are TimescaleDB DDL that no ORM generates; Exposed only describes the columns.
- Exposed is wired by its Spring Boot starter (`exposed-spring-boot4-starter`): it runs on Boot's
  Hikari `DataSource` and inside Spring transactions, so the repositories use `@Transactional` and
  there is no Exposed configuration class. Exposed's own transaction retry belongs to its
  `transaction { }` function, which is not used, so the writer's retry policy is the only one.
- SQL errors are translated to Spring's `DataAccessException` hierarchy (`translateSqlErrors`),
  because the writer decides retry or drop by that type. A transaction that cannot be opened or
  committed is raised by Spring as a transaction exception and is retried as well. Unit tested;
  `DatabaseOutageIntegrationTest` runs the write path against an unreachable database.

## Alternatives considered
- **JPA / Hibernate.** Rejected: `time_bucket`, the continuous aggregate and `ON CONFLICT` are not
  expressible in JPQL, so all three queries would become `nativeQuery` strings — the same SQL in an
  annotation. Entities, the persistence context and dirty checking are per-row overhead on a bulk
  write path, and Hibernate's schema generation cannot be used with TimescaleDB anyway.
- **jOOQ.** Type-safe SQL generated from the schema, the strongest option for PostgreSQL. Rejected for
  now: code generation needs a database at build time (or a DDL parser setup), more build machinery
  than three queries justify. A reasonable choice if the query surface grows.
- **Keep `JdbcTemplate` strings.** Least code and no dependency; rejected for the extension argument
  in the Context.

## Consequences
- Measured on the same laptop as `docs/load-test.md`, one `ingest-ceiling` run each at 7 500 msg/s
  (above the ceiling, so the writer is saturated in both): `JdbcTemplate` wrote 3 151 710 rows in
  97.6 s of writer time (≈5 380 msg/s, 186 ms per 1 000-message batch), Exposed 3 103 872 rows in
  97.6 s (≈5 300 msg/s, 188 ms per batch). A 1.5 % difference from one run each is within run-to-run
  noise; the bottleneck stays the single writer (ADR 0002).
- One more dependency outside the Spring Boot BOM (version in `service/build.gradle.kts`, ADR 0006).
- Tests still set up data with `JdbcTemplate`; they test the service through its HTTP and WebSocket
  interfaces, not through the repositories.
