package com.hosttelemetry.simulator.device

/** One reading of one device, in the units of the stats message. */
data class Sample(
    val cpuUtilPct: Double,
    val memUtilPct: Double,
    val rxBps: Long,
    val txBps: Long,
    val powerW: Double,
    val temperatureC: Double,
) {
    fun toStats(): Map<String, Number> = mapOf(
        "cpuUtilPct" to cpuUtilPct,
        "memUtilPct" to memUtilPct,
        "rxBps" to rxBps,
        "txBps" to txBps,
        "powerW" to powerW,
        "temperatureC" to temperatureC,
    )
}
