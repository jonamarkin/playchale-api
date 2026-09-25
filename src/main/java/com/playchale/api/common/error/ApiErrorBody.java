package com.playchale.api.web;

/** The JSON shape every error takes: {@code {"error": {"code": "...", "message": "..."}}}. */
public record ApiErrorBody(Detail error) {

	public record Detail(ErrorCode code, String message) {
	}

	static ApiErrorBody of(ErrorCode code, String message) {
		return new ApiErrorBody(new Detail(code, message));
	}

}
