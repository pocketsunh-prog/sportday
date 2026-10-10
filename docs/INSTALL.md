# SportDay — Docker install guide

Everything in this guide is done from the repository root with **Docker only**.
You do not need Java, Maven, Node or a local MySQL. You do need to be able to
type two commands and wait a few minutes for the first build.

This is the container path. The host-development path (JDK 25 + Maven + Node on
your own machine) is still in [`README.md`](../README.md#quick-start) and still
works; `docker-compose.yml` keeps publishing MySQL on the same host port it
always did, so an existing local workflow is not disturbed.

---

## 1. What you get

| Service  | Container name     | Image                       | Host URL                        |
|----------|--------------------|-----------------------------|---------------------------------|
| MySQL 8  | `sportday-mysql`   | `mysql:8.0`                 | `localhost:3307` (not for people) |
| Backend  | `sportday-backend` | `sportday-backend:1.0.0`    | <http://localhost:8080>         |
| Web app  | `sportday-frontend`| `sportday-frontend:1.0.0`   | <http://localhost:3000>         |

**Open <http://localhost:3000> and sign in.** That is the whole install. Read the
rest of this file when something does not work, or when you are putting it
somewhere other than your own laptop.

---

## 2. Prerequisites

- **Docker Desktop** (Windows/macOS) or **Docker Engine + Compose v2** (Linux).
  The guide was written and tested against Docker **29.8.2** with Compose
  **v5.5.1**.
- **Docker Compose v2 or later.** Use `docker compose` (a space). The old
  `docker-compose` v1 cannot express "wait until MySQL is *healthy*", which is
  what stops Hibernate from failing on a cold first boot.
- **Disk:** roughly 4 GB free. The MySQL image, the two Java images and the Node
  image are each a few hundred MB, and the Maven build downloads its
  dependencies on the first run.
- **RAM:** 4 GB available to Docker is comfortable; 2 GB usually works.
- **Ports** `3000`, `3307` and `8080` must be free on the host. See
  [§10](#10-common-failures) if they are not.

Check your installation:

```bash
docker --version
docker compose version
```

You do **not** need Git if you downloaded the source as a zip.

---

## 3. Get the source

```bash
git clone <repository-url> sportday
cd sportday
```

---

## 4. Start it

```bash
docker compose up -d --build
```

`--build` is what makes the first run compile the images. It takes **5–15
minutes** the first time: Maven downloads the whole Spring dependency tree and
Next.js runs a production build. Every later start is seconds.

Wait for the containers to report healthy, then open the app:

```bash
docker compose ps
```

You want `sportday-mysql` and `sportday-backend` and `sportday-frontend` all
`running` / `healthy`. Then go to <http://localhost:3000>.

Watch it come up if you would rather see the progress:

```bash
docker compose logs -f
```

---

## 5. First-boot behaviour (what happens on an empty database)

Nothing pre-creates the schema and nothing seeds it by hand. On the very first
start:

1. **MySQL initialises** the `sportday` database and the `sportday` user from the
   environment variables in `docker-compose.yml`. Its healthcheck moves to
   `healthy` only when the server is really accepting connections.
2. **The backend waits for that healthcheck** (`depends_on: mysql: condition:
   service_healthy`) and only then starts.
3. **Hibernate creates the schema.** `backend/src/main/resources/application.yml`
   sets `spring.jpa.hibernate.ddl-auto: update`, so Hibernate creates every table
   and column it needs on first boot. There is no Flyway in this project and no
   migration step to run — the SQL in `backend/db/migration/` is a set of manual
   records kept for databases that predate a change, **not** an automatic
   pipeline. On a fresh Docker database you can ignore that directory entirely.
4. **The first administrator is created by the application itself.**
   `backend/src/main/java/com/sportday/config/DataInitializer.java:54` runs
   `if (!userRepository.existsByUsername("admin"))` and, on the first boot of an
   empty database, inserts one account. The credentials it writes are in the same
   method:

   | Username | Password   |
   |----------|------------|
   | `admin`  | `admin123` |

   The line that proves it is
   [`DataInitializer.java:57`](../backend/src/main/java/com/sportday/config/DataInitializer.java#L57):

   ```java
   .password(passwordEncoder.encode("admin123"))
   ```

   and line 64 logs `Created default administrator: admin / admin123`. **Change
   this password immediately** — see [§7](#7-change-the-passwords).

   Because it is guarded by `existsByUsername("admin")`, the account is created
   once and is **never reset** on later restarts. If you change the password and
   later bring the containers up again, your password stays.

5. **The standard programme is seeded.** With `APP_EVENTS_SEED_DEFAULTS=true`
   (the default), the 112-event sport-day catalogue is created when the events
   table is empty, and the current school year is created with entries open. The
   backend logs `Seeded ... default events for ...`.
6. **No students exist yet.** Sample students are off by default
   (`SEED_SAMPLE_STUDENTS=0`). A real school imports its register as an
   administrator; for a demo install set `SEED_SAMPLE_STUDENTS=60` in `.env`
   before the first `up` and 60 generated students appear.

---

## 6. Ports

| Port | What                                             | Change it with            |
|------|--------------------------------------------------|---------------------------|
| 3000 | Web app — the address people type                | `FRONTEND_PORT` in `.env` |
| 8080 | Backend API, Swagger UI, PDF/CSV downloads       | `BACKEND_PORT` in `.env`  |
| 3307 | MySQL, published for a database tool on the host | `MYSQL_HOST_PORT` in `.env` |

The container-side ports (3000, 8080, 3306) never change.

Useful backend URLs (note: the API itself is served under `/api`, but the docs
and health endpoints are at the root):

- Swagger UI — <http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON — <http://localhost:8080/v3/api-docs> (public, and what the
  container healthcheck polls)
- The API itself — <http://localhost:8080/api/...>, e.g.
  <http://localhost:8080/api/events>

`/api/v3/api-docs` is **403** — the docs are not under the `/api` prefix.

---

## 7. Change the passwords

### The database and the JWT secret

Copy the template and edit it — `.env` is git-ignored, so your passwords never
reach the repository:

```bash
cp .env.example .env      # Windows: copy .env.example .env
```

Then change:

```dotenv
MYSQL_ROOT_PASSWORD=<a long, random root password>
MYSQL_PASSWORD=<a long, random app password>
JWT_SECRET=<output of: openssl rand -base64 48>
```

`JWT_SECRET` signs every login token, so anyone who knows it can mint an
administrator token. The value committed in `.env.example` is a placeholder for
local use only.

**A password change only takes effect on a database that is created with it.**
MySQL reads `MYSQL_PASSWORD` once, when it first initialises its data directory.
On a database that already exists, an existing volume keeps the old password and
the backend will then fail to connect. Pick one:

- **starting over** (throw the data away): `docker compose down -v` then
  `docker compose up -d --build`; or
- **keeping the data**: change the password inside MySQL and in `.env` to match:

  ```bash
  docker compose exec mysql mysql -u root -p -e \
    "ALTER USER 'sportday'@'%' IDENTIFIED BY '<new password>'; FLUSH PRIVILEGES;"
  ```

Then restart the backend: `docker compose up -d --force-recreate backend`.

### The first administrator's password

Sign in as `admin` / `admin123` and change it under **Admin → Users**. Do this
before the app is reachable by anyone else.

### Starting completely fresh

```bash
docker compose down -v     # -v removes the named volumes: ALL data is gone
docker compose up -d --build
```

`admin` / `admin123` comes back, because Hibernate re-creates the schema and
`DataInitializer` sees an empty `users` table again.

---

## 8. The API URL, and why the browser is the trap

The web app is a Next.js app. It reads its API base URL from a **compile-time**
constant, `NEXT_PUBLIC_API_URL`, in `frontend/lib/api.ts:1`:

```ts
const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api';
```

Anything starting with `NEXT_PUBLIC_` is **inlined into the JavaScript bundle by
`next build`**. It is not read from the container's environment at runtime. This
has two consequences that surprise people:

1. **The value must be a URL the browser can reach**, because the bundle runs in
   the browser. It is never used from inside the container.
2. **`http://backend:8080/api` does not work.** `backend` is a Docker Compose
   service name; it resolves only inside the compose network, through Docker's
   embedded DNS. Your browser is not on that network and will fail with
   `ERR_NAME_NOT_RESOLVED` or "Failed to fetch". This is the single most common
   mistake with a compose setup like this one.

**The correct default is `http://localhost:8080/api`**, which is what
`docker-compose.yml` builds the frontend image with. The browser resolves
`localhost` to the machine running Docker, the backend port 8080 is published
there, and the backend accepts the request (its CORS allow-list is exactly
`http://localhost:3000` — see `backend/src/main/java/com/sportday/config/SecurityConfig.java:99`).

### How the browser and the containers differ

| From where            | Backend is at              | Frontend is at             | MySQL is at          |
|-----------------------|----------------------------|----------------------------|----------------------|
| Your browser          | `localhost:8080`           | `localhost:3000`           | — (not exposed to it)|
| Inside the backend    | `localhost:8080`           | —                          | `mysql:3306`         |
| Inside the frontend   | `backend:8080` (unused)    | `localhost:3000`           | —                    |
| Another machine on the LAN | `http://<host-ip>:8080` | `http://<host-ip>:3000` | —                  |

### Changing it

Because it is baked in at build time, **rebuild the frontend image** — a restart
is not enough:

```bash
# Option A — one-off build argument
docker compose build \
  --build-arg NEXT_PUBLIC_API_URL=http://192.168.1.50:8080/api frontend
docker compose up -d frontend

# Option B — edit .env and rebuild (keeps the setting for next time)
#   NEXT_PUBLIC_API_URL=http://192.168.1.50:8080/api
docker compose build frontend
docker compose up -d frontend
```

Two things to remember when you point it somewhere other than `localhost:8080`:

- the address must be reachable **from the browser**, not from the container;
- the backend's CORS allow-list in `SecurityConfig` is `http://localhost:3000`
  only. Serving the web app on a different origin (another host, another port
  such as `http://localhost:8081`, or `http://127.0.0.1:3000`) is refused by the
  browser with a CORS error until that allow-list is widened. **Access the app at
  `http://localhost:3000`** to stay inside the shipped configuration.

---

## 9. Logs, status, backups and reset

### Logs

```bash
docker compose logs -f                # everything, following
docker compose logs -f backend        # one service
docker compose logs --tail=200 mysql  # the last 200 lines
```

The backend logs the first-boot messages described in [§5](#5-first-boot-behaviour-what-happens-on-an-empty-database)
at `INFO`. To see the SQL Hibernate emits, add `LOGGING_LEVEL_ORG_HIBERNATE_SQL=DEBUG`
to the backend's `environment:` block and `docker compose up -d backend`.

### Status

```bash
docker compose ps                     # state and health of each service
docker compose top                    # the processes inside them
docker compose exec backend sh        # a shell in the backend
docker compose exec mysql mysql -u sportday -p sportday   # a MySQL prompt
```

### Back up the database

```bash
# A consistent SQL dump of everything, written to the host.
docker compose exec -T mysql mysqldump -u root -proot123 --databases sportday \
  > sportday-backup-$(date +%Y%m%d).sql
```

On PowerShell, `$(date ...)` is not a shell substitution — write the filename
yourself:

```powershell
docker compose exec -T mysql mysqldump -u root -proot123 --databases sportday |
  Set-Content -Encoding utf8 sportday-backup.sql
```

Restore it with:

```bash
docker compose exec -T mysql mysql -u root -proot123 sportday < sportday-backup.sql
```

The database also lives in the named volume `sportday_sportday-mysql-data`, which
`docker compose down` preserves and `docker compose down -v` destroys.

The application writes its own restorable backup before a **season reset**
(Admin → Backups). Those files go to `app.backup.dir`, which compose sets to
`/app/backups` on the named volume `sportday_sportday-backend-backups`, so they
survive `docker compose down`. To copy them out:

```bash
docker compose cp backend:/app/backups ./backups-from-container
```

### Reset

```bash
docker compose restart backend        # restart one service, data kept
docker compose down                   # stop everything, data kept
docker compose down -v                # stop everything, DATA DESTROYED
docker compose up -d --build          # rebuild and start again
```

To wipe only the application data and keep MySQL itself: `down -v` removes both
volumes. To remove just the database volume:

```bash
docker compose down
docker volume rm sportday_sportday-mysql-data
docker compose up -d
```

---

## 10. Common failures

### `port is already allocated` / `bind: address already in use`

Another process holds 3000, 3307 or 8080. Find it:

```powershell
# Windows
netstat -ano | findstr :3000
Get-Process -Id <the-pid-from-the-line-above>
```

```bash
# macOS / Linux
lsof -i :3000
```

Either stop that process, or move ours in `.env`:

```dotenv
FRONTEND_PORT=3001
BACKEND_PORT=8081
MYSQL_HOST_PORT=3308
```

`docker compose up -d` afterwards. **If you move `FRONTEND_PORT` away from
3000, the browser origin changes and the backend's CORS allow-list no longer
matches** — expect CORS failures on every API call until the allow-list in
`SecurityConfig.java:99` is widened to the new origin. And if you move
`BACKEND_PORT`, remember `NEXT_PUBLIC_API_URL` has to be rebuilt to match
([§8](#8-the-api-url-and-why-the-browser-is-the-trap)).

A very common cause of the 3307 clash specifically: the **host development**
setup from the README already publishes MySQL on 3307, either as its own
`sportday-mysql` container started earlier or as a natively installed MySQL. Run
one or the other, not both — `docker compose stop mysql` stops just that service
and keeps its data.

### The frontend loads but every request fails, or the page is empty

The most likely cause by far is a wrong `NEXT_PUBLIC_API_URL`. Open the browser's
developer console.

| Symptom in the console                          | Cause                                                                 |
|-------------------------------------------------|-----------------------------------------------------------------------|
| `ERR_NAME_NOT_RESOLVED`, host `backend`         | The bundle was built with a Docker service name. Rebuild with a browser-reachable URL ([§8](#8-the-api-url-and-why-the-browser-is-the-trap)). |
| `Failed to fetch`, `ERR_CONNECTION_REFUSED`     | The URL points at a port nothing is published on, or the backend is not running (`docker compose ps`). |
| CORS error naming `http://127.0.0.1:3000` or another origin | You opened the app on an origin the backend does not allow. Use `http://localhost:3000`. |
| 401/403 on everything                           | The token was issued under a different `JWT_SECRET`, or it expired. Sign out and in again. |

Confirm what the bundle actually contains — this prints the compiled-in URL(s):

```bash
docker compose exec frontend sh -c \
  "grep -rho 'http[s]*://[A-Za-z0-9._:-]*' /app/.next/static/chunks | sort -u"
```

On a correct install that lists `http://localhost:8080` (interleaved with
`https://nextjs.org`, `http://www.w3.org` and other library URLs). If you see
`http://backend` in that list, the image was built with a service name and must
be rebuilt.

### `Connection refused` / `Communications link failure` from the backend, MySQL never becomes healthy

```bash
docker compose ps                       # is mysql "healthy" or still "starting"?
docker compose logs mysql | tail -50
```

- Still `starting` after a minute: on the very first boot MySQL initialises its
  data directory and that can take 30–60 s. The backend is not started until it
  is healthy, so just wait.
- `healthy` but the backend still cannot connect: check that
  `SPRING_DATASOURCE_URL` uses the **service name** `mysql`, not `localhost`.
  Inside the backend container, `localhost` is the backend itself.
- If you changed `MYSQL_PASSWORD` after the volume already existed, MySQL kept
  the old one. See [§7](#7-change-the-passwords).

### The backend exits immediately on start

```bash
docker compose logs backend | tail -80
```

- `Access denied for user 'sportday'` — password mismatch (above).
- `Unknown database 'sportday'` — the `createDatabaseIfNotExist=true` parameter
  was dropped from the JDBC URL.
- `Table ... doesn't exist` after a manual schema edit — Hibernate's
  `ddl-auto: update` adds missing tables and columns but never drops or alters
  one destructively. Restore from a backup or start fresh.

### The image builds but the app's stylesheets and images are missing

The runtime stage copies `.next/standalone`, then `.next/static` and `public`
beside it. If any of those three is missing the pages render unstyled or with
404s on assets. Rebuild the frontend image from a clean context:
`docker compose build --no-cache frontend`.

### `next build` fails with out-of-memory or is killed

The Next build is the heaviest step. Give Docker Desktop more memory
(Settings → Resources), or build the two images one at a time:

```bash
docker compose build backend
docker compose build frontend
```

### `docker compose` says the file is invalid

You are probably on Compose v1 (`docker-compose`). This file uses
`depends_on: condition: service_healthy`, which v1 does not support. Install the
Compose v2 plugin and use `docker compose`.

### It worked, then I ran `down -v` and everything is gone

That is what `-v` does. Use `docker compose down` without `-v` to stop the stack
and keep the data.

---

## 11. What was verified, and what was not

Verified on Windows with Docker **29.8.2** and Compose **v5.5.1**. Every image tag
was checked against its registry (`docker manifest inspect`) before it was used:

| Image                             | Purpose                                       | Check |
|-----------------------------------|-----------------------------------------------|-------|
| `maven:3.9.15-eclipse-temurin-25` | Maven 3.9.15 on Eclipse Temurin JDK 25 — backend build stage | resolved |
| `eclipse-temurin:25-jre`          | Eclipse Temurin JRE 25 — backend runtime      | resolved |
| `node:25-alpine`                  | Node 25 (Alpine) — frontend build and runtime | resolved |
| `mysql:8.0`                       | Database (unchanged from before)              | resolved |

Also verified, from the real run rather than by inspection:

- `docker compose config` parses (exit 0) with the defaults in `.env.example`.
- Both images build from the committed Dockerfiles. The backend build compiled
  129 main and 73 test sources inside the image and packaged
  `target/sportday-backend-1.0.0.jar`; `target/` is excluded by
  `backend/.dockerignore` and is never copied from the host.
- The stack ran end to end: MySQL 8.0 → backend (healthcheck `healthy` in ~10 s,
  "Started SportdayApplication in 13.538 seconds") → frontend (`healthy`).
- Hibernate created **14 tables** in the fresh `sportday` schema
  (`ddl-auto: update`), and `DataInitializer` logged
  `Created default administrator: admin / admin123`, `Created the 2026 sport day
  with entries open` and `Seeded 112 default events for 2026-10-10 (all enabled)`.
- `POST /api/auth/login` with `admin` / `admin123` returned **200** and a JWT with
  `"role":"ADMIN","fullName":"System Administrator"`; that token then fetched
  `GET /api/events` → 200 (112 events).
- The CORS behaviour the browser depends on: a preflight from
  `Origin: http://localhost:3000` answered **200** with
  `Access-Control-Allow-Origin: http://localhost:3000`, while
  `Origin: http://127.0.0.1:3000` was refused with **403** — which is why the guide
  insists on `localhost:3000`.
- The frontend served `/`, `/login` and `/events` with **200**, and the
  stylesheet and JS chunks it references also returned **200** (proving the
  `.next/static` and `public` copies in the runtime stage are right).
- `NEXT_PUBLIC_API_URL` is genuinely compiled in: a bundle built with
  `--build-arg NEXT_PUBLIC_API_URL=http://localhost:8081/api` contained
  `http://localhost:8081` and **no** `http://backend` anywhere. That is the proof
  that the value is baked at build time and that a compose service name would
  have leaked.

**Not verified, and why:** the run used host ports **3308** (MySQL) and **8081**
(backend) instead of the shipped 3307 and 8080, because other projects on this
machine already hold 3000 and 3307 and could not be disturbed. The frontend was
therefore built with `NEXT_PUBLIC_API_URL=http://localhost:8081/api`, which is
exactly the rebuild path described in [§8](#8-the-api-url-and-why-the-browser-is-the-trap).
Nothing in the configuration is port-specific, so the shipped defaults are
expected to work; but if you are the first to run `docker compose up -d --build`
with the committed defaults, treat that as the final confirmation.

No CI workflow, Kubernetes manifest or reverse proxy is added — the stack is
three services and two volumes.
