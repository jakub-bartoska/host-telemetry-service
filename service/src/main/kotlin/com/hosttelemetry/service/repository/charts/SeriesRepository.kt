package com.hosttelemetry.service.repository.charts

import com.hosttelemetry.service.model.charts.BucketAggregate
import com.hosttelemetry.service.model.charts.ChartRange
import com.hosttelemetry.service.model.charts.SeriesWindow
import com.hosttelemetry.service.repository.table.Telemetry5mView
import com.hosttelemetry.service.repository.table.TelemetryTable
import com.hosttelemetry.service.repository.translateSqlErrors
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Reads the aggregates of one series for one chart window. Every query filters on
 * `(device_id, metric)` and the time range, so it reads at most ~2 000 rows whatever the fleet size.
 */
@Repository
class SeriesRepository {

    /**
     * Hour: raw rows bucketed in SQL. Day and week: 5-minute rows of `telemetry_5m`; for week they are
     * rolled up into 30-minute buckets by [SeriesWindow.points]. The aggregate is real-time
     * (`materialized_only = false`), so the not-yet-materialized tail is included.
     */
    @Transactional(readOnly = true)
    fun aggregates(window: SeriesWindow, deviceId: String, metric: String): List<BucketAggregate> =
        translateSqlErrors("read chart series") {
            when (window.range) {
                ChartRange.HOUR -> rawBuckets(window, deviceId, metric)
                ChartRange.DAY, ChartRange.WEEK -> fiveMinuteBuckets(window, deviceId, metric)
            }
        }

    private fun rawBuckets(window: SeriesWindow, deviceId: String, metric: String): List<BucketAggregate> {
        val table = TelemetryTable
        val bucket = TimeBucket(window.range.bucket, table.ts)
        val min = table.value.min()
        val max = table.value.max()
        val sum = table.value.sum()
        val count = table.value.count()
        return table.select(bucket, min, max, sum, count)
            .where { (table.deviceId eq deviceId) and (table.metric eq metric) and table.ts.isIn(window) }
            .groupBy(bucket)
            .map { row ->
                // An aggregate over a non-empty group is never null.
                BucketAggregate(
                    bucket = row[bucket].toInstant(),
                    min = checkNotNull(row[min]),
                    max = checkNotNull(row[max]),
                    sum = checkNotNull(row[sum]),
                    count = row[count],
                )
            }
    }

    private fun fiveMinuteBuckets(window: SeriesWindow, deviceId: String, metric: String): List<BucketAggregate> {
        val view = Telemetry5mView
        return view.select(view.bucket, view.minValue, view.maxValue, view.sumValue, view.sampleCount)
            .where { (view.deviceId eq deviceId) and (view.metric eq metric) and view.bucket.isIn(window) }
            .map { row ->
                BucketAggregate(
                    bucket = row[view.bucket].toInstant(),
                    min = row[view.minValue],
                    max = row[view.maxValue],
                    sum = row[view.sumValue],
                    count = row[view.sampleCount],
                )
            }
    }

    /** `[from, to)` of the window. */
    private fun Column<OffsetDateTime>.isIn(window: SeriesWindow): Op<Boolean> =
        (this greaterEq window.from.atOffset(ZoneOffset.UTC)) and (this less window.to.atOffset(ZoneOffset.UTC))
}
