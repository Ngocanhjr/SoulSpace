#!/bin/sh
(
set -eu
: "${DB_PASSWORD:?Missing DB_PASSWORD}"
: "${FLYWAY_PASSWORD:?Missing FLYWAY_PASSWORD}"
if [ "$POSTGRES_PASSWORD" = "$DB_PASSWORD" ] || [ "$POSTGRES_PASSWORD" = "$FLYWAY_PASSWORD" ]; then
  echo 'Bootstrap, migrator and runtime passwords must be different.' >&2
  exit 1
fi
psql --no-psqlrc --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" --file /opt/soulspace/provision.sql
)
