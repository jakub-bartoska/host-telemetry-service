package com.hosttelemetry.service.model.ingest

/**
 * The fields of an incoming message as plain Kotlin values, before validation.
 * A field is `null` when it is missing or has the wrong JSON type; a stats value is `null`
 * when it is not a JSON number.
 */
data class RawMessage(
    val type: String?,
    val deviceId: String?,
    val timestamp: String?,
    val stats: Map<String, Double?>?,
)
