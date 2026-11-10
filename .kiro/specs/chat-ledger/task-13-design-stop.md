# Task 13 design stop: deployment

Status: **approved by the owner on D1 to D19 with three changes** (section 9). Implemented in stop 2. Branch: `task-13-deploy` (nothing
committed by me).

Owner's changes, in substance:
1. The image pipeline is split in three jobs:
   - build to an archive, with no write permission;
   - scan that archive with Trivy, `contents: read` only, no packages permission;
   - push in a separate job with `packages: write`, which runs no third-party scanner or tool, only login and push of the archive that
     was built and scanned.

   Every third-party action is pinned by commit SHA, and stop 2 checks that each SHA belongs to the intended release.
2. The owner sets the GHCR package to public once, in the GitHub UI, after the first push. The check is an anonymous `docker pull` from the
   VPS.
3. Whether the pinned cloudflared release reads its token from a file is tested. If yes, it is used; if not, the environment variable
   is named in the register.

I cannot reach the VPS. Every statement about it below comes from the two read-only checks the owner ran and pasted, or is marked
**(unverified)** with the command that settles it. Where I could look something up from my machine (public image digests), I did and
say when.

## What I read, and what it changes

- **tasks.md, task 13** (every line):
  - Docker image, host, health check, demo URL documented (no demo credentials), warm-up note.
  - Set `app.security.trusted-proxy-hops` for the host and prove it with a request through the real proxy.
  - Two processes for the demo: the web process and the refresh loop, or the host's scheduler.
  - Watch `pg_database_size`; before 80% drop the demo database and run `demo-init` and `demo-refresh` again.
  - PostgreSQL logging: `log_error_verbosity = terse`, `log_statement = none`, `log_min_duration_statement = -1`.
- **Task 9's "Demo deployment" section and register items 111 to 129.**
  - The demo is its own deployment and database, with two switches (`app.demo.enabled`, `deployment_mode`).
  - The web process must never hold the demo operator login (item 115).
  - The reset depends on a process outside the web application (item 122).
  - The forwarded header is trusted only when configured (item 121).
  - Size is estimated only (item 128).
- **`docker/`.**
  - `demo-entrypoint.sh` runs the web process and the refresh loop in one container, and strips `DEMO_DB_*` from the web process with
    `env -u`. It needs a shell, so it rules out a shell-less image.
  - `initdb/01_create_roles.sh` creates the migrate, app and provisioner logins (all required with `:?`), and the demo operator login only
    when `DEMO_DB_USER` is set. It reads passwords from environment variables only.
- **`.env.example`.** Placeholders for every login and `APP_SECRET`; `DEMO_DB_*` are commented out.
- **The commands.**
  - `ProvisioningCommand` (`PROVISION_DB_*`) and `DemoCommand` (`DB_*`, `DEMO_DB_*`, `APP_SECRET`) read the environment only.
  - Neither can read a secret from a file.
  - There is no command that only runs migrations: today Flyway runs inside the web process, with `FLYWAY_USER` and `FLYWAY_PASSWORD`,
    the schema owner's login.
- **`StartupGuards`.** It checks the session cookie (HttpOnly, SameSite strict, Secure outside `dev`, the `__Host-` rules) and logs the
  trusted proxy hops. `SecurityProperties` bounds the hops to 0..2.
- **`application.properties`.**
  - Datasource and Flyway from environment variables.
  - Actuator exposes `health` only, with details `when-authorized`.
  - The cookie is `__Host-chatledger-session` and Secure.
  - No error details, no `Server` header, request limits (16 KB headers, 64 KB forms).
  - **HSTS is written only on HTTPS requests** (`SecurityConfig`, Spring's default). Behind a tunnel the application sees plain HTTP, so
    today it would never send HSTS.
- **`HealthController`.** `GET /health` answers `{"status":"UP"}`. `/health` and `/actuator/health` are both public.
- **CI (`.github/workflows/ci.yml`).**
  - Build and test on every push.
  - OWASP check on pull requests and daily.
  - `permissions: contents: read`; actions pinned by tag (`@v4`), not by commit.
  - No image is built.
- **The VPS (from the owner's two checks).**
  - Debian 12 x86_64, user `debian`, not in the docker group (`sudo docker`).
  - 2 vCPU, 3.7 GiB RAM (about 2.7 GiB available with the other project up), 40 GB disk with 33 GB free.
  - Docker 29.9.0, Compose v5.6.0.
  - ufw: deny incoming, allow outgoing, only 22/tcp. sshd: key only, no root login.
  - Listening: 22, and 5355 (LLMNR) on all addresses.
  - The other project (Nkap Agent) runs 5 containers with limits 512 + 128 + 768 + 384 + 512 = 2,304 MiB (about 515 MiB in use), on
    networks `nkap-agent-demo_edge` and `nkap-agent-demo_internal`. It publishes no host port and runs its own cloudflared as a container.
  - Docker disk: 914 MB images, 66 MB volumes, 1.6 GB build cache.
- **The owner's decisions.**
  - chat-ledger gets its **own** Cloudflare tunnel container, token and Docker networks. Nothing is shared with Nkap Agent.
  - SSH stays as is.
  - Memory: about 1.2 GiB in total (web 640 MiB, PostgreSQL 384 MiB, refresh loop 192 MiB) plus a small tunnel.

**What this changes.**
- Task 9's single-container entrypoint does not fit a per-process memory budget. The refresh loop becomes its own container (D3).
- Flyway leaves the web process, so the web process never holds the schema owner's password (D4).
- Secrets move from environment variables to files (D6).
- HSTS must be written by the application for every response in production (D10).
- The image is built and published by CI (D1, D2).

## 1. The image

**One image, three commands** (web, refresh, migrate), built from the repository by a multi-stage `Dockerfile` at the root:

1. **Build stage:** `maven:3.9-eclipse-temurin-21` pinned by digest. It copies the POMs first and resolves dependencies (a cached layer),
   then copies the sources and runs `mvn -B -DskipTests package -pl web -am`.
   - Tests do not run here: CI runs them in the job this one depends on.
   - Nothing from the build stage reaches the runtime image but `web/target/web-*.jar`.
2. **Runtime stage:** `gcr.io/distroless/java21-debian12:nonroot` pinned by digest.
   - No shell, no package manager, user `nonroot` (uid 65532).
   - The jar goes to `/app/app.jar`, owned by root and read-only to the process.
   - `ENTRYPOINT ["java", "-jar", "/app/app.jar"]`. The compose file passes the command (`demo-refresh ...`, `db-migrate`) and the JVM
     options (`JAVA_TOOL_OPTIONS`).
   - `WORKDIR /tmp`.
   - Labels: source URL, commit SHA, build date. No secret values.

**Digests**, looked up today from my machine with `docker buildx imagetools inspect` (read-only queries to the public registries):

| Image | Tag | Version behind the tag | Index digest |
|---|---|---|---|
| Runtime | `gcr.io/distroless/java21-debian12:nonroot` | Java 21.0.10 | `sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d` |
| Build | `maven:3.9-eclipse-temurin-21` | JDK 21.0.12.1+1 | `sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320` |
| Database | `postgres:16.15-alpine` (= `16-alpine` today) | PostgreSQL 16.15 | `sha256:aa6cb33cf4c19c765cefcf48d96176258800870a9a1e930ad126200f9d825139` |
| Tunnel | `cloudflare/cloudflared:latest` | **not shown by the image's labels** | `sha256:4955524aeaa379e154023d5a027c250f7b35041a2e7e950cf13f144bc9b9c5ba` |

What I could **not** verify:
- **cloudflared.** I could not see which release `latest` is. In stop 2 I pin a numbered release tag with its digest, read from the
  release page and `cloudflared --version` in the image. I never pin `latest`.
- **Signatures.** That these digests are the publishers' builds, beyond the registries' word. Neither cosign signature is checked.
- **Java versions.** The runtime JRE (21.0.10) is older than the build JDK (21.0.12). Distroless lags; whether a newer distroless digest
  exists by stop 2 is unverified.
- **The test database.** Tests run on `postgres:16.4-alpine`, production would be 16.15 (D9: both move to the same digest).

**No secret in a layer.**
- A `.dockerignore` excludes `.env*`, `.git`, `**/target`, `tools/`, `docs/`, `.kiro/` and `*.pem`.
- The build takes no `ARG` holding a secret.
- A CI step lists every file in the saved image (`docker save | tar -t` per layer) and fails on `.env`, `*.pem`, `id_*` or anything
  under `/root`.
- `docker history --no-trunc` must show no secret-looking value (the same names as `NoBackDoorTest`).

**Health check.**
- Docker does not restart an unhealthy container in Compose, so a Docker `HEALTHCHECK` only shows state.
- The web image has no shell or `wget`. So a `health-probe` command mode in `ChatLedgerApplication` (like `provision-*`) makes one
  `GET http://127.0.0.1:8080/health` with `java.net.http`, without starting Spring, and exits 0 or 1. It runs with `-Xmx16m`,
  `-XX:TieredStopAtLevel=1`, every 60 s.
- Its cost is about 30 MiB for about 1 s, counted inside the web container's limit (section 2).
- `-XX:+ExitOnOutOfMemoryError` makes an out-of-memory JVM exit, so `restart: unless-stopped` brings it back.
- The external monitor (section 6) checks the whole path through Cloudflare.

**Read-only root filesystem.**
- Web and refresh run with `read_only: true`, a `tmpfs` `/tmp` (Tomcat's work directory, 64 MiB, `noexec,nosuid`), `cap_drop: [ALL]` and
  `security_opt: [no-new-privileges:true]`.
- PostgreSQL runs with `read_only: true`, its data volume, and a `tmpfs` for `/var/run/postgresql` and `/tmp`. It runs as uid 70
  (`user: "70:70"`) once the volume exists. Whether the official alpine image initialises a named volume as uid 70 without root is
  **unverified**; stop 2 checks it locally first. If it fails, PostgreSQL keeps its default (root entrypoint, then `postgres`) with
  `cap_drop: [ALL]` and only the capabilities its entrypoint needs added back, named in the register.
- cloudflared: `read_only: true`, its own non-root user, `cap_drop: [ALL]`.

**Published by CI (D1, D2).**
- A new job `image` runs on pushes to `main` only, after `build-and-test` passes. Its permissions are job-level only:
  `contents: read`, `packages: write`.
- It logs in to GHCR with the built-in `GITHUB_TOKEN`, pushes `ghcr.io/deval123/chat-ledger:sha-<commit>` and `:main`, and records the
  pushed digest in the job summary.
- No new secret is added. `GITHUB_TOKEN` is scoped to this repository's packages and expires with the job, so it can write nothing else.
- The actions this job uses (`docker/setup-buildx-action`, `docker/login-action`, `docker/build-push-action`) are pinned by commit SHA,
  not tag, because they hold a write token. The SHAs are resolved in stop 2 (**unverified** here).
- The other jobs keep `contents: read`.
- **The VPS pulls without a credential.** The package is public (the repository is public and the image holds no secret). The VPS pulls
  by digest (`image: ghcr.io/deval123/chat-ledger@sha256:...`), so a moved tag never changes what runs.
- If the owner wants the package private, the VPS needs a read-only token (`read:packages`). That token reads every package of the
  account, which is why I recommend public (D2).
- A Trivy scan of the image runs in the same job, pinned by SHA. It fails on fixable HIGH or CRITICAL findings and lists the rest (D11).

## 2. The compose file for the VPS

One file, `deploy/compose.yaml`, with `name: chat-ledger`, so every command and every Docker object is prefixed `chat-ledger_`. It is
never run without that file, so it cannot address Nkap Agent's containers.

**Services**

| Service | Image | Command | Networks | Memory / CPU | Notes |
|---|---|---|---|---|---|
| `db` | `postgres:16.15-alpine@sha256:...` | `postgres -c ...` (section 4) | `internal` | 384 MiB / 0.75 | Volume `db-data`; no `ports:`; `shm_size: 64m` |
| `migrate` | chat-ledger image | `db-migrate` | `internal` | 256 MiB / 0.5 | One-shot (`profiles: [ops]`); the only holder of the migrate login |
| `web` | chat-ledger image | (default) | `internal`, `edge` | 640 MiB / 1.0 | `app.demo.enabled=true`; no `DEMO_DB_*`; no migrate login |
| `refresh` | chat-ledger image | `demo-refresh --pool 30 --every 600s` | `internal` | 192 MiB / 0.5 | The demo operator and application logins |
| `tunnel` | `cloudflare/cloudflared:<release>@sha256:...` | `tunnel --no-autoupdate run` | `edge`, `egress` | 96 MiB / 0.25 | Its own token; the only container with internet egress |

**Every service:**
- `restart: unless-stopped` (migrate: `no`);
- `read_only: true`, `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`;
- `pids_limit` 200 (db 100, tunnel 50);
- `mem_limit` equal to `memswap_limit`, so no swap;
- logging `json-file` with `max-size: 10m`, `max-file: 3`;
- no `privileged`, no `ports:`, no `network_mode: host`, no Docker socket, no bind mount outside `/opt/chat-ledger`, no `extra_hosts`.

`depends_on` with `condition: service_healthy` on `db` (its health check is `pg_isready` from the image).

**Networks**, three and all new. None is shared with `nkap-agent-demo_*`:
- `internal` (`internal: true`): db, migrate, web, refresh. It has no route out.
- `edge` (`internal: true`): web and tunnel only. cloudflared reaches `http://web:8080` here, and nothing else is on it.
- `egress` (normal bridge): tunnel only, so it can reach Cloudflare.

The web process therefore has **no internet route at all**. It never makes an outbound call, and an exploit inside it cannot reach the
internet, the host's other containers, or the cloud metadata address. Web and db are not on any network with a route out.

**The tunnel's ingress** (configured in the Cloudflare dashboard for this tunnel only, D5):
1. `ledger.nkap.dev` → `http://web:8080`;
2. a catch-all `http_status:404`.

Nothing else is reachable through it. The management port (8081, section 5) is not routed.

**Memory budget** (D7). The owner's numbers, with the JVM settings I propose and what each container holds:

| Container | Limit | Inside it |
|---|---|---|
| web | 640 MiB | heap `-Xms192m -Xmx288m`, `-XX:MaxMetaspaceSize=160m`, `-XX:ReservedCodeCacheSize=48m`, `-XX:MaxDirectMemorySize=32m`, `-Xss512k`, `-XX:+UseSerialGC`, `-XX:+ExitOnOutOfMemoryError`; Tomcat `threads.max=40`; Hikari `maximum-pool-size=8`; about 30 MiB for the health probe |
| refresh | 192 MiB | `-Xmx96m`, `-XX:MaxMetaspaceSize=64m`, `-XX:ReservedCodeCacheSize=24m`, `-Xss512k`, `-XX:+UseSerialGC`, `-XX:TieredStopAtLevel=1`, `-XX:+ExitOnOutOfMemoryError`; one Argon2 hash (19 MiB) per workspace created |
| db | 384 MiB | `shared_buffers=64MB`, `work_mem=2MB`, `maintenance_work_mem=32MB`, `effective_cache_size=192MB`, `max_connections=30`, `shm_size` 64 MiB (counted in the limit) |
| tunnel | 96 MiB | cloudflared (Nkap Agent gives its own 128 MiB; actual use **unverified**) |
| **Total** | **1,312 MiB (1.28 GiB)** | `migrate` (256 MiB) runs only during a deploy, while `refresh` is stopped |

**What this leaves.**
- With the owner's figures (about 2.7 GiB available with Nkap Agent up), chat-ledger at its full limits leaves about **1.4 GiB free**.
  That is at least 1 GiB, as asked.
- This holds against Nkap Agent's **current use** (about 515 MiB), **not against its limits** (2,304 MiB). If both projects hit their
  limits at once they would need about 3.6 GiB of the 3.7 GiB, and the host would have almost nothing left. The hard limits make sure
  chat-ledger can never take more than 1.28 GiB, whatever happens inside it.
- The heap and native estimates are mine: **unverified**. Stop 2 measures them locally with `-XX:NativeMemoryTracking=summary` while
  seeding 30 workspaces and running the smoke script. On the VPS, `sudo docker stats --no-stream` after the first full refresh pass
  confirms them.
- If the web process needs more than 640 MiB, I lower `-Xmx` and Tomcat's threads before asking for more memory.

**CPU.** The limits are 1.0 + 0.5 + 0.75 + 0.25 = 2.5 of 2 vCPU, a soft overcommit. A limit caps a container; it reserves nothing.
- The busiest moment is the first refresh pass (30 seeds).
- During a JVM start the web process is capped at 1 CPU, so a start takes longer (section 6).

**Disk budget** (D8): chat-ledger uses at most **5 GB** of the 33 GB free:
- images: two versions kept, about 0.4 GB each (**unverified** until built);
- the database volume, **3 GB budget**, so the 80% line is **2.4 GB**;
- logs: five containers, at most 3 × 10 MB each.

The demo's measured size (about 150 KB per visited workspace, task 9 D25) makes 2.4 GB about 16,000 visited workspaces.

## 3. Secrets and configuration

**Where they live.**
- Everything is under `/opt/chat-ledger/` on the VPS, owned by root.
- `/opt/chat-ledger/` is mode 0750. `compose.yaml` and `config.env` (non-secret settings: image digests, hostname, hops, pool size) are
  mode 0640.
- `/opt/chat-ledger/secrets/` is mode 0710, one file per secret, mounted with Compose `secrets:`, which become files under `/run/secrets/`
  in the container.
- Each file is owned by `root` and the group of the one uid that reads it (65532 for the app, 70 for PostgreSQL, the cloudflared image's
  uid), mode 0440.
- Nothing secret is in the repository, the compose file, `config.env`, an image layer or an environment variable. So `docker inspect`
  shows no secret.

| Secret | File | Read by |
|---|---|---|
| PostgreSQL bootstrap superuser | `pg_superuser_password` | db (initdb only, `POSTGRES_PASSWORD_FILE`) |
| Migrate login (schema owner) | `migrate_password` | db (initdb), migrate |
| Application login | `app_password` | db (initdb), web, refresh |
| Provisioner login | `provisioner_password` | db (initdb) only: provisioning is refused on a demo database, but the role script requires the login |
| Demo operator login | `demo_operator_password` | db (initdb), refresh. **Never web** |
| `APP_SECRET` | `app_secret` | web, refresh (the same value, so phone hashes agree) |
| Cloudflare tunnel token | `tunnel_token` | tunnel |

**Generation.** On the VPS, as root, one command per file; no value is ever typed or shown:

```sh
sudo install -d -m 0750 -o root -g root /opt/chat-ledger
sudo install -d -m 0710 -o root -g root /opt/chat-ledger/secrets
for f in pg_superuser_password migrate_password app_password provisioner_password demo_operator_password app_secret; do
  sudo sh -c "umask 077; openssl rand -hex 32 > /opt/chat-ledger/secrets/$f"
done
```

- Each value is 32 random bytes, 64 hex characters. `APP_SECRET` is therefore 256 bits; `KeyedHasher` requires at least 32 characters.
- `sudo chgrp` and `chmod 0440` per file, per the table above.
- The tunnel token is pasted once from the Cloudflare dashboard into `tunnel_token` with `sudo tee` and `read -rs`, so it is not in the
  shell history.
- The owner keeps an encrypted copy of `secrets/` offline (password manager). It is regenerable: losing it means re-creating the demo
  database, nothing more.

**Code changes this needs** (stop 2):
1. `01_create_roles.sh` reads `*_FILE` variables (for example `MIGRATE_PASSWORD_FILE`) as well as the plain ones.
2. The web process reads its two secrets through Spring's `configtree` (`spring.config.import=optional:configtree:/run/secrets/`, files
   named `spring.datasource.password` and `app.secret`).
3. `DemoCommand` and the new `db-migrate` accept `DB_PASSWORD_FILE`, `DEMO_DB_PASSWORD_FILE` and `APP_SECRET_FILE`.
   - A variable and its `_FILE` both set is refused.
   - A file that is world-readable is refused.
   - The value never appears in output.

**The logins stay separate.** Migrate is used only by the `migrate` one-shot (D4). Application is used by web and refresh. Provisioner is
used by nobody on this deployment. The demo operator is used by refresh only. `DEMO_DB_*` exists only on this deployment.

**Configuration (not secret)**, in `config.env` and the compose file:
- `SPRING_PROFILES_ACTIVE=prod` (a new profile, section 5);
- `DB_URL=jdbc:postgresql://db:5432/chatledger`, `DB_USER`, `DEMO_DB_USER`;
- `app.demo.enabled=true` (web only);
- `APP_SECURITY_TRUSTED_PROXY_HOPS`, and `JAVA_TOOL_OPTIONS` per container.

A CI test parses `deploy/compose.yaml` and fails on any of these:
- a `dev` profile;
- a `ports:` key;
- `privileged`;
- a Docker socket mount;
- `DEMO_DB_*` on `web`;
- a secret value in `environment:`;
- a missing memory limit;
- a network that is not one of the three.

**`app.security.trusted-proxy-hops` for the tunnel: measured, not guessed** (D12). The value must be the number of entries that
Cloudflare's edge and cloudflared append to `X-Forwarded-For`. I believe Cloudflare's edge appends the client address and cloudflared adds
none, which would make it 1, but **I do not trust that belief**. The measurement, before the hostname goes public:

1. Deploy with `APP_SECURITY_TRUSTED_PROXY_HOPS=0` (the safe default: the header is ignored).
2. For ten minutes, route the tunnel's hostname to a throwaway echo container on the `edge` network instead of `web`
   (`traefik/whoami` pinned by digest; it prints every request header). It runs with the same hardening, and is removed afterwards.
3. From the owner's laptop, find the public address Cloudflare sees for it, then send a request with a forged entry:

   ```sh
   curl -s https://ledger.nkap.dev/ -H 'X-Forwarded-For: 198.51.100.7'
   curl -s https://ledger.nkap.dev/
   ```
4. Read three headers in the echo: `X-Forwarded-For`, `Cf-Connecting-Ip` (Cloudflare's own view of the client), and `X-Forwarded-Proto`.
   - The hops are **the number of entries to the right of `198.51.100.7`**, counted so that the entry `hops` places from the right equals
     `Cf-Connecting-Ip`.
   - For example, `198.51.100.7, 203.0.113.20` with `Cf-Connecting-Ip: 203.0.113.20` means hops = 1.
   - If cloudflared added its own entry, it would be 2.
   - The second request (no forged header) must show exactly `hops` entries.
5. If IPv6 is available, repeat from an IPv6 client. The entry must be an IPv6 literal (accepted by `ClientAddress.ipLiteral`).
6. Set the value, route the hostname back to `http://web:8080`, remove the echo container, and paste the two outputs into the notes.
7. The smoke script then proves it on the real application: six claims from one address are allowed and the next is refused. That
   costs six of the hour's sixty, so it runs once, before judging.

If the measurement shows something other than a fixed count (for example a header that varies by request), the value stays 0. I would
then propose reading `Cf-Connecting-Ip` instead, as a new design decision, not in this stop.

## 4. PostgreSQL

**Server settings** as `command:` arguments, so they are visible in the compose file and cannot drift:

```
postgres -c log_error_verbosity=terse -c log_statement=none -c log_min_duration_statement=-1
         -c log_min_error_statement=panic -c log_parameter_max_length=0 -c log_parameter_max_length_on_error=0
         -c log_connections=off -c log_disconnections=off -c shared_buffers=64MB -c work_mem=2MB
         -c maintenance_work_mem=32MB -c effective_cache_size=192MB -c max_connections=30 -c max_wal_size=256MB
```

- `terse` drops `DETAIL: Failing row contains (...)`.
- `log_min_error_statement=panic` stops the default (ERROR), which would write the text of every failing statement.
- `log_statement=none` and `log_min_duration_statement=-1` stop statement and slow-query logging.
- The two parameter lengths at 0 log no bound values.

**Check** (the owner runs it after every deploy; it is also in the smoke script):

```sh
sudo docker compose -f /opt/chat-ledger/compose.yaml exec db psql -U postgres -At -c \
  "SELECT name || '=' || setting FROM pg_settings WHERE name IN ('log_error_verbosity','log_statement','log_min_duration_statement',
   'log_min_error_statement','log_parameter_max_length','log_parameter_max_length_on_error') ORDER BY 1"
```

Expected output: six lines with the values above.

**Backups (D13): the demo database is not backed up, on purpose.**
- What it holds: synthetic workspaces, retired and scrubbed ones, and any free text a visitor typed before retirement.
- A backup would keep that free text after the retire step has overwritten it.
- Nothing in it is needed after a loss: "restore" means drop the database, run `demo-init`, run `demo-refresh`. That takes minutes.
- What is backed up instead: `secrets/` (the encrypted offline copy above), plus the compose file and `config.env`, which come from the
  repository.
- **Restore test:** stop 2 runs the whole "from nothing" sequence locally against `deploy/compose.yaml` (with a local tunnel stub). The
  owner runs it once on the VPS before the hostname goes public, and after any loss.
- If the owner wants a real tenant on this host later, that is a different deployment, with backups, and out of scope here.

**Size plan (D8).** The budget is 3 GB, so the line is 2.4 GB.
- The smoke script prints `pg_database_size('chatledger')`, and the uptime workflow (section 6) can read nothing private, so the owner
  checks size weekly with:

  ```sh
  sudo docker compose -f /opt/chat-ledger/compose.yaml exec db psql -U postgres -At -c \
    "SELECT pg_size_pretty(pg_database_size('chatledger'))"
  ```
- At or above 2.4 GB:
  1. `docker compose stop web refresh`.
  2. As the bootstrap superuser: `DROP DATABASE chatledger WITH (FORCE)`, then `CREATE DATABASE chatledger OWNER <migrate user>`.
  3. `docker compose run --rm migrate`, then `run --rm refresh demo-init`, then `run --rm refresh demo-refresh --pool 30`.
  4. `docker compose up -d web refresh`.

  No trigger is touched, and only this project's database is involved. The commands are in the runbook (section 6), word for word.

## 5. Headers, cookies, limits; what Cloudflare does and what the app still enforces

**New profile `prod`** (`application-prod.properties`):
- `spring.flyway.enabled=false` (D4);
- Tomcat `threads.max=40`; Hikari `maximum-pool-size=8`, `minimum-idle=2`;
- `management.server.port=8081`, so `/actuator/health` leaves the public port, and the tunnel routes 8080 only;
- `server.forward-headers-strategy=none` written out, so Tomcat's RemoteIpValve never rewrites the client address behind our
  `ClientAddress`;
- `app.security.hsts=always`.

`StartupGuards` refuses `prod` together with `dev`.

**HSTS (D10).**
- Spring writes HSTS only when the request is secure. Behind the tunnel the application sees HTTP, so today there is no HSTS.
- A new bounded setting `app.security.hsts` (`if-secure` by default, or `always`) makes the application write
  `Strict-Transport-Security: max-age=31536000; includeSubDomains` on every response.
  - `includeSubDomains` covers only names under `ledger.nkap.dev`, never `nkap.dev`'s other names.
  - No `preload`.
- `always` is refused by `StartupGuards` under `dev`.
- I prefer this to Cloudflare's own HSTS setting, which is zone-wide and would also change the other project's hostnames.
- Tested: the header is present on every response with `always`, and absent over plain HTTP with the default.

**CSP unchanged.**
- The policy stays exactly `EXPECTED_CSP`.
- Several Cloudflare features inject scripts or rewrite pages, and would break it or weaken it: Rocket Loader, Email Address
  Obfuscation, Web Analytics automatic injection, Zaraz, and Auto Minify.
- **They must be off for this hostname.** They are zone settings, so changing them may affect the other project; the owner decides,
  using a Configuration Rule scoped to `ledger.nkap.dev` where the plan allows it (**unverified** for this account's plan).
- The smoke script fails if the CSP header through the tunnel differs by one byte, or if a page contains a `<script>` not served from
  `/assets/`.

**Cookie.**
- `__Host-chatledger-session` with `Secure`, path `/`, no domain. `StartupGuards` refuses anything else outside `dev`, so the `prod`
  container cannot start weakened. The browser accepts it, because it sees HTTPS at Cloudflare's edge.
- Redirects are relative (Tomcat's default), so no `http://` link leaks. The smoke script checks that every `Location` starts with `/`.

**Health.**
- `/health` stays public with `{"status":"UP"}` and nothing else.
- `/actuator/health` moves to port 8081 (not routed), with details `when-authorized`, which means never for an anonymous caller.
- The smoke script checks that `https://ledger.nkap.dev/actuator/health` is 404.

**Request limits.**
- Unchanged in the application: 4 KB sign-in, 16 KB JSON, 64 KB forms, 16 KB headers, multipart off.
- Cloudflare's own limit (100 MB on the free plan, **unverified** for this account) is far above them. The application's limits are the
  ones that hold.

| Concern | Cloudflare (edge and tunnel) | The application, still |
|---|---|---|
| TLS, certificate, HTTP to HTTPS | Terminates TLS; "Always Use HTTPS" on (**owner checks**) | Cookie Secure and `__Host-`; HSTS `always`; relative redirects |
| Exposure | The origin has no public port; only the tunnel's one route reaches `web:8080` | Deny by default; `@PreAuthorize` on every handler; `/demo` exists only on the demo deployment |
| Volumetric attacks | DDoS absorption; optional one rate-limiting rule on `POST /demo` (D14) | Per-address (from `X-Forwarded-For` at the measured hops), per-account, per-tenant and global limits; the row cap; the pool size |
| Bots | Bot Fight Mode **off** for this hostname (its JavaScript challenge would block the no-JavaScript path and judges' tools) | The same limits; CSRF on every POST |
| Content | No caching of pages (HTML is not cached by default; pages are `no-store`); `/assets/` cacheable | CSP, escaping, template lint, CSV neutralisation |
| Client address | Writes `X-Forwarded-For` and `Cf-Connecting-Ip` | Trusts `X-Forwarded-For` only at the configured hops, only an IP literal |

## 6. Operations

**Warm-up note** (for the README and the submission).
- The VPS does not sleep, so there is no cold start for visitors, except right after a deploy or a restart.
- Then the web JVM needs about 30 to 60 s at 1 CPU (**unverified**, measured in stop 2), and Cloudflare answers 502 until it is up.
- The first refresh pass after `demo-init` seeds 30 workspaces, about one minute (from `DemoRefreshIT`: 10 in about 18 s; **unverified**
  on the VPS). `/demo` says "all in use" until the first ones are ready.
- The deploy steps below seed before routing traffic.

**First deploy** (owner, on the VPS). Each step has an expected output to paste back.
1. **Prerequisites** (section 7, checks 1 to 4).
2. **Directory and secrets** (section 3).
3. **Files.** Copy `deploy/compose.yaml` from the release and write `config.env` with the image digest from the CI job summary.
4. **Pull and start the database.**
   `sudo docker compose -f /opt/chat-ledger/compose.yaml pull`, then `up -d db`, then wait for `healthy`.
5. **Prepare the demo database.**
   `run --rm migrate`, then `run --rm refresh demo-init`, then `run --rm refresh demo-refresh --pool 30`.
6. **Start the rest.** `up -d web refresh tunnel`.
7. **Measure the hops** (section 3), set the value, then `up -d web` again.
8. **Smoke script, logging check, size, and `docker stats`.**

**A new version (D15): short downtime, not zero.**
1. Record the current digest (`config.env` is kept as `config.env.<date>`).
2. Set the new digest and `pull`.
3. If the release adds a migration: `stop refresh`, then `run --rm migrate`.
4. `up -d web refresh`.
5. Smoke.

Zero downtime would need two web containers and a balancer, about 640 MiB more, which the budget does not have. Expected downtime is the
JVM start, about 30 to 60 s (**unverified**).

Two effects to name, since sessions and rate-limit buckets live in memory (register item 5):
- every visitor is signed out, and their leased workspace stays CLAIMED until its lease ends, so a visitor who comes back takes a new one;
- the limits start from zero.

So deploys happen outside judging hours.

**Rollback.**
1. Restore `config.env.<date>`, then `up -d web refresh`.
2. If the new release had added a migration, the old image may not match the schema. The demo database is disposable, so the rollback is
   the size plan's reset (drop, `migrate` with the old image, `demo-init`, `demo-refresh`).
3. Migrations stay forward-only; no down-migration is written.

**Smoke script** (`deploy/smoke.sh`, POSIX `sh` with `curl`, run from the owner's laptop, no secrets). It takes one claim of the hour's
sixty. Each check prints PASS or FAIL, and it exits non-zero on any FAIL.
1. `GET /` → 302 to `/demo`; `GET /demo` → 200.
2. The exact CSP; HSTS `max-age=31536000; includeSubDomains`; no `Server`, no `X-Powered-By`; `Cache-Control: no-store`.
3. `GET /actuator/health` → 404; `GET /health` → `{"status":"UP"}`.
4. `POST /demo` with the cookie jar and the page's CSRF token → 302 to `/app`; the cookie is `__Host-`, Secure and HttpOnly.
5. `GET /app` contains "Demo, made-up data".
6. `POST /app/demo/scenarios/happy-path/both` → 302 to `/app?n=DEMO_PAYMENT_SENT`, and the board shows one more matched.
7. `GET /app/books` contains "The books balance."
8. Every `Location` header starts with `/`; no page has a `<script>` outside `/assets/`.
9. `POST /logout`.

On the VPS, a second part (`deploy/check-host.sh`, read-only, needs `sudo`) prints:
- the six log settings;
- `pg_database_size`;
- `docker stats --no-stream` for `chat-ledger_*`;
- `docker compose ps`;
- that no `chat-ledger` container publishes a port, and that `ss -tlnp` shows nothing new listening.

**Uptime monitoring at no cost (D16).**
- A scheduled GitHub Actions workflow, `uptime.yml`, runs every 30 minutes with `permissions: {}` and no secret.
  - It requests `https://ledger.nkap.dev/health`, expects `{"status":"UP"}`, and also `GET /demo` → 200.
  - A failure emails the owner (GitHub's notification for failed scheduled runs).
- No third party learns anything beyond what is already public.
- Limits:
  - the schedule is best effort (runs can be late);
  - GitHub disables scheduled workflows after 60 days without repository activity;
  - it runs from GitHub's addresses, so it never uses up a judge's per-address limit (it does not claim).

**Runbook: the end of judging.** Everything is scoped to the project name, so nothing of Nkap Agent is touched.
1. **Stop serving.** `sudo docker compose -f /opt/chat-ledger/compose.yaml down`. This removes chat-ledger's containers and its three
   networks and keeps its volume. Then, in the Cloudflare dashboard, delete **this** tunnel and the `ledger.nkap.dev` DNS record only;
   that revokes its token.
2. **Destroy chat-ledger's data**, when the owner decides:
   - `sudo docker compose -f /opt/chat-ledger/compose.yaml down --volumes` removes only `chat-ledger_db-data`;
   - `sudo docker image rm` on chat-ledger's own image and its pinned postgres and cloudflared digests, **only if Nkap Agent does not use
     the same image**: check with `sudo docker ps -a --format '{{.Image}}'` first;
   - `sudo shred -u /opt/chat-ledger/secrets/*`, then `sudo rm -r /opt/chat-ledger`.
3. **Never run, on this shared host:**
   - `docker system prune` in any form;
   - `docker volume prune`, `docker network prune`, `docker image prune -a`;
   - `docker compose down` without `-f /opt/chat-ledger/compose.yaml`;
   - `docker rm -f $(docker ps -aq)`;
   - a restart of the Docker daemon or the host without warning (it restarts Nkap Agent's containers too);
   - `ufw reset`, or any change to the `nkap-agent-demo_*` networks, the other tunnel, or `DOCKER-USER` rules that the other project's
     runbook added.
4. **Optional:** delete the GHCR package versions, and disable `uptime.yml`.

## 7. Host preparation (owner, on the VPS)

These are read-only checks unless stated. Each result is pasted back before the first deploy.
1. **LLMNR (5355).** Change: set `LLMNR=no` in a drop-in, `/etc/systemd/resolved.conf.d/no-llmnr.conf` (`[Resolve]` then `LLMNR=no`), then
   `sudo systemctl restart systemd-resolved`.
   Check: `sudo ss -lntup | grep 5355` must print nothing; `resolvectl status | grep -i llmnr` must show `-LLMNR`.
   Today ufw already denies it from outside, so this removes a listener, not an open door. **Unverified** until pasted.
2. **The metadata address.** Whether the other project's rule dropping container traffic to `169.254.169.254` is in place:
   `sudo iptables -S DOCKER-USER`, `sudo ip6tables -S DOCKER-USER`, and `sudo nft list ruleset | grep -n 169.254`.
   - I do not assume it is there. Only `tunnel` has a route out, so web, db and refresh cannot reach the address in any case.
   - If the rule is absent, I propose adding one for chat-ledger's `egress` subnet only (`-s <subnet> -d 169.254.169.254 -j DROP`), so
     nothing changes for Nkap Agent. The owner decides (D17).
   - Whether Docker 29 on this host uses the iptables or the nftables backend is **unverified**: `sudo docker info | grep -i firewall`.
3. **No collision.** `sudo docker network ls` and `sudo docker volume ls` must show nothing starting `chat-ledger_` before the first
   deploy. `sudo docker ps --format '{{.Names}} {{.Ports}}'` must show no published port afterwards.
4. **Room.** `free -m` and `df -h /var/lib/docker /opt` before and after. `sudo docker builder prune` is **not** run by me, because the
   1.2 GB reclaimable build cache may be the other project's; the owner decides.
5. **SSH stays as is** (the owner's decision). Key only, no root, ufw allows only 22.

## 8. Weakened defenses, scope, decisions, verification

### Weakened defenses and their compensation
1. **A second project shares the kernel and the Docker daemon.** A container escape on either side reaches both. Compensation:
   - non-root containers, `cap_drop: ALL`, `no-new-privileges`, read-only roots;
   - no socket and no host network; distroless (no shell) for the application;
   - separate networks, with web and db on internal-only networks.

   User namespaces are not enabled, because that is a daemon-wide change that would affect Nkap Agent. Named, not fixed.
2. **The `debian` user's `sudo docker` is root on the host**, for both projects. Unchanged (owner's machine and choice). Every command in
   the runbook names the compose file.
3. **The application trusts `X-Forwarded-For`** at the measured number of hops. It is measured through the real tunnel and recorded;
   the default is 0; only an IP literal is accepted. The origin is reachable only through cloudflared on an internal network, so no one
   else can write the header to it.
4. **The demo database is not backed up** (D13). Its content is disposable and synthetic; a backup would keep free text that retire
   scrubs; the reset is minutes.
5. **Short downtime on every deploy**, which signs visitors out and resets the in-memory limits. Deploys happen outside judging hours;
   the limits are back at their full values right away.
6. **A public image.** It holds no secret (CI checks the layers); the source is public anyway.
7. **Cloudflare sees all traffic in clear** (TLS ends at its edge). Inherent to the tunnel the owner chose. Only synthetic data is in
   this demo.
8. **The health probe runs a second small JVM** inside the web container every 60 s. 30 MiB for about 1 s, counted in the limit; the
   alternative was a shell in the image.
9. **Memory is safe against the other project's current use, not its limits.** Hard limits keep chat-ledger at 1.28 GiB whatever happens
   inside it; both projects at their limits would leave the host almost nothing.
10. **CPU limits add up to 2.5 of 2 vCPU** (soft overcommit). They are caps; the busy moments (a JVM start, the first refresh pass) are
    short and outside judging when planned.
11. **Uptime checks run from GitHub on a best-effort schedule.** No third party; late runs are possible; the smoke script is the real
    check after a deploy.
12. **The Cloudflare settings that would break the CSP are zone settings**, shared with the other project's hostnames. Scoped by a
    Configuration Rule where the plan allows it, otherwise the owner decides. The smoke script fails if the CSP or the pages change.

### What I will not build in task 13
- A second web instance or a load balancer.
- Backups of the demo database.
- A Kubernetes or Swarm setup.
- User namespaces or any daemon-wide Docker change.
- Changes to the other project's tunnel, networks or firewall rules.
- A paid monitor.
- Cloudflare WAF rules beyond the one optional rate rule (D14).
- Image signing (cosign); named, not done.
- A non-demo (real tenant) deployment on this host.

### Decisions I need from you

| # | Decision | Recommendation | Reason |
|---|---|---|---|
| D1 | Who builds the image | CI on pushes to `main`, after the tests, multi-stage `Dockerfile`, runtime distroless `java21-debian12:nonroot`, all bases pinned by digest | Reproducible; no shell in production; the VPS never builds |
| D2 | Registry and how the VPS pulls | GHCR, public package, pushed with the job-scoped `GITHUB_TOKEN` (`packages: write` on that job only), pulled by digest with no credential | No new secret anywhere; a moved tag changes nothing that runs |
| D3 | Web and refresh loop | Two containers from one image (web 640 MiB, refresh 192 MiB); `docker/demo-entrypoint.sh` is kept for single-container hosts and not used here | Per-process memory limits; the web container never even receives `DEMO_DB_*` |
| D4 | Migrations | A one-shot `migrate` service (`db-migrate` command, Flyway only, no Spring) holds the schema owner's login; the web process runs with Flyway off | The web process never holds the owner's password |
| D5 | Tunnel | Its own cloudflared container and token (owner's decision), on `edge` and `egress` only; ingress `ledger.nkap.dev` → `http://web:8080`, catch-all 404 | One route to one port; nothing shared with Nkap Agent |
| D6 | Secrets | One file each under `/opt/chat-ledger/secrets/` (root, 0440 to the one reading uid), Compose `secrets:`; `*_FILE` support in the role script and the commands, `configtree` in the web process | `docker inspect` shows none; nothing in the repository or the image |
| D7 | Memory and heap | web 640 MiB (`-Xmx288m`, metaspace 160m, code 48m, direct 32m, SerialGC, 40 threads), refresh 192 MiB (`-Xmx96m`, metaspace 64m, code 24m, C1 only), db 384 MiB (settings in section 4), tunnel 96 MiB: 1.28 GiB in total, no swap | The owner's budget; leaves about 1.4 GiB at the other project's current use; measured in stop 2 |
| D8 | Disk | 5 GB for chat-ledger: database volume 3 GB, reset at 2.4 GB; logs 3 × 10 MB per container; two image versions kept | 80% line from the owner's rule; logs cannot fill the disk |
| D9 | PostgreSQL version | 16.15-alpine by digest, and the tests move to the same digest | One version under test and in production; 16.4 lacks two years of fixes |
| D10 | HSTS | `app.security.hsts=always` in `prod` (one year, `includeSubDomains`, no preload), refused under `dev` | The application sees HTTP behind the tunnel; Cloudflare's own HSTS is zone-wide |
| D11 | Image scan | Trivy in the image job, pinned by SHA, failing on fixable HIGH/CRITICAL | The CI scan covered Maven dependencies only, not the OS layer |
| D12 | Proxy hops | Measured through the real tunnel with a throwaway echo container and a forged `X-Forwarded-For`; 0 until measured; recorded in the notes | Not guessed; a wrong value lets a client choose its key |
| D13 | Backups | None for the demo database; secrets copied offline; a "from nothing" restore drill locally in stop 2 and once on the VPS | Disposable synthetic data; a backup would keep free text that retire scrubs |
| D14 | Cloudflare rate rule | Optional: one rule on `POST /demo` (the free plan's one rule), as a second wall; the application's limits are the ones that count | Cheap defense in depth; not relied on |
| D15 | Deploys | Short downtime (30 to 60 s), outside judging hours; rollback by the previous digest, or by the database reset if a migration was added | Zero downtime does not fit the memory budget |
| D16 | Monitoring | A scheduled GitHub Actions check of `/health` and `/demo` every 30 minutes, `permissions: {}` | No account, no third party, no cost |
| D17 | Metadata address | Check first; if absent, a DROP rule for chat-ledger's `egress` subnet only | Only the tunnel has a route out; nothing changes for Nkap Agent |
| D18 | Management port | `/actuator/health` on 8081, not routed; `/health` stays public with `{"status":"UP"}` only | The public port shows no actuator at all |
| D19 | Cloudflare features | Rocket Loader, Email Obfuscation, Web Analytics injection, Zaraz, Auto Minify, Bot Fight Mode off for this hostname; Always Use HTTPS on | They break the CSP or the no-JavaScript path; owner checks the zone |

### Verification plan

**In CI** (every push, Docker available on the runner):
- Unit tests for:
  - the `_FILE` readers (refused when both are set, refused when world-readable, value never printed);
  - `db-migrate` argument and environment refusals;
  - `health-probe` (exit codes against a stub server);
  - `app.security.hsts` bounds and the `dev` refusal;
  - the `prod` profile with `StartupGuards`;
  - the compose-file test (section 3).
- An integration test of the `prod` profile: HSTS on every response, `/actuator/health` 404 on 8080, `/health` minimal, CSP unchanged,
  Flyway off.
- Image job on `main`: build, layer listing check, `docker history` check, Trivy, a start of the image against a throwaway PostgreSQL
  container with the compose file's settings, then a run of `deploy/smoke.sh` against it (with a local stand-in for the tunnel and
  `trusted-proxy-hops=0`). The digest goes into the job summary.
- Every guard mutation-checked, as in tasks 7 to 9.

**Locally, in stop 2** (my machine, not the VPS):
- `deploy/compose.yaml` started as written, with a local stand-in for cloudflared.
- Memory measured with Native Memory Tracking and `docker stats` while seeding 30 workspaces and running the smoke script.
- The "from nothing" drill and the database reset.
- PostgreSQL as uid 70 on a read-only root.

**Only on the VPS** (the owner runs them and pastes the output; until then each is **unverified**):
- section 7 checks 1 to 4;
- the hops measurement;
- the first deploy steps;
- the smoke script through `ledger.nkap.dev`;
- `check-host.sh` (log settings, size, `docker stats`, no published port);
- the Cloudflare settings of D19;
- one rollback rehearsal before judging.

### Facts I still need from the owner (all on the VPS or in the Cloudflare dashboard)
1. Output of section 7 checks 1 to 4 (LLMNR, metadata rule and firewall backend, no `chat-ledger_` objects, `free -m`/`df -h`).
2. The Cloudflare plan of the `nkap.dev` zone, and whether Configuration Rules can scope D19's settings to `ledger.nkap.dev`.
3. Which of D19's settings are on today for the zone.
4. The image Nkap Agent's tunnel runs (`sudo docker ps --format '{{.Names}} {{.Image}}'`), only to know whether removing a cloudflared image
   at the end could affect it.
5. The hops measurement output (section 3), at deploy time.

### Unverified in this proposal
- Everything about the VPS beyond the two pasted checks.
- The cloudflared release behind the digest above.
- That the four digests are the publishers' signed builds.
- Distroless's JRE lagging the build JDK (21.0.10 against 21.0.12) by stop 2.
- The heap and native memory estimates.
- The image size.
- The JVM start time at 1 CPU.
- The first seed's duration on the VPS.
- PostgreSQL's alpine image initialising a named volume as uid 70.
- Cloudflare's free-plan limits for this account.
- Whether cloudflared supports reading the token from a file in the pinned release (otherwise it takes `TUNNEL_TOKEN` from a Compose
  secret through a tiny wrapper, which needs a shell; then the tunnel keeps its own image's entrypoint and the token in its environment,
  named in the register).
- What Cloudflare and cloudflared write to `X-Forwarded-For` (measured at deploy, D12).

### Estimated credits

Not visible to me from inside the session; read it from the Kiro status bar. By scope I expect stop 2 to be smaller than task 9's:
- a Dockerfile, a compose file, two scripts, a CI job and a workflow;
- three small commands, a profile and their tests;
- local measurements.

The VPS steps are the owner's. That is a guess, not a measurement.

If you say "go" without changes, I take my recommendation on all nineteen decisions.

## 9. The owner's changes, as built (stop 2)

### Three image jobs
| Job | Permissions | What it runs | Third-party code |
|---|---|---|---|
| `image-build` | `contents: read` | `docker build` (the runner's own Docker), `docker save` to `chat-ledger.tar`, layer and history checks, upload as an artifact | `actions/checkout`, `actions/upload-artifact` (SHA-pinned) |
| `image-scan` | `contents: read` | Trivy from its image pinned by digest, against the archive (`--input`), failing on fixable HIGH and CRITICAL | `actions/download-artifact` (SHA-pinned); the Trivy image (digest-pinned) |
| `image-push` | `contents: read`, `packages: write`, only on `main` | download the archive, `docker load`, check that its image id is the one built and scanned, `docker login ghcr.io` with `GITHUB_TOKEN` on stdin, `docker push`, check that the pushed manifest names the same config | `actions/download-artifact` (SHA-pinned); nothing else |

No action from `docker/*` or `aquasecurity/*` is used. The Docker CLI on the runner is enough to build, save, load and push, so the
job that holds the write token runs only GitHub's own artifact action and the Docker CLI.

### Action pins, and how they were checked
Each SHA is what the action's own repository's tag resolves to (`git ls-remote https://github.com/<repo>.git refs/tags/<tag>^{}`). A
tag ref exists only in the repository itself, never in a fork, so a commit pushed to a fork and reached through the parent's network
cannot carry it. A non-draft, non-prerelease GitHub release exists for each tag (`gh api repos/<repo>/releases/tags/<tag>`). The major
versions already used by CI are kept.

| Action | Release | Commit | Release published |
|---|---|---|---|
| `actions/checkout` | v4.4.0 | `11d5960a326750d5838078e36cf38b85af677262` | 2026-07-20 |
| `actions/setup-java` | v4.9.1 | `cf277c60eb25467037889841efdb72551f06f6c3` | 2026-08-04 |
| `actions/cache` | v4.3.0 | `0057852bfaa89a56745cba8c7296529d2fc39830` | 2025-09-24 |
| `actions/upload-artifact` | v4.6.2 | `ea165f8d65b6e75b540449e92b4886f43607fa02` | 2025-03-19 |
| `actions/download-artifact` | v4.3.0 | `d3f86a106a0bac45b974a628896c90dbdf5c8093` | 2025-04-24 |

What I could **not** verify:
- That the release commits were built from reviewed code (I read no diffs).
- That the five commits are on a release branch. `checkout` and `setup-java` v4 are maintained off `main` ("diverged" in the
  compare API), and my second compare query failed with 404, so I have no branch evidence for those two.

Pinned images:
- **Trivy:** `aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb`. 0.76.2 is the latest
  GitHub release; the digest was read from Docker Hub. Its signature is not checked.
- **The hops echo:** `traefik/whoami:v1.12.0@sha256:c4717a8d1f0134a7444e24f881160e033991f23027c6c5a9a3f8fd22e70d1d44`.

### Public package
A new runbook step runs once, after the first push:
1. In the GitHub UI, open the `chat-ledger` package, then Package settings, Change visibility, Public.
2. Check from the VPS, with no login: `sudo docker logout ghcr.io; sudo docker pull ghcr.io/deval123/chat-ledger@sha256:<digest>`. It must
   succeed.

Until it is public, the pull fails with "denied" or "unauthorized" and nothing on the VPS is changed.

### Tunnel token from a file: yes
I tested the pinned release on my machine:
- `cloudflare/cloudflared:2026.10.7` is the same digest as `latest` today
  (`sha256:4955524aeaa379e154023d5a027c250f7b35041a2e7e950cf13f144bc9b9c5ba`). It prints `cloudflared version 2026.10.7 (built
  2026-10-30-17:17 UTC)` and runs as user 65532.
- `tunnel run --help` lists `--token-file` (`$TUNNEL_TOKEN_FILE`).
- With a read-only root, `--cap-drop ALL`, `no-new-privileges` and a fake token in `/run/secrets/tunnel_token`, it answers "Provided
  Tunnel token is not valid". It read the file.
- With a missing file it answers "Failed to read token file".

So the compose file uses `--token-file /run/secrets/tunnel_token`, and the token is in no environment variable. A real connection with
a real token is **unverified** until the first deploy.
