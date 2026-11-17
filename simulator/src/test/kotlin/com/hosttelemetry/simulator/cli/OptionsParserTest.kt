package com.hosttelemetry.simulator.cli

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.time.Duration

class OptionsParserTest {

    @Test
    fun `no flags give the defaults`() {
        assertThat(OptionsParser.parse(emptyList())).isEqualTo(Options())
    }

    @Test
    fun `parses every flag`() {
        val options = OptionsParser.parse(
            "--mode backfill --devices 10 --interval 60s --url ws://app:8080/ws/telemetry --duration 5m --days 1 --rate 500 --seed 7"
                .split(" "),
        )

        assertThat(options).isEqualTo(
            Options(
                mode = Mode.BACKFILL,
                devices = 10,
                interval = Duration.ofSeconds(60),
                url = URI("ws://app:8080/ws/telemetry"),
                duration = Duration.ofMinutes(5),
                days = 1,
                rate = 500,
                seed = 7,
            ),
        )
    }

    @Test
    fun `environment sets defaults and flags override them`() {
        val env = mapOf("SIM_DEVICES" to "1000", "SIM_URL" to "ws://app:8080/ws/telemetry", "SIM_MODE" to "", "PATH" to "/bin")

        val options = OptionsParser.parse(listOf("--devices", "5"), env)

        assertThat(options.devices).isEqualTo(5)
        assertThat(options.url).isEqualTo(URI("ws://app:8080/ws/telemetry"))
        assertThat(options.mode).isEqualTo(Mode.LIVE) // blank env value is ignored
    }

    @Test
    fun `parses duration units`() {
        assertThat(OptionsParser.duration("x", "1500ms")).isEqualTo(Duration.ofMillis(1500))
        assertThat(OptionsParser.duration("x", "2h")).isEqualTo(Duration.ofHours(2))
    }

    @Test
    fun `accepts the limits of each range`() {
        fun parse(vararg args: String) = OptionsParser.parse(args.toList())

        assertThat(parse("--devices", "1").devices).isEqualTo(1)
        assertThat(parse("--devices", "100000").devices).isEqualTo(100_000)
        assertThat(parse("--interval", "1s").interval).isEqualTo(Duration.ofSeconds(1))
        assertThat(parse("--days", "1").days).isEqualTo(1)
        assertThat(parse("--days", "7").days).isEqualTo(7)
        assertThat(parse("--rate", "1").rate).isEqualTo(1)
        assertThat(parse("--rate", "1000000").rate).isEqualTo(1_000_000)
        assertThat(parse("--duration", "1ms").duration).isEqualTo(Duration.ofMillis(1))
        assertThat(parse("--url", "wss://example.com/ws/telemetry").url).isEqualTo(URI("wss://example.com/ws/telemetry"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "--mode sideways", "--devices 0", "--devices many", "--interval 500ms", "--interval 10", "--days 8",
            "--days 0", "--rate 0", "--url http://localhost:8080", "--seed x", "--colour red", "--devices", "devices 5",
            "--duration 0s",
        ],
    )
    fun `rejects invalid flags`(args: String) {
        assertThatThrownBy { OptionsParser.parse(args.split(" ")) }.isInstanceOf(UsageException::class.java)
    }
}
