package com.hosttelemetry.service.web.controller

import com.hosttelemetry.service.TimescaleContainerConfig
import com.hosttelemetry.service.core.ingest.RejectionLog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real WebSocket client → handler → queue → writer → TimescaleDB. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TimescaleContainerConfig::class)
class IngestIntegrationTest(
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val rejectionLog: RejectionLog,
    @LocalServerPort private val port: Int,
) {

    private val frames = LinkedBlockingQueue<String>()
    private val session: WebSocketSession = StandardWebSocketClient()
        .execute(
            object : TextWebSocketHandler() {
                override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
                    frames.add(message.payload)
                }
            },
            "ws://localhost:$port/ws/telemetry",
        )
        .get(5, TimeUnit.SECONDS)

    // Unique per test, so tests sharing the database do not see each other's rows.
    private val deviceId = "it-${UUID.randomUUID()}"
    private val timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    @AfterEach
    fun close() {
        session.close()
    }

    private fun statsMessage(at: Instant = timestamp) =
        """{"type":"stats","deviceId":"$deviceId","timestamp":"$at","stats":{"cpuUtilPct":37.5,"memUtilPct":62.1}}"""

    private fun rows(): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT ts, metric, value FROM telemetry WHERE device_id = ? ORDER BY ts, metric", deviceId)

    private fun awaitRowCount(expected: Int): List<Map<String, Any?>> {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (rows().size < expected && System.nanoTime() < deadline) Thread.sleep(50)
        return rows()
    }

    private fun rejections(): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT reason, message_count, array_to_string(discarded_keys, ',') AS discarded_keys, sample
            FROM ingest_rejections WHERE device_id = ?
            """.trimIndent(),
            deviceId,
        )

    /** Writes the current window now instead of waiting for `rejection-flush-interval`. */
    private fun flushedRejections(): List<Map<String, Any?>> {
        rejectionLog.flush()
        // The periodic flush may have taken the window just before; then its transaction is still committing.
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (rejections().isEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
        return rejections()
    }

    @Test
    fun `a valid message is written as one row per stat`() {
        session.sendMessage(TextMessage(statsMessage()))

        val rows = awaitRowCount(2)

        assertThat(rows.map { it["metric"] to it["value"] })
            .containsExactly("cpuUtilPct" to 37.5, "memUtilPct" to 62.1)
    }

    @Test
    fun `the same message sent twice is stored once`() {
        session.sendMessage(TextMessage(statsMessage()))
        session.sendMessage(TextMessage(statsMessage()))
        // The single writer keeps queue order, so once this later message is stored, both copies were processed.
        session.sendMessage(TextMessage(statsMessage(at = timestamp.plusSeconds(10))))

        val rows = awaitRowCount(4)

        assertThat(rows.map { it["ts"] }.distinct()).hasSize(2)
        assertThat(rows).hasSize(4)
    }

    @Test
    fun `an invalid message gets an error frame and the connection stays open`() {
        session.sendMessage(TextMessage("""{"type":"stats","deviceId":"bad id","timestamp":"$timestamp","stats":{"a":1}}"""))

        assertThat(frames.poll(5, TimeUnit.SECONDS)).isEqualTo("""{"type":"error","reason":"invalid_device_id"}""")
        assertThat(session.isOpen).isTrue()

        session.sendMessage(TextMessage(statsMessage()))
        assertThat(awaitRowCount(2)).hasSize(2)
    }

    @Test
    fun `a rejected message is recorded with its device and reason`() {
        val tooOld = statsMessage(at = timestamp.minus(8, ChronoUnit.DAYS))
        session.sendMessage(TextMessage(tooOld))
        session.sendMessage(TextMessage(tooOld))
        repeat(2) { assertThat(frames.poll(5, TimeUnit.SECONDS)).isEqualTo("""{"type":"error","reason":"timestamp_too_old"}""") }

        assertThat(flushedRejections()).containsExactly(
            mapOf("reason" to "timestamp_too_old", "message_count" to 2L, "discarded_keys" to "", "sample" to tooOld),
        )
    }

    @Test
    fun `discarded stats entries are recorded with their keys`() {
        val message =
            """{"type":"stats","deviceId":"$deviceId","timestamp":"$timestamp","stats":{"cpuUtilPct":37.5,"temperatureC":"48.1"}}"""
        session.sendMessage(TextMessage(message))
        // No frame answers an accepted message; the stored row shows the handler is done with it.
        assertThat(awaitRowCount(1)).hasSize(1)

        assertThat(flushedRejections()).containsExactly(
            mapOf("reason" to "discarded_stats", "message_count" to 1L, "discarded_keys" to "temperatureC", "sample" to message),
        )
    }
}
