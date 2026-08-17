package com.fanshop.support.error;

import org.springframework.boot.logging.LogLevel;
import org.springframework.http.HttpStatus;

public enum ErrorType {

    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, ErrorCode.PAYMENT_NOT_FOUND, "Payment not found.", LogLevel.WARN),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, ErrorCode.PAYMENT_AMOUNT_MISMATCH, "Payment amount mismatch.",
            LogLevel.WARN),
    DEFAULT_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.E500, "An unexpected error has occurred.",
            LogLevel.ERROR);

    private final HttpStatus status;

    private final ErrorCode code;

    private final String message;

    private final LogLevel logLevel;

    ErrorType(HttpStatus status, ErrorCode code, String message, LogLevel logLevel) {
        this.status = status;
        this.code = code;
        this.message = message;
        this.logLevel = logLevel;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCode getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public LogLevel getLogLevel() {
        return logLevel;
    }

}
