package com.example.tradingpt.domain.memo.exception;

import org.springframework.http.HttpStatus;

import com.example.tradingpt.global.exception.code.BaseCode;
import com.example.tradingpt.global.exception.code.BaseCodeInterface;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Domain Error Status Pattern
 *
 * Each domain defines its own ErrorStatus enum implementing BaseCodeInterface.
 * This provides:
 * 1. Type-safe error codes (compile-time validation)
 * 2. Consistent HTTP status mapping
 * 3. Descriptive error messages
 * 4. Domain-scoped error code namespacing
 *
 * Naming Convention: {DOMAIN}_{HTTP_STATUS}_{SEQUENCE}
 *
 * Examples across domains:
 * - AUTH_401_0   : Authentication failed
 * - USER_404_0   : User not found
 * - MEMO_404_0   : Memo not found
 * - MEMO_409_0   : Memo already exists
 * - LECTURE_404_0 : Lecture not found
 * - SUB_404_0    : Subscription not found
 */
@Getter
@AllArgsConstructor
public enum MemoErrorStatus implements BaseCodeInterface {

    // 404 Not Found
    MEMO_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMO_404_0", "Memo not found"),

    // 409 Conflict
    MEMO_ALREADY_EXISTS(HttpStatus.CONFLICT, "MEMO_409_0", "Memo already exists"),

    // 403 Forbidden
    MEMO_ACCESS_DENIED(HttpStatus.FORBIDDEN, "MEMO_403_0", "Access denied to this memo"),
    ;

    private final HttpStatus httpStatus;
    private final boolean isSuccess = false;
    private final String code;
    private final String message;

    @Override
    public BaseCode getCode() {
        return BaseCode.builder()
            .httpStatus(httpStatus)
            .isSuccess(isSuccess)
            .code(code)
            .message(message)
            .build();
    }
}
