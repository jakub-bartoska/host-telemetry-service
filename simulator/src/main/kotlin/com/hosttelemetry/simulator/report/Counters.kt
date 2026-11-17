package com.hosttelemetry.simulator.report

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the run did so far, shared by all connections; read by [StatusReporter]. */
class Counters {
    val openConnections = AtomicLong()
    val sent = AtomicLong()
    val errorFrames = AtomicLong()
    val connectionErrors = AtomicLong()
    private val seenReasons = ConcurrentHashMap.newKeySet<String>()

    /** Counts an error frame; the first frame of each reason is also printed, so a rejected run is noticed at once. */
    fun errorFrame(reason: String) {
        errorFrames.incrementAndGet()
        if (seenReasons.add(reason)) println("First error frame from the service: reason=$reason")
    }

    fun reasons(): Set<String> = seenReasons.toSortedSet()
}
