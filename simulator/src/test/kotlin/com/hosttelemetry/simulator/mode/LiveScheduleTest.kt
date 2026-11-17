package com.hosttelemetry.simulator.mode

import com.hosttelemetry.simulator.device.DeviceGenerator
import com.hosttelemetry.simulator.transport.Messages
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class LiveScheduleTest {

    @Test
    fun `devices are spread over one interval`() {
        val phases = (0 until 4).map { LiveSchedule.phaseMillis(it, devices = 4, intervalMillis = 10_000) }
        assertThat(phases).containsExactly(0, 2_500, 5_000, 7_500)
    }

    @Test
    fun `next slot is the first one at or after now`() {
        assertThat(LiveSchedule.nextSlot(nowMillis = 500, originMillis = 1_000, intervalMillis = 10_000)).isEqualTo(1_000)
        assertThat(LiveSchedule.nextSlot(nowMillis = 11_000, originMillis = 1_000, intervalMillis = 10_000)).isEqualTo(11_000)
        assertThat(LiveSchedule.nextSlot(nowMillis = 11_001, originMillis = 1_000, intervalMillis = 10_000)).isEqualTo(21_000)
    }

    @Test
    fun `jitter is a small part of the interval`() {
        assertThat(LiveSchedule.maxJitterMillis(10_000)).isEqualTo(500)
        assertThat(LiveSchedule.maxJitterMillis(1_000)).isEqualTo(50)
    }

    @Test
    fun `slot timestamps of one device are unique and increasing at a 1 s interval`() {
        val intervalMillis = 1_000L
        // A phase just below a whole second: sending at slot ± jitter would cross second boundaries.
        val originMillis = 1_793_000_000_000L + LiveSchedule.phaseMillis(999, devices = 1_000, intervalMillis = intervalMillis)
        val firstSlot = LiveSchedule.nextSlot(nowMillis = originMillis + 1, originMillis, intervalMillis)
        val generator = DeviceGenerator(seed = 1, deviceIndex = 0)

        val timestamps = (0 until 1_000).map { k ->
            val text = Messages.stats("host-0001", Instant.ofEpochMilli(firstSlot + k * intervalMillis), generator.next())
            Instant.parse(checkNotNull(TIMESTAMP.find(text)) { "no timestamp in $text" }.groupValues[1])
        }

        assertThat(timestamps).doesNotHaveDuplicates()
        assertThat(timestamps).isSorted()
    }

    private companion object {
        val TIMESTAMP = Regex(""""timestamp":"([^"]+)"""")
    }
}
