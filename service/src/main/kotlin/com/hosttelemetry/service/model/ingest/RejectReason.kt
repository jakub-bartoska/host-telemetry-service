package com.hosttelemetry.service.model.ingest

/** Why a whole message was rejected; [code] is sent back to the device in the error frame. */
enum class RejectReason(val code: String) {
    INVALID_JSON("invalid_json"),
    UNSUPPORTED_TYPE("unsupported_type"),
    INVALID_DEVICE_ID("invalid_device_id"),
    INVALID_TIMESTAMP("invalid_timestamp"),
    TIMESTAMP_IN_FUTURE("timestamp_in_future"),
    TIMESTAMP_TOO_OLD("timestamp_too_old"),
    INVALID_STATS("invalid_stats"),
    TOO_MANY_STATS("too_many_stats"),
    NO_VALID_STATS("no_valid_stats"),
}
