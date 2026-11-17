package com.hosttelemetry.simulator.transport

import com.hosttelemetry.simulator.device.Sample
import tools.jackson.core.JacksonException
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The wire format: JSON in and out. The only class that talks to Jackson. */
object Messages {
    private val mapper = jacksonObjectMapper()

    /** `host-0001`, `host-0002`, … — 1-based to read naturally in charts. */
    fun deviceId(index: Int): String = String.format(Locale.ROOT, "host-%04d", index + 1)

    fun stats(deviceId: String, timestamp: Instant, sample: Sample): String =
        mapper.writeValueAsString(
            StatsMessage(
                type = "stats",
                deviceId = deviceId,
                // Whole seconds. Live and backfill pass slot times >= 1 s apart,
                // so truncation keeps one device's timestamps unique.
                timestamp = timestamp.truncatedTo(ChronoUnit.SECONDS).toString(),
                stats = sample.toStats(),
            ),
        )

    /** The `reason` of an error frame, or `null` when [text] is something else. */
    fun errorReason(text: String): String? =
        try {
            mapper.readValue<ErrorFrame>(text).takeIf { it.type == "error" }?.let { it.reason ?: "unknown" }
        } catch (e: JacksonException) {
            null
        }
}
