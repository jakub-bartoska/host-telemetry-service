package com.hosttelemetry.service.model

/** Allowed device ids and metric keys, shared by ingest validation and the charts API. */
object TelemetryIds {
    // Compile-time constants, so the same patterns can be used in validation annotations.
    const val DEVICE_ID_PATTERN = "^[A-Za-z0-9._-]{1,64}$"
    const val METRIC_KEY_PATTERN = "^[A-Za-z][A-Za-z0-9_]{0,63}$"

    val DEVICE_ID = Regex(DEVICE_ID_PATTERN)
    val METRIC_KEY = Regex(METRIC_KEY_PATTERN)
}
