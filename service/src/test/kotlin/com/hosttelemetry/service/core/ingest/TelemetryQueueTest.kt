package com.hosttelemetry.service.core.ingest

import com.hosttelemetry.service.model.ingest.TelemetryMessage
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class TelemetryQueueTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = IngestMetrics(registry)

    private fun message(n: Int) = TelemetryMessage("host-$n", Instant.EPOCH, mapOf("cpu" to n.toDouble()))

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    fun `when full, offer drops the message without blocking and counts it`() {
        val queue = TelemetryQueue(capacity = 2, metrics = metrics)

        val results = (1..5).map { queue.offer(message(it)) }

        assertThat(results).containsExactly(true, true, false, false, false)
        assertThat(queue.size).isEqualTo(2)
        assertThat(metrics.dropped.count()).isEqualTo(3.0)
        assertThat(registry.get("telemetry.queue.depth").gauge().value()).isEqualTo(2.0)
    }

    @Test
    fun `a closed queue drops new messages but can still be drained`() {
        val queue = TelemetryQueue(capacity = 10, metrics = metrics)
        queue.offer(message(1))

        queue.close()

        assertThat(queue.offer(message(2))).isFalse()
        assertThat(metrics.dropped.count()).isEqualTo(1.0)
        assertThat(queue.drainBatch(10, Duration.ZERO)).containsExactly(message(1))
    }

    @Test
    fun `a reopened queue accepts messages again`() {
        val queue = TelemetryQueue(capacity = 10, metrics = metrics)
        queue.close()

        queue.reopen()

        assertThat(queue.offer(message(1))).isTrue()
        assertThat(metrics.dropped.count()).isZero()
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    fun `drainBatch returns a full batch immediately`() {
        val queue = TelemetryQueue(capacity = 10, metrics = metrics)
        (1..5).forEach { queue.offer(message(it)) }

        val batch = queue.drainBatch(maxSize = 3, maxWait = Duration.ofMinutes(1))

        assertThat(batch).containsExactly(message(1), message(2), message(3))
        assertThat(queue.size).isEqualTo(2)
    }

    @Test
    fun `drainBatch returns a partial batch once the wait is over`() {
        val queue = TelemetryQueue(capacity = 10, metrics = metrics)
        queue.offer(message(1))
        val maxWait = Duration.ofMillis(200)

        val started = System.nanoTime()
        val batch = queue.drainBatch(maxSize = 3, maxWait = maxWait)
        val waited = Duration.ofNanos(System.nanoTime() - started)

        assertThat(batch).containsExactly(message(1))
        assertThat(waited).isBetween(maxWait, Duration.ofSeconds(2))
    }

    @Test
    fun `drainBatch picks up messages offered while it waits`() {
        val queue = TelemetryQueue(capacity = 10, metrics = metrics)
        Thread {
            Thread.sleep(50)
            (1..2).forEach { queue.offer(message(it)) }
        }.start()

        val batch = queue.drainBatch(maxSize = 2, maxWait = Duration.ofSeconds(5))

        assertThat(batch).containsExactly(message(1), message(2))
    }
}
