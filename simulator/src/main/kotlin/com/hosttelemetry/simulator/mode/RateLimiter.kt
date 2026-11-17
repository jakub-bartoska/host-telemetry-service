package com.hosttelemetry.simulator.mode

/**
 * Spaces calls evenly to at most [perSecond] per second over all callers (thread-safe).
 * The clock and the sleep are parameters, so tests run without waiting.
 */
class RateLimiter(
    private val perSecond: Int,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepNanos: (Long) -> Unit = { Thread.sleep(it / 1_000_000, (it % 1_000_000).toInt()) },
) {
    private val spacingNanos = 1_000_000_000L / perSecond
    private var nextSlot = nanoTime()

    /** Blocks until the caller's slot. A caller that was late does not earn a burst later. */
    fun acquire() {
        val waitNanos = synchronized(this) {
            val now = nanoTime()
            val slot = maxOf(nextSlot, now)
            nextSlot = slot + spacingNanos
            slot - now
        }
        if (waitNanos > 0) sleepNanos(waitNanos)
    }
}
