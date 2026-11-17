package com.hosttelemetry.service.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.validation.BindValidationException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

/** Binds `telemetry.ingest.*` the way the application does at startup, without starting the application. */
class IngestPropertiesTest {

    @EnableConfigurationProperties(IngestProperties::class)
    class PropertiesOnly

    private val valid = mapOf(
        "queue-capacity" to "10",
        "batch-size" to "5",
        "flush-interval" to "500ms",
        "retry-initial-backoff" to "100ms",
        "retry-max-backoff" to "5s",
        "shutdown-timeout" to "10s",
        "session-idle-timeout" to "90s",
        "rejection-flush-interval" to "60s",
        "rejection-max-entries" to "100",
    )

    private fun contextWith(vararg overrides: Pair<String, String>): ApplicationContextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly::class.java)
            .withPropertyValues(*(valid + overrides).map { (key, value) -> "telemetry.ingest.$key=$value" }.toTypedArray())

    @Test
    fun `valid values are bound`() {
        contextWith().run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(IngestProperties::class.java)).isEqualTo(
                IngestProperties(
                    queueCapacity = 10,
                    batchSize = 5,
                    flushInterval = Duration.ofMillis(500),
                    retryInitialBackoff = Duration.ofMillis(100),
                    retryMaxBackoff = Duration.ofSeconds(5),
                    shutdownTimeout = Duration.ofSeconds(10),
                    sessionIdleTimeout = Duration.ofSeconds(90),
                    rejectionFlushInterval = Duration.ofSeconds(60),
                    rejectionMaxEntries = 100,
                ),
            )
        }
    }

    @ParameterizedTest
    @CsvSource(
        "queue-capacity,        0,  queueCapacity",
        "batch-size,            0,  batchSize",
        "flush-interval,        0s, flushInterval",
        "retry-initial-backoff, 0s, retryInitialBackoff",
        "shutdown-timeout,      0s, shutdownTimeout",
        "session-idle-timeout,  0s, sessionIdleTimeout",
        "rejection-flush-interval, 0s, rejectionFlushInterval",
        "rejection-max-entries, 0,  rejectionMaxEntries",
    )
    fun `a zero value stops the application at startup`(key: String, value: String, field: String) {
        contextWith(key to value).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .rootCause()
                .isInstanceOf(BindValidationException::class.java)
                .hasMessageContaining("'$field'")
        }
    }

    @Test
    fun `a maximum retry backoff equal to the initial one is allowed`() {
        contextWith("retry-initial-backoff" to "5s", "retry-max-backoff" to "5s").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(IngestProperties::class.java).retryMaxBackoff).isEqualTo(Duration.ofSeconds(5))
        }
    }

    @Test
    fun `a maximum retry backoff below the initial one stops the application at startup`() {
        contextWith("retry-initial-backoff" to "10s", "retry-max-backoff" to "5s").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .rootCause()
                .isInstanceOf(BindValidationException::class.java)
                .hasMessageContaining("retry-max-backoff must not be shorter than retry-initial-backoff")
        }
    }
}
