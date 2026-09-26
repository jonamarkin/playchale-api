#!/bin/bash
# Nightly database backup for PlayChale (database on Supabase).
#
# Supabase's free plan keeps no backups, so this is the only copy of the data outside Supabase. Run
# it from cron in the folder holding .env.api:
#   30 3 * * * cd /opt/playchale && BACKUP_REMOTE=r2:playchale-backups ./backup.sh >> backups/backup.log 2>&1
#
# BACKUP_REMOTE (an rclone destination) copies each dump off this server too.
#
# Restore into an empty database (a new Supabase project, or a local Postgres):
#   gunzip -c backups/playchale_db_<timestamp>.sql.gz | docker run -i --rm postgres:17-alpine psql "<postgresql://... URL>"
set -euo pipefail

ENV_FILE="${ENV_FILE:-.env.api}"
BACKUP_DIR="${BACKUP_DIR:-$(pwd)/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
# pg_dump must be the same major version as the database or newer.
PG_IMAGE="${PG_IMAGE:-postgres:17-alpine}"

# One value from the env file, without surrounding quotes.
setting() {
  local value
  value=$(grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-)
  value="${value%\"}"
  echo "${value#\"}"
}

JDBC_URL=$(setting SPRING_DATASOURCE_URL)
DB_USER=$(setting SPRING_DATASOURCE_USERNAME)
DB_PASSWORD=$(setting SPRING_DATASOURCE_PASSWORD)
if [ -z "$JDBC_URL" ] || [ -z "$DB_USER" ]; then
  echo "SPRING_DATASOURCE_URL or SPRING_DATASOURCE_USERNAME not found in $ENV_FILE" >&2
  exit 1
fi
# Java's jdbc:postgresql://host:5432/db?... is libpq's postgresql://host:5432/db?... ; the user and
# password go in as environment variables, so they never appear in the process list.
DATABASE_URL="${JDBC_URL#jdbc:}"

TIMESTAMP=$(date +"%Y-%m-%d_%H-%M-%S")
FILE="$BACKUP_DIR/playchale_db_$TIMESTAMP.sql.gz"
mkdir -p "$BACKUP_DIR"

echo "Creating backup $FILE..."
# Only the app's schema: Supabase's own schemas (auth, storage...) are managed by Supabase and would
# not restore cleanly elsewhere. A schema-only dump leaves out extensions, so the one PlayChale needs
# (btree_gist, for the no-double-booking rule) is created first.
{
  echo "CREATE EXTENSION IF NOT EXISTS btree_gist;"
  # -e NAME without a value passes this shell's variable through, keeping it off the command line.
  PGUSER="$DB_USER" PGPASSWORD="$DB_PASSWORD" docker run --rm --network host -e PGUSER -e PGPASSWORD "$PG_IMAGE" \
    pg_dump "$DATABASE_URL" --schema=public --no-owner --no-privileges \
    | sed '/^CREATE SCHEMA public;$/d'  # every database already has it, so restoring would stop there
} | gzip > "$FILE"

# Fail loudly on an empty or broken archive instead of keeping it.
gzip -t "$FILE"
# (pipefail off here: grep -q exits early and gzip would report SIGPIPE)
if ! (set +o pipefail; gzip -cd "$FILE" | grep -q "CREATE TABLE public.games"); then
  echo "Backup has no games table, aborting" >&2
  rm -f "$FILE"
  exit 1
fi

if [ -n "${BACKUP_REMOTE:-}" ]; then
  echo "Copying to $BACKUP_REMOTE..."
  rclone copy "$FILE" "$BACKUP_REMOTE"
fi

find "$BACKUP_DIR" -type f -name 'playchale_db_*.sql.gz' -mtime +"$RETENTION_DAYS" -delete

echo "Backup completed: $FILE ($(du -h "$FILE" | cut -f1))"
