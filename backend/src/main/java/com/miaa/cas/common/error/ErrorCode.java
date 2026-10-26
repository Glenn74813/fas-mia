package com.miaa.cas.common.error;

import org.springframework.http.HttpStatus;

/** Stable, documented error codes returned in the {@code code} property of every problem response. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    FILE_REJECTED(HttpStatus.BAD_REQUEST),
    ACCOUNTING_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST),
    SEGREGATION_OF_DUTIES(HttpStatus.FORBIDDEN),
    PASSWORD_POLICY_VIOLATION(HttpStatus.BAD_REQUEST),
    INVALID_STATE(HttpStatus.CONFLICT),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    AUTH_MFA_INVALID(HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    AUTH_SESSION_INVALID(HttpStatus.UNAUTHORIZED),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    SELF_MODIFICATION_FORBIDDEN(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    SOD_CONFLICT(HttpStatus.CONFLICT),
    MFA_ALREADY_ENROLLED(HttpStatus.CONFLICT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
