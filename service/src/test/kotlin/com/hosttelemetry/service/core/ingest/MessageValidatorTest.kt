package com.hosttelemetry.service.core.ingest

import com.hosttelemetry.service.model.ingest.RejectReason
import com.hosttelemetry.service.model.ingest.TelemetryMessage
import com.hosttelemetry.service.model.ingest.ValidationResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant

class MessageValidatorTest {

    private val now = Instant.parse("2026-10-26T14:03:20Z")
    private val validator = MessageValidator()

    private fun message(
        type: String = "\"stats\"",
        deviceId: String = "\"host-0042\"",
        timestamp: String = "\"$now\"",
        stats: String = """{"cpuUtilPct": 37.5, "rxBps": 845200000}""",
    ) = """{"type": $type, "deviceId": $deviceId, "timestamp": $timestamp, "stats": $stats}"""

    private fun rejected(text: String) = validator.validate(text, now) as? ValidationResult.Rejected

    private fun rejectReason(text: String) = rejected(text)?.reason

    private fun accepted(text: String) = validator.validate(text, now) as ValidationResult.Accepted

    @Test
    fun `accepts a typical stats message`() {
        val result = accepted(message())

        assertThat(result.message).isEqualTo(
            TelemetryMessage("host-0042", now, mapOf("cpuUtilPct" to 37.5, "rxBps" to 845200000.0)),
        )
        assertThat(result.discardedKeys).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "not json", "{\"type\": \"stats\"", "[]", "42", "null"])
    fun `rejects text that is not a JSON object`(text: String) {
        assertThat(rejectReason(text)).isEqualTo(RejectReason.INVALID_JSON)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"hello\"", "\"STATS\"", "null", "1"])
    fun `rejects a type other than stats`(type: String) {
        assertThat(rejectReason(message(type = type))).isEqualTo(RejectReason.UNSUPPORTED_TYPE)
    }

    @Test
    fun `rejects a message without type`() {
        assertThat(rejectReason("""{"deviceId": "host-1", "timestamp": "$now", "stats": {"a": 1}}"""))
            .isEqualTo(RejectReason.UNSUPPORTED_TYPE)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"\"", "\"host 42\"", "\"host/42\"", "\"hóst\"", "null", "42"])
    fun `rejects an invalid device id`(deviceId: String) {
        assertThat(rejectReason(message(deviceId = deviceId))).isEqualTo(RejectReason.INVALID_DEVICE_ID)
    }

    @Test
    fun `device id length is limited to 64 characters`() {
        assertThat(validator.validate(message(deviceId = "\"${"a".repeat(64)}\""), now))
            .isInstanceOf(ValidationResult.Accepted::class.java)
        assertThat(rejectReason(message(deviceId = "\"${"a".repeat(65)}\"")))
            .isEqualTo(RejectReason.INVALID_DEVICE_ID)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"yesterday\"", "\"2026-10-26\"", "\"2026-10-26T14:03:20\"", "1761487400", "null"])
    fun `rejects a timestamp that is not an ISO-8601 instant`(timestamp: String) {
        assertThat(rejectReason(message(timestamp = timestamp))).isEqualTo(RejectReason.INVALID_TIMESTAMP)
    }

    @Test
    fun `accepts a timestamp with an offset`() {
        assertThat(accepted(message(timestamp = "\"2026-10-26T16:03:20+02:00\"")).message.timestamp).isEqualTo(now)
    }

    @Test
    fun `rejects a timestamp more than 5 minutes in the future`() {
        val limit = now + Duration.ofMinutes(5)

        assertThat(validator.validate(message(timestamp = "\"$limit\""), now))
            .isInstanceOf(ValidationResult.Accepted::class.java)
        assertThat(rejectReason(message(timestamp = "\"${limit.plusSeconds(1)}\"")))
            .isEqualTo(RejectReason.TIMESTAMP_IN_FUTURE)
    }

    @Test
    fun `rejects a timestamp older than 7 days`() {
        val limit = now - Duration.ofDays(7)

        assertThat(validator.validate(message(timestamp = "\"$limit\""), now))
            .isInstanceOf(ValidationResult.Accepted::class.java)
        assertThat(rejectReason(message(timestamp = "\"${limit.minusSeconds(1)}\"")))
            .isEqualTo(RejectReason.TIMESTAMP_TOO_OLD)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "null", "[]", "\"cpu\""])
    fun `rejects missing or empty stats`(stats: String) {
        assertThat(rejectReason(message(stats = stats))).isEqualTo(RejectReason.INVALID_STATS)
    }

    @Test
    fun `rejects more than 32 stats`() {
        fun statsOf(count: Int) = (1..count).joinToString(prefix = "{", postfix = "}") { "\"m$it\": $it" }

        assertThat(accepted(message(stats = statsOf(32))).message.stats).hasSize(32)
        assertThat(rejectReason(message(stats = statsOf(33)))).isEqualTo(RejectReason.TOO_MANY_STATS)
    }

    @Test
    fun `one bad stat is dropped and the rest of the message is kept`() {
        val stats = """
            {
              "cpuUtilPct": 37.5,
              "temperatureC": null,
              "powerW": "312.4",
              "fanOn": true,
              "rxBps": 1e400,
              "1badKey": 1,
              "bad-key": 2,
              "memUtilPct": 62
            }
        """.trimIndent()

        val result = accepted(message(stats = stats))

        assertThat(result.message.stats).containsExactlyInAnyOrderEntriesOf(
            mapOf("cpuUtilPct" to 37.5, "memUtilPct" to 62.0),
        )
        assertThat(result.discardedKeys)
            .containsExactlyInAnyOrder("temperatureC", "powerW", "fanOn", "rxBps", "1badKey", "bad-key")
    }

    @Test
    fun `rejects a message whose stats are all invalid`() {
        assertThat(rejectReason(message(stats = """{"cpu": null, "mem": "high"}""")))
            .isEqualTo(RejectReason.NO_VALID_STATS)
    }

    @Test
    fun `a rejection names the device once its id is known to be valid`() {
        val withValidId = listOf(
            message(timestamp = "\"yesterday\""),
            message(timestamp = "\"${now + Duration.ofHours(1)}\""),
            message(timestamp = "\"${now - Duration.ofDays(8)}\""),
            message(stats = "{}"),
            message(stats = (1..33).joinToString(prefix = "{", postfix = "}") { "\"m$it\": $it" }),
            message(stats = """{"cpu": null}"""),
        )

        assertThat(withValidId.map { rejected(it)?.deviceId }).containsOnly("host-0042")
    }

    @Test
    fun `a rejection names no device when the message is not a stats message with a valid id`() {
        val withoutValidId = listOf("not json", message(type = "\"hello\""), message(deviceId = "\"host 42\""))

        assertThat(withoutValidId.map { rejected(it) }).doesNotContainNull()
        assertThat(withoutValidId.map { rejected(it)?.deviceId }).containsOnlyNulls()
    }
}
