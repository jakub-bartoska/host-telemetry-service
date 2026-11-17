package com.hosttelemetry.simulator.cli

import java.net.URI
import java.net.URISyntaxException
import java.time.Duration

/**
 * `--name value` flags. Every flag can also be set by an environment variable `SIM_<NAME>`
 * (e.g. `SIM_DEVICES=1000`), so Compose can set defaults that `docker compose run … --flag` overrides.
 */
object OptionsParser {

    val USAGE = """
        Usage: simulator [--flag value]...   (each flag can also be set as env SIM_<FLAG>, e.g. SIM_DEVICES)
          --mode live|backfill   live: one connection per device, current timestamps (default live)
                                 backfill: history from now - days to now, then exit
          --devices N            number of devices host-0001.. (default 100)
          --interval 10s         spacing of one device's samples, ms/s/m/h (default 10s, at least 1s)
          --url URL              WebSocket endpoint (default ws://localhost:8080/ws/telemetry)
          --duration 10m         live: stop after this long (default: run until Ctrl+C)
          --days 7               backfill: days of history, 1..7 (default 7)
          --rate 2000            backfill: max messages per second in total (default 2000)
          --seed 1               random seed; same seed = same data (default 1)
    """.trimIndent()

    private val FLAGS = listOf("mode", "devices", "interval", "url", "duration", "days", "rate", "seed")

    // Timestamps are whole seconds and the service stores one value per (device, metric, timestamp),
    // so samples closer than this would share a timestamp and be dropped as duplicates.
    private val MIN_INTERVAL: Duration = Duration.ofSeconds(1)

    // At most 9 digits, so neither the number nor the resulting Duration can overflow.
    private val DURATION = Regex("""^(\d{1,9})(ms|s|m|h)$""")

    fun parse(args: List<String>, env: Map<String, String> = emptyMap()): Options {
        val values = fromEnv(env) + fromArgs(args)
        val defaults = Options()
        return Options(
            mode = values["mode"]?.let(::mode) ?: defaults.mode,
            devices = values["devices"]?.let { int("devices", it, 1..100_000) } ?: defaults.devices,
            interval = values["interval"]?.let(::interval) ?: defaults.interval,
            url = values["url"]?.let(::url) ?: defaults.url,
            duration = values["duration"]?.let { duration("duration", it) } ?: defaults.duration,
            // More than 7 days would be rejected by the service as timestamp_too_old.
            days = values["days"]?.let { int("days", it, 1..7) } ?: defaults.days,
            rate = values["rate"]?.let { int("rate", it, 1..1_000_000) } ?: defaults.rate,
            seed = values["seed"]?.let(::seed) ?: defaults.seed,
        )
    }

    /** `500ms`, `10s`, `5m`, `1h`; must be positive. */
    fun duration(flag: String, text: String): Duration {
        val (amount, unit) = DURATION.matchEntire(text.trim())?.destructured
            ?: throw UsageException("--$flag must look like 500ms, 10s, 5m or 1h, not '$text'")
        val value = amount.toLong()
        val result = when (unit) {
            "ms" -> Duration.ofMillis(value)
            "s" -> Duration.ofSeconds(value)
            "m" -> Duration.ofMinutes(value)
            else -> Duration.ofHours(value)
        }
        if (result.isZero) throw UsageException("--$flag must be positive")
        return result
    }

    private fun fromEnv(env: Map<String, String>): Map<String, String> =
        FLAGS.mapNotNull { flag -> env["SIM_${flag.uppercase()}"]?.takeIf(String::isNotBlank)?.let { flag to it } }.toMap()

    private fun fromArgs(args: List<String>): Map<String, String> {
        if (args.size % 2 != 0) throw UsageException("Every flag needs a value: ${args.joinToString(" ")}")
        return args.chunked(2).associate { (flag, value) ->
            val name = flag.removePrefix("--")
            if (!flag.startsWith("--") || name !in FLAGS) throw UsageException("Unknown flag: $flag")
            name to value
        }
    }

    private fun mode(text: String): Mode =
        Mode.entries.firstOrNull { it.label == text.lowercase() }
            ?: throw UsageException("--mode must be live or backfill, not '$text'")

    private fun interval(text: String): Duration {
        val interval = duration("interval", text)
        if (interval < MIN_INTERVAL) throw UsageException("--interval must be at least 1s")
        return interval
    }

    private fun url(text: String): URI {
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            null
        }
        if (uri?.scheme !in setOf("ws", "wss") || uri?.host == null) {
            throw UsageException("--url must be a ws:// or wss:// URL, not '$text'")
        }
        return uri
    }

    private fun seed(text: String): Long =
        text.toLongOrNull() ?: throw UsageException("--seed must be a whole number, not '$text'")

    private fun int(flag: String, text: String, range: IntRange): Int =
        text.toIntOrNull()?.takeIf { it in range }
            ?: throw UsageException("--$flag must be a whole number in ${range.first}..${range.last}, not '$text'")
}
