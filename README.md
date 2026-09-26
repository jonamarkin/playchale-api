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

**Demo data.** On a laptop (the dev profile) an empty database is filled with the same demo data as
the web app's mock: eleven players (sign in as any of them with 024 000 0001 to 0011 and the code
123456), four venues, games past and upcoming, results, a league in progress and a few
notifications. Each piece keeps the mock's ID as a fixed UUID, so a demo game has the same ID after
every reset. `POST /dev/reset` puts it all back as it started; the web app's end-to-end tests do
that before each test. The seed (`devsupport/internal/service/DemoSeed`) is the one place that
writes straight into every module's tables with SQL, because demo data needs games already played
and results already recorded; it exists only in the dev profile.

## Architecture

A **modular monolith**: one Spring Boot service, split into modules by business capability
(`auth`, `users`, `profiles`, `catalog`, `venues`, `games`, `notifications`, `payments`, `competitions`). Every
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
integration/   outside systems behind interfaces: SMS and payments (each with a dev stand-in)
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
| `PLAYCHALE_PAYSTACK_SECRET_KEY` | empty: the simulated provider | `sk_live_...` (or `sk_test_...` on staging) |
| `PLAYCHALE_PAYMENTS_IN_APP` | `true` | `false` to launch before Paystack: players pay hosts directly |
| `PLAYCHALE_PAYMENTS_WEB_APP_URL` | `http://localhost:3000` | the web app's address: Paystack sends payers back there |
| `PLAYCHALE_SIGN_IN_PER_CONNECTION_PER_HOUR` | 100000 | 20 (default) |
| `PLAYCHALE_SIGN_IN_PER_DAY` | 100000 | 3000 (default); raise it as the service grows |

Without the dev profile the app refuses to start with any laptop-only setting, without a Paystack
key, or without at least one sign-in provider (SMS or email). Sign-in codes are never written to
production logs: the logging stand-ins exist only in the dev profile.

## Endpoints

| Method and path | Web app contract | Does |
|---|---|---|
| `GET /actuator/health` | | `UP` when the app can reach the database (`/liveness`, `/readiness` for the load balancer) |
| `GET /actuator/info` | | Build version |
| `POST /dev/reset` | | Dev profile only: back to the demo data → 204 |
| `GET /dev/demo-accounts` | `auth.demoAccounts` | Dev profile only: the seeded players the sign-in page offers |
| `GET /auth/options` | `auth.options` | Which ways of signing in are set up: `{"phone", "email"}` |
| `POST /auth/codes` | `auth.requestOtp`, `auth.requestEmailCode` | Sends a sign-in code by SMS `{"phone"}` or email `{"email"}` → `{"demoCode"?}` |
| `POST /auth/sessions` | `auth.verifyOtp`, `auth.verifyEmailCode` | Checks the code (`{"phone"\|"email", "code"}`), creates the account on first sign-in, sets the session cookie → user |
| `GET /auth/session` | `auth.currentUser` | The signed-in user, or `null` |
| `DELETE /auth/session` | `auth.signOut` | Ends the session → 204 |
| `DELETE /me` | `profiles.deleteAccount` | Deletes your account (anonymised; see below) → 204 |
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
| `GET /games?query=&sport=&when=` | `games.list` | Upcoming games still on that you may see; `when` is `today`, `tomorrow` or `weekend` in local time |
| `GET /me/games` | `games.mine` | Games you host or have a spot in |
| `GET /games/{id}` | `games.get` | One game; 404 (the app's `null`) if it doesn't exist |
| `POST /games` | `games.create` | Hosts a game; on a partner pitch it's booked in the same transaction → 201 |
| `POST /games/{id}/repeat` | `games.repeat` | Host only: the same game a week later → 201 |
| `POST /games/{id}/players` | `games.join` | Takes a spot (the game row is locked, so the last spot can't go twice) |
| `DELETE /games/{id}/players/me` | `games.leave` | Gives up your spot, unless you've paid |
| `DELETE /games/{id}/players/{player}` | `games.removePlayer` | Host only: a player's id, or `guest:<token>` |
| `POST /games/{id}/cancellation` | `games.cancel` | Host only: `{"reason"?}`; releases the pitch and tells everyone |
| `POST /games/{id}/invites` | `games.invite` | Host only: `{"userIds"}` → `{"invited"}` |
| `POST /games/{id}/guests` | `games.addGuest` | Host only: holds a spot → `{"game", "token"}` for the claim link |
| `POST /games/{id}/claims` | `games.claimSpot` | `{"token"}` from the claim link |
| `POST /games/{id}/reminders` | `games.remind` | Host only: `{"userIds"?}` → `{"reminded"}` |
| `POST /games/{id}/players/{player}/cash` | `games.markPaidCash` | Host only: a share paid in cash |
| `PUT /games/{id}/result` | `games.recordResult` | Host only, after kick-off: records the result, or corrects it (which clears checks) |
| `POST /games/{id}/result/confirmations` | `games.confirmResult` | A player who was there says it's right |
| `POST /games/{id}/result/disputes` | `games.disputeResult` | `{"reason"?}`: says it isn't; the host is told |
| `GET /payments/options` | `payments.options` | `{"inApp"}`: whether shares are paid in the app, or straight to the host |
| `POST /payments` | `payments.start` | `{"gameId", "method", "payerPhone"?}`: starts collecting your share → 201, pending |
| `GET /payments/{id}/status` | `payments.status` | Asks the provider while pending; on success the share is marked paid and the ledger written |
| `GET /payments/{id}` | `payments.get` | Your payment; 404 (the app's `null`) for anyone else's |
| `GET /me/statement` | `payments.statement` | Every movement of money involving you, newest first |
| `GET /competitions` | `competitions.list` | Leagues with their fixtures drawn, newest first |
| `GET /me/competitions` | `competitions.mine` | Leagues you organise or play in |
| `GET /competitions/{id}` | `competitions.get` | A league with teams, table, fixtures and waiting requests; 404 (the app's `null`) if none |
| `POST /competitions` | `competitions.create` | A draft league → 201 |
| `POST /competitions/{id}/teams` | `competitions.addTeam` | Organiser only, before the draw: `{"name", "captainId"?, "playerIds"?}` |
| `DELETE /competitions/{id}/teams/{teamId}` | `competitions.removeTeam` | Organiser only, before the draw |
| `POST /competitions/{id}/fixtures` | `competitions.generateFixtures` | Organiser only: everyone plays everyone once, a round a week |
| `POST /competitions/{id}/teams/{teamId}/players` | `competitions.addPlayers` | Captain or organiser: `{"userIds"}` |
| `DELETE /competitions/{id}/teams/{teamId}/players/{userId}` | `competitions.removePlayer` | Captain or organiser; the captain stays |
| `POST /competitions/{id}/teams/{teamId}/requests` | `competitions.requestJoin` | Asks the captain for a place |
| `POST /competitions/{id}/requests/{requestId}` | `competitions.answerRequest` | Captain or organiser: `{"accept"}` |
| `POST /competitions/{id}/squad-joins` | `competitions.joinWithToken` | `{"token"}` from a squad link |
| `GET /notifications` | `notifications.list` | Your latest 50, newest first |
| `POST /notifications/read-all` | `notifications.markAllRead` | → 204 |

Opening hours are the venue's local time (its market's timezone); a day can close at 24:00. A pitch
can never be double-booked: besides the service's own check, a Postgres exclusion constraint
(`bookings_no_overlap`) refuses overlapping confirmed bookings however many requests race for them.

Profiles are worked out from results: a game counts for a player when they were on a side (not
marked absent). Set-based sports (volleyball, tennis) are scored from their sets, and each sport keeps
only its own player stats (goals and assists, or points). The scoring rules live in
`catalog/api/SportCatalog` and mirror the web app's `data/sports.ts`.

**Money.** PlayChale never holds it: the payment provider moves it from the payer to the host, and the
API records that it moved. `payments` holds each attempt; `movements` is an append-only ledger with a
line on each person's statement (share out for the payer, in for the host), for in-app and cash
payments alike. There is no balance anywhere.

**Paying the host directly.** With `PLAYCHALE_PAYMENTS_IN_APP=false`, nobody pays through the app: the
pay sheet tells players to send their share to the host's mobile money number (shown to players in
that game only) or hand it over in cash, and the host marks them paid, which writes the same ledger
lines as any cash payment. No payment provider is needed, so PlayChale can launch while Paystack is
being set up. Switch it on later and nothing else changes.

**Paystack.** With `PLAYCHALE_PAYSTACK_SECRET_KEY` set, payments go through Paystack's hosted
checkout (`integration/payments/PaystackPaymentProvider`): the payer is sent to Paystack's page for
mobile money or card and comes back to the game (`/games/{id}?payment={id}`), where the web app keeps
checking. Paystack needs an email for its receipt, so the web app asks for one the first time someone
pays. The rules, taken from how the FCL banking platform handles money:

- **Only Paystack's "verify" answer says money moved.** A webhook (`POST /webhooks/paystack`, set it in
  the Paystack dashboard) is believed only if its HMAC-SHA512 signature checks out, is handled once
  however often it's delivered (`webhook_events`), and even then only prompts us to verify.
- A "success" for a different amount or currency is left pending and logged for a person.
- "Abandoned" means nobody has paid yet (the payer may still be on the checkout page), so it only
  counts as failed after 30 minutes.
- **A background worker chases pending payments** (`PaymentChecks`): a minute after they start, then
  backing off to hourly, for about a day. It claims them with `FOR UPDATE SKIP LOCKED`, so any number of
  copies of the API can run it. A payment it gives up on stays pending and is logged: nothing is marked
  failed on a guess.

Settlement straight to hosts (Paystack subaccounts or splits, so PlayChale never holds money) comes
once Paystack confirms how it works for Ghana mobile money.

On a laptop, without a key, a simulated provider stands in, behaving like the web app's mock: a
payment settles about 2.6 s after it starts, and a mobile money number ending in 000 is declined.

**Background jobs** run on every copy of the API but do their work on one at a time (a Postgres
advisory lock, `shared/scheduling/ClusterLock`): hourly tidying of spent sign-in codes, ended sessions
and old rate-limit counters; and the payment checks above.

**Sign-in texts cost money**, and bots requesting them in bulk ("SMS pumping") is a known way to run
up the bill. Besides five an hour per number, there are caps per connection (IP address) and per day
for the whole service, counted in Postgres (`shared/security/RateLimiter`) so they hold across every
copy. Behind the proxy, the client's address comes from X-Forwarded-For, which Tomcat only believes
from private and loopback addresses. Sign-in codes are sent straight away rather than through an
outbox: a code is a secret we only store hashed, and one that arrives minutes late is useless anyway.

**Deleting an account** (`DELETE /me`) removes everything personal: name, handle, phone, emails, payout
number, photo, area. The player row stays, as "Deleted player", so past games, results, league tables
and other people's statements still add up. Their sessions, sign-in codes and notifications go, and
they give up unpaid spots in games still to come (a paid spot stays: the host has the money). Each
module handles its own part by listening for `users/api/AccountDeleted`, in the same transaction. It's
refused while others depend on them (hosting a game still to come, running a venue, organising a
league that's still going): modules say so through `users/api/AccountHolds`, which the users module
declares so it never depends on them.

**Competitions.** Fixtures are ordinary games (the competitions module asks games to create them
through `games/api/Fixtures`), so results, stats and notifications work for them unchanged. A squad
change reaches every fixture that team hasn't played yet. The database holds each player to one team
per competition. A squad link's token is only shown to the team's captain and the organiser (the link
also names the team, so whoever opens it can see which one). A team set up without a named captain is
run by the organiser, who isn't then in its squad.

A guest spot's claim token is a secret: it's returned once, to the host, when they hold the spot,
and only its hash is stored. In game data a guest is identified by the spot's public ID instead, so
nobody viewing a game can claim a spot meant for someone else.

Phone and payout numbers are only ever sent to the player themselves: anyone else gets a blank
phone and no payout number.

Players sign in with a mobile number (code by SMS) or an email address (code by email). A phone and an
email are separate accounts. The email someone signs in with is proven by the code and never changes
from the profile page; the profile's own email is only where receipts go. Each way of signing in is
offered only where its provider is set up (`GET /auth/options`), and outside the dev profile the app
won't start with neither, so the service can go live on email while an SMS sender ID is approved.

Sign-in rules: codes last 10 minutes, five wrong guesses lock a code, five codes an hour per
number. Codes and session tokens are stored only as hashes. The session cookie is `HttpOnly`,
`SameSite=Lax`, and `Secure` in production.

## Deploying

The API ships as a container image. CI (`.github/workflows/ci.yml`) runs every test on each push, and on
`main` publishes `ghcr.io/<owner>/playchale-api:<commit>` and `:latest`. Build one locally with
`docker build -t playchale-api .`. Any host that runs containers will do: Render, Railway or Fly.io
to start (no server to look after), or a Hetzner server later. Postgres should be a managed one with
daily backups and point-in-time recovery.

**First deploy**

1. Create the Postgres database (version 17) and note its JDBC URL, user and password.
2. Create the service from the image, listening on port 8080, with a health check on
   `/actuator/health/readiness` (and `/actuator/health/liveness` for restarts, where the host has both).
3. Set the environment (no dev profile; every one of these is required unless noted):

   | Variable | Value |
   |---|---|
   | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<host>:5432/<db>?sslmode=require` |
   | `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | the database's |
   | `PLAYCHALE_SECRET` | `openssl rand -hex 32`; keep it: changing it voids every sign-in code in flight |
   | `PLAYCHALE_CORS_ORIGINS` | the web app's origin, e.g. `https://playchale.com` |
   | `PLAYCHALE_PAYMENTS_WEB_APP_URL` | the same address, where Paystack sends payers back to |
   | `PLAYCHALE_PAYSTACK_SECRET_KEY` | `sk_test_...` on staging, `sk_live_...` in production |
   | an SMS and/or email provider's settings | when their adapters are added; at least one is needed |

4. Give the API its own address on the same site as the web app (e.g. `api.playchale.com` next to
   `playchale.com`), so the sign-in cookie counts as first-party. The host terminates HTTPS.
5. In the Paystack dashboard, set the webhook URL to `https://api.<domain>/webhooks/paystack`.
6. Point the web app at it: `NUXT_PUBLIC_API_BASE=https://api.<domain>` and
   `NUXT_PUBLIC_DEMO_PAYMENTS=false` (webapp README).

If a setting is missing or still a laptop value, the app refuses to start and says which. That's on
purpose: a misconfigured deploy never serves a request.

**Releasing.** Deploy the new image tag. Flyway applies any new migrations as the app starts, before it
takes traffic. Because the old version may still be serving while the new one starts, a migration
must work with the code before it: add columns and tables freely, but remove or rename them only in a
later release, once nothing uses them.

**Rolling back.** Deploy the previous image tag. Migrations aren't undone; the rule above means the
previous version still works with the newer schema.

**Several copies.** Any number can run behind the load balancer: sessions, rate limits and background
jobs all live in Postgres, and each job runs on one copy at a time.

**Logs** are JSON lines (Elastic Common Schema) in the container, each carrying its request's ID (also
returned to the client in `X-Request-Id`), so a player's bug report can be matched to the server's logs.
Watch for `ERROR` lines, the daily sign-in cap being hit, and payments left pending after a day of checks.

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

The web app's end-to-end tests (`webapp/tests`) are the acceptance test for this API. All 70 pass
against it (see `webapp/tests/README.md` for how to run them that way).

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
