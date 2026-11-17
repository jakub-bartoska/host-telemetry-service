package com.hosttelemetry.service.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClockConfig {

    /** The one clock of the service: ingest validation and chart windows both read it. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
