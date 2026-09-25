package httpapi

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/jonamarkin/playchale-api/internal/config"
)

// testHandler builds the API with a logger that discards output, so test runs stay quiet.
func testHandler(t *testing.T) http.Handler {
	t.Helper()
	cfg, err := config.Load(func(string) string { return "" })
	if err != nil {
		t.Fatalf("config: %v", err)
	}
	return NewHandler(Deps{Config: cfg, Logger: slog.New(slog.NewTextHandler(io.Discard, nil)), Version: "test"})
}

// httptest runs a handler without opening a real port: build a request, record the response.
func TestHealth(t *testing.T) {
	rec := httptest.NewRecorder()
	testHandler(t).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	var body struct{ Status, Version string }
	if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
		t.Fatalf("decoding body: %v", err)
	}
	if body.Status != "ok" || body.Version != "test" {
		t.Errorf("body = %+v, want status ok and version test", body)
	}
	if rec.Header().Get("X-Request-Id") == "" {
		t.Error("missing X-Request-Id header")
	}
}

func TestUnknownRouteIsJSONNotFound(t *testing.T) {
	rec := httptest.NewRecorder()
	testHandler(t).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/nope", nil))

	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
	var body errorBody
	if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
		t.Fatalf("decoding body: %v", err)
	}
	if body.Error.Code != CodeNotFound {
		t.Errorf("code = %q, want %q", body.Error.Code, CodeNotFound)
	}
}

// Only the web app's origin gets CORS headers; anything else is ignored and the browser blocks it.
func TestCORS(t *testing.T) {
	cases := []struct {
		origin  string
		allowed bool
	}{
		{"http://localhost:3000", true},
		{"https://evil.example", false},
	}
	for _, tc := range cases {
		t.Run(tc.origin, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodOptions, "/healthz", nil)
			req.Header.Set("Origin", tc.origin)
			rec := httptest.NewRecorder()
			testHandler(t).ServeHTTP(rec, req)

			got := rec.Header().Get("Access-Control-Allow-Origin")
			if tc.allowed && got != tc.origin {
				t.Errorf("Allow-Origin = %q, want %q", got, tc.origin)
			}
			if !tc.allowed && got != "" {
				t.Errorf("Allow-Origin = %q, want none", got)
			}
		})
	}
}

// A user-facing error keeps its code and message, even when wrapped; anything else is hidden.
func TestWriteError(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	cases := []struct {
		name       string
		err        error
		wantStatus int
		wantCode   ErrorCode
		wantMsg    string
	}{
		{"user-facing", Conflict("Sorry, this game just filled up."), http.StatusConflict, CodeConflict, "Sorry, this game just filled up."},
		{"wrapped", fmt.Errorf("joining: %w", Invalid("Pick a time in the future.")), http.StatusUnprocessableEntity, CodeInvalid, "Pick a time in the future."},
		{"internal", errors.New("connection refused on 10.0.0.4:5432"), http.StatusInternalServerError, codeInternal, "Something went wrong on our side. Please try again."},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := httptest.NewRecorder()
			WriteError(rec, httptest.NewRequest(http.MethodGet, "/", nil), logger, tc.err)

			var body errorBody
			if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
				t.Fatalf("decoding body: %v", err)
			}
			if rec.Code != tc.wantStatus || body.Error.Code != tc.wantCode || body.Error.Message != tc.wantMsg {
				t.Errorf("got %d %q %q, want %d %q %q", rec.Code, body.Error.Code, body.Error.Message, tc.wantStatus, tc.wantCode, tc.wantMsg)
			}
		})
	}
}

func TestPanicBecomesJSON500(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	boom := http.HandlerFunc(func(http.ResponseWriter, *http.Request) { panic("nil map") })
	rec := httptest.NewRecorder()
	withRecovery(logger)(boom).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))

	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want 500", rec.Code)
	}
}
