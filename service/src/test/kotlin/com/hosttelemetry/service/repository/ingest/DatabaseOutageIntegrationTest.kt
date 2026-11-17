package com.hosttelemetry.service.repository.ingest

import com.hosttelemetry.service.core.ingest.isTransientDatabaseError
import com.hosttelemetry.service.model.ingest.TelemetryMessage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant

/**
 * The whole write path (transactional proxy, Exposed, connection pool) against a database that does
 * not answer, as after an outage: the error must be one the writer retries, not one it drops the batch for.
 */
@SpringBootTest(
    // A real server: the WebSocket container of WebSocketConfig does not exist in a mock web environment.
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        // Nothing listens on port 1. The pool starts without a connection and gives up after 250 ms.
        "spring.datasource.url=jdbc:postgresql://localhost:1/telemetry",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "spring.datasource.hikari.connection-timeout=250",
        "spring.flyway.enabled=false",
    ],
)
class DatabaseOutageIntegrationTest(@Autowired private val repository: TelemetryRepository) {

    @Test
    fun `an unreachable database is a transient error for the writer`() {
        val message = TelemetryMessage("host-0001", Instant.now(), mapOf("cpuUtilPct" to 1.0))

        val error = catchThrowable { repository.insert(listOf(message)) }

        assertThat(error).isInstanceOf(Exception::class.java)
        assertThat(isTransientDatabaseError(error as Exception)).describedAs("retried: %s", error).isTrue()
    }
}
