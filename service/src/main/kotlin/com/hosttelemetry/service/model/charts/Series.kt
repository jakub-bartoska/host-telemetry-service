package com.hosttelemetry.service.model.charts

import java.time.Instant

/** One metric of one device over a chart range: the body of the series response. Times serialize as ISO-8601 in UTC. */
data class Series(
    val deviceId: String,
    val metric: String,
    val range: String,
    val bucketSeconds: Long,
    val from: Instant,
    val to: Instant,
    val points: List<SeriesPoint>,
)
