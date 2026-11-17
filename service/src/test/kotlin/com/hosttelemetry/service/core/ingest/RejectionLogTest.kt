package com.hosttelemetry.service.core.ingest

import com.hosttelemetry.service.config.IngestProperties
import com.hosttelemetry.service.model.ingest.IngestRejection
import com.hosttelemetry.service.model.ingest.RejectReason
import com.hosttelemetry.service.repository.ingest.IngestRejectionRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.QueryTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant

class RejectionLogTest {

    private val start = Instant.parse("2026-10-26T14:00:00Z")

    @Volatile
    private var now = start
    private val clock = mock<Clock> { on { instant() } doAnswer { now } }
    private val metrics = IngestMetrics(SimpleMeterRegistry())
    private val repository = mock<IngestRejectionRepository>()
    private var log = rejectionLog()

    // The flush interval is an hour unless a test sets it, so only explicit flushes write.
    private fun rejectionLog(flushInterval: Duration = Duration.ofHours(1), maxEntries: Int = 100) = RejectionLog(
        repository,
        IngestProperties(
            queueCapacity = 100,
            batchSize = 3,
            flushInterval = Duration.ofMillis(50),
            retryInitialBackoff = Duration.ofMillis(10),
            retryMaxBackoff = Duration.ofMillis(20),
            shutdownTimeout = Duration.ofSeconds(5),
            sessionIdleTimeout = Duration.ofSeconds(90),
            rejectionFlushInterval = flushInterval,
            rejectionMaxEntries = maxEntries,
        ),
        metrics,
        clock,
    )

    @AfterEach
    fun stopLog() {
        if (log.isRunning) log.stop()
    }

    private fun flushAfter(elapsed: Duration): List<IngestRejection> {
        now += elapsed
        log.flush()
        return insertedWindows().last()
    }

    /** Every window passed to `insert` so far, in call order. */
    private fun insertedWindows(): List<List<IngestRejection>> =
        argumentCaptor<List<IngestRejection>>().apply { verify(repository, atLeastOnce()).insert(capture()) }.allValues

    @Test
    fun `sums rejections per device and reason and keeps the last message as the sample`() {
        log.rejected(RejectReason.TIMESTAMP_IN_FUTURE, "host-1", "first")
        log.rejected(RejectReason.TIMESTAMP_IN_FUTURE, "host-1", "second")
        log.rejected(RejectReason.INVALID_STATS, "host-1", "no stats")
        log.rejected(RejectReason.TIMESTAMP_IN_FUTURE, "host-2", "other device")

        val end = start + Duration.ofSeconds(60)
        assertThat(flushAfter(Duration.ofSeconds(60))).containsExactlyInAnyOrder(
            IngestRejection(start, end, "host-1", "timestamp_in_future", 2, emptyList(), "second"),
            IngestRejection(start, end, "host-1", "invalid_stats", 1, emptyList(), "no stats"),
            IngestRejection(start, end, "host-2", "timestamp_in_future", 1, emptyList(), "other device"),
        )
    }

    @Test
    fun `rejections without a device id share one row per reason`() {
        log.rejected(RejectReason.INVALID_JSON, null, "not json")
        log.rejected(RejectReason.INVALID_JSON, null, "{")

        assertThat(flushAfter(Duration.ofSeconds(60))).containsExactly(
            IngestRejection(start, start + Duration.ofSeconds(60), null, "invalid_json", 2, emptyList(), "{"),
        )
    }

    @Test
    fun `discarded keys of one device are collected without duplicates`() {
        log.discarded("host-1", listOf("temperatureC", "fanOn"), "first")
        log.discarded("host-1", listOf("fanOn", "powerW"), "second")

        assertThat(flushAfter(Duration.ofSeconds(60))).containsExactly(
            IngestRejection(
                start, start + Duration.ofSeconds(60), "host-1", "discarded_stats", 2,
                listOf("temperatureC", "fanOn", "powerW"), "second",
            ),
        )
    }

    @Test
    fun `keeps at most 32 discarded keys per device`() {
        log.discarded("host-1", (1..40).map { "key$it" }, "message")

        assertThat(flushAfter(Duration.ofSeconds(60)).single().discardedKeys).isEqualTo((1..32).map { "key$it" })
    }

    @Test
    fun `the sample is cut to 1000 characters without splitting a surrogate pair`() {
        log.rejected(RejectReason.INVALID_JSON, "host-1", "a".repeat(1500))
        log.rejected(RejectReason.INVALID_STATS, "host-1", "a".repeat(999) + "😀" + "tail")

        val samples = flushAfter(Duration.ofSeconds(60)).associate { it.reason to it.sample }

        assertThat(samples["invalid_json"]).isEqualTo("a".repeat(1000))
        assertThat(samples["invalid_stats"]).isEqualTo("a".repeat(999))
    }

    @Test
    fun `text that PostgreSQL cannot store is made storable`() {
        log.discarded("host-1", listOf("bad\u0000key", "k".repeat(100)), "nul \u0000 inside")

        val rejection = flushAfter(Duration.ofSeconds(60)).single()

        assertThat(rejection.discardedKeys).containsExactly("bad�key", "k".repeat(64))
        assertThat(rejection.sample).isEqualTo("nul � inside")
    }

    @Test
    fun `each flush starts a new window where the previous one ended`() {
        log.rejected(RejectReason.INVALID_JSON, null, "one")
        flushAfter(Duration.ofSeconds(60))
        log.rejected(RejectReason.INVALID_JSON, null, "two")

        val second = flushAfter(Duration.ofSeconds(30)).single()

        assertThat(second.windowStart).isEqualTo(start + Duration.ofSeconds(60))
        assertThat(second.windowEnd).isEqualTo(start + Duration.ofSeconds(90))
        assertThat(second.messageCount).isEqualTo(1)
    }

    @Test
    fun `an empty window writes nothing`() {
        log.flush()

        verify(repository, never()).insert(any())
    }

    @Test
    fun `pairs beyond the limit are counted as unrecorded and known pairs still add up`() {
        log = rejectionLog(maxEntries = 2)
        log.rejected(RejectReason.INVALID_STATS, "host-1", "m")
        log.rejected(RejectReason.INVALID_STATS, "host-2", "m")

        log.rejected(RejectReason.INVALID_STATS, "host-3", "m")
        log.rejected(RejectReason.INVALID_STATS, "host-1", "m")

        val window = flushAfter(Duration.ofSeconds(60))
        assertThat(window.associate { it.deviceId to it.messageCount }).isEqualTo(mapOf("host-1" to 2L, "host-2" to 1L))
        assertThat(metrics.rejectionsUnrecorded.count()).isEqualTo(1.0)
    }

    @Test
    fun `a window whose write fails is counted as unrecorded and not written again, and the next one is written`() {
        doThrow(QueryTimeoutException("slow")).doNothing().whenever(repository).insert(any())
        log.rejected(RejectReason.INVALID_STATS, "host-1", "m")
        log.rejected(RejectReason.INVALID_STATS, "host-1", "m")
        log.rejected(RejectReason.INVALID_JSON, null, "m")

        log.flush()
        log.flush()

        verify(repository).insert(any())
        assertThat(metrics.rejectionsUnrecorded.count()).isEqualTo(3.0)

        log.rejected(RejectReason.INVALID_JSON, null, "after the failure")

        assertThat(flushAfter(Duration.ofSeconds(60)).map { it.sample }).containsExactly("after the failure")
    }

    @Test
    fun `writes a window on its own every flush interval`() {
        log = rejectionLog(flushInterval = Duration.ofMillis(20))
        log.start()

        log.rejected(RejectReason.INVALID_JSON, null, "m")

        verify(repository, timeout(5_000)).insert(any())
        assertThat(insertedWindows().flatten().map { it.reason }).containsExactly("invalid_json")
    }

    @Test
    fun `stop writes the last window, and the log can be started again`() {
        log.start()
        log.rejected(RejectReason.INVALID_JSON, null, "before stop")

        log.stop()

        assertThat(insertedWindows().flatten().map { it.sample }).containsExactly("before stop")

        log.start()
        log.rejected(RejectReason.INVALID_JSON, null, "after restart")
        log.stop()

        assertThat(insertedWindows().flatten().map { it.sample }).containsExactly("before stop", "after restart")
    }
}
