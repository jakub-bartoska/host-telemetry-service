package com.hosttelemetry.simulator.mode

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import java.time.Instant

class BackfillPlanTest {

    private val now = Instant.parse("2026-10-26T14:03:20Z")

    @ParameterizedTest
    @CsvSource(
        // days, interval s, expected samples: (days * 86400 - 300 s margin) / interval + 1
        "7, 10, 60451",
        "1, 60, 1436",
        "1, 10, 8611",
    )
    fun `number of samples per device follows days and interval`(days: Int, intervalSeconds: Long, expected: Int) {
        assertThat(BackfillPlan.create(now, days, Duration.ofSeconds(intervalSeconds)).samplesPerDevice).isEqualTo(expected)
    }

    @Test
    fun `timestamps are ascending, spaced by the interval and inside the accepted window`() {
        val plan = BackfillPlan.create(now, days = 7, interval = Duration.ofSeconds(10))
        val timestamps = (0 until plan.samplesPerDevice).map(plan::timestamp)

        val windowStart = now - Duration.ofDays(7) + BackfillPlan.DEFAULT_MARGIN
        assertThat(timestamps).allSatisfy { assertThat(it).isBetween(windowStart, now) }
        assertThat(timestamps.first()).isEqualTo(windowStart)
        assertThat(timestamps.zipWithNext { a, b -> Duration.between(a, b) }).containsOnly(Duration.ofSeconds(10))
        assertThat(Duration.between(timestamps.last(), now)).isLessThan(Duration.ofSeconds(10))
    }
}
