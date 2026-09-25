package com.playchale.api.web;

import com.fasterxml.jackson.annotation.JsonValue;
import org.springframework.http.HttpStatus;

/**
 * Mirrors {@code ApiErrorCode} in the web app (webapp/app/services/api.ts), so the app treats an
 * error from this API exactly like one from its mock. Keep the two lists in step.
 */
public enum ErrorCode {

	UNAUTHENTICATED("unauthenticated", HttpStatus.UNAUTHORIZED),
	NOT_FOUND("not-found", HttpStatus.NOT_FOUND),
	INVALID("invalid", HttpStatus.UNPROCESSABLE_CONTENT),
	CONFLICT("conflict", HttpStatus.CONFLICT),
	PAYMENT_FAILED("payment-failed", HttpStatus.PAYMENT_REQUIRED),
	/** Only ever sent with a generic message; the real error goes to the logs. */
	INTERNAL("internal", HttpStatus.INTERNAL_SERVER_ERROR);

	private final String value;

	private final HttpStatus status;

	ErrorCode(String value, HttpStatus status) {
		this.value = value;
		this.status = status;
	}

	@JsonValue
	public String value() {
		return value;
	}

	public HttpStatus status() {
		return status;
	}

}
