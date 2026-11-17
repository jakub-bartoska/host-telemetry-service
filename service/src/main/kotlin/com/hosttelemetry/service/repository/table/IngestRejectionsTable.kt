package com.hosttelemetry.service.repository.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Rejected messages and discarded stats entries, summed per flush window, device and reason.
 * Like [TelemetryTable], it is created by the Flyway migrations and only described here.
 */
object IngestRejectionsTable : Table("ingest_rejections") {
    val windowStart = timestampWithTimeZone("window_start")
    val windowEnd = timestampWithTimeZone("window_end")
    val deviceId = text("device_id").nullable()
    val reason = text("reason")
    val messageCount = long("message_count")
    val discardedKeys = array<String>("discarded_keys")
    val sample = text("sample")
}
