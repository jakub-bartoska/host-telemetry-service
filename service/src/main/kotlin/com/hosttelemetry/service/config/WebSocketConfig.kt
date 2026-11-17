package com.hosttelemetry.service.config

import com.hosttelemetry.service.web.controller.TelemetryWebSocketHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean

@Configuration
@EnableWebSocket
class WebSocketConfig(private val handler: TelemetryWebSocketHandler) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws/telemetry")
    }

    /**
     * Closes sessions that receive nothing for `session-idle-timeout`. Tomcat's default is 0 (never),
     * so a dead or half-open connection would otherwise be noticed only on an I/O error. Tomcat checks
     * expiry in its background thread, so a session may stay open up to ~10 s longer than the timeout.
     */
    @Bean
    fun webSocketContainer(properties: IngestProperties) = ServletServerContainerFactoryBean().apply {
        setMaxSessionIdleTimeout(properties.sessionIdleTimeout.toMillis())
    }
}
