package com.fanshop.support.error;

import org.springframework.boot.logging.LogLevel;
import org.springframework.http.HttpStatus;

public enum ErrorType {

	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, ErrorCode.MEMBER_NOT_FOUND, "Member not found.", LogLevel.WARN),
	DUPLICATE_EMAIL(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_EMAIL, "Email already registered.", LogLevel.WARN),
	INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_PASSWORD, "Invalid password.", LogLevel.WARN),
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
