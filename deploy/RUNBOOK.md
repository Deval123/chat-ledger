# chat-ledger demo on the shared VPS: runbook

Task 13. The design is in `.kiro/specs/chat-ledger/task-13-design-stop.md`; the decisions it names (D1 to D19) are there.

This host also runs another project (Nkap Agent: five containers, two `nkap-agent-demo_*` networks, its own tunnel). Everything here
is scoped to chat-ledger: every Docker command names `-f /opt/chat-ledger/compose.yaml`, so it can only address `chat-ledger_*`
objects. Read "Never run on this host" (last section) before the first command.

Docker on this host is root-only (the `debian` user is not in the `docker` group): every Docker command is `sudo docker ...`. Every
block below that uses `DC` expects this alias, set once per shell, **on its own line**:

```sh
alias DC='sudo docker compose -f /opt/chat-ledger/compose.yaml'
```

Bash reads a whole line before it applies an alias defined on it, so `alias DC=...; DC ps` pasted as one line fails with
`DC: command not found`. Define `DC` first, press Enter, then paste the block that uses it.

Two more shell traps on this host:

- Every edit of `/opt/chat-ledger/.env`, whatever it changes (the digest, the hops, the pool), starts with a dated copy, so there is
  always something to roll back to:

  ```sh
  sudo cp -p /opt/chat-ledger/.env "/opt/chat-ledger/.env.$(date +%Y%m%d-%H%M)"
  ```

- `/opt/chat-ledger` is root-only, so a glob there must be expanded by root's shell, not by the `debian` user's:
  `sudo ls /opt/chat-ledger/.env*` finds nothing; use `sudo sh -c 'ls -l /opt/chat-ledger/.env*'`.

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

## Deployed state

What runs now, so a change starts from the right place. Update this table with every deploy.

| Item | Value | Since |
|---|---|---|
| Release | `ghcr.io/deval123/chat-ledger@sha256:a0376662809095ffddadd193262d8ec0a9f595629cb59081e3b6f082cb370d00` (CI run #153, commit `67e3408`: PRs #40, #44 and #45, merged up to #46, schema V021) | 2026-11-24 |
| `APP_SECURITY_TRUSTED_PROXY_HOPS` | `1`, measured through the tunnel over IPv4 (section 4); the IPv6 repeat is still open | 2026-11-10 |
| Public hostname | `ledger.nkap.dev` → `http://web:8080`; catch-all `http_status:404` | 2026-11-10 |
| Zone settings of `nkap.dev` (D19) | Rocket Loader off, Bot Fight Mode off, Email Address Obfuscation off, Always Use HTTPS on, zone HSTS off; **zone-wide**, see section 2, step 3 | 2026-11-10 |
| Demo pool | 30 workspaces | 2026-11-10 |
| Last rehearsal | 2026-11-11: upgrade to CI run #60 and rollback to run #55 (sections 5 and 6); see `docs/kiro-notes.md` | 2026-11-11 |

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
   - **As set on 2026-11-10, zone-wide.** The settings were changed for the whole `nkap.dev` zone, not by a rule for this hostname:
     Email Address Obfuscation was on and is now off, Always Use HTTPS was off and is now on. Rocket Loader, Bot Fight Mode and the zone
     HSTS were already off. The zone is shared with another project, so these two changes apply to its hostnames too; tell its owner
     before changing them again, and prefer a Configuration Rule scoped to `ledger.nkap.dev` if the plan offers one.
   - Check: `curl -sI http://ledger.nkap.dev/ | head -3` answers `301` with a `Location` on `https://`.
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
   **Check the token's shape** before starting the tunnel (it prints the shape, never the token):
   ```sh
   sudo sh -c 't=$(cat /opt/chat-ledger/secrets/app/tunnel_token); case "$t" in eyJ*) s=ok ;; *) s="does NOT start with eyJ" ;; esac; case "$t" in *" "*) s="$s, has a space" ;; esac; echo "start: $s; length: ${#t} bytes"'
   # expected: start: ok; length: a few hundred bytes
   ```
   The token is the long string from the dashboard's install command, starting `eyJ`. The tunnel id (a 36-character UUID) is not the
   token: pasting it gives `Provided Tunnel token is not valid` in `DC logs tunnel` and a restart loop. `make-secrets.sh` refuses to
   overwrite a token, so to replace a wrong one, remove it first:
   ```sh
   sudo shred -u /opt/chat-ledger/secrets/app/tunnel_token
   sudo sh /opt/chat-ledger/make-secrets.sh --tunnel-token
   DC up -d tunnel
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
    The peak since each container started (cgroup v2 `memory.peak`; the cgroup directory is found by the container id, so the
    command does not depend on the cgroup driver's layout):
    ```sh
    for c in web refresh; do id=$(sudo docker inspect -f '{{.Id}}' chat-ledger-$c-1); f=$(sudo find /sys/fs/cgroup -type d -name "*$id*" | head -n 1); echo "$c peak $(( $(sudo cat "$f/memory.peak") / 1048576 )) MiB"; done
    ```
    First deploy, 2026-11-10, after three demo rounds: web 311 MiB (peak 349) of 640, refresh 123 MiB (peak 129) of 256, db 37 of
    384, tunnel 21 of 96; no OOM kill, no restart. The refresh peak does not yet cover a full retire-and-recreate cycle of the pool
    (section 8).

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
   - If an IPv6 client is available, repeat from it. (Still open after the first deploy: no IPv6 client was available. Repeating it
     means running this whole section again, echo and ten-minute limit included.)
   - Run the two requests from a machine with unrestricted outbound HTTPS. A workspace whose outbound access is restricted cannot reach
     the hostname; the first measurement ran from a laptop outside it.
   - If the count is not fixed, the value stays 0 and the owner reports it (a new design decision would read `Cf-Connecting-Ip`).
5. **Remove the echo, right away**, before anything else:
   ```sh
   sudo docker compose -f /opt/chat-ledger/compose.yaml -f /opt/chat-ledger/hops-echo.yaml rm -sf hops-echo
   sudo sh /opt/chat-ledger/check-host.sh | grep 'hops echo'   # expected: PASS no hops echo container
   ```
   Until step 6 the hostname answers 502 (its service is gone); that is expected and short.
6. **Only now, route the hostname to web.** Copy `.env` first (`sudo cp -p /opt/chat-ledger/.env "/opt/chat-ledger/.env.$(date +%Y%m%d-%H%M)"`),
   then set `APP_SECURITY_TRUSTED_PROXY_HOPS=<n>` in `.env`, run `DC up -d web` and wait for
   `(healthy)`; then in the dashboard change the public hostname's service to `http://web:8080`. Paste the two curl outputs into the
   notes, then continue with section 3, step 10.

Measured on 2026-11-10 over IPv4: the forged request showed 2 entries, 1 to the right of `198.51.100.7`, and that last entry equal to
`Cf-Connecting-Ip`; the plain request showed exactly 1 entry. So `APP_SECURITY_TRUSTED_PROXY_HOPS=1`. The echo was reachable for
about nine minutes (22:08 to 22:17 Paris time), then removed; `check-host.sh` reported no hops echo container.

## 5. A new version (D15: about 25 to 30 s of interruption, measured)

Outside judging hours. A restart of web signs every visitor out (sessions live in memory) and restarts the rate-limit counters (also in
memory). Measured on 2026-11-11 (section 6, "Rehearsed"): the upgrade took 38 s end to end including the pull, and `/health` answered
something other than 200 for about 27 s; the rollback took 33 s, with about 24 s without a 200. Count on 25 to 30 s of interruption
and 35 to 40 s end to end, for a release without a migration.

**The digest.** Read it from the `digest:` line of the "Push by commit" step of CI's "Image, push" job on `main`:

```sh
gh run view <run id> --log --job <image-push job id> | grep -m1 'digest: sha256:'
```

The job summary, where the push step also writes it, is not part of `gh run view --log`; open the run in the browser to see it.

**Measure the interruption** (optional; from the laptop, started before the upgrade and stopped with Ctrl-C after it): one request a
second to `/health`, then the windows without a 200:

```sh
while :; do printf '%s %s\n' "$(date -u +%H:%M:%S)" "$(curl -s -o /dev/null -m 2 -w '%{http_code}' https://ledger.nkap.dev/health)"; sleep 1; done > health.log
awk '$2 != 200 { if (!s) s = $1; e = $1; n++ } $2 == 200 && s { print s " to " e ": " n " samples without 200"; s = ""; n = 0 } END { if (s) print s " to " e ": " n " samples without 200 (still down)" }' health.log
```

**The upgrade** (define `DC` first, on its own line):

```sh
sudo cp -p /opt/chat-ledger/.env "/opt/chat-ledger/.env.$(date +%Y%m%d-%H%M)"
sudo nano /opt/chat-ledger/.env          # the new CHAT_LEDGER_DIGEST
DC pull web
# only if the release adds a migration:
DC stop refresh
DC run --rm migrate
# then:
DC up -d web refresh
DC ps                                    # wait until: web (healthy)
```

**Then, and only then, the smoke script** from the laptop: `sh deploy/smoke.sh https://ledger.nkap.dev`, expected `Failures: 0`. Run it
after `DC ps` shows `web (healthy)` and the health probe is back to 200, never while web is restarting: a run that overlaps a restart
fails on the restart and proves nothing about the release. If `compose.yaml` itself changed in the release, copy it as in step 3.2
first.

### Release note: task 12a (accessibility, money labels, French scaffolding; deployed 2026-11-15)
**What it is.**
- **PRs #29 and #30**, squashed into `main` as `30f5270` and then `b97f595`. CI run #83 on `b97f595` pushed
  `ghcr.io/deval123/chat-ledger@sha256:2104ccb989e7670d8450a56aea6858839ef535498eada4173215efd29763dc42` (the `digest:` line of its
  "Push by commit" step). Every job of that run passed.
- **Rolls back to** the release in "Deployed state": `116d11eb…`, CI run #55.

**What it changes, and what it does not.**
- **Templates, the stylesheet and the English messages:**
  - a skip link, the columns stacked on a phone, named card buttons, focus on the result after a card action;
  - "Collected that day" on the summary, linked to the books;
  - the sign-in and error pages take their text from the message file, with the same English words.

  Nothing in what is computed or posted changes.
- **French is in the image and is not served.** `messages_fr.properties` (254 of 323 keys) waits for the owner's review. The pages
  stay English: the locale resolver is fixed to `en`.
- **No migration.** Skip the `stop refresh` / `migrate` lines.
- **`compose.yaml` and `.env` are unchanged** apart from `CHAT_LEDGER_DIGEST`.
- **`smoke.sh` is unchanged** (25 checks). The four texts it reads ("Nothing here is real.", "Demo, made-up data", "The payment was
  sent.", "The books balance.") are the same in this release. In run #83, the drill's smoke run passed its 25 checks with
  `Failures: 0`.
- **The image has no test or browser code.** In run #83, `check-image.sh` passed its 12 checks, among them "no Node, Chrome,
  puppeteer or axe-core file in the image" and "app.jar holds no jsoup, axe-core, puppeteer, docker-java or Testcontainers".

**The locale guard at start-up (new with #30).** Before it serves anything, web checks three settings. All three come from the jar's
`application.properties`; neither `compose.yaml` nor `.env` sets them.

| Setting (environment form) | Required | Why |
|---|---|---|
| `spring.messages.fallback-to-system-locale` (`SPRING_MESSAGES_FALLBACK_TO_SYSTEM_LOCALE`) | `false` | Otherwise a JVM whose locale is French would serve French on English pages |
| `spring.web.locale-resolver` (`SPRING_WEB_LOCALE_RESOLVER`) | `fixed` | Boot's default, `accept-header`, would follow the browser's language |
| `spring.web.locale` (`SPRING_WEB_LOCALE`) | `en` | No other language is complete |

- **When one is wrong, web does not start.** The guard throws before the port opens. `restart: unless-stopped` then restarts web
  over and over, `DC ps` never shows `web (healthy)`, and `/health` keeps failing.
- **What the log says.** `DC logs web` names the setting: "spring.messages.fallback-to-system-locale must be false…", or
  "spring.web.locale-resolver=…, spring.web.locale=… is refused: messages_fr.properties lacks 69 keys…".
- **What to do.** Remove the setting from `.env` and run `DC up -d web`, or roll back with section 6. Never set the resolver to
  `accept-header` to try French: it stays refused until every key is translated.
- **Verified in CI only.** Run #83's drill started web with the prod profile and this guard, and web became healthy. On the VPS it is
  unverified until this deploy.

**Steps.**
1. Outside judging hours. The interruption is about 25 to 30 s and signs every visitor out.
2. Optionally, start the health probe above.
3. Run "The upgrade" above, with the dated `.env` copy and the new `CHAT_LEDGER_DIGEST`, and no migration.
4. Run `DC ps` and wait for `web (healthy)`. If web keeps restarting, read `DC logs --tail 50 web` for the locale guard (above),
   then fix or roll back.
5. After `web (healthy)` and the probe is back to 200, run `sh deploy/smoke.sh https://ledger.nkap.dev`. Expected: `Failures: 0`
   after 25 checks.
6. By hand, about two minutes on a phone:
   - on `/demo`, the first Tab shows "Skip to the main content";
   - the board stacks its columns, and nothing scrolls sideways;
   - "I checked this" on a flagged card leaves the focus on the result;
   - the summary says "Collected that day" and links to the books, and the books link back;
   - no page shows French, and the sign-in page reads as before.
7. The owner's ten-minute VoiceOver pass on the entry page, the board and one scenario. This is unverified until it is done.
8. Update "Deployed state" (release, digest, run, date) and record the times in `docs/kiro-notes.md`. A failing smoke run or a step
   that does not hold means rolling back with section 6.

**After it.** 12b gets its own stop 2 only after this deploy and its smoke run. Nothing of 12b is deployed after 2026-11-27.

### Release note: task 12b, first pull request (cancel and dismiss; deployed 2026-11-21)
**What it is.** Cancel an order nobody paid for, and dismiss a record that never reached the books. **It has a migration, V021**: the
first deploy with one.
- **Candidate image:** PR #33, squashed into `main` as `7cf50ee`. CI run #104 on that commit pushed
  `ghcr.io/deval123/chat-ledger@sha256:db34ed26d79d5744f663bf4162877d3b096204d1df9ced7780602cd12264e3c8` (the `digest:` line of its
  "Push by commit" step). Every job of that run passed.
- **Checked in that run's logs:**
  - `check-image.sh` passed its 12 checks, among them "no Node, Chrome, puppeteer or axe-core file in the image" and "app.jar holds no
    jsoup, axe-core, puppeteer, docker-java or Testcontainers";
  - the drill's smoke run passed its 25 checks;
  - the upgrade rehearsal passed.
- **Rolls back to** `2104ccb9…`, CI run #83, the release before this one (no down migration; see "Rolling back" below).
- **The second pull request of 12b (correct a total, V022) is not part of this release.** It is deferred until after judging
  (register 179).
- **The digest of the step "Rehearse the upgrade from the deployed release" in `.github/workflows/ci.yml`** stayed `2104ccb9…` until
  this deploy. It now names `db34ed26…`, the release that runs on the VPS, together with "Deployed state" (step 7).

**What V021 does.** It replaces three trigger functions on `orders`, adds a commit-time trigger on `orders` and one on `ledger_txn`,
and creates the table `record_dismissal`. It rewrites no row. Creating the triggers waits for the transactions in flight on `orders`
and `ledger_txn`, which is why refresh is stopped first. The old web keeps running during `migrate` (as below): V021 only adds a move
and checks moves that the old code never makes, so it is unaffected.

**Rehearsed in CI.** Every push runs `deploy/test-upgrade.sh` in the image job: the stack from nothing on the deployed release, used,
then this upgrade as written below. The run prints how long `migrate` took and the time without a 200. Ten demo workspaces on an amd64
runner, not the VPS's data. First run (2026-11-21): `migrate` 3 s, 11 s without a 200 (web's restart only; the old web kept answering
during `migrate`), 19 s end to end before any pull. Run #104 on the merged commit: `migrate` 4 s, 17 s without a 200, 30 s end to end
before any pull; smoke 25 of 25 after; 101 payable orders in 11 workspaces, each with its acceptance posting. The two runs differ by
the runner's speed. On the VPS add the pull, and expect the 25 to 30 s measured on 2026-11-11.

**Steps** (the upgrade above, with its migration lines):
1. Outside judging hours. Dated `.env` copy, new `CHAT_LEDGER_DIGEST`, the health probe started.
2. `DC pull web`, then `DC stop refresh`, then `DC run --rm migrate`. Expected: Flyway applies `21 - cancel and dismiss` and exits 0.
3. `DC up -d web refresh`, then `DC ps` until `web (healthy)`.
4. The books of every workspace, as the new check reads them. Expected: no row.
   ```sh
   DC exec -T db psql -U postgres -d chatledger -At -c "SELECT t.slug FROM tenant t WHERE
     (SELECT count(*) FROM ledger_txn x WHERE x.tenant_id = t.id AND x.kind = 'ORDER_ACCEPTED' AND NOT EXISTS
        (SELECT 1 FROM ledger_txn r WHERE r.tenant_id = x.tenant_id AND r.reverses_txn_id = x.id))
     <> (SELECT count(*) FROM orders o WHERE o.tenant_id = t.id AND o.status IN ('PENDING_PAYMENT', 'PARTIALLY_PAID', 'PAID'))"
   ```
   A row means a payable order without its acceptance posting, written before this release. Do not roll back for it: the books page
   says "does not agree" for that workspace only. Note the slug and tell the owner.
5. `sh deploy/smoke.sh https://ledger.nkap.dev`, expected `Failures: 0` after 25 checks.
6. By hand: in a fresh demo, cancel an unpaid order from its page (the check page first). The board drops it, and the books still
   balance. Dismiss the unreadable message of a scenario if one shows.
7. Update "Deployed state", the digest in `.github/workflows/ci.yml` (step "Rehearse the upgrade from the deployed release"), and the
   notes.

**Rolling back.** Section 6, by digest. There is no down migration: the schema stays at V021, which the old release runs on. **After a
cancel has happened**, the old release's books page reports "does not agree" for that workspace, because it still counts reversed
acceptance postings. The data is right. Prefer rolling forward with a fix; if you roll back, expect that line until the workspace
retires.

### Release note: the display fix of register 180 (deployed 2026-11-22)
- **What it was:** the page of a cancelled order no longer showed "Still to pay" (PR #35, commit `c5ebb4e`). Display only: no query,
  write or amount changed.
- **Image:** `ghcr.io/deval123/chat-ledger@sha256:1d1b70b2e2792ad7adfe7388b19c701b752ecc6d831b321d969f6c3263a48aef`, from the
  "Push by commit" step of CI run #110 (its second attempt: the first failed on a flaky test, fixed by PR #37).
- **How it went:** no migration; the schema stayed at V021. The `.env` copy was `.env.20261122-0023` (VPS time, UTC). The rollback
  target was `db34ed26…` (CI run #104).
- **Checks:** `smoke.sh` passed 25 of 25. The interruption was not measured: the probe was not started.
- **The CI rehearsal** now starts from this release (step "Rehearse the upgrade from the deployed release").

### Release note: the next release, no migration (#40, #44, #45; not deployed yet)
**What it is.** Three changes merged into `main` since the deployed release (`c5ebb4e`, CI run #110):
- **#40** (`94f4b7d`): when the engine stores a suggestion for a pair at the moment the seller matches the same pair by hand, the seller
  gets a refusal sentence ("Not matched: a suggestion for this pair arrived at the same moment. Nothing changed. Look again and
  decide."), not an error page. `MatchingService` maps that one unique violation of `uq_match_open_pair` to `PAIR_CHANGED`. Nothing is
  written, and the index is unchanged (register 182).
- **#44** (`ede9c45`): on an order's page, Confirm and Reject update the status line and "Still to pay" without a reload.
- **#45**: the same for the status line of a payment's page.

Also in the image since `c5ebb4e`: the `Dockerfile`'s build stage seeds Maven from CI's cache (#39). The runtime stage and its base
digest are unchanged.

**The candidate image is not built yet.** It is the image that CI pushes for a commit on `main` that contains #45; see "Find the
digest" below. The image carries its commit in a label, so every commit on `main` has its own digest.
- **Rolls back to** the release in "Deployed state": `1d1b70b2…`, CI run #110.

**What it changes, and what it does not.**
- **No migration.** The schema stays at V021. Skip the `stop refresh` / `migrate` lines. CI's step "Rehearse the upgrade from the
  deployed release" starts from `1d1b70b2…` and accepts a release without a migration (register 178).
- **`compose.yaml`, `env.example`, `smoke.sh` and `check-image.sh` are unchanged** since `c5ebb4e`. `.env` changes only in
  `CHAT_LEDGER_DIGEST`.
- **`smoke.sh` is unchanged** (25 checks). The four texts it reads ("Nothing here is real.", "Demo, made-up data", "The payment was
  sent.", "The books balance.") are the same in this release.
- **New texts:**
  - the refusal above;
  - after a decision on an order's or a payment's page, the notice adds "Order status: …" or "Payment status: …".

  Their French keys are in the image and not served: the locale stays fixed to `en`, and the locale guard (12a note) is unchanged.
- **The money path:** only #40's mapping of that one error to a refusal changes. No posting, amount or query changes.

**Find the digest** in the CI of `main`, in this order:
1. After #45 is merged, take the newest commit on `main`. `git log --oneline origin/main` must show #45, #44 and #40.
2. `gh run list --workflow ci.yml --branch main --event push --limit 5`: the run of workflow "CI" whose head commit is that commit.
3. In that run, every job passed: "Build and test", "Accessibility in a browser", "Image, build and drill", "Image, scan" and
   "Image, push". "OWASP dependency vulnerability check" is skipped on a push; it runs on pull requests and every day.
4. In "Image, build and drill":
   - "Check the image" passed;
   - "Run the stack and the smoke script" ends with `Failures: 0`;
   - "Rehearse the upgrade from the deployed release" passed.
5. In "Image, push", step "Push by commit", the line `<commit>: digest: sha256:<64 hex> size: …`. The `<commit>` must be the run's head
   commit:
   ```sh
   gh run view <run id> --json jobs --jq '.jobs[] | select(.name=="Image, push") | .databaseId'
   gh run view <run id> --log --job <that id> | grep -m1 'digest: sha256:'
   ```
   The run's page in the browser shows the same value in the job summary, as `CHAT_LEDGER_DIGEST=sha256:…`.

**Steps.**
1. Outside judging hours. The interruption is about 25 to 30 s and signs every visitor out.
2. Optionally, start the health probe above.
3. Run "The upgrade" above, with the dated `.env` copy and the new `CHAT_LEDGER_DIGEST`, and no migration.
4. Run `DC ps` and wait for `web (healthy)`. If web keeps restarting, read `DC logs --tail 50 web`, then fix or roll back with
   section 6.
5. After `web (healthy)` and the probe is back to 200, run `sh deploy/smoke.sh https://ledger.nkap.dev`. Expected: `Failures: 0`
   after 25 checks.
6. By hand, in a demo workspace:
   - on an order's page with a suggestion, Confirm. The status line and "Still to pay" change at once, and the notice says
     "Order status: …";
   - on a payment's page with a suggestion, Confirm. The status line changes at once, and the notice says "Payment status: …".
7. Afterwards, in one pull request:
   - update "Deployed state" (release, digest, run, date);
   - give the step "Rehearse the upgrade from the deployed release" in `.github/workflows/ci.yml` the same digest
     (`WorkflowFileTest` holds them equal);
   - record the times in `docs/kiro-notes.md`.

   A failing smoke run, or a step that does not hold, means rolling back with section 6.

## 6. Roll back

Pick the copy to restore (root's shell expands the glob):

```sh
sudo sh -c 'ls -l /opt/chat-ledger/.env*'
```

Then (define `DC` first, on its own line):

```sh
sudo cp -p /opt/chat-ledger/.env "/opt/chat-ledger/.env.$(date +%Y%m%d-%H%M)"   # the state being left, in case of second thoughts
sudo cp -p /opt/chat-ledger/.env.<date> /opt/chat-ledger/.env
DC up -d web refresh
DC ps                                    # wait until: web (healthy)
sudo docker inspect -f '{{.Config.Image}}' chat-ledger-web-1 chat-ledger-refresh-1   # expected: the restored digest, twice
sudo grep -E '^(APP_SECURITY_TRUSTED_PROXY_HOPS|DEMO_POOL)=' /opt/chat-ledger/.env   # expected: the values of the restored copy
```

Then the smoke script, as in section 5 (after `web (healthy)`).

If the release being rolled back had added a migration, the old image may not match the schema. The demo database is disposable: roll
back with the reset (section 7) using the old digest. Migrations are forward-only.

**Rehearsed on 2026-11-11** (no migration): upgrade from run #55 (`116d11eb…`) to run #60 (`85035766…`, the same code), then this
section. After the rollback, web and refresh ran `116d11eb…` again, web was healthy, the hops (1) and the pool (30) were unchanged,
and the smoke script passed (25 checks, `Failures: 0`). The smoke script was **not** run against the upgraded release, so the
rehearsal proves the mechanism and its timing, not a smoke-tested upgrade; the next upgrade runs it as section 5 says.

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
- **The rollback copies of `.env`**: `sudo sh -c 'ls -l /opt/chat-ledger/.env*'`. There must be a copy from before the last change of
  every kind (the digest, the hops, the pool); delete old ones only once the release they describe is no longer a rollback target.
- **After any upgrade or rollback**: `DC ps` shows `web (healthy)`, then the smoke script passes, in that order (section 5). The
  interruption to expect is 25 to 30 s; a longer one, measured with the health probe of section 5, is worth a look before the next one.
- **Memory peaks, after the first full refresh cycle** (a lease ends after 30 minutes and the next refresh pass, every 10 minutes,
  retires and recreates those workspaces; so about an hour after visitors started claiming): read `memory.peak` for web and refresh
  (the command in section 3, step 11). A **refresh peak above 220 MiB** is the signal to raise its limit (today 256 MiB, heap 96m):
  it leaves less than 15 % of headroom. A web peak near 640 MiB means lowering `-Xmx` and Tomcat's threads first (design stop,
  section 2). The first deploy's peaks (web 349, refresh 129) did not yet include such a cycle.

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
