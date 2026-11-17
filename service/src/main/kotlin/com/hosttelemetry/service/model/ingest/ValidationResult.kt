package com.hosttelemetry.service.model.ingest

sealed interface ValidationResult {
    /** [discardedKeys] are the stats keys dropped for a bad key or value. */
    data class Accepted(val message: TelemetryMessage, val discardedKeys: List<String>) : ValidationResult

    /** [deviceId] is the sender when the message carried a valid device id, otherwise `null`. */
    data class Rejected(val reason: RejectReason, val deviceId: String? = null) : ValidationResult
}
