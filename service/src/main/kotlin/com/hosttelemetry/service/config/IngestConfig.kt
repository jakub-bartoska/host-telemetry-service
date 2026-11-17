package com.hosttelemetry.service.config

import com.hosttelemetry.service.core.ingest.IngestMetrics
import com.hosttelemetry.service.core.ingest.MessageValidator
import com.hosttelemetry.service.core.ingest.TelemetryJson
import com.hosttelemetry.service.core.ingest.TelemetryQueue
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(IngestProperties::class)
class IngestConfig {

    @Bean
    fun telemetryJson(): TelemetryJson = TelemetryJson()

    @Bean
    fun messageValidator(json: TelemetryJson): MessageValidator = MessageValidator(json)

    @Bean
    fun telemetryQueue(properties: IngestProperties, metrics: IngestMetrics): TelemetryQueue =
        TelemetryQueue(properties.queueCapacity, metrics)
}
