package com.hosttelemetry.service.web.exception

import org.springframework.beans.TypeMismatchException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Answers request errors with RFC 9457 `application/problem+json` bodies. The base class does that for
 * every standard Spring MVC error; the two overrides only replace its generic `detail` with one that
 * names the offending parameter.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    /** A path variable or request parameter violates its validation annotation. */
    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        ex.body.detail = ex.parameterValidationResults.joinToString { "invalid ${it.methodParameter.parameterName}" }
        return super.handleHandlerMethodValidationException(ex, headers, status, request)
    }

    /** A request value cannot be converted to the parameter's type, such as an unknown chart range. */
    override fun handleTypeMismatch(
        ex: TypeMismatchException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val name = (ex as? MethodArgumentTypeMismatchException)?.name ?: ex.propertyName
        val body = ProblemDetail.forStatusAndDetail(status, "invalid $name: ${ex.mostSpecificCause.message}")
        return handleExceptionInternal(ex, body, headers, status, request)
    }
}
