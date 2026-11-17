package com.hosttelemetry.service.model.charts

import java.time.Duration
import java.time.Instant

/**
 * The buckets `[from, to)` of a chart. Buckets are aligned to the Unix epoch in UTC, which for
 * these sizes (all divide a day) matches the default `time_bucket` origin.
 */
data class SeriesWindow(val range: ChartRange, val from: Instant, val to: Instant) {

    /**
     * Rolls [aggregates] up into this window's buckets and returns one point per bucket, oldest first.
     * Finer input rows (5-minute rows for the 30-minute week buckets) are combined by summing sums and
     * counts, so avg is `sum / count` over all samples, not an average of averages. Buckets without
     * rows get `null` values: gaps stay visible, nothing is interpolated.
     */
    fun points(aggregates: List<BucketAggregate>): List<SeriesPoint> {
        val byBucket = aggregates
            .groupBy { floorToBucket(it.bucket, range.bucket) }
            .mapValues { (start, rows) -> rows.reduce(BucketAggregate::plus).copy(bucket = start) }
        return (0 until range.bucketCount).map { index ->
            val start = from + range.bucket.multipliedBy(index.toLong())
            when (val aggregate = byBucket[start]) {
                null -> SeriesPoint(start, null, null, null)
                else -> SeriesPoint(start, aggregate.min, aggregate.max, aggregate.sum / aggregate.count)
            }
        }
    }

    companion object {
        /** The window that ends with the bucket containing [now], so the last point may be partial. */
        fun endingAt(range: ChartRange, now: Instant): SeriesWindow {
            val to = floorToBucket(now, range.bucket) + range.bucket
            return SeriesWindow(range, from = to - range.length, to = to)
        }

        private fun floorToBucket(instant: Instant, bucket: Duration): Instant =
            Instant.ofEpochSecond(Math.floorDiv(instant.epochSecond, bucket.seconds) * bucket.seconds)
    }
}
