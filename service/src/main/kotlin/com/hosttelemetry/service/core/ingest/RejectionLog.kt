package com.hosttelemetry.service.core.ingest

import com.hosttelemetry.service.config.IngestProperties
import com.hosttelemetry.service.model.ingest.IngestRejection
import com.hosttelemetry.service.model.ingest.RejectReason
import com.hosttelemetry.service.repository.ingest.IngestRejectionRepository
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Records which devices send bad data: rejected messages and stats entries discarded from accepted
 * ones. The metrics count these per reason only, because a metric label per device would create a
 * time series per device.
 *
 * Records are summed in memory per device and reason, and every `rejection-flush-interval` the
 * window is written to the `ingest_rejections` table as one row per device and reason. A device that
 * fails on every message therefore costs one row per interval, and the WebSocket threads never wait
 * for the database. Only a message that is rejected or trimmed takes the lock; a clean message does
 * not come here.
 *
 * This is diagnostic data and may be lost: a window is dropped when its write fails (it is not
 * retried, so a database outage cannot grow the memory), a window holds at most
 * `rejection-max-entries` device and reason pairs, and a crash loses the window in memory. The first
 * two are counted in `telemetry.rejections.unrecorded`.
 *
 * Runs in the phase of [TelemetryWriter], so the last window is written after the web server has
 * stopped delivering messages. Can be started again after a stop, like the writer.
 */
@Component
class RejectionLog(
    private val repository: IngestRejectionRepository,
    private val properties: IngestProperties,
    private val metrics: IngestMetrics,
    private val clock: Clock,
) : SmartLifecycle {

    private val lock = Any()

    // Both guarded by lock.
    private var entries = HashMap<Key, Entry>()
    private var windowStart: Instant = clock.instant()

    @Volatile
    private var running = false

    // Only touched by start() and stop(), which Spring never calls concurrently.
    private var flusher: ScheduledExecutorService? = null

    /** A whole message was rejected; [deviceId] is `null` when the message did not carry a valid one. */
    fun rejected(reason: RejectReason, deviceId: String?, payload: String) {
        record(Key(deviceId, reason.code), emptyList(), payload)
    }

    /** A message was accepted, but the stats entries under [keys] were discarded. */
    fun discarded(deviceId: String, keys: List<String>, payload: String) {
        record(Key(deviceId, IngestRejection.DISCARDED_STATS), keys.map { it.clipped(MAX_KEY_LENGTH) }, payload)
    }

    /** Writes the current window and starts the next one. Does nothing when the window is empty. */
    fun flush() {
        val windowEnd = clock.instant()
        val rejections = synchronized(lock) {
            val rejections = entries.map { (key, entry) ->
                IngestRejection(windowStart, windowEnd, key.deviceId, key.reason, entry.count, entry.keys.toList(), entry.sample)
            }
            entries = HashMap()
            windowStart = windowEnd
            rejections
        }
        if (rejections.isEmpty()) return
        try {
            repository.insert(rejections)
        } catch (e: Exception) {
            // Catch everything: an exception would cancel the periodic task for good.
            log.warn("Could not write {} ingest rejection rows; this window is lost", rejections.size, e)
            metrics.rejectionsUnrecorded.increment(rejections.sumOf { it.messageCount }.toDouble())
        }
    }

    override fun start() {
        val interval = properties.rejectionFlushInterval.toMillis()
        flusher = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "rejection-log").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::flush, interval, interval, TimeUnit.MILLISECONDS) }
        running = true
    }

    override fun stop() {
        running = false
        val stopping = flusher ?: return
        flusher = null
        // The last window goes through the flusher thread, so a database that does not answer delays
        // shutdown by shutdown-timeout at most.
        stopping.execute(::flush)
        stopping.shutdown()
        if (!stopping.awaitTermination(properties.shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            log.warn("The last ingest rejection window was not written within {}", properties.shutdownTimeout)
        }
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = TelemetryWriter.PHASE

    private fun record(key: Key, discardedKeys: List<String>, payload: String) {
        val sample = payload.clipped(MAX_SAMPLE_LENGTH)
        val recorded = synchronized(lock) {
            val entry = entries[key]
                ?: Entry().takeIf { entries.size < properties.rejectionMaxEntries }?.also { entries[key] = it }
            entry?.add(discardedKeys, sample)
            entry != null
        }
        if (!recorded) metrics.rejectionsUnrecorded.increment()
    }

    private data class Key(val deviceId: String?, val reason: String)

    private class Entry {
        var count = 0L
        val keys = LinkedHashSet<String>()
        var sample = ""

        fun add(discardedKeys: List<String>, lastSample: String) {
            count++
            discardedKeys.forEach { if (keys.size < MAX_KEYS) keys.add(it) }
            sample = lastSample
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(RejectionLog::class.java)

        const val MAX_SAMPLE_LENGTH = 1000

        // A valid metric key has at most 64 characters; a discarded key can be any text.
        const val MAX_KEY_LENGTH = 64

        // Distinct discarded keys kept per device and window.
        const val MAX_KEYS = 32

        /**
         * At most [max] characters, without splitting a surrogate pair and without the NUL character,
         * which PostgreSQL cannot store in a text value.
         */
        fun String.clipped(max: Int): String {
            val end = if (length > max && this[max - 1].isHighSurrogate()) max - 1 else minOf(length, max)
            return substring(0, end).replace('\u0000', '�')
        }
    }
}
