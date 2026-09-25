package com.playchale.api.shared.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception into the one JSON error shape, and every {@link ErrorCode} into its HTTP
 * status. An {@link BusinessException} goes out as
 * written; anything unexpected is logged in full and reported generically, so internals never leak.
 */
@RestControllerAdvice
class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	private static final String NOT_VALID = "The request isn’t valid.";

	private static final String NOTHING_HERE = "There’s nothing here.";

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<ApiErrorBody> app(BusinessException e) {
		return respond(e.code(), e.getMessage());
	}

	/** Malformed JSON, or a field the API doesn't know. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiErrorBody> unreadable(HttpMessageNotReadableException e) {
		return respond(ErrorCode.INVALID, "The request couldn’t be read. Please try again.");
	}

	/**
	 * A request body failed its Bean Validation rules (@NotBlank and so on). Each rule carries a
	 * message written for people, and the first one is enough.
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiErrorBody> notValid(MethodArgumentNotValidException e) {
		var error = e.getBindingResult().getAllErrors().stream().findFirst();
		return respond(ErrorCode.INVALID, error.map(MessageSourceResolvable::getDefaultMessage).orElse(NOT_VALID));
	}

	/** A query parameter or path variable failed its validation rules. */
	@ExceptionHandler(HandlerMethodValidationException.class)
	ResponseEntity<ApiErrorBody> notValid(HandlerMethodValidationException e) {
		var error = e.getAllErrors().stream().findFirst();
		return respond(ErrorCode.INVALID, error.map(MessageSourceResolvable::getDefaultMessage).orElse(NOT_VALID));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ApiErrorBody> notJson(HttpMediaTypeNotSupportedException e) {
		return respond(ErrorCode.INVALID, "Send the request as JSON.");
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	ResponseEntity<ApiErrorBody> missingParameter(MissingServletRequestParameterException e) {
		return respond(ErrorCode.INVALID, NOT_VALID);
	}

	/**
	 * A value that can't be converted, e.g. /games/not-an-id. In the path it names something that
	 * doesn't exist; in a query parameter it's a bad request.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ApiErrorBody> mismatch(MethodArgumentTypeMismatchException e) {
		if (e.getParameter().hasParameterAnnotation(PathVariable.class)) {
			return respond(ErrorCode.NOT_FOUND, NOTHING_HERE);
		}
		return respond(ErrorCode.INVALID, NOT_VALID);
	}

	@ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class,
			HttpRequestMethodNotSupportedException.class })
	ResponseEntity<ApiErrorBody> nothingHere(Exception e) {
		return respond(ErrorCode.NOT_FOUND, NOTHING_HERE);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiErrorBody> unexpected(Exception e) {
		log.error("request failed", e);
		return respond(ErrorCode.INTERNAL, "Something went wrong on our side. Please try again.");
	}

	private static ResponseEntity<ApiErrorBody> respond(ErrorCode code, String message) {
		return ResponseEntity.status(status(code)).body(ApiErrorBody.of(code, message));
	}

	/** The one place a refusal becomes an HTTP status. */
	private static HttpStatus status(ErrorCode code) {
		return switch (code) {
			case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
			case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
			case CONFLICT -> HttpStatus.CONFLICT;
			case PAYMENT_FAILED -> HttpStatus.PAYMENT_REQUIRED;
			case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
	}

}
