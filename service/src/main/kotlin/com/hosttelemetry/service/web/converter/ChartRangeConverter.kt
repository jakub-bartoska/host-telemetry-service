package com.hosttelemetry.service.web.converter

import com.hosttelemetry.service.model.charts.ChartRange
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

/** Binds the `range` request parameter (`hour`, `day`, `week`) to a [ChartRange]. */
@Component
class ChartRangeConverter : Converter<String, ChartRange> {

    override fun convert(source: String): ChartRange =
        ChartRange.fromParam(source)
            ?: throw IllegalArgumentException("must be one of ${ChartRange.entries.joinToString { it.param }}")
}
