package com.hosttelemetry.service.repository

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.TransientDataAccessException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException

/**
 * The ingest writer retries only Spring's transient/resource-failure exceptions, so the
 * SQLState of the driver's exception must survive Exposed's wrapping.
 */
class TranslateSqlErrorsTest {

    /** Exposed wraps the driver exception and drops its SQLState, like this wrapper. */
    private fun wrapped(cause: SQLException) = SQLException("wrapped by Exposed", null, cause)

    @Test
    fun `lost connection inside a wrapper is a resource failure, which the writer retries`() {
        assertThatThrownBy { translateSqlErrors("insert") { throw wrapped(SQLException("connection lost", "08006")) } }
            .isInstanceOf(DataAccessResourceFailureException::class.java)
    }

    @Test
    fun `pool timeout is transient`() {
        assertThatThrownBy { translateSqlErrors("insert") { throw wrapped(SQLTransientConnectionException("pool timeout")) } }
            .isInstanceOf(TransientDataAccessException::class.java)
    }

    @Test
    fun `constraint violation is not transient`() {
        assertThatThrownBy { translateSqlErrors("insert") { throw wrapped(SQLException("not null", "23502")) } }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `the result passes through when nothing fails`() {
        assertThat(translateSqlErrors("read") { 42 }).isEqualTo(42)
    }
}
