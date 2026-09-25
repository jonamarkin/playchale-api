package com.playchale.api.shared.error;

/**
 * An error a user is allowed to see: a code the app can act on, and a message written for a person.
 * Anything else that goes wrong is logged and reported as a generic failure.
 *
 * <p>Throw one with the factory methods: {@code throw BusinessException.invalid("Pick a time in the future.")}.
 */
public final class BusinessException extends RuntimeException {

	private final ErrorCode code;

	private BusinessException(ErrorCode code, String message) {
		super(message, null, false, false);
		this.code = code;
	}

	public ErrorCode code() {
		return code;
	}

	/** A request that doesn't make sense, with a message saying why. */
	public static BusinessException invalid(String message) {
		return new BusinessException(ErrorCode.INVALID, message);
	}

	/** The thing asked for doesn't exist. */
	public static BusinessException notFound(String message) {
		return new BusinessException(ErrorCode.NOT_FOUND, message);
	}

	/** Allowed in general but not right now, like joining a full game. */
	public static BusinessException conflict(String message) {
		return new BusinessException(ErrorCode.CONFLICT, message);
	}

	/** The request needs a signed-in player. */
	public static BusinessException unauthenticated(String message) {
		return new BusinessException(ErrorCode.UNAUTHENTICATED, message);
	}

	/** The payment provider declined. */
	public static BusinessException paymentFailed(String message) {
		return new BusinessException(ErrorCode.PAYMENT_FAILED, message);
	}

}
