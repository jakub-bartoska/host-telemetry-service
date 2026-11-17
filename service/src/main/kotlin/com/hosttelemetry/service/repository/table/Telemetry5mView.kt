package com.hosttelemetry.service.repository.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * The 5-minute continuous aggregate (a view, read only). Like [TelemetryTable], it is created by the
 * Flyway migrations and only described here.
 */
object Telemetry5mView : Table("telemetry_5m") {
    val bucket = timestampWithTimeZone("bucket")
    val deviceId = text("device_id")
    val metric = text("metric")
    val minValue = double("min_value")
    val maxValue = double("max_value")
    val sumValue = double("sum_value")
    val sampleCount = long("sample_count")
}
