package com.hosttelemetry.simulator.mode

import java.time.Duration
import java.time.Instant

/**
 * Timestamps of one device's history: from `now - days + margin` up to `now`, one per [interval],
 * oldest first. The [margin] keeps the first samples inside the service's 7-day acceptance window
 * while the backfill is still running (the service rejects older data as `timestamp_too_old`).
 */
data class BackfillPlan(val start: Instant, val interval: Duration, val samplesPerDevice: Int) {

    fun timestamp(sample: Int): Instant = start + interval.multipliedBy(sample.toLong())

    companion object {
        val DEFAULT_MARGIN: Duration = Duration.ofMinutes(5)

        fun create(now: Instant, days: Int, interval: Duration, margin: Duration = DEFAULT_MARGIN): BackfillPlan {
            val start = now - Duration.ofDays(days.toLong()) + margin
            val samples = Duration.between(start, now).toMillis() / interval.toMillis() + 1
            return BackfillPlan(start, interval, Math.toIntExact(samples))
        }
    }
}
