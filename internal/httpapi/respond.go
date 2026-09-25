package httpapi

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
)

// ErrorCode mirrors ApiErrorCode in the web app (webapp/app/services/api.ts), so the app can treat
// an error from this API exactly like one from its mock. Keep the two lists in step.
type ErrorCode string

const (
	CodeUnauthenticated ErrorCode = "unauthenticated"
	CodeNotFound        ErrorCode = "not-found"
	CodeInvalid         ErrorCode = "invalid"
	CodeConflict        ErrorCode = "conflict"
	CodePaymentFailed   ErrorCode = "payment-failed"
	// codeInternal never reaches a user with its details; see WriteError.
	codeInternal ErrorCode = "internal"
)

// Error is an error a user is allowed to see: a code the app can act on, and a message written
// for a person. Anything else that goes wrong is logged and reported as a generic failure.
type Error struct {
	Code    ErrorCode
	Message string
}

func (e *Error) Error() string { return string(e.Code) + ": " + e.Message }

// The constructors below keep handlers short: return httpapi.Invalid("Pick a time in the future.").

// Invalid reports a request that doesn't make sense, with a message saying why.
func Invalid(message string) *Error {
	return &Error{Code: CodeInvalid, Message: message}
}

// NotFound reports that the thing asked for doesn't exist.
func NotFound(message string) *Error {
	return &Error{Code: CodeNotFound, Message: message}
}

// Conflict reports something allowed in general but not right now, like joining a full game.
func Conflict(message string) *Error {
	return &Error{Code: CodeConflict, Message: message}
}

// Unauthenticated reports a request that needs a signed-in player.
func Unauthenticated(message string) *Error {
	return &Error{Code: CodeUnauthenticated, Message: message}
}

// PaymentFailed reports that the payment provider declined.
func PaymentFailed(message string) *Error {
	return &Error{Code: CodePaymentFailed, Message: message}
}

func (c ErrorCode) status() int {
	switch c {
	case CodeUnauthenticated:
		return http.StatusUnauthorized
	case CodeNotFound:
		return http.StatusNotFound
	case CodeInvalid:
		return http.StatusUnprocessableEntity
	case CodeConflict:
		return http.StatusConflict
	case CodePaymentFailed:
		return http.StatusPaymentRequired
	default:
		return http.StatusInternalServerError
	}
}

// errorBody is the JSON shape every error takes: {"error": {"code": "...", "message": "..."}}.
type errorBody struct {
	Error struct {
		Code    ErrorCode `json:"code"`
		Message string    `json:"message"`
	} `json:"error"`
}

// WriteJSON sends value as JSON with the given status.
func WriteJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	// Encoding can only fail here if value can't be represented as JSON, which is a programming
	// mistake rather than something a request can cause, so there's nothing useful to send back.
	_ = json.NewEncoder(w).Encode(value)
}

// WriteError sends err to the client. A *Error goes out as written; anything else is logged in
// full and reported as a generic failure, so internals never leak into a response.
func WriteError(w http.ResponseWriter, r *http.Request, logger *slog.Logger, err error) {
	var body errorBody

	// errors.As looks through wrapped errors (fmt.Errorf with %w) for a *Error.
	var apiErr *Error
	if errors.As(err, &apiErr) {
		body.Error.Code = apiErr.Code
		body.Error.Message = apiErr.Message
	} else {
		logger.ErrorContext(r.Context(), "request failed", "error", err, "method", r.Method, "path", r.URL.Path, "request_id", RequestID(r.Context()))
		body.Error.Code = codeInternal
		body.Error.Message = "Something went wrong on our side. Please try again."
	}

	WriteJSON(w, body.Error.Code.status(), body)
}
