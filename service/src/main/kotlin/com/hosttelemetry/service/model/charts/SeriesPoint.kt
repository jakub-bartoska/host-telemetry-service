package com.hosttelemetry.service.model.charts

import java.time.Instant

/** One chart point; all three values are `null` when the bucket has no data. */
data class SeriesPoint(val ts: Instant, val min: Double?, val max: Double?, val avg: Double?)
