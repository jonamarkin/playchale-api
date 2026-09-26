# Deploying PlayChale

PlayChale runs on the same Contabo server as PayCycl (`62.171.145.181`). The server is already
hardened and runs Docker, the firewall, the `deploy` user and the shared Caddy (PayCycl's
`DEPLOYMENT.md`, steps 3 and 5). This guide only adds PlayChale next to it. Nothing here restarts
PayCycl.

| What | Where | Cost |
|---|---|---|
| API (`ghcr.io/jonamarkin/playchale-api`) and web app (`ghcr.io/jonamarkin/playchale-web`) | Containers in `/opt/playchale` on the server | Already paid |
| HTTPS | The shared Caddy in `/opt/caddy`, which gets certificates automatically | Free |
| Database | Supabase, a second free project, in Frankfurt | Free |
| Sign-in emails | Resend, from `alert@playchale.com` | See step 2 |
| DNS and proxy | Cloudflare (already set up for playchale.com) | Free |
| Backups | Nightly `pg_dump` to Cloudflare R2 | Free up to 10 GB |

Payments: players pay hosts directly (`PLAYCHALE_PAYMENTS_IN_APP=false`), so Paystack isn't needed
to launch. See "Later: in-app payments" at the end.

**Reading the commands.** `<LIKE_THIS>` is a placeholder to replace. Lines starting with `#` are
explanations; the shell ignores them, so whole blocks can be pasted. Each block says whether it runs
**on your machine** or **on the server** (`ssh deploy@62.171.145.181`).

## 1. Database (Supabase)

1. Supabase → **New project**: name `playchale`, region **Central EU (Frankfurt)**. Generate a strong
   database password and save it in your password manager.
2. **Project Settings → Data API → turn it off.** The app talks to Postgres directly. Left on,
   Supabase would publish the app's tables over its own web API.
3. **Connect → Session pooler** (not "Direct connection", which is IPv6-only, and not "Transaction
   pooler"). Supabase shows it as
   `postgresql://postgres.<PROJECT_REF>:[YOUR-PASSWORD]@<HOST>:5432/postgres`. Java wants it in three parts;
   you'll paste them in step 4:

   | Setting | Value |
   |---|---|
   | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<HOST>:5432/postgres?sslmode=require` |
   | `SPRING_DATASOURCE_USERNAME` | `postgres.<PROJECT_REF>` |
   | `SPRING_DATASOURCE_PASSWORD` | the password from 1 |

The API creates its own tables on first start (Flyway migrations), including the `btree_gist`
extension behind the no-double-booking rule. There's nothing to run in Supabase's SQL editor.

Supabase pauses free projects that it considers inactive, and keeps no backups on the free plan (step 7
covers backups). Once there are real players, Pro ($25/mo) removes both concerns.

## 2. Email (Resend)

Sign-in codes go out from `PlayChale <alert@playchale.com>`, in PlayChale's colours with a plain-text
copy. Resend has to verify that `playchale.com` is yours before it sends from it.

1. **Resend's free plan allows one domain, and PayCycl's `updates.atomarkin.com` already uses it.**
   Either upgrade Resend (Pro allows more domains) or use a separate Resend account for PlayChale.
   Check Resend's current pricing page first.
2. Resend → **Domains → Add domain** → `playchale.com`, region Ireland (eu-west-1, the
   closest of Resend's regions).
3. Resend lists DNS records: an MX and a TXT on `send.playchale.com`, and a DKIM TXT on
   `resend._domainkey.playchale.com`. Use its **Sign in to Cloudflare** button to add them, or copy
   them into Cloudflare → DNS by hand. Leave them **DNS only**; Cloudflare doesn't proxy these types
   anyway. They don't touch your existing MX records for `playchale.com` (Namecheap's email
   forwarding), so forwarding keeps working.
4. Also add a DMARC record in Cloudflare, which Gmail and Yahoo expect from senders:

   | Type | Name | Content |
   |---|---|---|
   | TXT | `_dmarc` | `v=DMARC1; p=none;` |

5. Wait for Resend to show the domain as **Verified** (minutes, usually).
6. Resend → **API Keys → Create**: name `playchale-production`, permission **Sending access**, domain
   `playchale.com`. Copy the key (`re_...`) into your password manager; Resend only shows it once.

The API refuses to start without an email provider, since nobody could sign in. If Resend rejects
an email (an unverified domain, say), the player sees "We couldn't send the email just now" and the
API logs Resend's reason.

## 3. DNS (Cloudflare)

Already done: `playchale.com`, `www` and `api` point at `62.171.145.181` as **DNS only** (grey
cloud). Keep them grey until step 5 has worked, because Caddy needs to reach Let's Encrypt directly
for the first certificates.

Check that the zone uses the same two settings as PayCycl: **SSL/TLS → Full (strict)** and **Edge
Certificates → Always Use HTTPS: Off**.

## 4. Put PlayChale on the server

The server doesn't need the code, only config files. The app arrives as images that CI builds on
every push to `main`, so **push both repos first** and wait for their CI runs to finish.

**The web image is private** because its repo (`playchalenew`) is, so the server has to log in to
GitHub's registry once. The API image is public.

1. GitHub → Settings → Developer settings → **Personal access tokens (classic) → Generate new token**:
   note `contabo pull images`, expiry of your choice, scope **`read:packages` only**.
2. **On the server:**
   ```bash
   # Log Docker in to GitHub's registry. Paste the token when asked for a password.
   # It's saved in ~/.docker/config.json, so later pulls (and automatic deploys) work.
   docker login ghcr.io -u jonamarkin
   ```

**On your machine, from this repo's folder (`backend/`):**

```bash
SERVER=deploy@62.171.145.181

# Create the app's folder and make deploy its owner.
ssh $SERVER 'sudo mkdir -p /opt/playchale && sudo chown deploy:deploy /opt/playchale'

# Copy the compose file, the settings templates and the backup script.
scp deploy/docker-compose.yml deploy/.env.api.example deploy/.env.web.example deploy/backup.sh $SERVER:/opt/playchale/

# The shared Caddyfile, which now also routes PlayChale. It lives in the PayCycl repo
# (deploy/caddy/Caddyfile), because one file serves every app on the server.
scp ../../pfinance/deploy/caddy/Caddyfile $SERVER:/opt/caddy/Caddyfile
```

**On the server:**

```bash
cd /opt/playchale

# Make the real settings files from the templates. They are never committed.
cp .env.api.example .env.api && cp .env.web.example .env.web

# .env.api holds secrets: 600 = only the deploy user can read or write it.
chmod 600 .env.api

# Make a secret for sign-in codes; copy the output into PLAYCHALE_SECRET below.
openssl rand -hex 32

# Fill in the three database settings (step 1), PLAYCHALE_SECRET and PLAYCHALE_RESEND_API_KEY
# (step 2). Save with Ctrl+O, Enter; quit with Ctrl+X.
nano .env.api

# The backup script needs to be runnable (step 7).
chmod +x backup.sh

# Start PlayChale. -d = in the background. Docker downloads both images first.
docker compose up -d

# Wait until both show "(healthy)". The API takes up to a minute and a half the first time,
# while it creates its tables. Run it again to refresh.
docker compose ps

# The API's output (JSON lines). The first start logs "Migrating schema" for each migration,
# then "Started PlaychaleApiApplication".
docker compose logs api | tail -30

# Tell Caddy to re-read the Caddyfile. PayCycl keeps running; nothing restarts.
docker compose -f /opt/caddy/docker-compose.yml exec caddy caddy reload --config /etc/caddy/Caddyfile

# Watch Caddy get certificates for the three PlayChale names ("certificate obtained successfully").
# They're issued on the first request, so open https://playchale.com in a browser if nothing shows.
docker compose -f /opt/caddy/docker-compose.yml logs caddy | grep -i playchale | grep -i certificate
```

The PlayChale block that `scp` put into the Caddyfile:

```caddy
playchale.com {
    encode zstd gzip
    reverse_proxy playchale-web:3000
}

www.playchale.com {
    redir https://playchale.com{uri} permanent
}

api.playchale.com {
    encode zstd gzip
    reverse_proxy playchale-api:8080 {
        # The real visitor's IP, for the API's sign-in limits (behind Cloudflare too).
        header_up X-Forwarded-For {client_ip}
    }
}
```

## 5. Check it

**From anywhere:**

```bash
# "UP" means the API is running and can reach the database.
curl https://api.playchale.com/actuator/health/readiness

# How people can sign in, and how they pay. Expected: {"phone":false,"email":true} and {"inApp":false}
curl https://api.playchale.com/auth/options
curl https://api.playchale.com/payments/options
```

Then in a browser, at https://playchale.com:

1. Sign in with your email. The code email should arrive from `alert@playchale.com` in PlayChale's
   colours. Check the spam folder the first time, and mark it "not spam" if it's there.
2. Finish onboarding, host a game, and open it from a second account (another email) to join.

The live site starts with no games, venues or players; the demo data only exists on laptops.

## 6. Turn on the Cloudflare proxy

Once step 5 works: Cloudflare → **DNS** → switch `playchale.com`, `www` and `api` to **Proxied**
(orange cloud). The shared Caddyfile already trusts Cloudflare's addresses, and the PlayChale block
passes each visitor's real IP to the API, so sign-in limits stay per visitor. (This was rehearsed
locally before release: behind a simulated Cloudflare, two visitors got separate limits, and a
client forging headers directly couldn't escape its own.)

```bash
# Responses now carry a cf-ray header: traffic goes through Cloudflare.
curl -sI https://playchale.com | grep -i cf-ray
```

## 7. Backups

Same approach as PayCycl: `backup.sh` dumps the app's tables every night and copies them to R2.

1. Cloudflare → R2 → create bucket `playchale-backups`, and add a lifecycle rule deleting objects
   older than 30 days.
2. The server's `r2` rclone destination (from PayCycl's setup) uses a token scoped to
   `paycycl-backups`. Edit that R2 API token and add `playchale-backups` to its buckets, or create a
   second token and a second destination (`rclone config create r2-playchale s3 ...`, as in PayCycl's
   step 7).
3. **On the server:**
   ```bash
   # One backup now, to check it works. The file lands in /opt/playchale/backups and in R2.
   cd /opt/playchale && BACKUP_REMOTE=r2:playchale-backups ./backup.sh
   rclone ls r2:playchale-backups
   ```
4. Schedule it nightly: `crontab -e` and add (03:30, just after PayCycl's 03:15):
   ```
   30 3 * * * cd /opt/playchale && BACKUP_REMOTE=r2:playchale-backups ./backup.sh >> backups/backup.log 2>&1
   ```

The dump includes the `btree_gist` extension the bookings table needs. It restores into an empty
database with one command (at the top of `backup.sh`). A backup and restore of a test database, with
all 23 tables, the no-double-booking constraint and the migration history, was checked before release.
Restore into a scratch database every few months anyway, to be sure.

## 8. Automatic deploys

Each repo's CI can deploy its own half after every push to `main`, once its tests pass and the image is
published: the API repo restarts `api`, the web repo restarts `web`. It's off until configured.
In **both** GitHub repos (Settings → Secrets and variables → Actions), set the same values as
PayCycl's (the same deploy key works for both apps):

| Kind | Name | Value |
|---|---|---|
| Variable | `DEPLOY_HOST` | `62.171.145.181` |
| Secret | `DEPLOY_USER` | `deploy` |
| Secret | `DEPLOY_SSH_KEY` | the private key PayCycl's deploys use |

The API deploy waits for the new version to report ready before it counts as done. If it doesn't,
the job fails and prints the API's last log lines.

## 9. Monitoring

In UptimeRobot, add HTTPS monitors (5-minute interval, email alerts) for:

- `https://api.playchale.com/actuator/health/readiness`
- `https://playchale.com`

Things worth watching in `docker compose logs api` (every line is JSON with a request ID):
`"log.level":"ERROR"` lines, Resend refusing emails, and the daily sign-in cap being reached.

## Releasing and rolling back

CI tags each image with its commit (`:<sha>`) and `:latest`. The compose file uses `:latest` unless
told otherwise.

```bash
cd /opt/playchale

# Update to the newest images (what automatic deploys do).
docker compose pull && docker compose up -d

# Roll the API back to an earlier commit. Its tag is on the CI run, or in the repo's Packages page.
# The variable only affects this command; the next plain "up -d" returns to :latest.
PLAYCHALE_API_TAG=<COMMIT_SHA> docker compose up -d api
```

The API applies new database migrations as it starts. Migrations add and never remove in the same
release (see README, "Releasing"), so rolling back never needs the database undone.

Handy commands, from `/opt/playchale`:

```bash
docker compose logs -f api        # follow the API's output live (Ctrl+C to stop)
docker compose restart web        # restart one container
docker compose down               # stop PlayChale (the data is in Supabase; PayCycl is unaffected)
docker stats                      # memory per container: the API is capped at 1 GB, the web app at 512 MB
```

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| API exits at startup, log says a `PLAYCHALE_...` setting "must be ..." | That setting is missing or still the template's value in `.env.api` |
| API log: "No SMS or email provider is configured" | `PLAYCHALE_RESEND_API_KEY` is empty |
| API log: `PLAYCHALE_RESEND_FROM must name the sender` | The sender line is missing from `.env.api` |
| API log: connection refused / timeout to the database | Wrong host or port (use the **Session pooler**), or the Supabase project is paused |
| `docker compose pull` says "denied" for `playchale-web` | The server isn't logged in to ghcr.io (step 4), or the token lacks `read:packages` |
| Sign-in says "We couldn't send the email just now" | Resend refused it: `docker compose logs api \| grep Resend` shows why (usually the domain isn't verified yet) |
| Codes arrive in spam | Check the domain is verified in Resend and the `_dmarc` record exists. It improves as people open the emails |
| Browser console: CORS error calling the API | `PLAYCHALE_CORS_ORIGINS` isn't exactly `https://playchale.com` |
| Signed in, but every page acts signed out | The web app and API aren't on the same site: `NUXT_PUBLIC_API_BASE` must be `https://api.playchale.com` |
| Caddy logs certificate errors for playchale.com | The records were proxied (orange) before the first certificates: switch to DNS only, wait a minute, reload Caddy |
| Everyone hits "Too many codes asked for from this connection" | The Caddyfile on the server predates the PlayChale block's `header_up X-Forwarded-For` line; copy it up again and reload |

## Later: in-app payments

When Paystack approves the account: add `PLAYCHALE_PAYSTACK_SECRET_KEY=sk_live_...` and set
`PLAYCHALE_PAYMENTS_IN_APP=true` in `.env.api`, run `docker compose up -d api`, and set the Paystack
webhook to `https://api.playchale.com/webhooks/paystack`. Try it with a `sk_test_` key first. The
README's "Paystack" section explains how the money moves.
