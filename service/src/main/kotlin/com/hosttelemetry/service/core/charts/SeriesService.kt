package com.hosttelemetry.service.core.charts

import com.hosttelemetry.service.model.charts.ChartRange
import com.hosttelemetry.service.model.charts.Series
import com.hosttelemetry.service.model.charts.SeriesWindow
import com.hosttelemetry.service.repository.charts.SeriesRepository
import org.springframework.stereotype.Service
import java.time.Clock

/** Builds the chart of one series: the window that ends now, read from storage and rolled up into points. */
@Service
class SeriesService(private val repository: SeriesRepository, private val clock: Clock) {

    fun series(range: ChartRange, deviceId: String, metric: String): Series {
        val window = SeriesWindow.endingAt(range, clock.instant())
        return Series(
            deviceId = deviceId,
            metric = metric,
            range = range.param,
            bucketSeconds = range.bucket.seconds,
            from = window.from,
            to = window.to,
            points = window.points(repository.aggregates(window, deviceId, metric)),
        )
    }
}
