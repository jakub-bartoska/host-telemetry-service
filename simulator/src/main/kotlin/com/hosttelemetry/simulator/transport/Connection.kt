package com.hosttelemetry.simulator.transport

import com.hosttelemetry.simulator.report.Counters
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One WebSocket connection (JDK `java.net.http.WebSocket`). Counts sent messages, error frames from the
 * service and unexpected closes; [onLost] runs once when the connection drops without us closing it.
 */
class Connection private constructor(
    private val counters: Counters,
    private val onLost: () -> Unit,
) : WebSocket.Listener {

    @Volatile
    private var socket: WebSocket? = null
    private val closed = AtomicBoolean(false)
    private val closingByUs = AtomicBoolean(false)
    private val closedByPeer = CompletableFuture<Unit>()

    // Only touched from the listener callbacks, which the JDK calls one at a time per connection.
    private val incoming = StringBuilder()

    val isOpen: Boolean
        get() = socket != null && !closed.get()

    /** Sends one message; the future fails if the connection is gone, and the connection is then dropped. */
    fun send(text: String): CompletableFuture<*> {
        val ws = socket.takeIf { isOpen } ?: return CompletableFuture.failedFuture<Unit>(IllegalStateException("not open"))
        val sending = try {
            ws.sendText(text, true)
        } catch (e: IllegalStateException) {
            // The previous send has not finished: the connection is stuck.
            CompletableFuture.failedFuture(e)
        }
        return sending.whenComplete { _, error ->
            when (error) {
                null -> counters.sent.incrementAndGet()
                else -> drop()
            }
        }
    }

    /**
     * Normal close that does not trigger [onLost]. Completes when the service has answered the close
     * (so every error frame it sent before has been counted) or after a short timeout; it never fails.
     */
    fun closeAsync(): CompletableFuture<*> {
        closingByUs.set(true)
        val ws = socket ?: return CompletableFuture.completedFuture(Unit)
        return ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .thenCompose { closedByPeer }
            .orTimeout(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            .handle { _, _ ->
                ws.abort()
                markClosed()
            }
    }

    private fun drop() {
        socket?.abort()
        markClosed()
    }

    override fun onOpen(webSocket: WebSocket) {
        socket = webSocket
        counters.openConnections.incrementAndGet()
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        incoming.append(data)
        if (last) {
            Messages.errorReason(incoming.toString())?.let(counters::errorFrame)
            incoming.setLength(0)
        }
        webSocket.request(1)
        return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        markClosed()
        closedByPeer.complete(Unit)
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        markClosed()
    }

    private fun markClosed() {
        val wasOpen = socket != null
        if (!closed.compareAndSet(false, true) || !wasOpen) return
        counters.openConnections.decrementAndGet()
        if (!closingByUs.get()) {
            counters.connectionErrors.incrementAndGet()
            onLost()
        }
    }

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val PROBE_RETRY: Duration = Duration.ofSeconds(2)

        /** A failed attempt is counted as a connection error and fails the future. */
        fun open(client: HttpClient, url: URI, counters: Counters, onLost: () -> Unit = {}): CompletableFuture<Connection> {
            val connection = Connection(counters, onLost)
            return client.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .buildAsync(url, connection)
                .thenApply { connection }
                .whenComplete { _, error -> if (error != null) counters.connectionErrors.incrementAndGet() }
        }

        /**
         * At startup the service may still be booting (Compose starts it at the same time, without a
         * healthcheck), so retry a single probe connection for up to [timeout].
         */
        fun waitForService(client: HttpClient, url: URI, timeout: Duration): Boolean {
            val deadline = System.nanoTime() + timeout.toNanos()
            while (System.nanoTime() < deadline) {
                try {
                    // The probe gets its own counters, so it does not show up in the run's statistics.
                    open(client, url, Counters()).get().closeAsync().join()
                    return true
                } catch (e: ExecutionException) {
                    println("Service not reachable at $url yet (${rootMessage(e)}), retrying")
                    Thread.sleep(PROBE_RETRY)
                }
            }
            return false
        }

        private fun rootMessage(error: Throwable): String {
            val root = generateSequence(error) { it.cause }.last()
            return "${root.javaClass.simpleName}: ${root.message}"
        }
    }
}
