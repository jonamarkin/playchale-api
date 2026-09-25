package com.playchale.api.web;

/**
 * An error a user is allowed to see: a code the app can act on, and a message written for a person.
 * Anything else that goes wrong is logged and reported as a generic failure.
 *
 * <p>Throw one with the factory methods: {@code throw AppException.invalid("Pick a time in the future.")}.
 */
public final class AppException extends RuntimeException {

	private final ErrorCode code;

	private AppException(ErrorCode code, String message) {
		super(message, null, false, false);
		this.code = code;
	}

	public ErrorCode code() {
		return code;
	}

	/** A request that doesn't make sense, with a message saying why. */
	public static AppException invalid(String message) {
		return new AppException(ErrorCode.INVALID, message);
	}

	/** The thing asked for doesn't exist. */
	public static AppException notFound(String message) {
		return new AppException(ErrorCode.NOT_FOUND, message);
	}

	/** Allowed in general but not right now, like joining a full game. */
	public static AppException conflict(String message) {
		return new AppException(ErrorCode.CONFLICT, message);
	}

	/** The request needs a signed-in player. */
	public static AppException unauthenticated(String message) {
		return new AppException(ErrorCode.UNAUTHENTICATED, message);
	}

	/** The payment provider declined. */
	public static AppException paymentFailed(String message) {
		return new AppException(ErrorCode.PAYMENT_FAILED, message);
	}

}
