package com.hosttelemetry.simulator.mode

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RateLimiterTest {

    @Test
    fun `spaces calls evenly to the rate`() {
        var clock = 0L
        val sleeps = mutableListOf<Long>()
        val limiter = RateLimiter(perSecond = 4, nanoTime = { clock }, sleepNanos = { sleeps += it; clock += it })

        repeat(5) { limiter.acquire() }

        assertThat(sleeps).containsExactly(250_000_000, 250_000_000, 250_000_000, 250_000_000)
        assertThat(clock).isEqualTo(1_000_000_000)
    }

    @Test
    fun `an idle period does not allow a burst afterwards`() {
        var clock = 0L
        val sleeps = mutableListOf<Long>()
        val limiter = RateLimiter(perSecond = 4, nanoTime = { clock }, sleepNanos = { sleeps += it; clock += it })

        limiter.acquire()
        clock += 10_000_000_000 // 10 s idle
        limiter.acquire()
        limiter.acquire()

        assertThat(sleeps).containsExactly(250_000_000)
    }
}
