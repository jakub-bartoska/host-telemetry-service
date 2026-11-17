package com.hosttelemetry.simulator.report

import com.hosttelemetry.simulator.cli.Mode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StatusReporterTest {

    @Test
    fun `summary is printed once even when both the main thread and the shutdown hook ask for it`() {
        val lines = mutableListOf<String>()
        val reporter = StatusReporter(Mode.LIVE, Counters(), out = lines::add)

        reporter.printSummary()
        reporter.printSummary()

        assertThat(lines).singleElement().asString().startsWith("[live] summary:")
    }

    @Test
    fun `summary reports progress against the known total and the reasons of error frames`() {
        val lines = mutableListOf<String>()
        val counters = Counters().apply {
            sent.set(40)
            errorFrame("timestamp_too_old")
        }
        val reporter = StatusReporter(Mode.BACKFILL, counters, total = 100, out = lines::add)

        reporter.printSummary()

        assertThat(lines.single())
            .startsWith("[backfill] summary:")
            .contains("sent=40/100", "errorFrames=1 reasons=timestamp_too_old", "connectionErrors=0")
    }
}
