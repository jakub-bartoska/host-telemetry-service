package com.hosttelemetry.service

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

// Keep in sync with the db image in docker-compose.yml.
private val TIMESCALE_IMAGE: DockerImageName =
    DockerImageName.parse("timescale/timescaledb:2.31.0-pg18").asCompatibleSubstituteFor("postgres")

/** TimescaleDB container wired into the Spring datasource; import it in integration tests. */
@TestConfiguration(proxyBeanMethods = false)
class TimescaleContainerConfig {

    @Bean
    @ServiceConnection
    fun timescale(): PostgreSQLContainer = PostgreSQLContainer(TIMESCALE_IMAGE)
}
