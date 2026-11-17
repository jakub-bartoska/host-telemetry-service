package com.hosttelemetry.service.repository.ingest

import com.hosttelemetry.service.model.ingest.IngestRejection

/** Where the rejection log stores its windows. */
interface IngestRejectionRepository {

    /** Stores one row per rejection. Not idempotent: the caller does not send a window twice. */
    fun insert(rejections: List<IngestRejection>)
}
