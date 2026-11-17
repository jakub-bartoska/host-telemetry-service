package com.hosttelemetry.simulator

import com.hosttelemetry.simulator.cli.Mode
import com.hosttelemetry.simulator.cli.Options
import com.hosttelemetry.simulator.cli.OptionsParser
import com.hosttelemetry.simulator.cli.UsageException
import com.hosttelemetry.simulator.mode.BackfillMode
import com.hosttelemetry.simulator.mode.LiveMode
import com.hosttelemetry.simulator.report.Counters
import com.hosttelemetry.simulator.report.StatusReporter
import com.hosttelemetry.simulator.transport.Connection
import java.net.http.HttpClient
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Device simulator: a plain Kotlin CLI (no Spring), so thousands of connections cost only what the
 * JDK WebSocket client costs. Exit codes: 0 done, 1 service unreachable or backfill incomplete, 2 bad flags.
 */
fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        println(OptionsParser.USAGE)
        return
    }
    val options = try {
        OptionsParser.parse(args.toList(), System.getenv())
    } catch (e: UsageException) {
        System.err.println("Error: ${e.message}")
        System.err.println(OptionsParser.USAGE)
        exitProcess(EXIT_USAGE)
    }
    exitProcess(run(options))
}

private fun run(options: Options): Int {
    val client = HttpClient.newHttpClient()
    if (!Connection.waitForService(client, options.url, STARTUP_TIMEOUT)) {
        println("Service not reachable at ${options.url} within ${STARTUP_TIMEOUT.toSeconds()}s, giving up")
        return EXIT_FAILURE
    }
    val counters = Counters()
    return when (options.mode) {
        Mode.LIVE -> runLive(options, client, counters)
        Mode.BACKFILL -> runBackfill(options, client, counters)
    }
}

private fun runLive(options: Options, client: HttpClient, counters: Counters): Int {
    val live = LiveMode(options, client, counters)
    val reporter = StatusReporter(Mode.LIVE, counters)
    println("[live] ${options.devices} devices every ${options.interval.toSeconds()}s to ${options.url}, " +
        (options.duration?.let { "for ${it.toSeconds()}s" } ?: "until Ctrl+C"))
    // Ctrl+C (or `docker compose stop`) still closes the connections and prints the summary. The hook
    // stays registered: removing it while a SIGTERM shutdown is running throws, and both stop() and
    // printSummary() are idempotent, so whichever of the hook and the main thread comes first does the work.
    Runtime.getRuntime().addShutdownHook(Thread {
        live.stop()
        reporter.printSummary()
    })
    reporter.start()
    live.run()
    reporter.stop()
    live.stop()
    reporter.printSummary()
    return EXIT_OK
}

private fun runBackfill(options: Options, client: HttpClient, counters: Counters): Int {
    val backfill = BackfillMode(options, client, counters)
    val reporter = StatusReporter(Mode.BACKFILL, counters, total = backfill.totalMessages)
    reporter.start()
    val complete = backfill.run()
    reporter.stop()
    reporter.printSummary()
    return if (complete) EXIT_OK else EXIT_FAILURE
}

private val STARTUP_TIMEOUT: Duration = Duration.ofSeconds(60)
private const val EXIT_OK = 0
private const val EXIT_FAILURE = 1
private const val EXIT_USAGE = 2
