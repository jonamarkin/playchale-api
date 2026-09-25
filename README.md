# PlayChale API

The backend for [PlayChale](../webapp): games, venues, payments, results and competitions for
grassroots sport. Written in Go, backed by Postgres.

> **Status: skeleton.** The server, configuration, error handling and tests are in place. The
> database and the first endpoints come next.

## Running it

You need **Go 1.26+** and **Docker** (for Postgres).

```bash
cp .env.example .env    # once
make db-up              # start Postgres
make run                # http://localhost:8080/healthz
make check              # format, vet and test before committing
```

`make` on its own lists every command.

## Layout

```
cmd/api/            the program: reads config, builds the server, handles shutdown
internal/
  config/           settings from environment variables
  httpapi/          routing, middleware, JSON responses and errors
migrations/         database schema changes, applied in order (goose)
queries/            SQL the API runs, turned into typed Go (sqlc)
compose.yaml        Postgres for local development
```

Everything under `internal/` can only be imported by this module, which is Go's way of keeping
the API's insides private.

## The contract with the web app

The web app talks to one interface, `PlayChaleApi` in `webapp/app/services/api.ts`. Today an
in-browser mock implements it; this API is the real implementation. Its methods are the list of
endpoints to build, and the web app's types are the JSON shapes to return.

Errors match the web app's `ApiError` codes, as `{"error": {"code", "message"}}`:

| Code | HTTP | Meaning |
|---|---|---|
| `invalid` | 422 | The request doesn't make sense; the message says why |
| `unauthenticated` | 401 | Not signed in |
| `not-found` | 404 | No such thing |
| `conflict` | 409 | Allowed in general, not right now (the game is full) |
| `payment-failed` | 402 | The payment provider said no |
| `internal` | 500 | Our fault. Details are logged, never sent |

The web app's end-to-end tests (`webapp/tests`) are the acceptance test for this API: pointed at a
build that uses it, they should pass unchanged.

## Decisions, so they aren't re-argued

- **Plain Postgres, portable.** Migrations and queries live here as SQL. No provider-specific features.
- **Every record carries its country, timezone and currency** where it matters; timestamps are UTC.
- **PlayChale never holds money.** Payments move between people through the provider; this API records movements.
- **Standard library first.** `net/http` routing, `log/slog` logging. Libraries only where they earn it.
# playchale-api
