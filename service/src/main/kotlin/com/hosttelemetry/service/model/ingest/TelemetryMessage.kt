package com.hosttelemetry.service.model.ingest

import java.time.Instant

/** A validated stats message from one device; every entry of [stats] becomes one row in `telemetry`. */
data class TelemetryMessage(
    val deviceId: String,
    val timestamp: Instant,
    val stats: Map<String, Double>,
)
