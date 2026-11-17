package com.hosttelemetry.simulator.mode

/** When each device sends in live mode. */
object LiveSchedule {

    /** Devices are spread evenly over one interval, so 10 000 devices do not all send in the same second. */
    fun phaseMillis(deviceIndex: Int, devices: Int, intervalMillis: Long): Long = deviceIndex * intervalMillis / devices

    /** The first slot `origin + k * interval` (k >= 0) at or after [nowMillis]. */
    fun nextSlot(nowMillis: Long, originMillis: Long, intervalMillis: Long): Long {
        if (nowMillis <= originMillis) return originMillis
        val steps = (nowMillis - originMillis + intervalMillis - 1) / intervalMillis
        return originMillis + steps * intervalMillis
    }

    /** Up to ±5 % of the interval, at most ±500 ms: devices are not perfectly regular. */
    fun maxJitterMillis(intervalMillis: Long): Long = minOf(intervalMillis / 20, 500L)
}
