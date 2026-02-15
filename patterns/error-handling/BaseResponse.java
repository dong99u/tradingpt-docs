package com.example.tradingpt.global.common;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.example.tradingpt.global.exception.code.BaseCodeInterface;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Standard API Response Wrapper
 *
 * All API responses follow this structure for consistency:
 * {
 *   "timestamp": "2025-01-15T10:30:00",
 *   "code": "COMMON200",
 *   "message": "Request successful",
 *   "result": { ... }
 * }
 *
 * Factory methods:
 * - onSuccess(data)       → 200 response
 * - onSuccessCreate(data) → 201 response
 * - onSuccessDelete(null) → 202 response
 * - onFailure(code, data) → error response with typed error code
 */
@Getter
@AllArgsConstructor
@JsonPropertyOrder({"timestamp", "code", "message", "result"})
public class BaseResponse<T> {

    private final LocalDateTime timestamp = LocalDateTime.now();
    private final String code;
    private final String message;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private T result;

    /** 200 OK - Standard success */
    public static <T> BaseResponse<T> onSuccess(T result) {
        return new BaseResponse<>("COMMON200", "Request successful", result);
    }

    /** 201 Created - Resource creation success */
    public static <T> BaseResponse<T> onSuccessCreate(T result) {
        return new BaseResponse<>("COMMON201", "Created successfully", result);
    }

    /** 202 Accepted - Deletion success */
    public static <T> BaseResponse<T> onSuccessDelete(T result) {
        return new BaseResponse<>("COMMON202", "Deleted successfully", result);
    }

    /** Generic success with custom code */
    public static <T> BaseResponse<T> of(BaseCodeInterface code, T result) {
        return new BaseResponse<>(
            code.getCode().getCode(), code.getCode().getMessage(), result);
    }

    /** Error response with typed error code */
    public static <T> BaseResponse<T> onFailure(BaseCodeInterface code, T result) {
        return new BaseResponse<>(
            code.getCode().getCode(), code.getCode().getMessage(), result);
    }
}
