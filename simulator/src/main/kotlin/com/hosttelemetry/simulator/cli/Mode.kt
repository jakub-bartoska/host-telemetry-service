package com.hosttelemetry.simulator.cli

enum class Mode {
    LIVE,
    BACKFILL,
    ;

    /** The `--mode` value and the prefix of the status lines. */
    val label: String = name.lowercase()
}
