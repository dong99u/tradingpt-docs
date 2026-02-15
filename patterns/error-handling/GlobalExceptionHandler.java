package com.example.tradingpt.global.exception;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.example.tradingpt.global.common.BaseResponse;
import com.example.tradingpt.global.exception.code.BaseCodeInterface;
import com.example.tradingpt.global.exception.code.GlobalErrorStatus;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Global Exception Handler - 13-Layer Catch Hierarchy
 *
 * Extends ResponseEntityExceptionHandler for Spring MVC exception coverage.
 * Each handler produces a standardized BaseResponse with appropriate HTTP status.
 *
 * Security: Production error messages hide internal details.
 * Monitoring: Integrates with Sentry for 5xx and unexpected errors.
 */
@Slf4j
@RestControllerAdvice(annotations = {RestController.class})
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * Layer 1: Domain/Business exceptions (BaseException hierarchy).
     * Each domain defines its own ErrorStatus enum with HTTP status and code.
     */
    @ExceptionHandler(value = BaseException.class)
    public ResponseEntity<BaseResponse<String>> handleRestApiException(BaseException e) {
        BaseCodeInterface errorCode = e.getErrorCodeInterface();
        log.error("[Domain Exception] {}", e.getMessage(), e);

        if (e.hasCustomMessage()) {
            return handleExceptionInternal(errorCode, e.getCustomMessage());
        }
        return handleExceptionInternal(errorCode);
    }

    /**
     * Layer 6: Database constraint violations (duplicate entry, FK violations).
     * Hides sensitive DB details from the response.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<BaseResponse<String>> handleDataIntegrityViolation(
            DataIntegrityViolationException e) {
        log.error("[DB Constraint] {}", e.getMessage());
        String message = "Data integrity constraint violated.";
        if (e.getMessage() != null && e.getMessage().contains("Duplicate entry")) {
            message = "Duplicate data exists.";
        }
        return handleExceptionInternal(GlobalErrorStatus.CONFLICT, message);
    }

    /**
     * Layer 7: SQL exceptions - critical database errors.
     */
    @ExceptionHandler(SQLException.class)
    public ResponseEntity<BaseResponse<String>> handleSQLException(SQLException e) {
        log.error("[SQL Error] {}", e.getMessage(), e);
        return handleExceptionInternal(GlobalErrorStatus.DATABASE_ERROR);
    }

    /**
     * Layer 8: Optimistic locking conflicts.
     * Occurs when concurrent modifications target the same entity version.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<BaseResponse<String>> handleOptimisticLockException(
            ObjectOptimisticLockingFailureException e) {
        log.warn("[Optimistic Lock] Entity: {}, ID: {}",
            e.getPersistentClassName(), e.getIdentifier());
        return handleExceptionInternal(GlobalErrorStatus.CONFLICT,
            "Concurrent modification detected. Please retry.");
    }

    /**
     * Layer 9: Bean Validation constraint violations.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<BaseResponse<String>> handleConstraintViolation(
            ConstraintViolationException e) {
        String errors = e.getConstraintViolations().stream()
            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
            .collect(Collectors.joining(", "));
        return handleExceptionInternal(GlobalErrorStatus.VALIDATION_ERROR, errors);
    }

    /**
     * Layer 10: @Valid annotation failures with field-level error details.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(fieldError -> {
            String fieldName = fieldError.getField();
            String errorMessage = Optional.ofNullable(fieldError.getDefaultMessage())
                .orElse("Invalid value");
            errors.merge(fieldName, errorMessage, (old, nw) -> old + ", " + nw);
        });
        log.error("[Validation] {}", errors);
        return handleExceptionInternalArgs(GlobalErrorStatus.VALIDATION_ERROR, errors);
    }

    /**
     * Layer 13: Catch-all safety net for unexpected exceptions.
     * Hides internal error details in production.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<String>> handleException(Exception e) {
        log.error("[Unexpected Error] {}", e.getMessage(), e);
        return handleExceptionInternal(GlobalErrorStatus._INTERNAL_SERVER_ERROR,
            "An internal server error occurred. Please contact support.");
    }

    // === Internal helper methods ===

    private ResponseEntity<BaseResponse<String>> handleExceptionInternal(
            BaseCodeInterface errorCode) {
        return ResponseEntity
            .status(errorCode.getCode().getHttpStatus())
            .body(BaseResponse.onFailure(errorCode, null));
    }

    private ResponseEntity<BaseResponse<String>> handleExceptionInternal(
            BaseCodeInterface errorCode, String customMessage) {
        return ResponseEntity
            .status(errorCode.getCode().getHttpStatus())
            .body(BaseResponse.onFailure(errorCode, customMessage));
    }

    private ResponseEntity<Object> handleExceptionInternalArgs(
            BaseCodeInterface errorCode, Map<String, String> errorArgs) {
        return ResponseEntity
            .status(errorCode.getCode().getHttpStatus())
            .body(BaseResponse.onFailure(errorCode, errorArgs));
    }
}
