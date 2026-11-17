package com.hosttelemetry.service.web.controller

import com.hosttelemetry.service.core.ingest.IngestMetrics
import com.hosttelemetry.service.core.ingest.MessageValidator
import com.hosttelemetry.service.core.ingest.RejectionLog
import com.hosttelemetry.service.core.ingest.TelemetryJson
import com.hosttelemetry.service.core.ingest.TelemetryQueue
import com.hosttelemetry.service.model.ingest.RejectReason
import com.hosttelemetry.service.model.ingest.ValidationResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.io.IOException
import java.time.Clock

/**
 * `ws://…/ws/telemetry`: parses, validates and enqueues; never touches the database.
 * A rejected message gets an error frame and the connection stays open. Rejections and discarded
 * stats entries are also handed to the [RejectionLog], which records the device they came from.
 */
@Component
class TelemetryWebSocketHandler(
    private val validator: MessageValidator,
    private val json: TelemetryJson,
    private val queue: TelemetryQueue,
    private val metrics: IngestMetrics,
    private val rejectionLog: RejectionLog,
    private val clock: Clock,
) : TextWebSocketHandler() {

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        metrics.received.increment()
        val payload = message.payload
        when (val result = validator.validate(payload, clock.instant())) {
            is ValidationResult.Accepted -> {
                if (result.discardedKeys.isNotEmpty()) {
                    metrics.discardedStats.increment(result.discardedKeys.size.toDouble())
                    rejectionLog.discarded(result.message.deviceId, result.discardedKeys, payload)
                }
                // A full queue is load shedding: counted in telemetry.messages.dropped, not reported per message.
                queue.offer(result.message)
            }
            is ValidationResult.Rejected -> reject(session, result.reason, result.deviceId, payload)
        }
    }

    override fun handleBinaryMessage(session: WebSocketSession, message: BinaryMessage) {
        metrics.received.increment()
        reject(session, RejectReason.INVALID_JSON, deviceId = null, payload = "<binary frame, ${message.payloadLength} bytes>")
    }

    private fun reject(session: WebSocketSession, reason: RejectReason, deviceId: String?, payload: String) {
        metrics.rejected(reason).increment()
        rejectionLog.rejected(reason, deviceId, payload)
        // DEBUG only: the counter and the rejection log are the signal, and a misconfigured fleet would
        // otherwise log every message.
        log.debug("Rejected message on session {}: {}", session.id, reason.code)
        try {
            session.sendMessage(TextMessage(json.errorFrame(reason.code)))
        } catch (e: IOException) {
            log.debug("Could not send error frame to session {}", session.id, e)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(TelemetryWebSocketHandler::class.java)
    }
}
