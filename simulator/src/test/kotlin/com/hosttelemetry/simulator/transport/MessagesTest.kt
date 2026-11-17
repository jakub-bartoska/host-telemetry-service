package com.hosttelemetry.simulator.transport

import com.hosttelemetry.service.core.ingest.MessageValidator
import com.hosttelemetry.service.model.ingest.ValidationResult
import com.hosttelemetry.simulator.device.DeviceGenerator
import com.hosttelemetry.simulator.device.Sample
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class MessagesTest {

    private val now = Instant.parse("2026-10-26T14:03:20.734Z")

    @Test
    fun `generated messages pass the service's validator with every stat kept`() {
        val validator = MessageValidator()
        (0 until 20).forEach { device ->
            val generator = DeviceGenerator(seed = 1, deviceIndex = device)
            repeat(50) {
                val text = Messages.stats(Messages.deviceId(device), now, generator.next())

                val result = validator.validate(text, now)

                assertThat(result).isInstanceOf(ValidationResult.Accepted::class.java)
                result as ValidationResult.Accepted
                assertThat(result.discardedKeys).isEmpty()
                assertThat(result.message.stats.keys).containsExactlyInAnyOrder(
                    "cpuUtilPct", "memUtilPct", "rxBps", "txBps", "powerW", "temperatureC",
                )
            }
        }
    }

    @Test
    fun `message has the expected JSON shape`() {
        val sample = Sample(37.5, 62.1, 845200000, 412800000, 312.4, 58.2)

        assertThat(Messages.stats("host-0042", now, sample)).isEqualTo(
            """{"type":"stats","deviceId":"host-0042","timestamp":"2026-10-26T14:03:20Z",""" +
                """"stats":{"cpuUtilPct":37.5,"memUtilPct":62.1,"rxBps":845200000,"txBps":412800000,""" +
                """"powerW":312.4,"temperatureC":58.2}}""",
        )
    }

    @Test
    fun `device ids are zero padded and one based`() {
        assertThat(Messages.deviceId(0)).isEqualTo("host-0001")
        assertThat(Messages.deviceId(9_999)).isEqualTo("host-10000")
    }

    @Test
    fun `reads the reason of an error frame and ignores other text`() {
        assertThat(Messages.errorReason("""{"type":"error","reason":"timestamp_too_old"}""")).isEqualTo("timestamp_too_old")
        assertThat(Messages.errorReason("""{"type":"hello"}""")).isNull()
        assertThat(Messages.errorReason("not json")).isNull()
    }
}
