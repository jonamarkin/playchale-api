// Command api runs the PlayChale API server.
//
// main stays tiny and hands off to run, which returns an error instead of exiting. That keeps
// run testable, and means every deferred cleanup actually happens before the process ends.
package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jonamarkin/playchale-api/internal/config"
	"github.com/jonamarkin/playchale-api/internal/httpapi"
)

// version is set at build time: go build -ldflags "-X main.version=abc123" ./cmd/api
var version = "dev"

func main() {
	if err := run(context.Background(), os.Getenv, os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, getenv func(string) string, stdout io.Writer) error {
	// This context is cancelled on Ctrl+C or when the host asks the process to stop (SIGTERM),
	// which is how shutdown reaches every part of the program.
	ctx, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()

	cfg, err := config.Load(getenv)
	if err != nil {
		return fmt.Errorf("loading config: %w", err)
	}

	// Readable logs on a laptop, JSON in production where a log service parses them.
	var handler slog.Handler = slog.NewTextHandler(stdout, &slog.HandlerOptions{Level: slog.LevelDebug})
	if cfg.IsProduction() {
		handler = slog.NewJSONHandler(stdout, nil)
	}
	logger := slog.New(handler)

	server := &http.Server{
		Addr:    cfg.Addr,
		Handler: httpapi.NewHandler(httpapi.Deps{Config: cfg, Logger: logger, Version: version}),
		// Timeouts stop slow or stalled clients from holding connections open forever.
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	// ListenAndServe blocks, so it runs in its own goroutine and reports back on a channel.
	serveErr := make(chan error, 1)
	go func() {
		logger.Info("api listening", "addr", cfg.Addr, "env", cfg.Env, "version", version)
		serveErr <- server.ListenAndServe()
	}()

	// Wait for whichever comes first: the server failing to start, or a request to stop.
	select {
	case err := <-serveErr:
		if !errors.Is(err, http.ErrServerClosed) {
			return fmt.Errorf("serving: %w", err)
		}
	case <-ctx.Done():
		logger.Info("shutting down", "grace", cfg.ShutdownTimeout)
	}

	// Stop accepting new requests and give in-flight ones time to finish.
	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		return fmt.Errorf("shutting down: %w", err)
	}
	logger.Info("stopped")
	return nil
}
