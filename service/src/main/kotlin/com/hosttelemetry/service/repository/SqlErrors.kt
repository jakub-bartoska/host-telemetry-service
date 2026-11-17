package com.hosttelemetry.service.repository

import org.springframework.dao.DataAccessException
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.sql.SQLException

private val translator = SQLExceptionSubclassTranslator()

/**
 * Runs [block] and turns SQL errors into Spring's [DataAccessException] hierarchy, which the ingest
 * writer uses to tell transient errors (retry) from the rest (drop the batch). Exposed wraps the
 * driver's exception in `ExposedSQLException` without its SQLState, so the innermost
 * [SQLException] is translated. Called inside the repository method, because an [SQLException] is a
 * checked exception that must not leave through the transactional proxy.
 */
internal fun <T> translateSqlErrors(task: String, block: () -> T): T =
    try {
        block()
    } catch (e: SQLException) {
        val cause = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SQLException>().last()
        throw translator.translate(task, null, cause) ?: e
    }
