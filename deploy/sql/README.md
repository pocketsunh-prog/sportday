# `deploy/sql` — database setup for a fresh install

Four files that take an empty MySQL server to a working Sport Day database.

| File | What it does | Safe to re-run |
| --- | --- | --- |
| [`01-schema.sql`](01-schema.sql) | Creates the database, the application user and its grant, and all **14 tables** — columns, types, nullability, defaults, primary keys, foreign keys, native `ENUM`s and indexes. | Yes — creates only what is missing |
| [`02-seed.sql`](02-seed.sql) | The minimum rows for a working, empty system: the single `sport_day_settings` row and the `admin` account. | Yes — no-ops when the rows exist |
| [`03-sample-data.sql`](03-sample-data.sql) | **Optional.** A clearly synthetic demo: one season, four events, twelve students. | Yes — creates only what is missing |
| `README.md` | This file. | — |

Run them in that order. Nothing here touches the school's live database: every
statement is `IF NOT EXISTS`, `ON DUPLICATE KEY UPDATE` or a `WHERE NOT EXISTS`
guard, and there is not one `DROP`, `TRUNCATE` or `DELETE` in `01`, `02` or `03`.

---

## 1. Running them

### Against the Docker MySQL (host port **3307**)

The compose stack publishes MySQL on the host as **3307** (`3307:3306`), creates
the `sportday` database and the `sportday` / `sportday123` account on first
container start, and takes the **root** password `root123`.

Straight into the container — the simplest route, and byte-exact:

```bash
docker exec -i sportday-mysql mysql -uroot -proot123 < deploy/sql/01-schema.sql
docker exec -i sportday-mysql mysql -uroot -proot123 < deploy/sql/02-seed.sql

# optional demo data
docker exec -i sportday-mysql mysql -uroot -proot123 sportday < deploy/sql/03-sample-data.sql
```

Or from a MySQL client on the host, over the published port:

```bash
mysql -h 127.0.0.1 -P 3307 -u root -proot123 < deploy/sql/01-schema.sql
mysql -h 127.0.0.1 -P 3307 -u root -proot123 < deploy/sql/02-seed.sql
mysql -h 127.0.0.1 -P 3307 -u root -proot123 < deploy/sql/03-sample-data.sql
```

At the end of each file there is a commented-out verification query block; paste
one in to check the result by hand.

### > Running them from PowerShell — read this

`02-seed.sql` and `03-sample-data.sql` contain Chinese text
(`田徑運動會記錄表 / Sport Day Marking Sheet`) and a middle dot (`·`) in the event
names. **PowerShell's redirection and `Get-Content | …` pipelines re-encode
text and will corrupt those bytes before MySQL ever sees them** — and
`SET NAMES utf8mb4` inside the script cannot save you, because the damage
happens on this side of the wire. This repository has already lost a Java file
to exactly that mistake.

Use one of these two byte-exact routes instead:

```powershell
# (a) let cmd.exe do the redirection
cmd /c "docker exec -i sportday-mysql mysql -uroot -proot123 < deploy\sql\02-seed.sql"

# (b) copy the file into the container, then redirect inside it
docker cp deploy\sql\02-seed.sql sportday-mysql:/tmp/02-seed.sql
docker exec sportday-mysql sh -c "mysql -uroot -proot123 < /tmp/02-seed.sql"
```

Do **not** use `Get-Content -Raw … | docker exec -i …` for these two files.

Route (a) was verified at the byte level — the Chinese settings title and the
`·` in the event names arrive intact (§6). Note that the `mysql` client on
Windows does **not** default to `utf8mb4`; the `SET NAMES utf8mb4;` statement at
the top of `02-seed.sql` and `03-sample-data.sql` is what makes it work, and it
must not be removed. `01-schema.sql` contains no non-ASCII text, so it is safe
under any route.

### Privileges

Steps 1–5 of `01-schema.sql` (create database, alter database, create user,
grant) need an administrative account such as **root**. That is why the commands
above use `-uroot`. Where the database and the account already exist — which is
the case for the Docker stack, whose `MYSQL_DATABASE` / `MYSQL_USER` /
`MYSQL_PASSWORD` create them at first container start — that block is a no-op,
and you may comment it out and run the rest as `sportday` itself.

Running the whole file as `sportday` fails loudly and early, which is the
intended behaviour rather than something to work around:

```
ERROR 1227 (42000) at line 81: Access denied; you need (at least one of) the
CREATE USER privilege(s) for this operation
```

Two deliberate non-destructive choices worth knowing about:

* **`02-seed.sql` uses `ON DUPLICATE KEY UPDATE id = id`**, an explicit no-op. If
  the settings row or the admin account is already there, it is left exactly as
  it is. Re-running the seed never overwrites data somebody has since edited.
* **`01-schema.sql`'s `CREATE TABLE IF NOT EXISTS` cannot reconcile a table that
  already exists with a different shape.** The existing definition wins and no
  warning is raised. That is the trade for never destroying live data.

---

## 2. What `02-seed.sql` duplicates, and what it leaves to the application

`DataInitializer` (`backend/src/main/java/com/sportday/config/DataInitializer.java`)
runs on every boot and does four things. `02-seed.sql` deliberately covers only
the first two, and **those two are genuine duplicates** — the user asked to be
told rather than have it buried.

| On first boot the application… | Also in `02-seed.sql`? | Why |
| --- | --- | --- |
| creates the `admin` user (`admin` / `admin123`) when no such username exists | **Yes** — duplicate | Cheap, and it means a database can be handed over ready to log into without anyone having booted the app first. |
| creates the `sport_day_settings` row (`settingsService.get()`) | **Yes** — duplicate | Same reason. The values are then reviewable in the repository instead of only in Java. |
| creates the current year's season with entries open | No | It dates the season to the day the app first boots. A date baked into a checked-in file goes stale. |
| creates the standard event catalogue (~40 rows) when `events` is empty | No | ~40 rows of derived data, each carrying that same boot date. `EventService.createDefaults()` is idempotent, so it is safe either way. |

Nothing creates `standard_defaults` on first boot; it is meant to start empty
and is filled from the Standards page. Sample students are created only when
`app.students.seed-sample-count` is greater than zero, and it defaults to **0**
(`application.yml`), so the stock application creates none.

**The drift risk, stated plainly:** the two duplicated rows are copies of Java
defaults. If a default changes in `SportDaySettings.defaults()`, `02-seed.sql`
will not follow on its own. Because both inserts are no-ops once the row exists,
only a database seeded *before* its first boot ever takes the SQL values — an
installation that has booted once keeps the application's. The worst case is
therefore a stale starting value on a brand-new install, not a broken one, but
**when you change those defaults in Java, change them here too.**

### Where the admin password comes from

`02-seed.sql` contains a BCrypt hash (cost 10, Spring's `BCryptPasswordEncoder` —
the encoder `SecurityConfig` installs) of the application's documented first-run
password `admin123`. It was generated for this file and verified to match. It is
**not** copied from the school's server, and no real account, hash, email or name
from the live database appears in any file here.

> **Change the admin password immediately after the first login.** A published
> default administrator password is a published administrator password, and the
> application has no forced-change-on-first-login step.

---

## 3. How the schema was derived

Honest source: the live database itself.

```bash
docker exec sportday-mysql sh -c \
  "mysqldump -usportday -psportday123 --no-data --routines --triggers --databases sportday"
```

That dump was transcribed table by table, then every column was checked against
the JPA entity classes in `backend/src/main/java/com/sportday/entity/` and
against the hand-written records in `backend/db/migration/`.

**The database has more than tables to reproduce.** Checked through
`information_schema`, and found empty, so nothing is created for them:
triggers `0`, stored routines `0`, views `0`, scheduled events `0`,
`CHECK` constraints `0`.

**`AUTO_INCREMENT` counters were deliberately dropped.** A fresh install starts
every table at 1; the live values (`enrollments` 30766, `event_results` 33779, …)
are a record of how much data the school holds and are no part of a schema.

### The dump versus the live database, verified

A database built from `01-schema.sql` was dumped the same way and compared with
the live dump, normalising away the `CREATE DATABASE` / `USE` preamble, the
redundant `CHARACTER SET utf8mb4` keyword and the `AUTO_INCREMENT` counters:

```
live tables: 14
mine tables: 14
tables only in live : (none)
tables only in mine : (none)
tables with a real definition difference: 0
RESULT: identical to the live database for every table, index, key and constraint.
```

---

## 4. What the dump could not settle, and how it was resolved

**a. The `ENUM` value lists.** Hibernate writes MySQL `ENUM` values in
*alphabetical* order, and the dump reports that order — which says nothing about
what the code can actually handle. Each `ENUM` column was therefore compared
against the Java enum constants, value for value:

| Column | Dump | Entity enum | Match |
| --- | --- | --- | --- |
| `users.role` | `ADMIN, HELPER, MANAGER, STUDENT, TEACHER, USER` | `User.Role` | ✅ 6/6 |
| `enrollments.status` | `CANCELLED, CONFIRMED, PENDING` | `Enrollment.EnrollmentStatus` | ✅ 3/3 |
| `events.category` | `FIELD, RELAY, TRACK` | `EventCategory` | ✅ 3/3 |
| `events.sex`, `event_records.sex`, `standard_defaults.sex`, `students.sex` | `FEMALE, MALE` | `Sex` | ✅ 2/2 |
| `events.grade`, `event_records.grade`, `standard_defaults.grade`, `students.grade` | `A, B, C` | `Grade` | ✅ 3/3 |
| `events.type`, `event_records.event_type`, `standard_defaults.type` | 21 values | `Event.EventType` | ✅ 21/21 |
| `event_groups.stage`, `event_results.stage` | `FINAL, HEAT` | `EventStage` | ✅ 2/2 |
| `events.relay_team_kind`, `relay_teams.kind` | `FORM, HOUSE` | `RelayTeamKind` | ✅ 2/2 |

Nothing was widened and nothing was missing: the dump and the code agree
everywhere. The declared order in the file is the dump's alphabetical order,
which is also what Hibernate generates — so the two cannot disagree later about
an `ENUM`'s implicit sort order.

**b. `event_results.outcome` is `varchar(10)`, not an `ENUM`** — the one column
where the live schema departs from the pattern. It was traced to
`backend/db/migration/mark-outcome-migration.sql`, which adds it as
`VARCHAR(10) NULL` by hand. The entity declares `@Enumerated(EnumType.STRING)`
with `length = 10`, so the obvious assumption is that Hibernate would convert it.
**It does not** — see §5; the column is reproduced here exactly as the live
database has it.

**c. `event_records.has_previous` is `NOT NULL` with no default.** The entity
declares it `@Column(nullable = false)` and sets it in `@PrePersist`/`@PreUpdate`,
so this is the code's own requirement rather than a dump artefact. Kept as-is,
and called out in the file: a hand-written `INSERT` into `event_records` must
supply it.

**d. `teacher_classes` is the only table with a different collation**
(`utf8mb4_0900_ai_ci`, where the other thirteen are `utf8mb4_unicode_ci`). This
is not a typo in the dump.
`backend/db/migration/teacher-accounts-migration.sql` declares
`ENGINE = InnoDB DEFAULT CHARSET = utf8mb4` with no `COLLATE`, so the table
inherited MySQL 8's server default. It is **reproduced deliberately**, so a fresh
install behaves identically to the school's database.

Consequence: `teacher_classes.class_name` and `students.class_name` have
different collations, so a future SQL `JOIN` or comparison between them would
raise *Illegal mix of collations*. Nothing does that today — every query on
either column is a single-table JPQL query (`TeacherClassRepository`,
`StudentRepository`) and the two values are only ever compared in Java. To
normalise a fresh install instead, change the `COLLATE` on the last line of that
statement in `01-schema.sql` to `utf8mb4_unicode_ci`; no application code depends
on the difference.

**e. Which columns are Hibernate's and which are hand-written.** Read out of
`backend/db/migration/*.sql`. It shows up in the declaration order of `events`:
the alphabetical block (`category` … `updated_at`) is Hibernate's, and
`season_id` onward were appended by migration scripts. `01-schema.sql` keeps the
live order, so a `SHOW CREATE TABLE` from a fresh install lines up with the live
one.

**f. Column and index names were kept verbatim**, including Hibernate's hashed
names (`FKlih6fb6gc52jvgbtbyuenm24f`, `UKn0r…`) and the hand-written semantic
ones (`uk_relay_team_event_kind_key`, `fk_event_result_relay_team`). These are
the names Hibernate looks for, so it finds them all and adds no duplicate index.

**g. `sport_day_settings.id` has no `AUTO_INCREMENT`.** The entity declares a
plain `@Id private Long id` and the application assigns
`SportDaySettings.SINGLETON_ID = 1`, so the column is `bigint NOT NULL` with no
auto-increment. Confirmed against the entity, not guessed from the dump.

---

## 5. `ddl-auto: update` versus this script

**Measured, not reasoned about.** `application.yml` sets
`spring.jpa.hibernate.ddl-auto: update`, so a fresh install created by these
scripts gets a Hibernate pass on first boot. The real application jar
(`backend/target/sportday-backend-1.0.0.jar`, Hibernate ORM 7.4.1.Final) was
booted against a throwaway database built by `01` + `02` + `03`, with
`org.hibernate.SQL` logging on, twice in a row.

| | First boot | Second boot |
| --- | --- | --- |
| `CREATE TABLE` | **0** | **0** |
| `DROP TABLE` | **0** | **0** |
| `ALTER TABLE` | **13** | **13** |
| Schema afterwards | — | **byte-identical** to after the first boot |

**No table was created, none was dropped, and no column was added.** Hibernate
found all 14 tables and every column, index and constraint exactly where the
script put them. The schema does **not** drift between boots.

The 13 `ALTER TABLE` statements are the whole of the disagreement, and they are
all the same thing — Hibernate re-stating an existing `ENUM` column with an
unchanged value list:

```
alter table event_groups      modify column stage           enum ('FINAL','HEAT')
alter table event_records     modify column event_type      enum ('DISCUSSION_THROW', … )
alter table event_records     modify column grade           enum ('A','B','C') not null
alter table event_records     modify column sex             enum ('FEMALE','MALE') not null
alter table event_results     modify column stage           enum ('FINAL','HEAT')
alter table events            modify column grade           enum ('A','B','C') not null
alter table events            modify column relay_team_kind enum ('FORM','HOUSE')
alter table relay_teams       modify column kind            enum ('FORM','HOUSE')
alter table standard_defaults modify column grade           enum ('A','B','C') not null
alter table standard_defaults modify column sex             enum ('FEMALE','MALE') not null
alter table standard_defaults modify column type            enum ('DISCUSSION_THROW', … ) not null
alter table students          modify column grade           enum ('A','B','C') not null
alter table students          modify column sex             enum ('FEMALE','MALE') not null
```

These are effectively no-ops: not one value was added to or removed from a list,
and no nullability changed. The only visible result is that MySQL re-renders the
column's definition with the redundant `CHARACTER SET utf8mb4` dropped —

```
before boot : `grade` enum('A','B','C') CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL
after boot  : `grade` enum('A','B','C') COLLATE utf8mb4_unicode_ci NOT NULL
```

— which is precisely how the school's live database renders them. In other
words a fresh install **converges on** the live definitions rather than drifting
away from them.

Two things worth naming anyway:

* **It is not a one-off.** The same 13 statements are re-issued on *every* boot,
  because Hibernate's expected SQL type string for these columns never compares
  equal to what MySQL reports back for an `ENUM`. This is already happening
  against the live database today — it is pre-existing behaviour, not something
  these scripts introduce.
* **On a large table, a `MODIFY COLUMN` is not guaranteed free.** MySQL 8 can
  serve an unchanged `ENUM` modification in place, but if it ever decides to
  rebuild, it rebuilds a table with tens of thousands of rows
  (`event_results` holds ~33,800 in the live database). If that ever becomes a
  problem, the fix belongs in `application.yml`, not in these files: pinning the
  enum JDBC type so Hibernate stops re-stating the columns.

**The one place the dump *looked* like it would disagree, and does not:**
`event_results.outcome`. It is `varchar(10)` in both the live database and this
script, and Hibernate left it alone — it is *absent* from the 13 statements
above. So there is no first-boot `ALTER` waiting on that column, and no
`ddl-auto` conflict there either.

**Where a real conflict could still appear:** `ddl-auto: update` only ever *adds*.
If a future entity change makes Hibernate want a *different* type or a *narrower*
column, `update` will not reconcile it against a database built by this file —
that is exactly what the hand-written files in `backend/db/migration/` are for.
These scripts are a starting point, not a replacement for that directory.

---

## 6. What was tested, and the evidence

A throwaway database `sportday_install_test` was created **on the same MySQL
server** (`sportday-mysql`, MySQL 8.0.46). The live `sportday` database was not
written to, and no row was ever selected from it.

The scripts were run as shipped, with one substitution: the back-quoted database
name `` `sportday` `` was rewritten to `` `sportday_install_test` `` so that
`CREATE DATABASE` / `USE` / `GRANT` targeted the throwaway database. That is the
only edit — the admin email, the password, every comment and every byte of
Chinese text were left exactly as shipped.

### Table count and a clean slate

```
=== table count (expect 14) ===
+----------------+
| tables_created |
+----------------+
|             14 |
+----------------+
```

All 14 tables present, with `teacher_classes` correctly the odd one out
(`utf8mb4_0900_ai_ci`), and every one of them empty.

### A second run is harmless

`01-schema.sql` and `02-seed.sql` were then run **again**, and:

```
+------------+---------------+-----------+
| tables_now | settings_rows | user_rows |
+------------+---------------+-----------+
|         14 |             1 |         1 |
+------------+---------------+-----------+
```

Fourteen tables, one settings row, one admin — identical before and after. No
error, no duplicate, nothing overwritten.

### Row counts after the seed, and after the sample data

```
=== row counts after 03-sample-data.sql ===
+-------------------+----+
| t                 | n  |
+-------------------+----+
| seasons           |  1 |
| events            |  4 |
| event_groups      |  0 |
| users             | 13 |   <- 12 demo students + the admin from 02-seed.sql
| students          | 12 |
| enrollments       |  0 |
| event_results     |  0 |
| event_records     |  0 |
| relay_teams       |  0 |
| standard_defaults |  0 |
+-------------------+----+
```

`03-sample-data.sql` was then run a **second** time: still 1 season, 4 events,
13 users, 12 students, 1 settings row — unchanged.

The sample rows themselves were checked for internal consistency: the stored
grades (C / B / A) match the grades the application derives from the same dates
of birth (ages 13 / 15 / 17), class numbers restart at 1 in each class, houses
are the four the app knows, and both sexes are present.

### UTF-8 survives the import

Checked at the byte level, because the console's *rendering* of Chinese is not
evidence of anything:

```
title stored as : E794B0E5BE91E9818BE58B95E69C83E8A898E98C84E8A1A8 202F2053706F72 …
chars           : 34
first event name: 426F7973203130304D20 C2B7 2041204772616465     ->  "Boys 100M · A Grade"
```

`E794B0 E5BE91 …` is the correct UTF-8 for `田徑運動會記錄表`, and `C2B7` is
`·`. Both land intact through the `cmd /c "… < file"` route documented in §1.

That was worth measuring, because `SET NAMES utf8mb4` at the top of
`02-seed.sql` and `03-sample-data.sql` is doing real work. A control query
containing the same Chinese but **without** `SET NAMES` got double-encoded by the
same server on the same route:

```
correct   (with SET NAMES utf8mb4) : E794B0 E5BE91 E9818B …
doubled   (no SET NAMES)           : C3A7E2809D C2B0 C3A5C2BE …
```

The Windows `mysql` client does not default to `utf8mb4`, so without that line
the Chinese would be stored as mojibake. Do not remove it.

### The application actually starts on the result

The real jar was booted against a database built only by these three scripts:

```
Started SportdayApplication in 8.902 seconds
Tomcat started on port 60771
```

### The seeded logins authenticate

Against the running application's own endpoint, `POST /api/auth/login`:

```
  admin / admin123 (02-seed)             HTTP 200  role=ADMIN    tokenLen=174
  admin / wrong-password (must fail)     HTTP 401  (rejected)
  DEMO0001 / 201304181A1 (03-sample)     HTTP 200  role=STUDENT  tokenLen=178
  DEMO0009 / 200902275A1 (03-sample)     HTTP 200  role=STUDENT  tokenLen=178
  DEMO0012 / 200902275B2 (03-sample)     HTTP 200  role=STUDENT  tokenLen=178
  DEMO0012 / another pupil's pwd (fail)  HTTP 401  (rejected)
```

So the BCrypt hash in `02-seed.sql` really is `admin123`, and the sample
students' hashes really are their rule-derived passwords
(`yyyyMMdd` + class + class number).

### Cleanup

The throwaway database was dropped, and the grant that `01-schema.sql` created
for it on the `sportday` account was revoked:

```sql
DROP DATABASE IF EXISTS sportday_install_test;
REVOKE ALL PRIVILEGES ON sportday_install_test.* FROM 'sportday'@'%';
```

The live `sportday` database was never modified.

---

## 7. Privacy

* No real student name, username, password, email, mark or result appears in any
  file here. Nothing was copied out of the live database's tables — the only
  things read from it were `information_schema` metadata, the grants, and
  `mysqldump --no-data`, which by definition emits no rows.
* `03-sample-data.sql` is **visibly** synthetic: every pupil is called
  `Demo Student NN` with the id `DEMO00NN`, and every student row carries the
  import batch `SAMPLE-DEMO-2026` so the whole set can be found or removed with
  one query.
* The school's register holds hundreds of real pupils. None of them ship.

---

## 8. Known limitations

* **`01-schema.sql` must be run by an account holding `CREATE USER`** (root, in
  the Docker stack). See §1. Running it as `sportday` stops at the `CREATE USER`
  statement.
* **The scripts were not run against the live `sportday` database** — only
  against a throwaway one, with the database name substituted as described in
  §6. `01-schema.sql` is written so that running it against a live database
  would not drop or alter anything, but that was not exercised, deliberately.
* **`CREATE TABLE IF NOT EXISTS` silently accepts a pre-existing table of a
  different shape.** If a database was first built by an older version of these
  scripts, they will not upgrade it. Compare `SHOW CREATE TABLE` against
  `01-schema.sql`, or use the files in `backend/db/migration/`.
* **The sample data is dated.** It is a 2026 season with a 2026-11-06 sport day.
  The application recomputes a student's displayed grade from their date of
  birth, so if `03-sample-data.sql` is run years from now the demo pupils will
  show the grade they have aged into. Cosmetic; it is sample data.
* **Running `03` before the application's first boot suppresses the automatic
  event programme**, because `DataInitializer` only builds it when the `events`
  table is empty. This is called out at the top of `03-sample-data.sql`. Boot the
  application first if you want both.
* **`03-sample-data.sql` seeds no enrolments, heats, marks or results.** Every one
  of those goes through a service enforcing rules the schema cannot (entry
  limits, group allocation, the direct-to-final decision, relay derivation, the
  records engine), and hand-written rows would bypass all of it.
