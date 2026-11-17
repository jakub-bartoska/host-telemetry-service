package com.hosttelemetry.service.model.charts

import java.time.Duration

/** The three chart ranges; [param] is the `range` query parameter value. */
enum class ChartRange(val param: String, val length: Duration, val bucket: Duration) {
    HOUR("hour", Duration.ofHours(1), Duration.ofSeconds(30)),
    DAY("day", Duration.ofDays(1), Duration.ofMinutes(5)),
    WEEK("week", Duration.ofDays(7), Duration.ofMinutes(30)),
    ;

    val bucketCount: Int get() = (length.seconds / bucket.seconds).toInt()

    companion object {
        fun fromParam(param: String): ChartRange? = entries.firstOrNull { it.param == param }
    }
}
