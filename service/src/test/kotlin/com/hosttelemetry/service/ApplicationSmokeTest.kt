package com.hosttelemetry.service

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/** Boots the whole app against TimescaleDB: Flyway must apply all migrations and the health endpoint must be UP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TimescaleContainerConfig::class)
class ApplicationSmokeTest(
    @Autowired private val rest: TestRestTemplate,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val transactionManagers: List<PlatformTransactionManager>,
) {

    @Test
    fun `Exposed's transaction manager is the only one`() {
        // Two managers would make every @Transactional call fail; Boot's JDBC one must back off.
        assertThat(transactionManagers).singleElement().isInstanceOf(SpringTransactionManager::class.java)
    }

    @Test
    fun `health endpoint reports UP`() {
        val response = rest.getForEntity("/actuator/health", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"status\":\"UP\"")
    }

    @Test
    fun `migration creates hypertable, continuous aggregate and policies`() {
        val hypertables = jdbc.queryForList(
            "SELECT hypertable_name FROM timescaledb_information.hypertables",
            String::class.java,
        )
        val aggregates = jdbc.queryForList(
            "SELECT view_name FROM timescaledb_information.continuous_aggregates",
            String::class.java,
        )
        val jobs = jdbc.queryForList(
            "SELECT proc_name FROM timescaledb_information.jobs WHERE hypertable_name IS NOT NULL",
            String::class.java,
        )

        assertThat(hypertables).contains("telemetry", "ingest_rejections")
        assertThat(aggregates).containsExactly("telemetry_5m")
        assertThat(jobs).containsExactlyInAnyOrder(
            // telemetry
            "policy_compression",
            "policy_retention",
            // telemetry_5m
            "policy_refresh_continuous_aggregate",
            "policy_compression",
            "policy_retention",
            // ingest_rejections
            "policy_retention",
        )
    }
}
