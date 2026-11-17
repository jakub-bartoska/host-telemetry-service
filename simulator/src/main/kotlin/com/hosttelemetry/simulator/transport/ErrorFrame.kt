package com.hosttelemetry.simulator.transport

/** The service's reply to a rejected message: `{"type":"error","reason":…}`. */
data class ErrorFrame(val type: String? = null, val reason: String? = null)
