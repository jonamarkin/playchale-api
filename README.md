# PlayChale API

The backend for [PlayChale](../webapp): games, venues, payments, results and competitions for
grassroots sport. Java 21, Spring Boot 4, Postgres.

> **Status: sign-in works.** Players can sign in with a phone number and a code, and stay signed in
> with a session. Profiles, games, venues and payments come next, endpoint by endpoint.

## Running it

You need **Java 21+** and **Docker**. Maven comes with the project (`./mvnw`).

```bash
./mvnw spring-boot:run    # starts Postgres from compose.yaml, migrates, serves on :8080
./mvnw test               # every test, against a real Postgres in a throwaway container
```

Both run with the `dev` profile (`application-dev.yml`): laptop settings that nothing deployed
uses. Then `curl localhost:8080/actuator/health`. Texts aren't sent: they're written to the log, and
every sign-in code is `123456`, which is also what the web app's end-to-end tests type.

`docker compose down` stops Postgres; `docker compose down -v` also deletes its data.

## Architecture

A **modular monolith**: one Spring Boot service, split into modules by business capability
(`auth`, `users`, `profiles`, `catalog`, `venues`, and next `games`, `payments`, `notifications`, `competitions`). Every
module has the same shape:

```
<module>/
  api/          what other modules may use: a small interface, events, records
  web/          REST controllers; web/dto holds the request and response records
  internal/
    domain/       JPA entities and enums, with the rules that belong to them
    repository/   Spring Data repositories
    service/      business logic and transactions
```

Plus the pieces every module shares:

```
shared/        settings, the error shape, CORS, request IDs and logging, JPA base classes
integration/   outside systems behind interfaces: SMS today, Paystack next (each with a dev stand-in)
market/        per-country rules: phone formats, currency, timezone
devsupport/    /dev endpoints for the web app's end-to-end tests (dev profile only)
src/main/resources/db/migration/   the schema, as numbered SQL files applied by Flyway
```

**Rules between modules** (ModularityTest fails the build if they're broken):

- A module uses another only through its `api` package: never its `internal` classes, its tables or its
  repositories.
- Modules refer to each other's records by ID, not with JPA relationships.
- Side effects in another module (notify the host when someone pays) go through application
  events, so modules don't call each other in circles.

**Rules inside a module:**

- Controllers are thin: validate the request, call a service, return a record. They never use a
  repository (ArchitectureTest checks).
- Services hold the business logic and the transaction (`@Transactional`); small rules that belong
  to one entity live on it, like `SignInCode.attempt()`.
- Entities never go out as JSON. Responses are records, so tables and the API change independently,
  and `open-in-view` is off so nothing lazy is loaded by accident while a response is written.
- Errors a person may see are `BusinessException`s (`invalid`, `conflict`, ...) with a message written
  for them. Anything else is logged and reported generically.

**Data:**

- **Flyway owns the schema.** Hibernate only checks at startup that the entities match
  (`ddl-auto: validate`). To change it, add `db/migration/V2__what_it_does.sql` and update the
  entity. Never edit a migration that has already run anywhere that matters.
- JPA for records like users and games; `JdbcClient` where SQL is the point: stats, leaderboards,
  queues.
- IDs are UUID v7 (`@UuidGenerator(style = VERSION_7)`), so they sort by creation time.
- Times come from the `Clock` bean, never `Instant.now()`, so tests can move time.
- Watch the SQL when writing a query (`-Dspring.jpa.show-sql=true`), and use `join fetch` rather
  than loading related rows one at a time.

**Tests**, by level: plain unit tests for entity rules (`SignInCodeTest`), service tests against
real Postgres in Testcontainers (`AuthServiceTest`), HTTP tests with MockMvc (`AuthApiTest`), and
the web app's Playwright suite end to end.

## Settings

Every value in `application.yml` can be set by an environment variable: `playchale.secret` is
`PLAYCHALE_SECRET`, `spring.datasource.url` is `SPRING_DATASOURCE_URL`, and so on.

| Setting | Development | Production |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` (set by `./mvnw`) | unset |
| `PLAYCHALE_SECRET` | a fixed placeholder | required, 32+ random characters (`openssl rand -hex 32`) |
| `PLAYCHALE_DEMO_SIGN_IN_CODE` | `123456` | must be empty |
| `PLAYCHALE_CORS_ORIGINS` | `http://localhost:3000` | required: the web app's real origin |
| `PLAYCHALE_SECURE_COOKIES` | `false` (plain http) | `true` |
| `PLAYCHALE_TEST_SUPPORT` | `true` (`/dev` endpoints) | must be `false` |
| `SPRING_DATASOURCE_URL` etc. | set automatically from compose.yaml | the managed Postgres |

Without the dev profile the app refuses to start with any laptop-only setting, and until a real SMS
provider exists it refuses to start at all, so sign-in codes can never end up in production logs.

## Endpoints

| Method and path | Web app contract | Does |
|---|---|---|
| `GET /actuator/health` | | `UP` when the app can reach the database (`/liveness`, `/readiness` for the load balancer) |
| `GET /actuator/info` | | Build version |
| `POST /auth/codes` | `auth.requestOtp` | Sends a sign-in code. `{"phone"}` → `{"demoCode"?}` |
| `POST /auth/sessions` | `auth.verifyOtp` | Checks the code, creates the account on first sign-in, sets the session cookie → user |
| `GET /auth/session` | `auth.currentUser` | The signed-in user, or `null` |
| `DELETE /auth/session` | `auth.signOut` | Ends the session → 204 |
| `PATCH /me` | `profiles.update` | Edits your profile; absent fields stay, blank optional ones clear |
| `POST /me/onboarding` | `profiles.completeOnboarding` | The same, then marks you onboarded (needs a name and a handle) |
| `GET /handles/{handle}` | `profiles.isHandleAvailable` | `{"available"}`; your own handle counts as free |
| `GET /users/{id}/profile` | `profiles.get` | A player's profile and record |
| `GET /profiles/{handle}` | `profiles.getByHandle` | The same by handle; 404 (the app's `null`) when no one has it |
| `GET /users/{id}/history` | `profiles.history` | Verified results they played in, newest first |
| `GET /sports` | `catalog.sports` | The sports and their formats |
| `GET /venues?query=` | `venues.search` | Listed venues matching a name or area |
| `GET /venues/{id}` | `venues.get` | A venue with its owner; 404 (the app's `null`) if it doesn't exist |
| `GET /me/venues` | `venues.mine` | Venues you own |
| `POST /venues` | `venues.create` | Lists a venue you own → 201 |
| `PUT /venues/{id}` | `venues.update` | Owner only. Pitches keep their id; a pitch with bookings to come can't be removed |
| `GET /venues/{id}/availability?date=` | `venues.availability` | Every hour on every pitch for one local day, and why it's taken |
| `GET /venues/{id}/schedule?from=&to=` | `venues.schedule` | Owner only: bookings and blocks in that range |
| `POST /venues/{id}/blocks` | `venues.block` | Owner only: holds time on a pitch → 201 |
| `DELETE /bookings/{id}` | `venues.cancelBlock` | Owner only: releases a block → 204 |

Opening hours are the venue's local time (its market's timezone); a day can close at 24:00. A pitch
can never be double-booked: besides the service's own check, a Postgres exclusion constraint
(`bookings_no_overlap`) refuses overlapping confirmed bookings however many requests race for them.

Phone and payout numbers are only ever sent to the player themselves: anyone else gets a blank
phone and no payout number.

Sign-in rules: codes last 10 minutes, five wrong guesses lock a code, five codes an hour per
number. Codes and session tokens are stored only as hashes. The session cookie is `HttpOnly`,
`SameSite=Lax`, and `Secure` in production.

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

- **Java and Spring Boot**, because it's the language the team knows best. (A Go version was started
  first; it's kept in `git stash` for reference.)
- **Spring Data JPA, with Flyway migrations as the source of truth for the schema.** Plain Postgres,
  no provider-specific features. (Plain SQL with `JdbcClient` was tried first and swapped for JPA,
  since it's what the team knows.)
- **Every record carries its country, timezone and currency** where it matters; timestamps are UTC.
- **PlayChale never holds money.** Payments move between people through the provider; this API records movements.
- **Tests hit real Postgres** through Testcontainers, the same version as production.
- **Virtual threads** are on: one lightweight thread per request.
