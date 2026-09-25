package com.playchale.api.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception into the one JSON error shape. An {@link AppException} goes out as
 * written; anything unexpected is logged in full and reported generically, so internals never leak.
 */
@RestControllerAdvice
class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(AppException.class)
	ResponseEntity<ApiErrorBody> app(AppException e) {
		return respond(e.code(), e.getMessage());
	}

	/** Malformed JSON, or a field the API doesn't know. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiErrorBody> unreadable(HttpMessageNotReadableException e) {
		return respond(ErrorCode.INVALID, "The request couldn’t be read. Please try again.");
	}

	/** A field failed its validation rule; the first message is enough for a person. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiErrorBody> notValid(MethodArgumentNotValidException e) {
		var field = e.getBindingResult().getFieldError();
		var message = field == null ? "The request isn’t valid." : field.getField() + " " + field.getDefaultMessage();
		return respond(ErrorCode.INVALID, message);
	}

	@ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class,
			HttpRequestMethodNotSupportedException.class })
	ResponseEntity<ApiErrorBody> nothingHere(Exception e) {
		return respond(ErrorCode.NOT_FOUND, "There’s nothing here.");
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiErrorBody> unexpected(Exception e) {
		log.error("request failed", e);
		return respond(ErrorCode.INTERNAL, "Something went wrong on our side. Please try again.");
	}

	private static ResponseEntity<ApiErrorBody> respond(ErrorCode code, String message) {
		return ResponseEntity.status(code.status()).body(ApiErrorBody.of(code, message));
	}

}
