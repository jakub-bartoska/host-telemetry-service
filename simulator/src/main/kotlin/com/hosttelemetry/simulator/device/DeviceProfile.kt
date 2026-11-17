package com.hosttelemetry.simulator.device

import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/** Per-device levels the random walk reverts to, so charts of different devices look different. */
data class DeviceProfile(
    val cpuBasePct: Double,
    val memBasePct: Double,
    val rxBaseBps: Double,
    val txBaseBps: Double,
    val idlePowerW: Double,
    val cpuPowerW: Double,
    val ambientC: Double,
    val heatCPerW: Double,
) {
    fun power(cpuPct: Double) = idlePowerW + cpuPowerW * cpuPct / 100.0

    /** Steady-state temperature at a given power draw. */
    fun targetTemperature(powerW: Double) = ambientC + heatCPerW * powerW

    companion object {
        fun random(random: Random): DeviceProfile {
            // Log-uniform from ~1 Mbit/s to ~900 Mbit/s: real fleets span orders of magnitude.
            val rx = exp(random.nextDouble(ln(1e6), ln(9e8)))
            return DeviceProfile(
                cpuBasePct = random.nextDouble(10.0, 60.0),
                memBasePct = random.nextDouble(20.0, 80.0),
                rxBaseBps = rx,
                txBaseBps = rx * random.nextDouble(0.2, 0.9),
                idlePowerW = random.nextDouble(80.0, 200.0),
                cpuPowerW = random.nextDouble(100.0, 250.0),
                ambientC = random.nextDouble(22.0, 32.0),
                heatCPerW = random.nextDouble(0.06, 0.12),
            )
        }
    }
}
