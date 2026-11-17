package com.hosttelemetry.service.repository.ingest

import com.hosttelemetry.service.model.ingest.TelemetryMessage
import com.hosttelemetry.service.repository.table.TelemetryTable
import com.hosttelemetry.service.repository.translateSqlErrors
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class ExposedTelemetryRepository : TelemetryRepository {

    /**
     * One transaction per batch. `ignore = true` is `ON CONFLICT DO NOTHING` on PostgreSQL, so an
     * exact resend hits the unique key and is ignored, and retrying a batch that was partly committed
     * is safe. The rows go out as one JDBC batch, which the driver turns into multi-row INSERTs
     * (`reWriteBatchedInserts=true`).
     */
    @Transactional
    override fun insert(messages: List<TelemetryMessage>) {
        val rows = messages.flatMap { message ->
            val ts = message.timestamp.atOffset(ZoneOffset.UTC)
            message.stats.map { (metric, value) -> Row(ts, message.deviceId, metric, value) }
        }
        translateSqlErrors("insert telemetry batch") {
            TelemetryTable.batchInsert(rows, ignore = true, shouldReturnGeneratedValues = false) { row ->
                this[TelemetryTable.ts] = row.ts
                this[TelemetryTable.deviceId] = row.deviceId
                this[TelemetryTable.metric] = row.metric
                this[TelemetryTable.value] = row.value
            }
        }
    }

    private data class Row(val ts: OffsetDateTime, val deviceId: String, val metric: String, val value: Double)
}
