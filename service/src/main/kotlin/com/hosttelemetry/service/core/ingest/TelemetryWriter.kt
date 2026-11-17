package com.hosttelemetry.service.core.ingest

import com.hosttelemetry.service.config.IngestProperties
import com.hosttelemetry.service.model.ingest.TelemetryMessage
import com.hosttelemetry.service.repository.ingest.TelemetryRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.SmartLifecycle
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import java.time.Duration

/**
 * The single writer thread: drains the queue in batches of up to `batch-size` messages
 * or every `flush-interval`, and inserts each batch with one JDBC batch.
 *
 * Errors: a transient database error (including "cannot connect") retries the same batch with
 * exponential backoff while the queue fills up and starts shedding; any other error is logged,
 * counted and the batch is dropped, so one poison batch cannot stop ingestion.
 *
 * Shutdown: this lifecycle runs in a phase below the web server, so it stops only after the server
 * has stopped delivering messages. It then closes the queue, drains it and flushes the rest.
 * A stopped writer can be started again, as Spring does with the lifecycle beans of a paused
 * application context.
 */
@Component
class TelemetryWriter(
    private val queue: TelemetryQueue,
    private val repository: TelemetryRepository,
    private val properties: IngestProperties,
    private val metrics: IngestMetrics,
) : SmartLifecycle {

    @Volatile
    private var running = false

    // Only touched by start() and stop(), which Spring never calls concurrently.
    private var thread: Thread? = null

    override fun start() {
        queue.reopen()
        running = true
        thread = Thread(::drainLoop, "telemetry-writer").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        queue.close()
        running = false
        val writer = thread ?: return
        writer.join(properties.shutdownTimeout.toMillis())
        if (writer.isAlive) {
            log.warn("Writer did not finish within {}; {} queued messages lost", properties.shutdownTimeout, queue.size)
        }
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = PHASE

    private fun drainLoop() {
        while (running || !queue.isEmpty()) {
            val batch = queue.drainBatch(properties.batchSize, properties.flushInterval)
            if (batch.isNotEmpty()) write(batch)
        }
    }

    private fun write(batch: List<TelemetryMessage>) {
        var backoff = properties.retryInitialBackoff
        while (true) {
            try {
                metrics.batchLatency.record(Runnable { repository.insert(batch) })
                metrics.rowsWritten.increment(batch.sumOf { it.stats.size }.toDouble())
                return
            } catch (e: Exception) {
                // Catch everything: an unexpected exception must not kill the only writer thread.
                if (!isTransientDatabaseError(e) || !running) {
                    log.error("Dropping batch of {} messages after database error", batch.size, e)
                    metrics.batchesFailed.increment()
                    return
                }
                log.warn("Transient database error, retrying batch of {} messages in {}: {}", batch.size, backoff, e.message)
                Thread.sleep(backoff)
                backoff = minOf(backoff.multipliedBy(2), properties.retryMaxBackoff)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TelemetryWriter::class.java)

        /** Lower phase = started earlier, stopped later: after the web server has stopped delivering messages. */
        const val PHASE = WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1024
    }
}

/**
 * Whether sending the same batch again can succeed: the database is unreachable or busy, or the
 * transaction could not be opened or completed. After a failed commit the outcome is unknown, and
 * inserting again is harmless because the insert is idempotent.
 */
internal fun isTransientDatabaseError(e: Exception): Boolean =
    e is TransientDataAccessException ||
        e is RecoverableDataAccessException ||
        e is DataAccessResourceFailureException ||
        e is CannotCreateTransactionException ||
        e is TransactionSystemException
