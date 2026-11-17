package com.hosttelemetry.service.repository.ingest

import com.hosttelemetry.service.model.ingest.IngestRejection
import com.hosttelemetry.service.repository.table.IngestRejectionsTable
import com.hosttelemetry.service.repository.translateSqlErrors
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset

@Repository
class ExposedIngestRejectionRepository : IngestRejectionRepository {

    /** One transaction and one JDBC batch per window, so a window is stored whole or not at all. */
    @Transactional
    override fun insert(rejections: List<IngestRejection>) {
        translateSqlErrors("insert ingest rejections") {
            IngestRejectionsTable.batchInsert(rejections, shouldReturnGeneratedValues = false) { rejection ->
                this[IngestRejectionsTable.windowStart] = rejection.windowStart.atOffset(ZoneOffset.UTC)
                this[IngestRejectionsTable.windowEnd] = rejection.windowEnd.atOffset(ZoneOffset.UTC)
                this[IngestRejectionsTable.deviceId] = rejection.deviceId
                this[IngestRejectionsTable.reason] = rejection.reason
                this[IngestRejectionsTable.messageCount] = rejection.messageCount
                this[IngestRejectionsTable.discardedKeys] = rejection.discardedKeys
                this[IngestRejectionsTable.sample] = rejection.sample
            }
        }
    }
}
