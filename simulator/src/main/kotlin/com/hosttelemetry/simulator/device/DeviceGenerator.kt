package com.hosttelemetry.simulator.device

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Plausible stats for one device, one sample per [next] call. Pure (no I/O, no clock): the same
 * seed and device index always give the same sequence.
 *
 * Every value is a mean-reverting random walk around the device's [DeviceProfile]: CPU moves fast and
 * has occasional bursts, memory drifts slowly, traffic walks in log space (so it stays positive), power
 * follows CPU, and temperature chases the power-driven target by at most [MAX_TEMPERATURE_STEP_C] per
 * sample, so it lags behind power.
 */
class DeviceGenerator(seed: Long, deviceIndex: Int) {

    // Mix the device index into the seed so devices get independent, reproducible streams.
    private val random = Random(seed * 1_000_003L + deviceIndex)
    val profile = DeviceProfile.random(random)

    private var cpu = profile.cpuBasePct
    private var mem = profile.memBasePct
    private var logRx = ln(profile.rxBaseBps)
    private var logTx = ln(profile.txBaseBps)
    private var burstSamplesLeft = 0

    // Start at steady state, so the first samples of a backfill do not show a fake warm-up.
    private var temperature = profile.targetTemperature(profile.power(cpu))

    fun next(): Sample {
        if (burstSamplesLeft == 0 && random.nextDouble() < BURST_PROBABILITY) {
            burstSamplesLeft = random.nextInt(3, 30)
        }
        val cpuTarget = if (burstSamplesLeft > 0) BURST_CPU_PCT else profile.cpuBasePct
        if (burstSamplesLeft > 0) burstSamplesLeft--

        cpu = (cpu + 0.2 * (cpuTarget - cpu) + gaussian(3.0)).coerceIn(0.0, 100.0)
        mem = (mem + 0.01 * (profile.memBasePct - mem) + gaussian(0.2)).coerceIn(0.0, 100.0)
        logRx += 0.1 * (ln(profile.rxBaseBps) - logRx) + gaussian(0.1)
        logTx += 0.1 * (ln(profile.txBaseBps) - logTx) + gaussian(0.1)
        val power = (profile.power(cpu) + gaussian(2.0)).coerceAtLeast(0.0)
        val towardsTarget = 0.05 * (profile.targetTemperature(power) - temperature)
        temperature += towardsTarget.coerceIn(-MAX_TEMPERATURE_STEP_C, MAX_TEMPERATURE_STEP_C)

        return Sample(
            cpuUtilPct = round1(cpu),
            memUtilPct = round1(mem),
            rxBps = exp(logRx).roundToLong(),
            txBps = exp(logTx).roundToLong(),
            powerW = round1(power),
            temperatureC = round1(temperature),
        )
    }

    /** Box–Muller on the seeded [random], so the sequence stays reproducible. */
    private fun gaussian(stdDev: Double): Double {
        val u1 = 1.0 - random.nextDouble() // (0, 1], keeps ln() finite
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2) * stdDev
    }

    private fun round1(value: Double) = (value * 10).roundToLong() / 10.0

    companion object {
        /** Largest change of the internal temperature between two samples; rounding adds up to 0.1 °C. */
        const val MAX_TEMPERATURE_STEP_C = 0.3
        private const val BURST_PROBABILITY = 0.01
        private const val BURST_CPU_PCT = 95.0
    }
}
