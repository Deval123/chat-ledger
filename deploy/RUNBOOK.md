# chat-ledger demo on the shared VPS: runbook

Task 13. The design is in `.kiro/specs/chat-ledger/task-13-design-stop.md`; the decisions it names (D1 to D19) are there.

This host also runs another project (Nkap Agent: five containers, two `nkap-agent-demo_*` networks, its own tunnel). Everything here
is scoped to chat-ledger: every Docker command names `-f /opt/chat-ledger/compose.yaml`, so it can only address `chat-ledger_*`
objects. Read "Never run on this host" (last section) before the first command.

Docker on this host is root-only (the `debian` user is not in the `docker` group): every Docker command is `sudo docker ...`. Every
block below that uses `DC` expects this alias, set once per shell:

```sh
alias DC='sudo docker compose -f /opt/chat-ledger/compose.yaml'
```

Nothing below has been run on the VPS by the author. Each step says what to expect; paste the output back when it differs.

## Layout

| Path | Owner, mode | What |
|---|---|---|
| `/opt/chat-ledger/` | root:root 0750 | the project directory |
| `compose.yaml` | root:root 0640 | `deploy/compose.yaml` of the release |
| `.env` | root:root 0640 | non-secret settings (`deploy/env.example`): the image digest, the hops, the pool |
| `.env.<date>` | root:root 0640 | the previous `.env`, kept for rollback |
| `initdb/` | root:root **0755** | bind-mounted as a directory, so PostgreSQL (uid 70) must be able to list it; holds no secret |
| `initdb/01_create_roles.sh` | root:root 0755 | `docker/initdb/01_create_roles.sh`; executable, so it runs in its own process (a non-executable one is sourced into the image's entrypoint) |
| `secrets/`, `secrets/db/`, `secrets/app/` | root:root 0700 | written by `make-secrets.sh` |
| `secrets/db/*` | root:70 0440 | read by PostgreSQL |
| `secrets/app/*` | root:65532 0440 | read by the chat-ledger image and cloudflared |
| `make-secrets.sh`, `check-host.sh`, `check-processes.sh`, `hops-echo.yaml` | root:root 0640 | from `deploy/` |

`smoke.sh` runs from the owner's laptop, not from the VPS.

## 1. Host preparation (once, before the first deploy)

1. **LLMNR (port 5355).**
   ```sh
   sudo install -d -m 0755 /etc/systemd/resolved.conf.d
   printf '[Resolve]\nLLMNR=no\n' | sudo tee /etc/systemd/resolved.conf.d/no-llmnr.conf >/dev/null
   sudo systemctl restart systemd-resolved
   sudo ss -lntup | grep 5355          # expected: nothing
   resolvectl status | grep -i llmnr   # expected: -LLMNR
   ```
2. **The metadata address (D17).** Read-only first:
   ```sh
   sudo docker info --format '{{.FirewallBackend.Driver}}'
   sudo iptables -S DOCKER-USER
   sudo ip6tables -S DOCKER-USER
   sudo nft list ruleset | grep -n 169.254
   ```
   Only the tunnel container has a route out; web, refresh and db cannot reach `169.254.169.254` at all (no route). If no rule drops
   chat-ledger's egress subnet (`10.213.7.0/28`, fixed in `compose.yaml`) to that address, the owner decides whether to add one, for
   that subnet only, so nothing changes for the other project. With the `iptables` backend:
   ```sh
   sudo iptables -I DOCKER-USER -s 10.213.7.0/28 -d 169.254.169.254/32 -j DROP
   ```
   A `DOCKER-USER` rule does not survive a reboot by itself. Persist it the way the other project persists its own rules, if it does
   (unknown to the author), and re-check after each reboot: `check-host.sh` reports it.
3. **No collision.**
   ```sh
   sudo docker network ls | grep chat-ledger   # expected: nothing
   sudo docker volume ls | grep chat-ledger    # expected: nothing
   ip -4 route | grep '^10\.213\.7\.'          # expected: nothing (the egress subnet is free)
   ```
4. **Room.** `free -m` and `df -h /var/lib/docker /opt`. chat-ledger needs at most 1,376 MiB (1.34 GiB) of memory while serving (web 640, refresh 256, db 384, tunnel 96) and 5 GB of
   disk. With the other project at its current use (about 2.7 GiB available), that leaves about 1.3 GiB free. Do not run
   `docker builder prune`: the build cache may be the other project's.
5. **SSH stays as it is** (owner's decision).

## 2. Cloudflare (once, in the dashboard)

1. Create a **new** tunnel for chat-ledger (never reuse the other project's). Copy its token for step 3.3 below.
2. **No public hostname yet.** The tunnel keeps only its catch-all rule, `http_status:404`, and no DNS record exists for
   `ledger.nkap.dev`. The hostname is created at the moment of the hops measurement (section 4), pointed at the echo first and at
   `http://web:8080` only after the echo is gone. Nothing else is ever routed.
3. For `ledger.nkap.dev` only (a Configuration Rule where the plan allows it), D19: Rocket Loader off, Email Obfuscation off, Web
   Analytics automatic injection off, Zaraz off, Auto Minify off, Bot Fight Mode off; Always Use HTTPS on. These would break the CSP or
   the no-JavaScript path; the smoke script fails if they are on.
4. Optional (D14): one rate-limiting rule on `POST /demo` per client address.
5. Do not turn on the zone's HSTS setting: it is zone-wide and would change the other project's hostnames. The application writes HSTS
   for its own hostname.

## 3. First deploy

1. **Prerequisites:** sections 1 and 2 done.
2. **The files.** From a checkout of the released commit on the laptop:
   ```sh
   scp deploy/compose.yaml deploy/env.example deploy/make-secrets.sh deploy/check-host.sh deploy/check-processes.sh deploy/hops-echo.yaml \
       docker/initdb/01_create_roles.sh debian@<vps>:/tmp/
   ```
   On the VPS:
   ```sh
   sudo install -d -m 0750 -o root -g root /opt/chat-ledger
   sudo install -d -m 0755 -o root -g root /opt/chat-ledger/initdb
   sudo install -m 0755 -o root -g root /tmp/01_create_roles.sh /opt/chat-ledger/initdb/
   for f in compose.yaml make-secrets.sh check-host.sh check-processes.sh hops-echo.yaml; do sudo install -m 0640 -o root -g root /tmp/$f /opt/chat-ledger/; done
   sudo install -m 0640 -o root -g root /tmp/env.example /opt/chat-ledger/.env
   rm /tmp/compose.yaml /tmp/env.example /tmp/make-secrets.sh /tmp/check-host.sh /tmp/check-processes.sh /tmp/hops-echo.yaml /tmp/01_create_roles.sh
   ```
3. **The secrets.**
   ```sh
   sudo sh /opt/chat-ledger/make-secrets.sh
   # expected: Written: /opt/chat-ledger/secrets/db (5 files, root:70 0440) and .../app (4 files, root:65532 0440) ...
   sudo sh /opt/chat-ledger/make-secrets.sh --tunnel-token
   # paste the tunnel token at the prompt (not shown), Enter. expected: Written: .../app/tunnel_token (root:65532 0440).
   ```
   Keep an encrypted copy of `/opt/chat-ledger/secrets/` offline (password manager). Losing it means re-creating the demo database,
   nothing more (D13).
4. **The release.** Edit `/opt/chat-ledger/.env` (`sudo nano`): `CHAT_LEDGER_DIGEST` from the summary of CI's "Image, push" job on
   `main`. Leave `APP_SECURITY_TRUSTED_PROXY_HOPS=0`.
5. **The package is public (once, after the first push).** In GitHub: the `chat-ledger` package, Package settings, Change visibility,
   Public. Then check that an anonymous pull works from the VPS:
   ```sh
   sudo docker logout ghcr.io
   sudo docker pull "ghcr.io/deval123/chat-ledger@$(sudo grep '^CHAT_LEDGER_DIGEST=' /opt/chat-ledger/.env | cut -d= -f2)"
   # expected: Status: Downloaded newer image ... ; "denied" or "unauthorized" means it is still private. Nothing was changed.
   ```
6. **Pull and start the database.**
   ```sh
   DC pull db web tunnel
   DC up -d db
   DC ps db        # expected after about 30 s: (healthy)
   ```
7. **Prepare the demo database.**
   ```sh
   DC run --rm migrate                               # expected: Migrations applied: 20; schema version 020.
   DC run --rm refresh demo-init                     # expected: This database is a demo deployment (mode DEMO).
   DC run --rm refresh demo-refresh --pool 30        # expected: Demo pool: retired 0, created 30, failed 0.
   ```
8. **Start the rest.**
   ```sh
   DC up -d web refresh tunnel
   DC ps           # expected: db, web (healthy), refresh, tunnel (healthy)
   ```
   The tunnel is connected but routes nothing yet: `dig +short ledger.nkap.dev` from the laptop prints nothing.
9. **Measure the hops, then route the hostname to web** (section 4, every step in order).
10. **Check.** On the VPS: `sudo sh /opt/chat-ledger/check-host.sh` (expected: no FAIL). From the laptop:
    `sh deploy/smoke.sh https://ledger.nkap.dev` (expected: `Failures: 0`). Paste both outputs back.
11. **Memory on this CPU** (measured only under emulation on amd64; register 158). After the first full refresh pass:
    ```sh
    sudo docker stats --no-stream $(sudo docker ps -q --filter label=com.docker.compose.project=chat-ledger)
    for c in web refresh db; do echo "$c OOMKilled=$(sudo docker inspect -f '{{.State.OOMKilled}}' chat-ledger-$c-1) restarts=$(sudo docker inspect -f '{{.RestartCount}}' chat-ledger-$c-1)"; done
    ```
    Expected: every `OOMKilled=false restarts=0`. refresh has 256 MiB (raised from 192 after the emulated amd64 seed was killed at
    192); if it, or web at 640 MiB, was killed anyway, paste it back: the limits are the owner's to change.

## 4. Measure the hops (D12), then route the hostname

`APP_SECURITY_TRUSTED_PROXY_HOPS` is the number of `X-Forwarded-For` entries the tunnel path appends. It is measured, never guessed;
0 (the header is ignored) until then.

The echo (`traefik/whoami`, pinned by digest in `hops-echo.yaml`) carries fixable findings in its binary. It is accepted for this one
measurement only (register 157): the same hardening as every service (read-only root, no capability, no-new-privileges, uid 65532,
32 MiB, never restarted), on the `edge` network only, which has no route out; about ten minutes, then removed. `check-host.sh` fails
whenever a hops-echo container exists at any other time.

1. **Before.** web, refresh and the tunnel run (section 3, step 8); the tunnel has no public hostname, and `dig +short ledger.nkap.dev`
   prints nothing.
2. **Start the echo**, and check it runs as configured:
   ```sh
   sudo docker compose -f /opt/chat-ledger/compose.yaml -f /opt/chat-ledger/hops-echo.yaml up -d hops-echo
   sudo sh /opt/chat-ledger/check-processes.sh   # expected: hops-echo configured as 65532:65532, read-only root, no FAIL
   ```
3. **Create the hostname, pointed at the echo.** In the dashboard, add this tunnel's public hostname `ledger.nkap.dev` with the service
   `http://hops-echo:8080` (this creates the DNS record). Note the time: the echo is removed within ten minutes of it.
4. **Measure**, from the laptop, a forged entry and a plain request:
   ```sh
   curl -s https://ledger.nkap.dev/ -H 'X-Forwarded-For: 198.51.100.7'
   curl -s https://ledger.nkap.dev/
   ```
   Read `X-Forwarded-For` and `Cf-Connecting-Ip` in each output.
   - The hops are the number of entries to the right of `198.51.100.7`, counted so that the entry `hops` places from the right equals
     `Cf-Connecting-Ip`. For example `198.51.100.7, 203.0.113.20` with `Cf-Connecting-Ip: 203.0.113.20` is 1.
   - The plain request must show exactly `hops` entries.
   - If an IPv6 client is available, repeat from it.
   - If the count is not fixed, the value stays 0 and the owner reports it (a new design decision would read `Cf-Connecting-Ip`).
5. **Remove the echo, right away**, before anything else:
   ```sh
   sudo docker compose -f /opt/chat-ledger/compose.yaml -f /opt/chat-ledger/hops-echo.yaml rm -sf hops-echo
   sudo sh /opt/chat-ledger/check-host.sh | grep 'hops echo'   # expected: PASS no hops echo container
   ```
   Until step 6 the hostname answers 502 (its service is gone); that is expected and short.
6. **Only now, route the hostname to web.** Set `APP_SECURITY_TRUSTED_PROXY_HOPS=<n>` in `.env`, run `DC up -d web` and wait for
   `(healthy)`; then in the dashboard change the public hostname's service to `http://web:8080`. Paste the two curl outputs into the
   notes, then continue with section 3, step 10.

## 5. A new version (D15: 30 to 60 s of downtime, unverified)

Outside judging hours: every visitor is signed out and the rate-limit counters restart (they live in memory).

```sh
sudo cp -p /opt/chat-ledger/.env "/opt/chat-ledger/.env.$(date +%Y%m%d-%H%M)"
sudo nano /opt/chat-ledger/.env          # the new CHAT_LEDGER_DIGEST
DC pull web
# only if the release adds a migration:
DC stop refresh
DC run --rm migrate
# then:
DC up -d web refresh
DC ps                                    # expected: web (healthy)
```

Then the smoke script from the laptop. If `compose.yaml` itself changed in the release, copy it as in step 3.2 first.

## 6. Roll back

```sh
sudo cp -p /opt/chat-ledger/.env.<date> /opt/chat-ledger/.env
DC up -d web refresh
```

If the release being rolled back had added a migration, the old image may not match the schema. The demo database is disposable: roll
back with the reset (section 7) using the old digest. Migrations are forward-only.

## 7. Size: the 2.4 GB line, and the reset

Weekly, or when `check-host.sh` fails on size:

```sh
DC exec -T db psql -U postgres -d chatledger -At -c "SELECT pg_size_pretty(pg_database_size('chatledger'))"
```

At or above 2.4 GB (80 % of the 3 GB budget), the reset, which also restores the database from nothing:

```sh
DC stop web refresh
DC exec -T db psql -v ON_ERROR_STOP=1 -U postgres -d postgres \
   -c "DROP DATABASE chatledger WITH (FORCE)" -c "CREATE DATABASE chatledger OWNER chatledger_migrate"
DC run --rm migrate
DC run --rm refresh demo-init
DC run --rm refresh demo-refresh --pool 30
DC up -d web refresh
```

Only this project's database is touched; no trigger is changed. Visitors in a workspace lose it (it is made-up data).

## 8. Regular checks

- `sudo sh /opt/chat-ledger/check-host.sh`: log settings, size, containers and memory, no published port, no privileged container, the
  secret files' modes, LLMNR, the metadata rule. Expected: no FAIL.
- The `Uptime` workflow in GitHub Actions checks `/health` and `/demo` every 30 minutes; a failed run emails the owner.

## 9. The end of judging

1. **Stop serving.**
   ```sh
   DC down          # removes chat-ledger's containers and its three networks; keeps its volume
   ```
   In the dashboard, delete **this** tunnel and the `ledger.nkap.dev` record only. That revokes its token.
2. **Destroy chat-ledger's data**, when the owner decides:
   ```sh
   DC down --volumes                       # removes chat-ledger_db-data only
   sudo docker ps -a --format '{{.Image}}' # before removing an image: is it used by the other project?
   sudo docker image rm ghcr.io/deval123/chat-ledger@<digest>   # and postgres/cloudflared digests only if unused by the other project
   sudo shred -u /opt/chat-ledger/secrets/db/* /opt/chat-ledger/secrets/app/*
   sudo rm -r /opt/chat-ledger
   ```
3. **Optional:** delete the GHCR package versions; disable the `Uptime` workflow; remove the metadata rule if one was added for
   `10.213.7.0/28` (`sudo iptables -D DOCKER-USER -s 10.213.7.0/28 -d 169.254.169.254/32 -j DROP`).

## 10. Pinned digests go stale, and when a scan finds something

Every image is pinned by digest, so nothing changes under the deployment by itself, and nothing is fixed by itself either. A
vulnerability can be published against a pin that has not moved (this happened in task 13: the distroless base and Jackson 3.1.5).

**Seeing it, read-only.**
- The `Pinned digests` workflow (GitHub Actions, weekly on Monday, or "Run workflow" by hand) has no permission, no secret and no
  action. Its `report` job lists every pin as CURRENT, STALE (the tag now points elsewhere, usually a rebuilt base with fixes) or
  UNKNOWN. Its `scan` job scans the images that run here (PostgreSQL, cloudflared, and the application image of the latest `main`)
  for fixable HIGH or CRITICAL findings. A STALE pin or a finding fails the run, and GitHub emails the owner. It changes nothing.
- The same by hand, from a checkout on the laptop (Docker needed, nothing on the VPS):
  ```sh
  sh deploy/check-digests.sh
  sh deploy/scan-runtime.sh "ghcr.io/deval123/chat-ledger@$(ssh debian@<vps> sudo grep '^CHAT_LEDGER_DIGEST=' /opt/chat-ledger/.env | cut -d= -f2)"
  ```

**The image job fails by design** when the application image has a HIGH or CRITICAL finding with a fix available. Nothing is pushed,
so the VPS keeps running the last good digest. A finding without a fix is listed and does not fail. Never add an ignore file for a
finding that has a fix. To fix:
1. Read the failed `Image, scan` job: each finding names its package, the fixed version, and whether it is an OS package (the base) or
   a library inside `app.jar`.
2. **An OS package of the base.** `docker buildx imagetools inspect gcr.io/distroless/java21-debian13:nonroot` (or `sh
   deploy/check-digests.sh`).
   - If the tag points to a newer digest: re-pin it in the `Dockerfile`, record its Java version
     (`docker run --rm --entrypoint java <image>@<digest> -version`), rebuild and scan locally (below).
   - If it does not (the base has not been rebuilt yet): stop and decide between another pinned base and waiting; see register
     item 152 (how debian12 was replaced by debian13). No exclusion. A new base must still be Java 21 (`check-image.sh`, `DockerfileTest`).
3. **A library in the jar.** Find who manages it (`mvn -pl web dependency:tree -Dincludes=<group>:<artifact>`). Override the Spring
   Boot property in the root `pom.xml` to the fixed release of the same minor line (as `jackson-bom.version`), with a test that fails
   if the override is removed (as `JacksonVersionTest`). Remove the override once Boot manages a fixed release.
4. Unit phase with Docker stopped (`mvn -o clean test`), then `mvn -o clean verify`, then `mvn -P security-check verify -DskipTests`.
5. Scan locally, as CI does:
   ```sh
   docker build --platform linux/amd64 -t chat-ledger:test . && docker save chat-ledger:test -o /tmp/chat-ledger.tar
   docker run --rm -v /tmp/chat-ledger.tar:/work/chat-ledger.tar:ro \
     aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb \
     image --input /work/chat-ledger.tar --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 --no-progress
   rm /tmp/chat-ledger.tar
   ```
6. Push; once CI pushes the image, deploy the new digest (section 5).

**PostgreSQL, cloudflared and the hops echo** are not built by CI, so only the weekly scan sees them (four images in all, with the
application image). The one accepted finding: the Go standard-library findings in PostgreSQL's `gosu`, and only while
`sh deploy/check-gosu.sh` passes (register 155: gosu is never executed, because db starts as uid 70). If it fails, the scan fails on
them again: find out what changed (the image, or the db service's settings) before anything else. Re-pin them the same way (a newer digest of the
same tag, or the next release), run the drill (`sudo sh deploy/test-stack.sh /tmp/stack` on a Linux machine with Docker), then
`DC pull db tunnel` and `DC up -d db tunnel` outside judging hours.

## Never run on this host

The other project shares the Docker daemon, its image store, its volumes and the firewall.

- `docker system prune` in any form.
- `docker volume prune`, `docker network prune`, `docker image prune -a`, `docker builder prune`.
- `docker compose ...` without `-f /opt/chat-ledger/compose.yaml` (it would pick up whatever compose file is in the directory).
- `docker rm -f $(docker ps -aq)` or any command over all containers.
- A restart of the Docker daemon or of the host without warning the other project (its containers restart too).
- `ufw reset`; any change to the `nkap-agent-demo_*` networks, the other tunnel, or firewall rules the other project added.
