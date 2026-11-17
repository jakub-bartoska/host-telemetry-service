package com.hosttelemetry.service.core.ingest

import com.hosttelemetry.service.config.IngestProperties
import com.hosttelemetry.service.model.ingest.TelemetryMessage
import com.hosttelemetry.service.repository.ingest.TelemetryRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.QueryTimeoutException
import org.springframework.transaction.TransactionSystemException
import java.time.Duration
import java.time.Instant

class TelemetryWriterTest {

    private val properties = IngestProperties(
        queueCapacity = 100,
        batchSize = 3,
        flushInterval = Duration.ofMillis(50),
        retryInitialBackoff = Duration.ofMillis(10),
        retryMaxBackoff = Duration.ofMillis(20),
        shutdownTimeout = Duration.ofSeconds(5),
        sessionIdleTimeout = Duration.ofSeconds(90),
        rejectionFlushInterval = Duration.ofSeconds(60),
        rejectionMaxEntries = 100,
    )
    private val metrics = IngestMetrics(SimpleMeterRegistry())
    private val queue = TelemetryQueue(properties.queueCapacity, metrics)
    private val repository = mock<TelemetryRepository>()
    private val writer = TelemetryWriter(queue, repository, properties, metrics)

    private fun message(n: Int) =
        TelemetryMessage("host-$n", Instant.EPOCH, mapOf("cpu" to n.toDouble(), "mem" to 1.0))

    @AfterEach
    fun stopWriter() {
        if (writer.isRunning) writer.stop()
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within 5 s" }
            Thread.sleep(10)
        }
    }

    /** The next calls of `insert` throw these errors in order; calls after them succeed. */
    private fun failInsertWith(first: RuntimeException, vararg more: RuntimeException) {
        doThrow(first, *more).doNothing().whenever(repository).insert(any())
    }

    /** Every batch passed to `insert` so far, in call order. */
    private fun insertedBatches(): List<List<TelemetryMessage>> =
        argumentCaptor<List<TelemetryMessage>>().apply { verify(repository, atLeastOnce()).insert(capture()) }.allValues

    @Test
    fun `splits the queue into batches of at most batch-size messages`() {
        (1..7).forEach { queue.offer(message(it)) }

        writer.start()
        awaitUntil { metrics.rowsWritten.count() == 14.0 }

        assertThat(insertedBatches().map { it.size }).allMatch { it <= 3 }
        assertThat(insertedBatches().flatten()).containsExactlyElementsOf((1..7).map(::message))
    }

    @Test
    fun `flushes a partial batch after the flush interval`() {
        writer.start()

        queue.offer(message(1))

        verify(repository, timeout(WAIT_MILLIS)).insert(listOf(message(1)))
        verifyNoMoreInteractions(repository)
    }

    @Test
    fun `retries the same batch after a transient error`() {
        failInsertWith(QueryTimeoutException("slow"), QueryTimeoutException("slow"))
        queue.offer(message(1))

        writer.start()

        verify(repository, timeout(WAIT_MILLIS).times(3)).insert(listOf(message(1)))
        assertThat(metrics.batchesFailed.count()).isZero()
    }

    @Test
    fun `retries the same batch after a failed commit`() {
        failInsertWith(TransactionSystemException("connection lost during commit"))
        queue.offer(message(1))

        writer.start()

        verify(repository, timeout(WAIT_MILLIS).times(2)).insert(listOf(message(1)))
        assertThat(metrics.batchesFailed.count()).isZero()
    }

    @Test
    fun `drops a batch after a non-transient error and keeps writing`() {
        failInsertWith(DataIntegrityViolationException("bad row"))
        queue.offer(message(1))
        writer.start()
        awaitUntil { metrics.batchesFailed.count() == 1.0 }

        queue.offer(message(2))

        verify(repository, timeout(WAIT_MILLIS)).insert(listOf(message(2)))
        verify(repository).insert(listOf(message(1)))
        verifyNoMoreInteractions(repository)
    }

    @Test
    fun `a stopped writer can be started again and keeps writing`() {
        writer.start()
        writer.stop()

        writer.start()

        assertThat(queue.offer(message(1))).isTrue()
        verify(repository, timeout(WAIT_MILLIS)).insert(listOf(message(1)))
        verifyNoMoreInteractions(repository)
    }

    @Test
    fun `stop drains the queue before returning`() {
        writer.start()
        (1..10).forEach { queue.offer(message(it)) }

        writer.stop()

        assertThat(insertedBatches().flatten()).hasSize(10)
        assertThat(queue.offer(message(11))).isFalse()
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
