package config

import (
	"testing"
	"time"
)

// env turns a map into the getenv function Load expects, so tests never touch the real environment.
func env(values map[string]string) func(string) string {
	return func(key string) string { return values[key] }
}

func TestLoadDefaults(t *testing.T) {
	cfg, err := Load(env(nil))
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.Addr != ":8080" {
		t.Errorf("Addr = %q, want :8080", cfg.Addr)
	}
	if cfg.Env != "development" {
		t.Errorf("Env = %q, want development", cfg.Env)
	}
	if len(cfg.CORSOrigins) != 1 || cfg.CORSOrigins[0] != "http://localhost:3000" {
		t.Errorf("CORSOrigins = %v, want [http://localhost:3000]", cfg.CORSOrigins)
	}
	if cfg.ShutdownTimeout != 10*time.Second {
		t.Errorf("ShutdownTimeout = %v, want 10s", cfg.ShutdownTimeout)
	}
}

// A table-driven test: each case is a row, and one loop runs them all. It's the most common way
// to test in Go, because adding a case is one line.
func TestLoadRejectsBadSettings(t *testing.T) {
	cases := []struct {
		name string
		env  map[string]string
	}{
		{"port not a number", map[string]string{"PORT": "eighty"}},
		{"unknown environment", map[string]string{"APP_ENV": "staging"}},
		{"bad shutdown timeout", map[string]string{"SHUTDOWN_TIMEOUT": "soon"}},
		{"production without a database", map[string]string{"APP_ENV": "production"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := Load(env(tc.env)); err == nil {
				t.Fatal("Load() succeeded, want an error")
			}
		})
	}
}

func TestLoadReadsEverything(t *testing.T) {
	cfg, err := Load(env(map[string]string{
		"PORT":             "9000",
		"APP_ENV":          "production",
		"DATABASE_URL":     "postgres://x",
		"CORS_ORIGINS":     "https://playchale.com, https://www.playchale.com ,",
		"SHUTDOWN_TIMEOUT": "3s",
	}))
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.Addr != ":9000" || !cfg.IsProduction() || cfg.DatabaseURL != "postgres://x" || cfg.ShutdownTimeout != 3*time.Second {
		t.Errorf("unexpected config: %+v", cfg)
	}
	if len(cfg.CORSOrigins) != 2 || cfg.CORSOrigins[1] != "https://www.playchale.com" {
		t.Errorf("CORSOrigins = %v, want two trimmed origins", cfg.CORSOrigins)
	}
}
