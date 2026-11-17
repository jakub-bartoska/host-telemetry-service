package com.hosttelemetry.simulator.device

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs

class DeviceGeneratorTest {

    private fun samples(seed: Long, device: Int, count: Int): List<Sample> {
        val generator = DeviceGenerator(seed, device)
        return List(count) { generator.next() }
    }

    @Test
    fun `same seed and device give the same data`() {
        assertThat(samples(seed = 42, device = 7, count = 500)).isEqualTo(samples(seed = 42, device = 7, count = 500))
    }

    @Test
    fun `a different seed gives different data`() {
        assertThat(samples(seed = 1, device = 0, count = 50)).isNotEqualTo(samples(seed = 2, device = 0, count = 50))
    }

    @Test
    fun `values stay within bounds over many steps`() {
        // 20 000 samples ≈ 2.3 days at 10 s, for a few devices.
        (0 until 5).forEach { device ->
            val series = samples(seed = 3, device = device, count = 20_000)
            series.forEach { sample ->
                assertThat(sample.cpuUtilPct).isBetween(0.0, 100.0)
                assertThat(sample.memUtilPct).isBetween(0.0, 100.0)
                assertThat(sample.rxBps).isGreaterThanOrEqualTo(0)
                assertThat(sample.txBps).isGreaterThanOrEqualTo(0)
                assertThat(sample.powerW).isGreaterThanOrEqualTo(0.0)
                listOf(sample.cpuUtilPct, sample.memUtilPct, sample.powerW, sample.temperatureC).forEach {
                    assertThat(it).isFinite()
                }
            }
        }
    }

    @Test
    fun `temperature moves by at most the configured step between samples`() {
        val maxVisibleStep = DeviceGenerator.MAX_TEMPERATURE_STEP_C + 0.1 + 1e-9 // one-decimal rounding
        (0 until 5).forEach { device ->
            samples(seed = 3, device = device, count = 20_000).zipWithNext().forEach { (previous, next) ->
                assertThat(abs(next.temperatureC - previous.temperatureC)).isLessThanOrEqualTo(maxVisibleStep)
            }
        }
    }

    @Test
    fun `memory changes slowly`() {
        samples(seed = 5, device = 0, count = 5_000).zipWithNext().forEach { (previous, next) ->
            assertThat(abs(next.memUtilPct - previous.memUtilPct)).isLessThan(2.0)
        }
    }

    @Test
    fun `power grows with cpu`() {
        val series = samples(seed = 11, device = 0, count = 20_000)
        val (busy, idle) = series.partition { it.cpuUtilPct > 70.0 }
        assertThat(busy).isNotEmpty() // bursts happen within 20 000 samples
        assertThat(busy.map { it.powerW }.average()).isGreaterThan(idle.map { it.powerW }.average())
    }

    @Test
    fun `different devices have different base levels`() {
        val profiles = (0 until 100).map { DeviceGenerator(seed = 42, deviceIndex = it).profile }
        assertThat(profiles.map { it.cpuBasePct }.distinct()).hasSize(100)
        assertThat(profiles.map { it.rxBaseBps }.distinct()).hasSize(100)
        assertThat(profiles.maxOf { it.cpuBasePct } - profiles.minOf { it.cpuBasePct }).isGreaterThan(20.0)
    }
}
