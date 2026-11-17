package com.hosttelemetry.simulator.report

import com.hosttelemetry.simulator.cli.Mode
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Writes one status line every [period] and a summary at the end to [out]. */
class StatusReporter(
    private val mode: Mode,
    private val counters: Counters,
    /** Messages the run will send in total, when known (backfill). */
    private val total: Long? = null,
    private val period: Duration = Duration.ofSeconds(5),
    private val out: (String) -> Unit = ::println,
) {
    private val startNanos = System.nanoTime()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "status").apply { isDaemon = true }
    }

    // Only read and written by the single status thread.
    private var lastSent = 0L
    private val summaryPrinted = AtomicBoolean(false)

    fun start() {
        executor.scheduleAtFixedRate(::printLine, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun stop() {
        executor.shutdownNow()
    }

    /** Prints once: the main thread and the shutdown hook may both call it on SIGTERM. */
    fun printSummary() {
        if (!summaryPrinted.compareAndSet(false, true)) return
        val seconds = Duration.ofNanos(System.nanoTime() - startNanos).toMillis() / 1000.0
        val sent = counters.sent.get()
        val average = if (seconds > 0) sent / seconds else 0.0
        val reasons = counters.reasons().takeIf { it.isNotEmpty() }?.joinToString(prefix = " reasons=") ?: ""
        out(
            "[${mode.label}] summary: elapsed=${"%.1f".format(Locale.ROOT, seconds)}s sent=$sent${progress()} " +
                "avg=${rate(average)}/s errorFrames=${counters.errorFrames.get()}$reasons " +
                "connectionErrors=${counters.connectionErrors.get()}",
        )
    }

    private fun printLine() {
        val sent = counters.sent.get()
        val perSecond = (sent - lastSent) * 1000.0 / period.toMillis()
        lastSent = sent
        out(
            "[${mode.label}] connections=${counters.openConnections.get()} sent=$sent${progress()} " +
                "(${rate(perSecond)}/s) errorFrames=${counters.errorFrames.get()} " +
                "connectionErrors=${counters.connectionErrors.get()}",
        )
    }

    private fun progress() = total?.let { "/$it" } ?: ""

    private fun rate(perSecond: Double) = "%.0f".format(Locale.ROOT, perSecond)
}
