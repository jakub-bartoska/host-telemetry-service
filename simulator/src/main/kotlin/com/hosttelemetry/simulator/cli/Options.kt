package com.hosttelemetry.simulator.cli

import java.net.URI
import java.time.Duration

data class Options(
    val mode: Mode = Mode.LIVE,
    val devices: Int = 100,
    /** Spacing of one device's samples, in both modes. */
    val interval: Duration = Duration.ofSeconds(10),
    val url: URI = URI("ws://localhost:8080/ws/telemetry"),
    /** Live only; `null` runs until Ctrl+C. */
    val duration: Duration? = null,
    /** Backfill only: how many days back to start. */
    val days: Int = 7,
    /** Backfill only: upper limit of messages per second over all connections. */
    val rate: Int = 2_000,
    val seed: Long = 1,
)
