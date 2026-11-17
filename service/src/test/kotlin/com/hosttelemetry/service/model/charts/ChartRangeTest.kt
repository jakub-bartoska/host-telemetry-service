package com.hosttelemetry.service.model.charts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChartRangeTest {

    @Test
    fun `each range is found by its parameter value`() {
        assertThat(ChartRange.entries).allSatisfy { assertThat(ChartRange.fromParam(it.param)).isEqualTo(it) }
    }

    @Test
    fun `unknown range parameter is not a range`() {
        assertThat(ChartRange.fromParam("foo")).isNull()
        assertThat(ChartRange.fromParam("HOUR")).isNull()
    }
}
