package com.miaa.cas.common.error;

import com.miaa.cas.audit.SecurityEventService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Central translation of exceptions into problem responses. Never returns stack traces, SQL or
 * internal identifiers; unexpected errors are logged with the correlation ID and returned as a
 * generic message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final SecurityEventService securityEvents;

    public GlobalExceptionHandler(SecurityEventService securityEvents) {
        this.securityEvents = securityEvents;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail body = ProblemFactory.create(ex.code(), ex.getMessage(), ex.properties());
        return ResponseEntity.status(ex.code().status()).body(body);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return handleApi(ApiException.staleVersion());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return handleApi(new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                "The request conflicts with existing data (for example a duplicate code or username)."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        securityEvents.accessDenied(request.getDescription(false));
        return handleApi(new ApiException(ErrorCode.ACCESS_DENIED, "You do not have permission to perform this action."));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return handleApi(new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return handleApi(new ApiException(ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Quote the correlation ID when contacting support."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        ProblemDetail body = ProblemFactory.create(ErrorCode.VALIDATION_FAILED, "The request contains invalid fields.",
                Map.of("errors", errors));
        return ResponseEntity.badRequest().body(body);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        // Framework-level errors (malformed JSON, unsupported media type, missing params ...).
        ErrorCode code = statusCode.is4xxClientError() ? ErrorCode.VALIDATION_FAILED : ErrorCode.INTERNAL_ERROR;
        ProblemDetail problem = ProblemFactory.create(code, statusCode.is4xxClientError()
                ? "The request could not be processed: " + safeReason(ex) : "An unexpected error occurred.");
        problem.setStatus(statusCode.value());
        return ResponseEntity.status(statusCode).headers(headers).body(problem);
    }

    private static String safeReason(Exception ex) {
        return ex.getClass().getSimpleName().replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
    }
}
