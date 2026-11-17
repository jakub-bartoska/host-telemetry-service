package com.hosttelemetry.service.web.controller

import com.hosttelemetry.service.core.charts.SeriesService
import com.hosttelemetry.service.model.TelemetryIds
import com.hosttelemetry.service.model.charts.ChartRange
import com.hosttelemetry.service.model.charts.Series
import jakarta.validation.constraints.Pattern
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class SeriesController(private val service: SeriesService) {

    /**
     * One metric of one device over a fixed range. An unknown device or metric is not an error:
     * there is no device registry, so it is simply a series with no data (all points `null`).
     * A malformed id or an unknown range is answered with 400.
     */
    @GetMapping("/api/{version}/devices/{deviceId}/metrics/{metric}/series", version = "1")
    fun series(
        @PathVariable @Pattern(regexp = TelemetryIds.DEVICE_ID_PATTERN) deviceId: String,
        @PathVariable @Pattern(regexp = TelemetryIds.METRIC_KEY_PATTERN) metric: String,
        @RequestParam range: ChartRange,
    ): Series = service.series(range, deviceId, metric)
}
