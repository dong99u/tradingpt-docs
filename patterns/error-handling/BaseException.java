package com.example.tradingpt.global.exception;

import com.example.tradingpt.global.exception.code.BaseCode;
import com.example.tradingpt.global.exception.code.BaseCodeInterface;

/**
 * Base Exception - Parent of all domain-specific exceptions.
 *
 * Features:
 * 1. Typed error codes via BaseCodeInterface (HTTP status + code + message)
 * 2. Optional custom message for external system errors (e.g., PG gateway)
 * 3. Meaningful message in RuntimeException for logging
 *
 * Usage:
 *   throw new MemoException(MemoErrorStatus.MEMO_NOT_FOUND);
 *   throw new PaymentException(PaymentErrorStatus.PG_ERROR, pgErrorMessage);
 */
public class BaseException extends RuntimeException {

    private final BaseCodeInterface errorCode;
    private final String customMessage;

    /**
     * Standard constructor - uses errorCode's default message.
     */
    public BaseException(BaseCodeInterface errorCode) {
        super(errorCode.getCode().getMessage());
        this.errorCode = errorCode;
        this.customMessage = null;
    }

    /**
     * Custom message constructor - for external system error details.
     * The custom message is returned to the client instead of the default.
     */
    public BaseException(BaseCodeInterface errorCode, String customMessage) {
        super(customMessage != null ? customMessage : errorCode.getCode().getMessage());
        this.errorCode = errorCode;
        this.customMessage = customMessage;
    }

    public BaseCode getErrorCode() {
        return errorCode.getCode();
    }

    public BaseCodeInterface getErrorCodeInterface() {
        return errorCode;
    }

    public String getCustomMessage() {
        return customMessage;
    }

    public boolean hasCustomMessage() {
        return customMessage != null && !customMessage.isEmpty();
    }
}
