package com.hosttelemetry.simulator.transport

/** The stats message a device sends to the service. */
data class StatsMessage(
    val type: String,
    val deviceId: String,
    val timestamp: String,
    val stats: Map<String, Number>,
)
