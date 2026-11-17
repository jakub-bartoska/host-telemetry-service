package com.hosttelemetry.service.model.charts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import java.time.Instant

class SeriesWindowTest {

    private val uglyNow = Instant.parse("2026-10-26T14:07:13.500Z")

    private fun at(text: String) = Instant.parse(text)

    @ParameterizedTest
    @CsvSource("hour, 30, 120", "day, 300, 288", "week, 1800, 336")
    fun `each range has its bucket size and point count`(param: String, bucketSeconds: Long, points: Int) {
        val range = requireNotNull(ChartRange.fromParam(param))
        val window = SeriesWindow.endingAt(range, uglyNow)

        assertThat(range.bucket).isEqualTo(Duration.ofSeconds(bucketSeconds))
        assertThat(window.points(emptyList())).hasSize(points)
        assertThat(Duration.between(window.from, window.to)).isEqualTo(range.length)
    }

    @ParameterizedTest
    @CsvSource(
        "hour, 2026-10-26T13:07:30Z, 2026-10-26T14:07:30Z",
        "day,  2026-10-25T14:10:00Z, 2026-10-26T14:10:00Z",
        "week, 2026-10-19T14:30:00Z, 2026-10-26T14:30:00Z",
    )
    fun `window ends with the current UTC-aligned bucket`(param: String, from: String, to: String) {
        val window = SeriesWindow.endingAt(requireNotNull(ChartRange.fromParam(param)), uglyNow)

        assertThat(window.from).isEqualTo(at(from))
        assertThat(window.to).isEqualTo(at(to))
    }

    @Test
    fun `a time exactly on a boundary starts a new bucket`() {
        val window = SeriesWindow.endingAt(ChartRange.HOUR, at("2026-10-26T14:00:00Z"))

        assertThat(window.to).isEqualTo(at("2026-10-26T14:00:30Z"))
        assertThat(window.points(emptyList()).last().ts).isEqualTo(at("2026-10-26T14:00:00Z"))
    }

    @Test
    fun `points are consecutive bucket starts from 'from'`() {
        val window = SeriesWindow.endingAt(ChartRange.DAY, uglyNow)
        val points = window.points(emptyList())

        assertThat(points.first().ts).isEqualTo(window.from)
        assertThat(points.last().ts).isEqualTo(window.to - ChartRange.DAY.bucket)
        assertThat(points.zipWithNext { a, b -> Duration.between(a.ts, b.ts) }.distinct())
            .containsExactly(ChartRange.DAY.bucket)
    }

    @Test
    fun `missing buckets are null and values do not leak into neighbours`() {
        val window = SeriesWindow.endingAt(ChartRange.HOUR, uglyNow)
        val first = window.from
        val third = window.from + Duration.ofSeconds(60)

        val points = window.points(
            listOf(
                BucketAggregate(first, min = 10.0, max = 30.0, sum = 60.0, count = 3),
                BucketAggregate(third, min = 5.0, max = 7.0, sum = 12.0, count = 2),
            ),
        )

        assertThat(points[0]).isEqualTo(SeriesPoint(first, 10.0, 30.0, 20.0))
        assertThat(points[1]).isEqualTo(SeriesPoint(first + Duration.ofSeconds(30), null, null, null))
        assertThat(points[2]).isEqualTo(SeriesPoint(third, 5.0, 7.0, 6.0))
        assertThat(points.drop(3)).allSatisfy { assertThat(it.avg).isNull() }
    }

    @Test
    fun `week avg is sum of sums over sum of counts, not an average of averages`() {
        val window = SeriesWindow.endingAt(ChartRange.WEEK, uglyNow)
        val bucket = window.from

        // One sample of 10 and three samples of 40: true avg 130 / 4 = 32.5, average of averages would be 25.
        val points = window.points(
            listOf(
                BucketAggregate(bucket, min = 10.0, max = 10.0, sum = 10.0, count = 1),
                BucketAggregate(bucket + Duration.ofMinutes(5), min = 40.0, max = 40.0, sum = 120.0, count = 3),
            ),
        )

        assertThat(points[0]).isEqualTo(SeriesPoint(bucket, min = 10.0, max = 40.0, avg = 32.5))
        assertThat(points.drop(1)).allSatisfy { assertThat(it.min).isNull() }
    }

    @Test
    fun `rows outside the window are ignored`() {
        val window = SeriesWindow.endingAt(ChartRange.HOUR, uglyNow)

        val points = window.points(
            listOf(
                BucketAggregate(window.from - Duration.ofSeconds(30), 1.0, 1.0, 1.0, 1),
                BucketAggregate(window.to, 1.0, 1.0, 1.0, 1),
            ),
        )

        assertThat(points).allSatisfy { assertThat(it.avg).isNull() }
    }
}
