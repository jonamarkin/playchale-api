// Package config reads the API's settings from environment variables.
//
// Settings come from the environment rather than a file so the same binary runs unchanged on a
// laptop, in CI and in production; only the environment differs. Locally, `make run` loads them
// from a .env file (see .env.example).
package config

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// Config holds every setting the API needs. It is built once at startup and passed to whatever
// needs it, never read from a global, which keeps each part testable on its own.
type Config struct {
	// Addr is where the HTTP server listens, e.g. ":8080".
	Addr string
	// Env is "development" or "production". Production turns on stricter behaviour, like JSON logs.
	Env string
	// DatabaseURL is the Postgres connection string. Optional until the database is wired up.
	DatabaseURL string
	// CORSOrigins are the web origins allowed to call the API from a browser, e.g. the web app.
	CORSOrigins []string
	// ShutdownTimeout is how long in-flight requests get to finish when the server is stopped.
	ShutdownTimeout time.Duration
}

// IsProduction reports whether the API is running for real users.
func (c Config) IsProduction() bool { return c.Env == "production" }

// Load builds a Config from getenv, which is normally os.Getenv. Taking the lookup as a function
// rather than calling os.Getenv directly is a common Go pattern: tests pass a map-backed function
// instead of touching the real environment.
func Load(getenv func(string) string) (Config, error) {
	cfg := Config{
		Addr:            ":" + orDefault(getenv("PORT"), "8080"),
		Env:             orDefault(getenv("APP_ENV"), "development"),
		DatabaseURL:     getenv("DATABASE_URL"),
		CORSOrigins:     splitList(orDefault(getenv("CORS_ORIGINS"), "http://localhost:3000")),
		ShutdownTimeout: 10 * time.Second,
	}

	if raw := getenv("SHUTDOWN_TIMEOUT"); raw != "" {
		d, err := time.ParseDuration(raw)
		if err != nil {
			// %w wraps the original error, so callers can still inspect it with errors.Is/As.
			return Config{}, fmt.Errorf("SHUTDOWN_TIMEOUT %q: %w", raw, err)
		}
		cfg.ShutdownTimeout = d
	}

	if _, err := strconv.Atoi(strings.TrimPrefix(cfg.Addr, ":")); err != nil {
		return Config{}, fmt.Errorf("PORT must be a number, got %q", strings.TrimPrefix(cfg.Addr, ":"))
	}

	switch cfg.Env {
	case "development", "production":
	default:
		return Config{}, fmt.Errorf("APP_ENV must be development or production, got %q", cfg.Env)
	}

	// Production must not silently run without a database.
	if cfg.IsProduction() && cfg.DatabaseURL == "" {
		return Config{}, errors.New("DATABASE_URL is required in production")
	}

	return cfg, nil
}

func orDefault(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

// splitList turns "a, b,c" into ["a" "b" "c"], dropping empty entries.
func splitList(raw string) []string {
	var out []string
	for _, part := range strings.Split(raw, ",") {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}
