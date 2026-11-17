# 0006 — Dependency versions: latest GA, LTS JDK

Status: accepted (decision by the project owner)

## Context
This is a greenfield project started in November 2026. The skeleton was first built on Spring Boot
3.5.16, JDK 21 and TimescaleDB on PostgreSQL 16. Defending an older major line in an interview needs
a reason, and there is none for code that has no users or legacy to protect.

## Decision
Use the latest GA release of every part of the stack, except the JDK, which follows the latest LTS.
Versions as committed (`build.gradle.kts`, `settings.gradle.kts`, wrapper, `Dockerfile`,
`docker-compose.yml`, commits `817b669` and `2876b70`):

| Component | Version | Note |
|---|---|---|
| Spring Boot | 4.1.2 | Spring Framework 7, Jackson 3 (`tools.jackson`) |
| Jackson | 3.1.7 | from the Boot BOM, incl. `jackson-module-kotlin` (not 3.3.0, see below) |
| Kotlin | 2.4.21 | `-Xjsr305=strict` kept |
| Gradle | 9.9.1 | wrapper; foojay resolver 1.0.0 provisions the toolchain |
| JDK | 25 | toolchain and `eclipse-temurin:25-jdk` / `25-jre` images |
| TimescaleDB | 2.31.0-pg18 | same tag in Compose and Testcontainers |
| Testcontainers | 2.0.5 | from the Boot BOM |
| Exposed | 1.6.1 | added later, ADR 0007; not in the Boot BOM, version in `service/build.gradle.kts` |

**Why:** Spring Framework 7 ships JSpecify nullness annotations, so Kotlin sees Spring APIs as
nullable or non-null instead of platform types; a newer line has a longer support window; and it
shows the author follows the stack. **JDK 25, not 27:** 27 is a non-LTS release with about six
months of updates; a backend service should sit on an LTS.

## Alternatives considered
- **Stay on Spring Boot 3.5.x / JDK 21** (the original default). Most examples and Stack Overflow
  answers still target it, and the migration costs nothing. Lost because it is the previous major
  line and the cost of moving was small while the code base is tiny. It remains the **fallback**: if
  Boot 4 blocks something and the fix takes more than an hour, return to 3.5.x and record why.
- **Newest everything, including non-LTS JDK 27.** Rejected: short support, no benefit we use.

## Consequences
- What the upgrade actually broke (HOS-5), all fixed: split starters (`spring-boot-starter-webmvc`
  instead of `starter-web`; Flyway does not run without `spring-boot-starter-flyway`);
  `TestRestTemplate` moved to `spring-boot-resttestclient` and needs `@AutoConfigureTestRestTemplate`;
  `START_STOP_LIFECYCLE_PHASE` moved package; Testcontainers 2 renamed artifacts
  (`testcontainers-postgresql`, `testcontainers-junit-jupiter`) and `PostgreSQLContainer` package;
  PostgreSQL 18 images keep data under `/var/lib/postgresql/18/docker`, so the Compose volume had to
  move to `/var/lib/postgresql` or data would silently land in an anonymous volume.
- Jackson 3 is confined to one class (`TelemetryJson`). `jackson-module-kotlin` 3.3.0 was available,
  but we keep the BOM's 3.1.7 so every Jackson module matches the databind version Boot is tested with.
- Fewer web examples exist for Boot 4 and Jackson 3; changes must follow the official migration
  guide, not memory.
- The JDK 21 image tags in ADR 0004 are superseded by this record.
- **Rule from now on:** patch versions may be raised freely (own commit, build green); a
  **major** version change needs a new ADR.
