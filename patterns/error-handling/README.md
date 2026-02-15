# Error Handling Architecture

## Overview

This project implements a centralized, layered exception handling system with:

1. **Base exception hierarchy** - All domain exceptions extend `BaseException`
2. **Domain error codes** - Typed enums with HTTP status, code, and message
3. **Global exception handler** - 13-layer catch hierarchy for comprehensive coverage
4. **Standardized response** - `BaseResponse<T>` wrapper for all API responses

## Exception Hierarchy

```
RuntimeException
└── BaseException
    ├── AuthException
    ├── UserException
    ├── MemoException
    ├── LectureException
    ├── SubscriptionException
    ├── PaymentMethodException
    ├── ConsultationException
    ├── FeedbackRequestException
    └── ... (18 domain-specific exceptions)
```

## Error Code Pattern

Each domain defines its own `ErrorStatus` enum implementing `BaseCodeInterface`:

```java
public enum MemoErrorStatus implements BaseCodeInterface {
    MEMO_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMO_404_0", "Memo not found"),
    MEMO_ALREADY_EXISTS(HttpStatus.CONFLICT, "MEMO_409_0", "Memo already exists"),
    MEMO_ACCESS_DENIED(HttpStatus.FORBIDDEN, "MEMO_403_0", "Access denied");
}
```

**Naming convention**: `{DOMAIN}_{HTTP_STATUS}_{SEQUENCE}`

## GlobalExceptionHandler Layers

| Layer | Exception Type | HTTP Status |
|-------|---------------|-------------|
| 1 | `BaseException` (domain) | Varies by error code |
| 2 | `HttpMessageNotReadableException` | 400 |
| 3 | `HttpMediaTypeNotSupportedException` | 415 |
| 4 | `MissingServletRequestParameterException` | 400 |
| 5 | `MaxUploadSizeExceededException` | 400 |
| 6 | `DataIntegrityViolationException` | 409 |
| 7 | `SQLException` | 500 |
| 8 | `ObjectOptimisticLockingFailureException` | 409 |
| 9 | `ConstraintViolationException` | 400 |
| 10 | `MethodArgumentTypeMismatchException` | 400 |
| 11 | `MethodArgumentNotValidException` | 400 |
| 12 | `NoHandlerFoundException` | 404 |
| 13 | `Exception` (catch-all safety net) | 500 |

## Response Format

### Success
```json
{
  "timestamp": "2025-01-15T10:30:00",
  "code": "COMMON200",
  "message": "Request successful",
  "result": { ... }
}
```

### Error
```json
{
  "timestamp": "2025-01-15T10:30:00",
  "code": "MEMO_404_0",
  "message": "Memo not found",
  "result": null
}
```

### Validation Error (field-level details)
```json
{
  "timestamp": "2025-01-15T10:30:00",
  "code": "COMMON_400_5",
  "message": "Validation failed",
  "result": {
    "title": "Title is required",
    "content": "Content must not exceed 5000 characters"
  }
}
```

## Key Files

| File | Description |
|------|-------------|
| `GlobalExceptionHandler.java` | 13-layer centralized exception handler |
| `BaseException.java` | Base class for all domain exceptions |
| `BaseResponse.java` | Standard API response wrapper |
| `DomainErrorStatus.java` | Example ErrorStatus enum pattern |
