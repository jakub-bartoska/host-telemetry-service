package com.hosttelemetry.service.model.charts

import java.time.Instant

/** min/max/sum/count of the samples in one bucket starting at [bucket], as stored in the database. */
data class BucketAggregate(val bucket: Instant, val min: Double, val max: Double, val sum: Double, val count: Long) {
    operator fun plus(other: BucketAggregate) =
        copy(min = minOf(min, other.min), max = maxOf(max, other.max), sum = sum + other.sum, count = count + other.count)
}
