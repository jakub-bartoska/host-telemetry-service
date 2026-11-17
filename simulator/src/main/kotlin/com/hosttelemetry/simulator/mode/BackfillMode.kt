package com.hosttelemetry.simulator.mode

import com.hosttelemetry.simulator.cli.Options
import com.hosttelemetry.simulator.device.DeviceGenerator
import com.hosttelemetry.simulator.report.Counters
import com.hosttelemetry.simulator.transport.Connection
import com.hosttelemetry.simulator.transport.Messages
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * History from `now - days` to `now` over a few parallel connections, oldest samples first, at most
 * [Options.rate] messages per second in total.
 *
 * The limit matters: the service has no acknowledgement and drops messages when its queue is full,
 * so sending as fast as possible would lose data silently. A device always uses the same
 * connection, so its samples arrive in order.
 */
class BackfillMode(
    private val options: Options,
    private val client: HttpClient,
    private val counters: Counters,
    now: Instant = Instant.now(),
) {
    val plan = BackfillPlan.create(now.truncatedTo(ChronoUnit.SECONDS), options.days, options.interval)
    val totalMessages = plan.samplesPerDevice.toLong() * options.devices

    private val limiter = RateLimiter(options.rate)
    private val connections = minOf(options.devices, CONNECTIONS)

    /** Returns `false` when a connection could not be re-established and the run is incomplete. */
    fun run(): Boolean {
        printPlan()
        val pool = Executors.newFixedThreadPool(connections)
        try {
            val workers = (0 until connections).map { worker -> pool.submit<Boolean> { Worker(worker).run() } }
            return workers.all { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun printPlan() {
        println(
            "[backfill] ${options.devices} devices x ${plan.samplesPerDevice} samples = $totalMessages messages " +
                "from ${plan.start} every ${options.interval.toSeconds()}s over $connections connections, " +
                "max ${options.rate}/s (at least ${totalMessages / options.rate}s)",
        )
        // Sending slower than the data's own time passes means the oldest samples age past the
        // service's 7-day window before they are sent.
        if (options.rate.toLong() * options.interval.toSeconds() < options.devices) {
            println("[backfill] warning: --rate is below devices / interval; old samples may be rejected as timestamp_too_old")
        }
    }

    /** Devices `worker, worker + connections, …` over one connection. */
    private inner class Worker(worker: Int) {
        private val deviceIndices = (worker until options.devices step connections).toList()
        private val deviceIds = deviceIndices.map(Messages::deviceId)
        private val generators = deviceIndices.map { DeviceGenerator(options.seed, it) }
        private var connection: Connection? = null

        fun run(): Boolean {
            for (sample in 0 until plan.samplesPerDevice) {
                val timestamp = plan.timestamp(sample)
                deviceIds.forEachIndexed { i, deviceId ->
                    limiter.acquire()
                    if (!send(Messages.stats(deviceId, timestamp, generators[i].next()))) return false
                }
            }
            connection?.closeAsync()?.join()
            return true
        }

        /** Sends over the current connection, reconnecting with backoff; a resend after a drop is a harmless duplicate. */
        private fun send(text: String): Boolean {
            val deadline = System.nanoTime() + GIVE_UP_AFTER.toNanos()
            var backoffMillis = INITIAL_BACKOFF_MILLIS
            while (System.nanoTime() < deadline) {
                val current = connection?.takeIf(Connection::isOpen) ?: openOrNull()
                connection = current
                if (current != null && sent(current, text)) return true
                Thread.sleep(backoffMillis)
                backoffMillis = minOf(backoffMillis * 2, MAX_BACKOFF_MILLIS)
            }
            println("[backfill] giving up: no connection to ${options.url} for ${GIVE_UP_AFTER.toSeconds()}s")
            return false
        }

        private fun openOrNull(): Connection? =
            try {
                Connection.open(client, options.url, counters).get()
            } catch (e: ExecutionException) {
                null
            }

        private fun sent(connection: Connection, text: String): Boolean =
            try {
                connection.send(text).get()
                true
            } catch (e: ExecutionException) {
                false
            }
    }

    private companion object {
        const val CONNECTIONS = 8
        const val INITIAL_BACKOFF_MILLIS = 500L
        const val MAX_BACKOFF_MILLIS = 10_000L
        val GIVE_UP_AFTER: Duration = Duration.ofSeconds(60)
    }
}
