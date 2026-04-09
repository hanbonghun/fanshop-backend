package com.fanshop.support.error;

import org.springframework.boot.logging.LogLevel;
import org.springframework.http.HttpStatus;

public enum ErrorType {

    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, ErrorCode.PRODUCT_NOT_FOUND, "Product not found.", LogLevel.WARN),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock.", LogLevel.WARN),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, ErrorCode.ORDER_NOT_FOUND, "Order not found.", LogLevel.WARN), DEFAULT_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.E500, "An unexpected error has occurred.", LogLevel.ERROR);

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
