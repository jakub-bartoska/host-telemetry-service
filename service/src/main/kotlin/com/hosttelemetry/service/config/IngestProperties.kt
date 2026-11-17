package com.hosttelemetry.service.config

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Positive
import org.hibernate.validator.constraints.time.DurationMin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Ingest pipeline tuning (`telemetry.ingest.*` in application.yml).
 * Validated when the properties are bound, so a bad value stops the application at startup.
 */
@Validated
@ConfigurationProperties("telemetry.ingest")
data class IngestProperties(
    @field:Positive
    val queueCapacity: Int,

    // A zero batch size or flush interval would make the writer spin without writing.
    @field:Positive
    val batchSize: Int,

    @field:DurationMin(millis = 1)
    val flushInterval: Duration,

    // A zero backoff would retry a failing batch in a hot loop.
    @field:DurationMin(millis = 1)
    val retryInitialBackoff: Duration,

    val retryMaxBackoff: Duration,

    // Zero would mean "wait forever" for Thread.join, so shutdown could hang on a dead database.
    @field:DurationMin(millis = 1)
    val shutdownTimeout: Duration,

    // Zero would mean "never time out" in Tomcat, which is exactly what this setting is there to prevent.
    @field:DurationMin(millis = 1)
    val sessionIdleTimeout: Duration,

    // The period of a scheduled task, which must be positive.
    @field:DurationMin(millis = 1)
    val rejectionFlushInterval: Duration,

    // Zero would record no rejection at all.
    @field:Positive
    val rejectionMaxEntries: Int,
) {
    @AssertTrue(message = "retry-max-backoff must not be shorter than retry-initial-backoff")
    fun isRetryMaxBackoffAtLeastInitial(): Boolean = retryMaxBackoff >= retryInitialBackoff
}
