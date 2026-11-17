package com.hosttelemetry.service.repository.charts

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Function
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import java.time.Duration
import java.time.OffsetDateTime

/**
 * TimescaleDB `time_bucket(INTERVAL '<n> seconds', <ts>)`, which Exposed has no built-in for.
 * The width is a [Duration] from code (a chart range), rendered as whole seconds, so no request
 * text can reach the SQL.
 */
class TimeBucket(private val width: Duration, private val ts: Expression<OffsetDateTime>) :
    Function<OffsetDateTime>(JavaOffsetDateTimeColumnType()) {

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("time_bucket(INTERVAL '")
        queryBuilder.append(width.seconds.toString())
        queryBuilder.append(" seconds', ")
        queryBuilder.append(ts)
        queryBuilder.append(")")
    }
}
