package com.hosttelemetry.service.repository.ingest

import com.hosttelemetry.service.model.ingest.TelemetryMessage

/** Where the ingest writer stores its batches. */
interface TelemetryRepository {

    /**
     * Stores one row per stats entry. Must be idempotent, because the writer sends the same batch
     * again after a transient error.
     */
    fun insert(messages: List<TelemetryMessage>)
}
