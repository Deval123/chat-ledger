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
#   chatledger_provisioner  NOLOGIN group role; migration V013 grants it EXECUTE on
#                    the two provisioning functions and nothing else.
#   PROVISION_DB_USER  Login role, member of chatledger_provisioner. Used only by
#                    the operator commands provision-tenant and provision-user.
#                    It has no table privileges.
#   chatledger_demo_operator  NOLOGIN group role; migration V020 grants it EXECUTE
#                    on the demo functions and nothing else.
#   DEMO_DB_USER     Optional login role (a demo deployment only), member of
#                    chatledger_demo_operator. Used only by demo-init and demo-refresh.
#
# Credentials come from environment variables only. They are read by psql with
# \getenv (never placed on a command line) and quoted by PostgreSQL itself with
# format('%I') for identifiers and format('%L') for literals, so passwords or
# names containing quotes, backslashes, dollar signs or semicolons are safe.
# The heredoc is quoted ('EOSQL'), so the shell never touches the SQL text.
#
# Requirement: 9.4 (secrets from environment variables only).

set -euo pipefail

# A password may be given as NAME_FILE instead, the path of a file that holds it (a Compose secret; task 13, D6). Both at once is
# refused; the value is never printed.
file_env() {
    local name="$1" file_name="${1}_FILE"
    if [ -n "${!name:-}" ] && [ -n "${!file_name:-}" ]; then
        echo "Refused: both $name and $file_name are set." >&2
        exit 1
    fi
    if [ -n "${!file_name:-}" ]; then
        if [ ! -r "${!file_name}" ]; then
            echo "Refused: $file_name does not name a readable file." >&2
            exit 1
        fi
        # The command substitution drops the trailing newline; anything else that is not one line is refused, not joined.
        local value
        value="$(cat -- "${!file_name}")"
        case "$value" in
            ''|*$'\n'*|*$'\r'*)
                echo "Refused: the file of $file_name must hold one line." >&2
                exit 1 ;;
        esac
        export "$name"="$value"
    fi
}
for secret in MIGRATE_PASSWORD APP_LOGIN_PASSWORD PROVISION_DB_PASSWORD DEMO_DB_PASSWORD; do
    file_env "$secret"
done

: "${MIGRATE_USER:?MIGRATE_USER must be set}"
: "${MIGRATE_PASSWORD:?MIGRATE_PASSWORD must be set}"
: "${APP_LOGIN_USER:?APP_LOGIN_USER must be set}"
: "${APP_LOGIN_PASSWORD:?APP_LOGIN_PASSWORD must be set}"
: "${PROVISION_DB_USER:?PROVISION_DB_USER must be set}"
: "${PROVISION_DB_PASSWORD:?PROVISION_DB_PASSWORD must be set}"
: "${POSTGRES_DB:?POSTGRES_DB must be set}"

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname   "$POSTGRES_DB" \
<<'EOSQL'
\getenv migrate_user     MIGRATE_USER
\getenv migrate_password MIGRATE_PASSWORD
\getenv app_user         APP_LOGIN_USER
\getenv app_password     APP_LOGIN_PASSWORD
\getenv prov_user        PROVISION_DB_USER
\getenv prov_password    PROVISION_DB_PASSWORD

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
-- Provisioning: group role (granted EXECUTE by migration V013) and its login.
SELECT 'CREATE ROLE chatledger_provisioner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chatledger_provisioner')
\gexec
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
    :'prov_user', :'prov_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'prov_user')
\gexec
SELECT format('GRANT chatledger_provisioner TO %I', :'prov_user')
\gexec
-- The demo operator group role (granted EXECUTE on the demo functions by V020, nothing else). Created on every
-- database so V020 can grant to it; it only matters on a demo deployment.
SELECT 'CREATE ROLE chatledger_demo_operator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chatledger_demo_operator')
\gexec
EOSQL

# The demo operator login exists only where it is asked for (a demo deployment): set DEMO_DB_USER and
# DEMO_DB_PASSWORD. The demo-init and demo-refresh commands use it; the web process never does.
if [ -n "${DEMO_DB_USER:-}" ]; then
    : "${DEMO_DB_PASSWORD:?DEMO_DB_PASSWORD must be set when DEMO_DB_USER is}"
    psql -v ON_ERROR_STOP=1 \
         --username "$POSTGRES_USER" \
         --dbname   "$POSTGRES_DB" \
<<'EOSQL'
\getenv demo_user     DEMO_DB_USER
\getenv demo_password DEMO_DB_PASSWORD
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
    :'demo_user', :'demo_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'demo_user')
\gexec
SELECT format('GRANT chatledger_demo_operator TO %I', :'demo_user')
\gexec
EOSQL
    echo "Demo operator login created."
fi

echo "Roles for the migrate and application logins created."
