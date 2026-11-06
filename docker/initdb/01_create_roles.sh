#!/usr/bin/env bash
# docker/initdb/01_create_roles.sh
#
# Runs inside the PostgreSQL container as the bootstrap superuser (POSTGRES_USER)
# during docker-entrypoint-initdb.d processing, i.e. on first volume creation.
#
# Creates:
#   MIGRATE_USER     Non-superuser login role that owns the database and runs
#                    Flyway. No CREATEDB, no CREATEROLE, no REPLICATION.
#   chatledger_app   NOLOGIN group role; migration V008 grants it table privileges.
#   APP_LOGIN_USER   Runtime login role, member of chatledger_app. Not owner,
#                    not superuser.
#
# Credentials come from environment variables only. They are read by psql with
# \getenv (never placed on a command line) and quoted by PostgreSQL itself with
# format('%I') for identifiers and format('%L') for literals, so passwords or
# names containing quotes, backslashes, dollar signs or semicolons are safe.
# The heredoc is quoted ('EOSQL'), so the shell never touches the SQL text.
#
# Requirement: 9.4 (secrets from environment variables only).

set -euo pipefail

: "${MIGRATE_USER:?MIGRATE_USER must be set}"
: "${MIGRATE_PASSWORD:?MIGRATE_PASSWORD must be set}"
: "${APP_LOGIN_USER:?APP_LOGIN_USER must be set}"
: "${APP_LOGIN_PASSWORD:?APP_LOGIN_PASSWORD must be set}"
: "${POSTGRES_DB:?POSTGRES_DB must be set}"

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname   "$POSTGRES_DB" \
<<'EOSQL'
\getenv migrate_user     MIGRATE_USER
\getenv migrate_password MIGRATE_PASSWORD
\getenv app_user         APP_LOGIN_USER
\getenv app_password     APP_LOGIN_PASSWORD

-- Schema owner and Flyway login. It owns the database (set below), which is
-- enough to create tables, functions and triggers. It is not a superuser and
-- cannot create databases or roles.
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
    :'migrate_user', :'migrate_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migrate_user')
\gexec

SELECT format('ALTER DATABASE %I OWNER TO %I', current_database(), :'migrate_user')
\gexec

-- Group role holding the application privileges. Created here because the
-- migrate role has no CREATEROLE and migrations must not create roles or hold
-- credentials.
SELECT 'CREATE ROLE chatledger_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chatledger_app')
\gexec

-- Runtime login. INHERIT is the default, so membership in chatledger_app gives
-- it the group privileges.
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
    :'app_user', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user')
\gexec

SELECT format('GRANT chatledger_app TO %I', :'app_user')
\gexec
EOSQL

echo "Roles for the migrate and application logins created."
