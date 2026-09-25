// Package httpapi is the API's HTTP layer: routing, middleware, and turning results and errors
// into responses. Business rules live in their own packages; handlers here stay thin.
package httpapi

import (
	"log/slog"
	"net/http"

	"github.com/jonamarkin/playchale-api/internal/config"
)

// Deps is everything the handlers need, passed in explicitly. As the API grows this gains the
// database, the payment provider and so on; tests build one with fakes.
type Deps struct {
	Config config.Config
	Logger *slog.Logger
	// Version identifies the build, reported by /healthz so you can tell which deploy is live.
	Version string
}

// NewHandler builds the whole API as one http.Handler.
//
// Routes use Go's standard router, which since Go 1.22 understands methods and path parameters
// ("GET /games/{id}"), so there's no need for a third-party router.
func NewHandler(deps Deps) http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", health(deps))

	// Anything unmatched gets the same JSON error shape as everything else, not Go's plain text.
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		WriteError(w, r, deps.Logger, NotFound("There’s nothing here."))
	})

	return chain(mux,
		withRequestID,
		withRecovery(deps.Logger),
		withLogging(deps.Logger),
		withCORS(deps.Config.CORSOrigins),
	)
}

// health answers load balancers and uptime checks. It will check the database too, once there
// is one, so "healthy" means "can actually serve requests".
func health(deps Deps) http.HandlerFunc {
	type response struct {
		Status  string `json:"status"`
		Version string `json:"version"`
		Env     string `json:"env"`
	}
	return func(w http.ResponseWriter, r *http.Request) {
		WriteJSON(w, http.StatusOK, response{Status: "ok", Version: deps.Version, Env: deps.Config.Env})
	}
}
