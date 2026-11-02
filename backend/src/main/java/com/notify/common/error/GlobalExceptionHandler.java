package com.notify.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * RFC 9457 Problem Details for HTTP APIs (application/problem+json).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_DOC_BASE_URL = "https://notify.dev/errors/";

    @ExceptionHandler(com.notify.ratelimit.error.RateLimitException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitException(com.notify.ratelimit.error.RateLimitException ex) {
        log.warn("Rate limit exception: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getErrorCode().name());
        problem.setType(URI.create(ERROR_DOC_BASE_URL + ex.getErrorCode().name().toLowerCase()));
        problem.setProperty("errorCode", ex.getErrorCode().name());
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("retryAfter", ex.getRetryAfterSeconds());

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        if (ex.getLimit() > 0) {
            headers.set("X-RateLimit-Limit", String.valueOf(ex.getLimit()));
            headers.set("X-RateLimit-Remaining", String.valueOf(ex.getRemaining()));
            headers.set("X-RateLimit-Reset", String.valueOf(ex.getResetSeconds()));
        }
        return new ResponseEntity<>(problem, headers, ex.getStatus());
    }

    /**
     * A webhook destination the policy refuses (/ ). It is a client error at configuration time: the tenant
     * asked for a destination Notify will not call. The reason names the rule, never a resolved address.
     */
    @ExceptionHandler(com.notify.webhook.security.DestinationRejectedException.class)
    public ResponseEntity<ProblemDetail> handleDestinationRejected(
            com.notify.webhook.security.DestinationRejectedException ex) {
        log.warn("Webhook destination rejected [{}]", ex.reason());
        ErrorCode errorCode = ErrorCode.VALIDATION_FAILED;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.getDefaultStatus(), ex.getMessage());
        problem.setTitle(errorCode.name());
        problem.setType(URI.create(ERROR_DOC_BASE_URL + errorCode.name().toLowerCase()));
        problem.setProperty("errorCode", errorCode.name());
        problem.setProperty("destinationRejectionReason", ex.reason().name());
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(errorCode.getDefaultStatus()).body(problem);
    }

    @ExceptionHandler(NotifyException.class)
    public ResponseEntity<ProblemDetail> handleNotifyException(NotifyException ex) {
        log.warn("Business exception [{}]: {}", ex.getErrorCode(), ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getErrorCode().name());
        problem.setType(URI.create(ERROR_DOC_BASE_URL + ex.getErrorCode().name().toLowerCase()));
        problem.setProperty("errorCode", ex.getErrorCode().name());
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDeniedException(org.springframework.security.access.AccessDeniedException ex) {
        log.warn("Access denied exception: {}", ex.getMessage());
        boolean isApiKey = com.notify.common.context.TenantContext.get()
                .map(p -> "API_KEY".equals(p.authType()))
                .orElse(false);

        ErrorCode code = isApiKey ? ErrorCode.INSUFFICIENT_SCOPE : ErrorCode.FORBIDDEN;
        String detail = isApiKey ? "API key lacks required scope for this operation" : "Access denied";

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatus.FORBIDDEN,
                detail);
        problem.setTitle(code.name());
        problem.setType(URI.create(ERROR_DOC_BASE_URL + code.name().toLowerCase()));
        problem.setProperty("errorCode", code.name());
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ErrorCode.VALIDATION_FAILED.getDefaultStatus(),
                "Request validation failed");
        problem.setTitle(ErrorCode.VALIDATION_FAILED.name());
        problem.setType(URI.create(ERROR_DOC_BASE_URL + "validation_failed"));
        problem.setProperty("errorCode", ErrorCode.VALIDATION_FAILED.name());
        problem.setProperty("invalidFields", fieldErrors);
        problem.setProperty("timestamp", Instant.now());

        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getDefaultStatus()).body(problem);
    }

    /**
     * Maps the active-template-name uniqueness race (partial unique index
     * {@code uq_templates_active_name}) to the frozen 422 VALIDATION_FAILED contract.
     * This is the concurrent create/rename case that slips past the service's
     * check-then-insert guard. Any other integrity violation is left as an internal
     * error so unrelated database faults are never masked as validation errors.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            org.springframework.dao.DataIntegrityViolationException ex) {
        if (violatesNamedConstraint(ex, "uq_templates_active_name")) {
            log.warn("Duplicate active template name (concurrent create/rename race): {}",
                    ex.getMostSpecificCause().getMessage());
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    ErrorCode.VALIDATION_FAILED.getDefaultStatus(),
                    "A template with this name already exists");
            problem.setTitle(ErrorCode.VALIDATION_FAILED.name());
            problem.setType(URI.create(ERROR_DOC_BASE_URL + "validation_failed"));
            problem.setProperty("errorCode", ErrorCode.VALIDATION_FAILED.name());
            problem.setProperty("timestamp", Instant.now());
            return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getDefaultStatus()).body(problem);
        }

        // Unrelated integrity violations keep the existing internal-error behavior.
        log.error("Unhandled data integrity violation", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ErrorCode.INTERNAL_ERROR.getDefaultStatus(),
                "An unexpected internal error occurred.");
        problem.setTitle(ErrorCode.INTERNAL_ERROR.name());
        problem.setType(URI.create(ERROR_DOC_BASE_URL + "internal_error"));
        problem.setProperty("errorCode", ErrorCode.INTERNAL_ERROR.name());
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getDefaultStatus()).body(problem);
    }

    /**
     * True only when the given index/constraint name appears in the Hibernate
     * ConstraintViolationException metadata or anywhere in the cause-chain messages.
     * Bounded cause walk; matches a specific named constraint only.
     */
    private static boolean violatesNamedConstraint(Throwable ex, String constraintName) {
        String needle = constraintName.toLowerCase(Locale.ROOT);
        Throwable t = ex;
        for (int depth = 0; t != null && depth < 20; t = t.getCause(), depth++) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String cn = cve.getConstraintName();
                if (cn != null && cn.toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(Exception ex) {
        log.error("Unhandled internal server error", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ErrorCode.INTERNAL_ERROR.getDefaultStatus(),
                "An unexpected internal error occurred.");
        problem.setTitle(ErrorCode.INTERNAL_ERROR.name());
        problem.setType(URI.create(ERROR_DOC_BASE_URL + "internal_error"));
        problem.setProperty("errorCode", ErrorCode.INTERNAL_ERROR.name());
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getDefaultStatus()).body(problem);
    }
}
