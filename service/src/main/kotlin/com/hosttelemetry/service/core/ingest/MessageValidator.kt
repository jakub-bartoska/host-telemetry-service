package com.hosttelemetry.service.core.ingest

import com.hosttelemetry.service.model.TelemetryIds
import com.hosttelemetry.service.model.ingest.RejectReason
import com.hosttelemetry.service.model.ingest.TelemetryMessage
import com.hosttelemetry.service.model.ingest.ValidationResult
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** Validates incoming messages: a pure function of the message text and the current time. */
class MessageValidator(private val json: TelemetryJson = TelemetryJson()) {

    fun validate(text: String, now: Instant): ValidationResult {
        val raw = json.parse(text) ?: return reject(RejectReason.INVALID_JSON)
        if (raw.type != MESSAGE_TYPE) return reject(RejectReason.UNSUPPORTED_TYPE)

        val deviceId = raw.deviceId?.takeIf(TelemetryIds.DEVICE_ID::matches) ?: return reject(RejectReason.INVALID_DEVICE_ID)
        // From here on the sender is known, so a rejection names the device.
        val timestamp = raw.timestamp?.let(::parseInstant) ?: return reject(RejectReason.INVALID_TIMESTAMP, deviceId)
        when {
            timestamp > now + MAX_CLOCK_AHEAD -> return reject(RejectReason.TIMESTAMP_IN_FUTURE, deviceId)
            timestamp < now - MAX_AGE -> return reject(RejectReason.TIMESTAMP_TOO_OLD, deviceId)
        }

        val rawStats = raw.stats ?: return reject(RejectReason.INVALID_STATS, deviceId)
        when {
            rawStats.isEmpty() -> return reject(RejectReason.INVALID_STATS, deviceId)
            rawStats.size > MAX_STATS -> return reject(RejectReason.TOO_MANY_STATS, deviceId)
        }
        // One broken sensor must not hide the others: drop only the bad entries.
        val stats = buildMap {
            rawStats.forEach { (key, value) ->
                if (value != null && value.isFinite() && TelemetryIds.METRIC_KEY.matches(key)) put(key, value)
            }
        }
        if (stats.isEmpty()) return reject(RejectReason.NO_VALID_STATS, deviceId)

        return ValidationResult.Accepted(
            message = TelemetryMessage(deviceId, timestamp, stats),
            discardedKeys = rawStats.keys.filterNot(stats::containsKey),
        )
    }

    private fun reject(reason: RejectReason, deviceId: String? = null) = ValidationResult.Rejected(reason, deviceId)

    private fun parseInstant(text: String): Instant? =
        try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            null
        }

    private companion object {
        const val MESSAGE_TYPE = "stats"
        const val MAX_STATS = 32
        val MAX_CLOCK_AHEAD: Duration = Duration.ofMinutes(5)

        // Data older than this is rejected; the database keeps 8 days.
        val MAX_AGE: Duration = Duration.ofDays(7)
    }
}
