package com.playchale.api.shared.error;

/** The JSON shape every error takes: {@code {"error": {"code": "...", "message": "..."}}}. */
public record ApiErrorBody(Detail error) {

	public record Detail(String code, String message) {
	}

	static ApiErrorBody of(ErrorCode code, String message) {
		return new ApiErrorBody(new Detail(code.value(), message));
	}

}
