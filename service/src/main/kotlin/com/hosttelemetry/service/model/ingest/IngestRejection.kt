package com.hosttelemetry.service.model.ingest

import java.time.Instant

/**
 * One row of `ingest_rejections`: how many messages of one device failed for one reason between
 * [windowStart] and [windowEnd].
 *
 * [deviceId] is `null` when the messages carried no valid device id. [reason] is a
 * [RejectReason.code], or [DISCARDED_STATS] for messages that were accepted without the stats
 * entries in [discardedKeys]. [sample] is the last such message of the window, shortened.
 */
data class IngestRejection(
    val windowStart: Instant,
    val windowEnd: Instant,
    val deviceId: String?,
    val reason: String,
    val messageCount: Long,
    val discardedKeys: List<String>,
    val sample: String,
) {
    companion object {
        const val DISCARDED_STATS = "discarded_stats"
    }
}
