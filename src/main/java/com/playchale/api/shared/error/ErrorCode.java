package com.playchale.api.shared.error;

/**
 * What kind of refusal an {@link BusinessException} is. Plain Java, so the domain can use it; only
 * {@link ApiExceptionHandler} decides which HTTP status each one becomes.
 *
 * <p>The values mirror {@code ApiErrorCode} in the web app (webapp/app/services/api.ts), so the app
 * treats an error from this API exactly like one from its mock. Keep the two lists in step.
 */
public enum ErrorCode {

	UNAUTHENTICATED("unauthenticated"),
	NOT_FOUND("not-found"),
	INVALID("invalid"),
	CONFLICT("conflict"),
	PAYMENT_FAILED("payment-failed"),
	/** Only ever sent with a generic message; the real error goes to the logs. */
	INTERNAL("internal");

	private final String value;

	ErrorCode(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

}
