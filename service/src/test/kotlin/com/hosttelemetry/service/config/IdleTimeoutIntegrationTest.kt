package com.hosttelemetry.service.config

import com.hosttelemetry.service.TimescaleContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A device that sends nothing is disconnected by the server after `session-idle-timeout`; one that sends stays connected. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["telemetry.ingest.session-idle-timeout=2s"],
)
@Import(TimescaleContainerConfig::class)
class IdleTimeoutIntegrationTest(@LocalServerPort private val port: Int) {

    private val idleTimeout = Duration.ofSeconds(2)

    // Tomcat checks expiry in its background thread about every 10 s, so allow well beyond the 2 s timeout.
    private val closeDeadline = Duration.ofSeconds(30)

    private fun connect(closed: CompletableFuture<CloseStatus> = CompletableFuture()): WebSocketSession =
        StandardWebSocketClient()
            .execute(
                object : TextWebSocketHandler() {
                    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
                        closed.complete(status)
                    }
                },
                "ws://localhost:$port/ws/telemetry",
            )
            .get(5, TimeUnit.SECONDS)

    private val deviceId = "it-${UUID.randomUUID()}"

    private fun statsMessage() = TextMessage(
        """{"type":"stats","deviceId":"$deviceId","timestamp":"${Instant.now().truncatedTo(ChronoUnit.SECONDS)}","stats":{"cpuUtilPct":1.0}}""",
    )

    @Test
    fun `a silent connection is closed by the server while a sending one stays open`() {
        val silentClosed = CompletableFuture<CloseStatus>()
        val silent = connect(silentClosed)
        val active = connect()
        val connectedAt = System.nanoTime()
        assertThat(silent.isOpen).isTrue()

        // The active device keeps sending until the server has closed the silent one.
        var status: CloseStatus? = null
        while (status == null) {
            check(System.nanoTime() - connectedAt < closeDeadline.toNanos()) { "silent connection not closed within $closeDeadline" }
            active.sendMessage(statsMessage())
            status = try {
                silentClosed.get(500, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                null
            }
        }
        val openFor = Duration.ofNanos(System.nanoTime() - connectedAt)

        assertThat(status.code).isEqualTo(CloseStatus.GOING_AWAY.code)
        assertThat(openFor).isGreaterThanOrEqualTo(idleTimeout)
        assertThat(silent.isOpen).isFalse()
        // The same expiry check found the active session newer than the timeout and left it open.
        assertThat(active.isOpen).isTrue()
        active.close()
    }
}
