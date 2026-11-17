package com.hosttelemetry.service.core.ingest

import com.hosttelemetry.service.model.ingest.TelemetryMessage
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Bounded hand-off between WebSocket handler threads and the single writer.
 *
 * When full, [offer] drops the message and counts it instead of blocking: a slow database must not
 * stall Tomcat threads and every device behind them. Load shedding is visible in
 * `telemetry.messages.dropped`.
 */
class TelemetryQueue(capacity: Int, private val metrics: IngestMetrics) {

    private val queue = ArrayBlockingQueue<TelemetryMessage>(capacity)

    @Volatile
    private var closed = false

    init {
        metrics.registerQueueDepth { queue.size }
    }

    val size: Int get() = queue.size

    fun isEmpty(): Boolean = queue.isEmpty()

    /** Never blocks. Returns `false` (and counts a drop) when the queue is full or closed. */
    fun offer(message: TelemetryMessage): Boolean {
        val accepted = !closed && queue.offer(message)
        if (!accepted) metrics.dropped.increment()
        return accepted
    }

    /** Stops accepting new messages; messages already queued can still be drained. */
    fun close() {
        closed = true
    }

    /** Accepts messages again after [close]. */
    fun reopen() {
        closed = false
    }

    /**
     * Waits until [maxSize] messages are available or [maxWait] has passed, whichever comes first,
     * and returns what it collected (possibly nothing).
     */
    fun drainBatch(maxSize: Int, maxWait: Duration): List<TelemetryMessage> {
        val batch = ArrayList<TelemetryMessage>(maxSize)
        val deadline = System.nanoTime() + maxWait.toNanos()
        while (batch.size < maxSize) {
            queue.drainTo(batch, maxSize - batch.size)
            val remaining = deadline - System.nanoTime()
            if (batch.size == maxSize || remaining <= 0) break
            val next = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            batch.add(next)
        }
        return batch
    }
}
