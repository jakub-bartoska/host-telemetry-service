package com.hosttelemetry.service.repository.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * The raw narrow hypertable: one row per (time, device, metric).
 *
 * Flyway owns the schema, including everything TimescaleDB-specific. This object only describes the
 * columns, so the repositories can build typed, parameterized queries.
 */
object TelemetryTable : Table("telemetry") {
    val ts = timestampWithTimeZone("ts")
    val deviceId = text("device_id")
    val metric = text("metric")
    val value = double("value")
}
