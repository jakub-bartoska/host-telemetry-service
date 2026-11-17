package com.hosttelemetry.service.web.controller

import com.hosttelemetry.service.TimescaleContainerConfig
import com.hosttelemetry.service.model.ingest.TelemetryMessage
import com.hosttelemetry.service.repository.ingest.TelemetryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Rows inserted straight into `telemetry`, read back through the HTTP endpoint. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TimescaleContainerConfig::class)
class SeriesIntegrationTest(
    @Autowired private val rest: TestRestTemplate,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val ingestRepository: TelemetryRepository,
) {

    private val mapper = JsonMapper()

    // Unique per test, so tests sharing the database do not see each other's rows.
    private val deviceId = "it-${UUID.randomUUID()}"
    private val metric = "cpuUtilPct"

    private fun insert(vararg rows: Pair<Instant, Double>) {
        jdbc.batchUpdate(
            "INSERT INTO telemetry (ts, device_id, metric, value) VALUES (?, ?, ?, ?)",
            rows.map { (ts, value) -> arrayOf<Any>(ts.atOffset(ZoneOffset.UTC), deviceId, metric, value) },
        )
    }

    private fun request(range: String, device: String = deviceId, metricKey: String = metric) =
        rest.getForEntity("/api/v1/devices/$device/metrics/$metricKey/series?range=$range", String::class.java)

    private fun problemDetail(response: ResponseEntity<String>): String {
        assertThat(response.headers.contentType.toString()).isEqualTo("application/problem+json")
        return mapper.readTree(response.body).get("detail").stringValue()
    }

    private fun series(range: String): JsonNode {
        val response = request(range)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return mapper.readTree(response.body)
    }

    /** Points keyed by bucket start; a point is `[min, max, avg]` with `null` for a gap. */
    private fun JsonNode.pointsByTs(): Map<Instant, List<Double?>> =
        get("points").associate { point ->
            Instant.parse(point.get("ts").stringValue()) to
                listOf("min", "max", "avg").map { field -> point.get(field).takeUnless(JsonNode::isNull)?.doubleValue() }
        }

    private fun floor(instant: Instant, bucket: Duration): Instant =
        Instant.ofEpochSecond(Math.floorDiv(instant.epochSecond, bucket.seconds) * bucket.seconds)

    private val gap = listOf(null, null, null)

    private fun assertSeries(body: JsonNode, range: String, bucketSeconds: Int, points: Int, expected: Map<Instant, List<Double?>>) {
        assertThat(body.get("deviceId").stringValue()).isEqualTo(deviceId)
        assertThat(body.get("metric").stringValue()).isEqualTo(metric)
        assertThat(body.get("range").stringValue()).isEqualTo(range)
        assertThat(body.get("bucketSeconds").intValue()).isEqualTo(bucketSeconds)
        val from = Instant.parse(body.get("from").stringValue())
        val to = Instant.parse(body.get("to").stringValue())
        assertThat(Duration.between(from, to).seconds).isEqualTo(bucketSeconds.toLong() * points)

        val actual = body.pointsByTs()
        assertThat(actual).hasSize(points)
        assertThat(actual).containsAllEntriesOf(expected)
        assertThat(actual.filterKeys { it !in expected }.values).allSatisfy { assertThat(it).isEqualTo(gap) }
    }

    @Test
    fun `hour buckets raw rows into 30 s with a null gap`() {
        val base = floor(Instant.now() - Duration.ofMinutes(40), Duration.ofMinutes(5))
        insert(
            base to 10.0, base + Duration.ofSeconds(10) to 20.0, base + Duration.ofSeconds(20) to 30.0,
            // base + 30 s: no data
            base + Duration.ofSeconds(60) to 5.0, base + Duration.ofSeconds(70) to 7.0,
        )

        assertSeries(
            series("hour"), "hour", bucketSeconds = 30, points = 120,
            expected = mapOf(
                base to listOf(10.0, 30.0, 20.0),
                base + Duration.ofSeconds(30) to gap,
                base + Duration.ofSeconds(60) to listOf(5.0, 7.0, 6.0),
            ),
        )
    }

    @Test
    fun `day reads 5 minute buckets from the real-time aggregate`() {
        val base = floor(Instant.now() - Duration.ofMinutes(40), Duration.ofMinutes(5))
        insert(
            base to 10.0, base + Duration.ofSeconds(10) to 20.0, base + Duration.ofSeconds(20) to 30.0,
            // base + 5 min: no data
            base + Duration.ofMinutes(10) to 5.0, base + Duration.ofMinutes(10).plusSeconds(10) to 7.0,
        )

        assertSeries(
            series("day"), "day", bucketSeconds = 300, points = 288,
            expected = mapOf(
                base to listOf(10.0, 30.0, 20.0),
                base + Duration.ofMinutes(5) to gap,
                base + Duration.ofMinutes(10) to listOf(5.0, 7.0, 6.0),
            ),
        )
    }

    @Test
    fun `week rolls materialized 5 minute rows into 30 min with a sample-weighted avg`() {
        val base = floor(Instant.now() - Duration.ofHours(3), Duration.ofMinutes(30))
        insert(
            // Two 5-minute buckets with different sample counts: avg 130 / 4 = 32.5, not (10 + 40) / 2.
            base to 10.0,
            base + Duration.ofMinutes(5) to 40.0,
            base + Duration.ofMinutes(5).plusSeconds(10) to 40.0,
            base + Duration.ofMinutes(5).plusSeconds(20) to 40.0,
            // base + 30 min: no data
            base + Duration.ofMinutes(60) to 1.0,
        )
        // Materialize, so this test also covers the stored (not real-time) path of the aggregate.
        jdbc.execute("CALL refresh_continuous_aggregate('telemetry_5m', NULL, NULL)")

        assertSeries(
            series("week"), "week", bucketSeconds = 1800, points = 336,
            expected = mapOf(
                base to listOf(10.0, 40.0, 32.5),
                base + Duration.ofMinutes(30) to gap,
                base + Duration.ofMinutes(60) to listOf(1.0, 1.0, 1.0),
            ),
        )
    }

    @Test
    fun `late data reaches compressed chunks and the week chart after the next aggregate refresh`() {
        val base = floor(Instant.now() - Duration.ofDays(2), Duration.ofMinutes(30))
        insert(base to 10.0)
        refreshAggregateAround(base)
        // Both the raw chunk and the aggregate chunk of `base` are compressed, as the policies would do.
        jdbc.execute("SELECT compress_chunk(c, if_not_compressed => true) FROM show_chunks('telemetry', older_than => INTERVAL '1 day') c")
        jdbc.execute("SELECT compress_chunk(c, if_not_compressed => true) FROM show_chunks('telemetry_5m', older_than => INTERVAL '1 day') c")
        assertThat(isCompressed("telemetry", base)).isTrue()
        assertThat(isCompressed(materializationHypertable(), base)).isTrue()

        // Through the real write path: a late sample for the same bucket, and a resend of the first one
        // with a different value, which the unique key must ignore even in a compressed chunk.
        ingestRepository.insert(
            listOf(
                TelemetryMessage(deviceId, base + Duration.ofSeconds(10), mapOf(metric to 30.0)),
                TelemetryMessage(deviceId, base, mapOf(metric to 99.0)),
            ),
        )
        // Until the refresh policy runs (every 5 minutes) the chart still shows the materialized bucket;
        // the policy's refresh window covers 8 days, so this call is what it would do.
        refreshAggregateAround(base)

        assertThat(series("week").pointsByTs()[base]).isEqualTo(listOf(10.0, 30.0, 20.0))
    }

    private fun refreshAggregateAround(instant: Instant) {
        val from = OffsetDateTime.ofInstant(instant - Duration.ofHours(1), ZoneOffset.UTC)
        val to = OffsetDateTime.ofInstant(instant + Duration.ofHours(1), ZoneOffset.UTC)
        jdbc.update("CALL refresh_continuous_aggregate('telemetry_5m', ?, ?)", from, to)
    }

    private fun materializationHypertable(): String =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT materialization_hypertable_name FROM timescaledb_information.continuous_aggregates WHERE view_name = 'telemetry_5m'",
                String::class.java,
            ),
        )

    private fun isCompressed(hypertable: String, instant: Instant): Boolean =
        jdbc.queryForObject(
            """
            SELECT is_compressed FROM timescaledb_information.chunks
            WHERE hypertable_name = ? AND range_start <= ? AND range_end > ?
            """,
            Boolean::class.java,
            hypertable,
            OffsetDateTime.ofInstant(instant, ZoneOffset.UTC),
            OffsetDateTime.ofInstant(instant, ZoneOffset.UTC),
        ) == true

    @Test
    fun `unknown device returns 200 with all points null`() {
        val body = series("day")

        assertThat(body.pointsByTs()).hasSize(288)
        assertThat(body.pointsByTs().values).allSatisfy { assertThat(it).isEqualTo(gap) }
    }

    @Test
    fun `invalid range is 400 with a problem detail`() {
        val response = request("foo")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(problemDetail(response)).isEqualTo("invalid range: must be one of hour, day, week")
    }

    @Test
    fun `a missing range is 400`() {
        val response = rest.getForEntity("/api/v1/devices/$deviceId/metrics/$metric/series", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(problemDetail(response)).contains("range")
    }

    @Test
    fun `an API version that is not served is 400`() {
        val response =
            rest.getForEntity("/api/v2/devices/$deviceId/metrics/$metric/series?range=hour", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(problemDetail(response)).contains("API version")
    }

    @Test
    fun `device id is limited to the 64 characters ingest accepts`() {
        assertThat(request("hour", device = "x".repeat(64)).statusCode).isEqualTo(HttpStatus.OK)

        val response = request("hour", device = "x".repeat(65))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(problemDetail(response)).isEqualTo("invalid deviceId")
    }

    @Test
    fun `metric outside the ingest pattern is 400`() {
        val response = request("hour", metricKey = "1cpu")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(problemDetail(response)).isEqualTo("invalid metric")
    }
}
