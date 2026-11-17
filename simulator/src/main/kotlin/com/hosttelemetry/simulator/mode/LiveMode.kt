package com.hosttelemetry.simulator.mode

import com.hosttelemetry.simulator.cli.Options
import com.hosttelemetry.simulator.device.DeviceGenerator
import com.hosttelemetry.simulator.report.Counters
import com.hosttelemetry.simulator.transport.Connection
import com.hosttelemetry.simulator.transport.Messages
import java.net.http.HttpClient
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One connection per device with current timestamps. Connections open at [RAMP_PER_SECOND]; a dropped
 * connection reconnects with exponential backoff; samples due while disconnected are skipped, like a
 * real agent that cannot reach the backend.
 */
class LiveMode(
    private val options: Options,
    private val client: HttpClient,
    private val counters: Counters,
) {
    private val stopping = AtomicBoolean(false)
    private val stopped = CountDownLatch(1)
    private val intervalMillis = options.interval.toMillis()
    private val originMillis = System.currentTimeMillis()

    // Sends are asynchronous, so a few threads drive thousands of devices.
    private val scheduler = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
    private val devices = List(options.devices, ::LiveDevice)

    /** Blocks until [Options.duration] has passed or [stop] is called (Ctrl+C). */
    fun run() {
        val ramp = RateLimiter(RAMP_PER_SECOND)
        for (device in devices) {
            if (stopping.get()) break
            ramp.acquire()
            device.connect()
        }
        when (val duration = options.duration) {
            null -> stopped.await()
            else -> stopped.await(duration.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        scheduler.shutdownNow()
        val closing = devices.mapNotNull { it.connection?.closeAsync() }
        try {
            CompletableFuture.allOf(*closing.toTypedArray()).get(CLOSE_ALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            // Connections that did not close in time are abandoned; the process is about to exit.
        }
        stopped.countDown()
    }

    private fun schedule(delayMillis: Long, task: () -> Unit) {
        if (stopping.get()) return
        try {
            scheduler.schedule(task, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // Stopping: the scheduler is shut down.
        }
    }

    private inner class LiveDevice(index: Int) {
        private val deviceId = Messages.deviceId(index)
        private val generator = DeviceGenerator(options.seed, index)
        private val firstSlotMillis = originMillis + LiveSchedule.phaseMillis(index, options.devices, intervalMillis)
        private val backoffMillis = AtomicLong(INITIAL_BACKOFF_MILLIS)

        @Volatile
        var connection: Connection? = null
            private set

        fun connect() {
            if (stopping.get()) return
            Connection.open(client, options.url, counters, onLost = ::reconnectLater).whenComplete { opened, error ->
                when (error) {
                    null -> {
                        connection = opened
                        backoffMillis.set(INITIAL_BACKOFF_MILLIS)
                        sendAt(opened, LiveSchedule.nextSlot(System.currentTimeMillis(), firstSlotMillis, intervalMillis))
                    }
                    else -> reconnectLater()
                }
            }
        }

        private fun reconnectLater() {
            val backoff = backoffMillis.getAndUpdate { minOf(it * 2, MAX_BACKOFF_MILLIS) }
            // Random half of the backoff added, so a restarted service is not hit by all devices at once.
            schedule(backoff + ThreadLocalRandom.current().nextLong(backoff / 2 + 1), ::connect)
        }

        private fun sendAt(connection: Connection, slotMillis: Long) {
            val jitter = LiveSchedule.maxJitterMillis(intervalMillis).let { ThreadLocalRandom.current().nextLong(-it, it + 1) }
            schedule(slotMillis + jitter - System.currentTimeMillis()) {
                // A chain whose connection was lost ends here; the reconnect starts a new one.
                if (connection.isOpen) {
                    // The timestamp is the slot, not the send time: jitter moves only the moment of sending,
                    // so two samples of one device never share a whole-second timestamp (which the
                    // service's ON CONFLICT DO NOTHING would silently drop).
                    connection.send(Messages.stats(deviceId, Instant.ofEpochMilli(slotMillis), generator.next()))
                    sendAt(connection, slotMillis + intervalMillis)
                }
            }
        }
    }

    private companion object {
        const val RAMP_PER_SECOND = 200
        const val INITIAL_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
        const val CLOSE_ALL_TIMEOUT_SECONDS = 5L
    }
}
