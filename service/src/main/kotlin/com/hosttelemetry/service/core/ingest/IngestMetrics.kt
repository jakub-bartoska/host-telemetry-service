package com.hosttelemetry.service.core.ingest

import com.hosttelemetry.service.model.ingest.RejectReason
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

/** Ingest counters, exposed at `/actuator/metrics/telemetry.*`. */
@Component
class IngestMetrics(private val registry: MeterRegistry) {

    /** Every text frame that reached the handler. */
    val received: Counter = Counter.builder("telemetry.messages.received").register(registry)

    /** Valid messages not enqueued because the queue was full or closed. */
    val dropped: Counter = Counter.builder("telemetry.messages.dropped").register(registry)

    /** Stats entries removed from otherwise valid messages (bad key or value). */
    val discardedStats: Counter = Counter.builder("telemetry.stats.discarded").register(registry)

    /**
     * Rejected messages and messages with discarded stats that did not reach the `ingest_rejections`
     * table: the window in memory was full, or its write failed.
     */
    val rejectionsUnrecorded: Counter = Counter.builder("telemetry.rejections.unrecorded").register(registry)

    /** Rows sent in successfully committed batches; duplicates ignored by ON CONFLICT are included. */
    val rowsWritten: Counter = Counter.builder("telemetry.rows.written").register(registry)

    /** Batches dropped after a non-transient database error (or a transient one during shutdown). */
    val batchesFailed: Counter = Counter.builder("telemetry.batches.failed").register(registry)

    /** Time of one successful batch insert. */
    val batchLatency: Timer = Timer.builder("telemetry.batch.latency").register(registry)

    // Registered up front, so every reason is visible as a zero series before its first rejection.
    private val rejectedByReason: Map<RejectReason, Counter> = RejectReason.entries.associateWith { reason ->
        Counter.builder("telemetry.messages.rejected").tag("reason", reason.code).register(registry)
    }

    fun rejected(reason: RejectReason): Counter = rejectedByReason.getValue(reason)

    fun registerQueueDepth(depth: () -> Int) {
        Gauge.builder("telemetry.queue.depth") { depth() }.strongReference(true).register(registry)
    }
}
