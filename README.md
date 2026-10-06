# SportDay — school sports day management

A full-stack system for running a school sports day: an administrator imports the
whole student register, students enter themselves for events, the system splits
each event into heats, and helpers get a printed marking sheet per heat.

Spring Boot 4.1 (Java 25) backend, Next.js 16 web app, MySQL 8.

---

## What this revamp adds

| # | Requirement | Where it lives |
|---|-------------|----------------|
| 1 | Admin uploads all student records; student logs in with student id, password = dob + class + class number | `AdminStudentController`, `StudentService`, `StudentPasswordPolicy` |
| 2 | Events are **enabled by default**; admin can disable | `EventService.createEvent`, `Event.enabled`, `PATCH /api/events/{id}/enable` |
| 3 | A student may enter **2 track (徑項)**, **1 field (田項)** and **2 relay (接力)** events | `EnrollmentService`, `EventCategory.getMaxEntriesPerStudent()` |
| 4 | After entries close, events are split into groups — 8 per group for 60/100/200/400, 24 for 800 and above | `EventGroupService`, `Event.EventType.getDefaultGroupSize()` |
| 5 | Marking-sheet PDF per group — **A5** for 60/100/200/400, **A4** otherwise, columns: student id, name, grade, record, remark | `PdfSheetService`, `PdfFontProvider` |
| 6 | Grade derived from date of birth — C ≤ 14, B 15–16, A ≥ 17 | `GradeCalculator`, `Grade` |
| 7 | 600 students of sample test data | `StudentSampleDataGenerator`, `sportday-sample-data/` |
| 8 | Short sprints (60/100/200/400) run **heats then a final**; the top 8 go through, with their own marks and their own marking sheet | `FinalQualificationService`, `EventStage`, `FinalEntry` |
| 9 | **Past events** can be browsed, with their placings | `GET /api/events/past`, `GET /api/events/{id}/standings` |
| 10 | An admin sets how many **track and field events** a student may enter | `SportDaySettings`, `SettingsService` |
| 11 | **No public sign-up** — only an admin creates accounts; students arrive by register import | `AdminController.createUser` |
| 12 | **School records** per event × division × grade, updated automatically when a mark beats one | `EventRecord`, `RecordService` |
| 13 | **Personal and house champions** from configurable points (9/6/3, then 1 to 8th; relay 30/20/10, house only) | `ChampionService` |
| 14 | An admin can enter a student in events and update their entries, quota and all | `AdminStudentController` entry endpoints |
| 15 | Every event has a **record by default**, which an admin can type in by hand | `EventRecord.manualMark`, `RecordService.setBaseline` |
| 16 | The programme can be **viewed by date**, and an event edited from there | `GET /api/events/dates`, `?date=` |
| 17 | The admin uploads **all students every year**; anyone not on the list is **locked** | `StudentService.importStudents`, `Student.enabled` |
| 18 | **One sport day per school year**, with the school's details and an enrolment switch; past years browsable and editable | `Season`, `SeasonService`, `SportDaySettings.schoolName` |
| 19 | Marks are recorded in **M** for a field event and **s** for a track one | `Event.EventType.getDefaultUnit()` |
| 20 | A field event gives **three attempts**, and the best of them is the result | `EventResult.attempt1..3`, `MarkEntryService` |
| 21 | An event runs **direct to a final** by default; only 60/100/200/400 can be split into heats and a final | `Event.directToFinal`, `FinalQualificationService` |
| 22 | **An event belongs to one grade** — no grade is ever ranked against another | `Event.grade`, `EventService.createDefaults` |
| 23 | A sprint with **8 or fewer entered** switches itself to direct to final — a final would be the same athletes as the heat | `FinalQualificationService.syncFinalFormat` |
| 24 | A race **longer than 400M** is timed in **minutes and seconds**; the mark is still stored in seconds | `EventType.usesMinutesAndSeconds` |
| 25 | Mark entry lists only events with **more than one athlete** entered | `app/admin/marks` |
| 26 | A **100M hurdles** for the C grade, with the 110M hurdles for A and B | `EventType.HURDLES_100M`, `EventGradeRule` |
| 27 | A result reads the way the sport writes it — **14.123s, 1.04.123s, 18.12M** — and prints to PDF | `MarkFormatter`, `PdfResultService` |
| 28 | A season reset **writes a restorable backup first**, and refuses to reset if the backup fails; backups can be listed, downloaded and restored | `SeasonBackupService`, `BackupStore`, `POST /api/admin/season/reset` |
| 29 | An admin **uploads teacher accounts**, each carrying the classes they look after | `TeacherService`, `POST /api/admin/teachers/upload` |
| 30 | A teacher may **enter, withdraw and look up a student's events — but only in their own classes** | `TeacherClassService`, `TeacherHelpService`, `/api/teacher/**` |
| 31 | **Relay teams**: one team per class of the event's form (a form relay) or per grade × house, four runners and one reserve each, with the runners, their legs and the team's name chosen for it | `RelayTeam`, `RelayTeamService` |
| 32 | **A relay event built around its teams**: the school chooses the teams by hand first, on a *draft* relay event, and then moves them onto the race — all of them or none | `Event.isDraft()`, `RelayTeamService.moveTeamsToEvent` |

Events are also split by **sex division** (Boys / Girls), so each event is
contested in exactly one division.

## Language, printing and the mark-entry grid

Three further features, all in the web app.

### English / 中文

A toggle in the top-right corner switches every UI label between English and
Traditional Chinese, and the choice is remembered in `localStorage`. All strings
live in one place — `frontend/lib/i18n.tsx`:

```tsx
const { t, label } = useI18n();
t('marks.saveAll')                                  // "Save all marks" / "儲存全部成績"
t('marks.marked', { marked: 3, total: 8 })          // fills {placeholders}
label('category', event.category)                   // TRACK -> "Track 徑項" / "徑項"
```

Adding a string means adding the key to **both** the `en` and `zh` blocks; the
`MessageKey` type is derived from `en`, so a key used in a component but missing
from the dictionary is a compile error rather than a blank label. Domain values
(`category`, `sex`, `grade`, `sheet`, `unit`, `role`) go through `label(...)`
instead of being hardcoded, and API-supplied text (names, venues, server error
messages) is deliberately left as it comes.

### Printing marking sheets — **Admin → Print Sheets** (`/admin/print`)

The print run for the helpers:

- filter by **division** and **category**, or pick a single event;
- **Download all matching sheets** in one PDF — it contains one page per heat and
  correctly mixes A5 and A4 pages, ordered like the programme (short sprints
  first);
- per-event and per-heat downloads;
- an in-page **preview** plus a **Print** button, so a sheet can go straight to
  the printer without leaving the browser.

Each sheet carries the five columns the helper needs — student ID, name, grade,
record, remark — with record and remark left blank. A **final**'s sheet carries
one more, **初賽 Heat**, showing what each finalist ran in their heat beside the
blank box the final is written in.

### Mark entry grid — **Admin → Mark Entry** (`/admin/marks`)

Type a whole heat's results straight into a grid and save them in one go.

- choose an **event**, then filter the grid by **heat** (event group) and
  **grade** — both filters are applied server-side, and the option lists always
  describe the whole event so they stay stable;
- the **unit** defaults to the event's own (`seconds` for track, `metres` for
  field);
- edit **record** and **remark** inline, or tick **clear** to remove a mark;
- **Save all marks** sends only the rows that were touched. Rows left blank are
  untouched, so a half-filled grid is safe to save;
- on a **final** grid each row also carries the athlete's heat performance —
  `heatMark` and `heatDisplayMark` (`11.86s`, or `ABS` / `DQ`), with `heatOutcome`
  naming which — so what earned the place is shown beside the box being written
  in. A heat grid carries none of it;
- the response reports `saved / cleared / skipped / failed` and lists any row
  problems, and the leaderboard is shown underneath.

```jsonc
// POST /api/events/2/marks
{ "rows": [
    { "userId": 12, "mark": 8.123, "notes": "PB" },  // stored
    { "userId": 13, "mark": null },                   // left untouched
    { "userId": 14, "clear": true }                   // mark removed
] }
```

A row is refused — without losing the rest of the batch — when the athlete is not
entered in the event, the mark is negative or absurd, or the same athlete appears
twice in one save.

---

## Quick start

### Prerequisites

- **JDK 25** (the project targets Java 25) — e.g. `C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot`
- Maven 3.9+, Node.js 20+, Docker

### 1. Database

```bash
docker-compose up -d
```

MySQL is published on **port 3307** (see `docker-compose.yml`).

### 2. Backend

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
cd backend
mvn spring-boot:run
```

Runs at `http://localhost:8080` — Swagger UI at `/swagger-ui.html`.

On first start it creates the administrator account (`admin` / `admin123`) and, if
the events table is empty, the standard event catalogue for both divisions —
**every event enabled**.

### 3. Web app

```bash
cd frontend
npm install
npm run dev
```

Runs at `http://localhost:3000`.

---

## Student logins

The administrator uploads the register, and every student gets an account derived
from their own record:

```
username = student id
password = yyyyMMdd(date of birth) + class + class number
```

Student `S0001`, born 2010-03-15, class `5A`, class number 12 → **`201003155A12`**.

Download every student's credentials from **Admin → Students → Credentials CSV**.
Grades are **not** part of the password, and re-uploading a student always resets
their password back to this derived value, so it can never drift out of sync.

---

## The event model

| Concept | Meaning |
|---------|---------|
| **Category** | `TRACK` (徑項), `FIELD` (田項) or `RELAY` (接力). Fixed by the event type. A relay is its own family rather than a track event, because it is run and scored by team; its marks are still times, so only `FIELD` is measured |
| **Sex division** | `MALE` or `FEMALE` — the event is run once per division |
| **Group size** | Athletes per heat. **8** for 60/100/200/400, **24** for 800 and above and for every field event. Overridable per event |
| **Sheet size** | Follows the group size: **A5** for 60/100/200/400, **A4** otherwise |
| **Enabled** | New events are created enabled. Disabling closes the event to new entries but keeps existing entries, heats and results |

### Entry rules

A student may hold at most **2 track** entries, **1 field** entry and **2 relay**
entries. A relay is counted in its own family rather than as a track entry, so a
student may hold a leg in a **house relay** and a leg in a **class relay** — the two
ways a relay is divided, see [Relay teams](#relay-teams) — and still hold their two
individual track entries. An entry is refused when:

- the event is disabled — *"This event is closed — it has been disabled by the organiser."*
- the student is already entered
- the event is in the other sex division
- the quota of the event's **own category** is used up — *"You have already entered 2 徑項 Track event(s), which is the maximum (徑項: 2)."*,
  and for a relay *"You have already entered 2 接力 Relay event(s), which is the maximum (接力 Relay: 2)."*
- the event is full

The web app shows the remaining **track and field** quota (`徑項 1/2 · 田項 0/1`) and
surfaces the server's message whenever an entry is refused. A relay's own count is
enforced by the server, but it is not one of those two figures.

### Heats

`POST /api/events/{id}/groups/allocate` splits the confirmed entries into heats.
By default athletes are seeded by class then class number, so the same entries
always produce the same heats; pass `?shuffle=true` to draw lanes at random.
Re-running replaces the previous heats, so it is safe after late entries. It also
discards any final, because a final drawn from a different set of heats no longer
means anything.

### Heats and the final (60 / 100 / 200 / 400)

An event is **decided by its own run** unless the school asks for heats and a
final. That is the default, and what most of a school day is: everybody competes
once and that is the result. Each event carries a **direct to final** check box,
ticked by default.

Only **60M, 100M, 200M and 400M** may be unticked. Every other event — every field
event, the 800M and above, the hurdles, the relays — is run straight to a final and
cannot be asked for one; the request is refused with the reason rather than quietly
ignored.

A direct-to-final event may still have **groups drawn**: that is how a large field
is split across several marking sheets. What it does not have is a final. So the
800M can be printed as three sheets of 24 and still be one race decided by time.

```
POST   /api/events/2/final        # draw (or re-draw) — refused unless the event is split
GET    /api/events/2/final        # who would qualify — likewise
DELETE /api/events/2/final        # remove the final and the marks recorded in it
PUT    /api/events/2              # {"directToFinal": false} to allow a final
```

The short sprints are run in two stages. Everyone runs a heat, the marks are
recorded, and the **fastest eight go through to the final**.

Ranking follows the event: a **track** event is ordered fastest first, a **field**
event longest or highest first, and ties are broken by student id so the same
marks always produce the same final. Athletes who have withdrawn cannot qualify.
The final size defaults to the event's group size — 8 for a short sprint — and
`?limit=` overrides it.

Three details make the two stages safe to live with:

- **Heat and final marks are stored separately.** The final time never overwrites
  the heat time that earned the athlete their place, so the heat leaderboard and
  the qualifying order survive the final being run.
- **A finalist stays in their heat.** The final has its own field
  (`final_entries`) rather than moving the athlete's entry, so a reprinted heat
  sheet still lists all eight of its runners.
- **The final is group number 0**, while heats are numbered from 1. That keeps a
  final from colliding with Heat 1 on the group unique key, and it prints as
  "Final" rather than "Heat 0".

Re-drawing discards the previous final **and the marks recorded in it**, because
those belong to a field that no longer exists; the response says how many were
cleared. Heat marks are never touched.

### The school year

A sport day belongs to a **school year**, and every event belongs to one of those
years. That is what lets the school open entries for this year while last year's
programme and results stay intact — and stay editable.

```
GET    /api/seasons                    # every year, newest first, with its event count
GET    /api/seasons/current            # the year students may enter
POST   /api/admin/seasons              # set up a year (optionally copying a programme)
PUT    /api/admin/seasons/{id}         # its date, name, notes, enrolment switch
POST   /api/admin/seasons/{id}/activate# open entries for it, closing the others
DELETE /api/admin/seasons/{id}         # only while it has no events
GET    /api/events?seasonId=1          # one year's programme
```

One year is **current** — the one students may enter. Activating a year closes the
others, because two open years would let a student enter events for the wrong sport
day. A year with events cannot be deleted, so a programme is never orphaned.

`app.events.seed-event-date` sets up this year's date on a fresh database, and any
event created before years existed is adopted into the year of its own date on the
next start, so the year picker is never empty.

The **school's own details** — name in English and Chinese, address, principal and
the title printed on the sheets — live with the rest of the settings and are
edited on the same page. They head every marking sheet, so a printed sheet
identifies the school without anyone writing it on.

### The year's student list

The register is uploaded once a year, and **that upload is what decides who is a
current student**. Anyone it does not mention is **locked**: they can no longer sign
in or be entered in an event, but their entries, results and records are kept, and
an administrator can unlock them.

```
POST   /api/admin/students/upload/roster?dryRun=true    # rehearse: who would be locked
POST   /api/admin/students/upload/roster?dryRun=false   # apply it
PATCH  /api/admin/students/{studentId}/lock?locked=      # lock or unlock one student
GET    /api/admin/students?enabled=false                 # just the locked ones
POST   /api/admin/students/lock-missing                  # lock anyone left out of the last upload
```

**A rehearsal is required first**, and deliberately so: a per-class or per-form
upload would otherwise lock the rest of the school. `dryRun=true` reports exactly
who would be locked — the count and the students, by name and class — and writes
nothing; the upload is applied on a second call. The plain
`POST /api/admin/students/upload` is unchanged and never locks anyone.

Being on the list is also what makes a student active again: re-uploading somebody
who was locked restores their access, so a student who has been away and returns
needs no separate step.

A student is locked by disabling both their roster record and the account they sign
in with, so the lock is enforced at sign-in and at entry, not just in the user
interface. A locked account signing in gets a **403** saying why.

### An event belongs to one grade

An event is per **type × division × grade**. `Boys 100M · A Grade`, `Boys 100M · B Grade`
and `Boys 100M · C Grade` are three separate events, each with its own heats, marking
sheets, results and placings — so no grade is ever ranked against another. The grade is
in the event's name, so a printed sheet, a results list and an entry list all say which
grade a result belongs to without a column of explanation.

The seeded programme is **112 events**:

| Grade | Events | What it does not run |
|-------|--------|----------------------|
| A | 40 | — |
| B | 38 | the 5000M |
| C | 34 | the 1500M, the 5000M and the 110M hurdles |

Everything else — the sprints, the 800M, the 100M hurdles, the relays and every field
event — runs in all three grades. A grade that does not run an event simply has **no
such event**, which is also how a school adds one: create it for that grade.

```
POST /api/events   { "type": "RUN_100M", "sex": "MALE", "grade": "B", ... }
```

A create or update without a grade is refused, as is a grade the type does not run
(a C-grade 5000M). Entry is then enforced server-side against the event's own grade:

> `This is the A Grade 1500M and you are in the C grade.`

That applies to the student's own entry, to an administrator entering somebody, and to
reviving a withdrawn entry — so the rule cannot be worked around by doing it for them.

There used to be a separate page assigning which grades could enter which events. With
an event per grade that page had nothing left to decide, so it and its rules table are
gone: a grade's programme *is* its list of events.

### Timed in minutes and seconds

A race longer than 400M — the 800M, 1500M and 5000M — is timed on a stopwatch, so
the grid asks for **minutes and seconds**, and a helper writes `2 : 15`, not `135`.
The marking sheet's Record column reads **M:S** for those events.

The mark is still **stored in seconds**: `mark` stays the total, which is what the
placings, the records and the championship points all use. Only the way it is typed
and read changes.

```
{ "userId": 105, "minutes": 2, "seconds": 15 }   # sent
{ "mark": 135, "minutes": 2, "seconds": 15, "unit": "s" }   # stored and read back
```

The seconds box must be **under 60**. `1 minute 75 seconds` is how a helper mistypes
2:15, so that row is refused with a plain explanation rather than silently carried
into a time nobody ran — and the good time already saved is left alone.

### A field too small for a final

A sprint with **8 or fewer** athletes entered — one group's worth — runs straight to
a final, because a final would be the same athletes as the heat. The system switches
the event itself whenever entries are added, withdrawn or cancelled, and marks it as
its own doing so the event list can say **"Direct to final · set automatically"**
rather than passing it off as the school's choice.

The seeded sprints start in exactly that state — marked as the system's own doing —
so **a school that changes nothing gets a final in every sprint that fills past eight
and a straight final in every sprint that does not**. Entries clearing, as a season
reset does, puts them all back.

Entries rising again bring the final back, but only if the system was the one that
took it away. An event the school chose to run straight to a final stays that way
however large the field becomes — which is the whole point of the flag: a deliberate
straight final is not undone behind the school's back.

The rule is therefore one-sided in the other direction too: a school that untickes
the box with six entered is overruled on the next entry change, because a final with
six athletes really would be pointless.

### Mark entry lists what is worth marking

The event picker on the mark-entry page shows only events with **more than one
athlete entered**. An event with nobody, or with one, is not worth a sheet — in the
live register that is 36 of 38 events — so they are left out and the page says how
many were hidden.

### How a result reads

A mark is stored as one plain number — seconds for a race, metres for a throw — which
is right for comparing and wrong for reading. Everything that shows a result to a
person goes through one formatter:

| Event | Stored | Reads as |
|-------|--------|----------|
| 100M | `11.86` | `11.86s` |
| 400M under a minute | `52.337` | `52.337s` |
| 400M over a minute | `64.123` | `1.04.123s` |
| 800M | `130.281` | `2.10.281s` |
| 5000M | `1001.875` | `16.41.875s` |
| Shot put | `18.12` | `18.12M` |
| High jump | `1.95` | `1.95M` |

A time under a minute is just the seconds. Over a minute it gains a minutes part, with
the seconds **padded to two digits** — `1.04.123`, never `1.4.123`, which would read
as a tenth of a second. The separator is a full stop throughout, matching how the
school writes times on paper. The unit is `s` or `M` in English and `秒` or `米` in
Chinese; the raw `mark` and `unit` are always still there beside the formatted text,
so a client that needs the number never has to parse it back out.

### Printing the results

```
GET /api/events/{id}/results.pdf          # one event
GET /api/results.pdf                      # the whole programme
GET /api/results.pdf?category=FIELD       # narrowed to the field events
GET /api/results.pdf?sex=FEMALE           # one division
```

The sheet carries place, student id, name, grade, class, house, the result as it reads
and the points, with a star against a school record. Column headings repeat on every
page, so a sheet lifted off the results board still says what its columns are. An
event with no results yet is left out rather than printed empty, and a print run with
nothing to show is refused with the reason.

### The 100M hurdles

The C grade — fourteen or under — hurdles over the shorter distance, so the programme
carries **100M hurdles** for all three grades and **110M hurdles** for the A and B
grades only. Both are 24 to a group and print on A4.

### Teacher accounts

A teacher is an account (`TEACHER`) plus the set of classes they look after. The
office uploads them in bulk, the same way as the student register:

```
POST /api/admin/teachers/upload?dryRun=true     # rehearse the whole file first
GET  /api/admin/teachers                        # each teacher and their classes
GET  /api/admin/teachers/credentials.csv        # username, name, email, classes, password
GET  /api/admin/teachers/template.csv
PUT  /api/admin/teachers/{username}/classes?classes=1A;3B
DELETE /api/admin/teachers/{username}
```

Columns are `username`, `name`, `classes` (required), and `email` and `password`
(optional). `classes` is one cell with one or more class names separated by `;`, `,`,
`|` or `、`. Re-uploading **updates** a teacher and **replaces** their class list
rather than duplicating either, so the staff file can be run as often as the office
likes. A password that is not supplied is derived from the username (not the student
date-of-birth rule) and returned in the response so it can be handed over; the
credentials sheet always prints the derived one, because a supplied password is never
stored in clear text.

Only a TEACHER can be removed through this path — an administrator or a manager is
refused, so a staff-maintenance call cannot lock the school out of its own system.

### A teacher helping a student

A teacher signs in and gets their own pages; the Navbar shows them no admin links,
because none of them would work.

```
GET    /api/teacher/me                                            # own account + classes
GET    /api/teacher/students[?className=]                         # students in those classes
GET    /api/teacher/students/{studentId}/enrollments              # entries + quota
POST   /api/teacher/students/{studentId}/enrollments/{eventId}    # enter them
DELETE /api/teacher/students/{studentId}/enrollments/{eventId}    # withdraw them
```

**The rule is one implementation**, in `TeacherClassService`, applied by
`TeacherHelpService` before anything is read or written — so entering, withdrawing and
looking somebody up all share it, and it cannot be bypassed by choosing a different
endpoint. An administrator is let through by the rule itself rather than by an
exception at each endpoint. A teacher with **no** classes assigned may help nobody,
which is a refusal and not a silent allow. Every existing rule still applies to the
student: the event's division and grade, and the 2-track/1-field quota.

### Relay teams

A relay event's **category is `RELAY`**, not `TRACK`: it is run and scored by team,
so it is its own family in [the event model](#the-event-model) above. It is still a
race in every way that matters — its mark is a **time** in seconds, the smallest one
wins, and its marking sheet is a race sheet with one record box per line — and it is
that category which gives a relay its own entry allowance rather than the student's
track ones. A live database needs `db/migration/relay-category-migration.sql` for it;
see the note at the head of that file.

A relay event can be divided two ways, chosen on the event itself:

- a **form relay** is one team per **class** — `1A`, `1B`, `1C`, `1D`, then `2A` —
  and the team is named after its class. Which classes it takes is the event's
  **form**: a "Form 1 4x100M" takes every class of Form 1 **whatever grade its
  students are in**, and Form 2 is a separate event;
- a **house relay** is one team per **grade × house**, since the event already
  belongs to one grade, named the way the school writes it: `C Grade Yellow`,
  `C Grade Green`.

The form is written on the event as `form` (`"1"` for Form 1, exposed beside
`formLabel` as `Form 1`). It is a plain number — the number the form's classes
begin with — and a leading zero is read the way the register reads it, so `01` is
Form 1. **Only a relay divided into `FORM` teams may carry one**: a sprint, an
undivided relay and a house relay are all refused with the reason, because none of
them has class teams to draw from. On an update, leaving `form` out leaves it
alone and an **empty string clears it** — the same reading as `relayTeamKind`.
**An event with no form is scoped by its grade, exactly as every relay was before
the form existed, so the relays already on the programme are untouched.**

```
GET    /api/admin/events/{eventId}/relay-teams
POST   /api/admin/events/{eventId}/relay-teams/derive?prune=
POST   /api/admin/relay-events/{eventId}/teams       { name, userIds: [...] }
POST   /api/admin/relay-events/drafts                { type, sex, grade, relayTeamKind, form, teams: [...] }
PUT    /api/admin/relay-events/{draftEventId}/teams/move   { targetEventId }
DELETE /api/admin/relay-events/{draftEventId}/teams
POST   /api/admin/relay-teams/{teamId}/runners      { userId, leg }
DELETE /api/admin/relay-teams/{teamId}/runners/{userId}
PUT    /api/admin/relay-teams/{teamId}/legs         { userIds: [...] }
PUT    /api/admin/relay-teams/{teamId}/name         { name }
DELETE /api/admin/events/{eventId}/relay-teams
```

The same runners, legs and names are available to a teacher under
`/api/teacher/**`, with `PUT /api/teacher/relay-teams/{teamId}/name` admitted only
for a team that is theirs — see below.

The teams are **derived from the register**: a form relay offers one team per class
of the classes that have athletes in **that form and the event's division** — across
grades, so a B-grade athlete and a C-grade athlete in `1A` are one `1A` team — in
school order (`1A, 1B, …, 2A, …, 10B`, never `10B` before `2A`). An event with no form
derives from its **own grade** and division, exactly as before. A relay with no kind is
*undivided* — which is how every existing relay event stays, untouched, until the
school divides it, and deriving one is refused with the reason rather than inventing
teams.

A team is **four runners and at most one reserve** — four members or five. A sixth is
refused with the reason; a team that is still being collected is *saved* and reported
incomplete (`complete: false`), because a helper has to be able to put four runners
down one at a time. A reserve is the runner past the race's own legs and is marked as
one on the wire (`members[].reserve`), so a client needs no second rule to spot it.

A runner must be in the event's division and **scope** — its grade, or, on a
form-scoped event, the form it is scoped to, in which case the grade is not asked
about at all, so a Form 1 relay admits a Form 1 athlete of any grade — and in the
**one group the team runs for**: the class of a form relay, the house of a house
relay. The group rule is read from the **event's** `relayTeamKind`, never from the
team's own `kind`, so it binds a team made by hand exactly as it binds a derived one
— see below. A member of another grade on a *graded* event is refused by the
division-and-grade rule, which the event already implies; on a form-scoped event the
form takes that place, and the class rule is what keeps `1A` to `1A`. One athlete may
hold a leg in a form relay **and** in a house relay, because those are different
events, but never two legs of the **same** event. Every rule is
enforced in one place — `RelayTeamService.requireEligibleForTeam`, which every
endpoint goes through — and a teacher may only pick runners from their own classes —
the same rule as helping a student.

#### Teams made by hand

A derive gives one team per class or per house, which is not always what the school
wants. A teacher can instead **tick the students who applied and create a team from
them**, typing the team's own name:

```
POST /api/admin/relay-events/{eventId}/teams    { "name": "B Grade Yellow", "userIds": [41, 42, 43, 44] }
POST /api/teacher/relay-events/{eventId}/teams  the same, for a teacher's own classes' students
```

The name is **free text** — `1A`, `B Grade Yellow`, anything the school writes — while
the **runners are not**: a hand-made team obeys the event's own kind, so on a
form-class relay every one of them is in the same class and on a house relay every one
is in the same house. `userIds` is the running order, so **leg 1 is the first student
listed**. Fewer than four is allowed and reported incomplete (`complete: false`), a
fifth is the reserve when the event allows one and a sixth is refused, exactly as
adding runners one at a time behaves. Every eligibility rule is the same one: the
caller may help each student (`TeacherClassService`), the student is in the event's
division and grade, the student is in the team's one class or house, and one athlete
holds one leg per event. A name is trimmed, must not be blank, is at most 40
characters and must be unique within its event — the same check a rename uses.

**A relay team never mixes, and that binds a hand-made team too.** A form-class relay
is one class's — a `1A` team cannot hold a `1B` or a `2A` student — and a house relay
is one house's. The rule is read from the event's `relayTeamKind` and not from the
team's own `kind`, which is exactly what makes it bind a hand-made team: such a team
has no `kind` at all, so judging it by that would exempt it from the rule. A hand-made
team's group is whoever was named first, and every runner after them has to be in it,
so a mixed squad is refused in one request or one runner at a time, naming the student
who does not belong:

```
"Athlete S0003 is in class 2A, and 1A runs for this team only — a form relay team
 cannot mix classes."
"Athlete S0002 is in Blue House, and Red House runs for this team only — a house
 relay team cannot mix houses."
```

Every runner is judged before anything is written, so a refused request leaves no team
and no runners behind.

A relay with **no kind** is undivided: it has no class rule and no house rule to break,
so a hand-made team of any students is allowed there — that is the state a relay is in
while its teams are still being put together, and the school builds its teams on a
draft event (which always has a kind) before moving them onto the race.

A team made this way is **not the roster's**, and says so: it carries **no
`kind`** at all, whatever kind its event has, and is returned with
`handMade: true` (and `kind: null`). A derive builds its key set from class names or
house names, so a team with no kind can never be in it. The consequence, which is the
point of the design:

- a derive **never renames** it — the school's typed name survives every re-derive;
- a derive **never re-keys** it, and `(event_id, kind, team_key)` cannot collide with
  a derived team, because the derived one always has a kind and this one never does;
- a derive **never prunes** it, even with `prune=true` and even when the team has
  nobody in it yet — a teacher who names a team before choosing its runners does not
  lose it the next time anybody derives.

The team's `teamKey` is **its own name** (trimmed), not a generated id: the name is
already unique within the event, so it satisfies the unique key on its own and stays
readable in a database row. Renaming the team afterwards leaves the key where it is,
as it does for every team.

A hand-made team can be filled and reordered with the ordinary
`relay-teams/{teamId}/...` endpoints, and shows on the board beside the derived ones.
It is also shown on an event that has **no kind** at all — a relay nobody has divided
yet still has whatever teams the school has made for it.

**Schema.** A hand-made team has no `kind`, so `relay_teams.kind` must be nullable,
and the flag is one new column, `relay_teams.hand_made`. The original
`relay-teams-migration.sql` created `kind` as `ENUM('FORM','HOUSE') NOT NULL`, so on
a database that predates this round both changes must be applied —
`backend/db/migration/relay-teams-hand-made-migration.sql` does exactly that and
touches no data. Hibernate's `ddl-auto=update` adds `hand_made` on start, but
whether it also relaxes `kind` depends on the dialect's column comparison, so the
script is the reliable path (and is required where `ddl-auto` is `validate`).

#### Renaming a team

A team's name is free text — what the school writes on the sheet — and a rename never
touches the team's runners or the key it is matched on. An **administrator** may rename
any team. A **teacher** may rename a class team that belongs to one of their own
classes, on the same `TeacherClassService` rule that governs helping a student; a
**house** team spans many classes and belongs to no one teacher, so a teacher may
rename it only while one of their own athletes is named on it, and an empty house team
is an administrator's to name.

A name is trimmed, must not be blank, is at most 40 characters, and must be unique
within its event — a marking sheet lists the teams of a race by name, so two teams
sharing one is a result nobody could read off. A name typed by hand is remembered
(`nameOverridden`), so deriving the teams again refreshes the names the roster gives
and leaves a renamed team's name alone.

#### The applicants beside the teams

The same board also carries the students who **applied**: every student with a
**confirmed entry** in the event, so the page can tick them and group them into the
teams that already exist. Each one carries the form, class, house and house code,
and the team they are already on when they are on one:

```json
{
  "teamCount": 4, "runnerCount": 14,
  "applicantCount": 8, "placedCount": 5, "unplacedCount": 3,
  "applicants": [
    { "userId": 41, "studentRef": "S0041", "name": "Chan Tai Man",
      "form": "1", "className": "1A", "classNumber": 1, "classLabel": "1A 1",
      "house": "Red", "houseCode": "R",
      "teamId": 7, "teamLabel": "1A", "placed": true }
  ]
}
```

An applicant on no team has `teamId: null` and `placed: false`, which is what the
page shows as still unplaced; `unplacedCount` is returned so it need not count them
itself. A **withdrawn** or still-**unconfirmed** entry is not an applicant, and the
list reads in the register's own order — form numerically (Form 2 before Form 10),
then class, then class number, then name. It costs two queries for the event
however many applicants there are.

A **teacher** is shown only the applicants of their own classes, because those are
the students they may actually place; an **administrator** is shown all of them.
That holds on a **house** relay too: a house team deliberately spans classes, so a
teacher sees the whole team and everyone named on it — otherwise they could not see
who is running with whom — but only their own students are offered as somebody to
place, and `PUT .../relay-teams/{id}/name` is unchanged.


#### A relay event built around its teams

The school builds the **teams** first and wants the **event** created around them.
A relay team cannot live without an event — `relay_teams.event_id` is `NOT NULL`, and
the marking sheet and the mark grid both reach a team *through* its event — so the
teams are collected on a **draft** relay event and then moved onto the race the
school actually runs:

```
GET    /api/admin/relay-events/drafts                        # the drafts being filled
POST   /api/admin/relay-events/drafts                        # the event, plus its teams
PUT    /api/admin/relay-events/{draftEventId}/teams/move     { "targetEventId": 7 }
DELETE /api/admin/relay-events/{draftEventId}/teams          # discard, deliberately
```

A draft is an ordinary `events` row with one extra flag, `is_draft`. The flag is
**nullable and null reads as false**, so every event already on file — and every
event created without it — is a real event and behaves exactly as it did.

```
POST /api/admin/relay-events/drafts
{
  "type": "RELAY_4X100M", "sex": "MALE", "grade": "B",
  "relayTeamKind": "FORM", "form": "1", "eventDate": "2026-10-01",
  "teams": [
    { "name": "B Grade Yellow", "userIds": [41, 42, 43, 44] },
    { "name": "B Grade Green",  "userIds": [51, 52, 53] }
  ]
}
```

`teams` is the same shape as one team made by hand, so each one is created through
exactly that call: the name rules, the four-plus-one cap, the event's division and
scope (its grade, or the form the draft is scoped to), one leg per athlete per event,
and the class rule a teacher is held to are all the rules that already exist, with no
second copy of any of them. Every team is
judged before any is written, and the kind (`FORM` or `HOUSE`) is required because
that is what the register judges the teams and their runners against. A draft of
anything that is not a relay — and a draft with no kind — is refused with the reason.

**A draft never looks like a real event to the school.** The programme
(`GET /api/events`), the dates the picker offers (`GET /api/events/dates`), the
past-events list and the year's event count all exclude it, so it offers no entry and
is counted nowhere; the whole-programme results PDF and the whole-school sheet print
run skip it too. The **draft's own relay board** — `GET
/api/admin/events/{id}/relay-teams` — of course shows it, and an administrator finds
the drafts in `GET /api/admin/relay-events/drafts`, which is the one listing a draft
belongs in. A year that still contains a draft cannot be deleted, even though the
draft is not counted on it.

The **move** re-points every team of the draft at the target event, **all of them or
none**. The target must be a relay event, and every team is re-judged against it
before anything moves: a name already used in the target refuses the whole move (the
same name rule a create and a rename share), a squad bigger than the target's own
team refuses it, and a runner no longer in the target's division and grade refuses
it. Nothing is renamed, re-keyed or dropped to make a move fit. Because every rule is
checked before the first write — and because the method is one transaction that ends
with a flush — a refused move leaves the draft exactly as it was. The answer says how
much moved:

```json
{
  "draftEventId": 12, "draftEventName": "Boys 4x100M Relay · B Grade (teams)",
  "targetEventId": 7, "targetEventName": "Boys 4x100M Relay · B Grade",
  "teamsMoved": 2, "runnersMoved": 7,
  "teamLabels": ["B Grade Yellow", "B Grade Green"],
  "targetEvent": { "id": 7, "...": "as every other event endpoint describes it" }
}
```

**The draft is kept**, emptied of its teams, with its kind, grade and division
untouched — so there is somewhere to build the next squad, and the draft can be
cleared away deliberately once it is empty. A draft's teams are **never destroyed as
a side effect**: `EventService.deleteEvent` removes an event's teams with it, so
deleting a **draft that still holds teams is refused** outright, and the shared call
that clears an event's teams refuses a draft too. Throwing a draft's teams away is
its own explicit request, `DELETE /api/admin/relay-events/{id}/teams`.

**Schema.** One nullable column, `events.is_draft`:
`backend/db/migration/event-drafts-migration.sql`. Hibernate's `ddl-auto=update`
would add it on the next start anyway — a nullable `ADD COLUMN` is the one case its
schema update handles reliably — with every existing row `NULL`, which reads as
false; the script is the explicit path and is what is required where `ddl-auto` is
`validate` or `none`. It touches no data.


### School records

A record belongs to one event, division and grade — **Boys 100M, B Grade**. A row
exists for **every** such combination from the moment the event does, so the
records page is complete before anybody has competed and an event with nothing
recorded yet still appears.

```
GET    /api/records                            # every record
PUT    /api/admin/records/{id}                  # type one in by hand
DELETE /api/admin/records/{id}/baseline         # clear the typed-in mark
POST   /api/admin/records/seed                  # create any missing rows
POST   /api/admin/records/recompute             # rebuild from the results
```

The mark that stands is the better of two things:

- a **baseline** an administrator types in — last season's best, or a mark held by
  a student who has since left, which is why the holder is a **name, not an
  account**;
- the **best result** recorded in any event of that type and division, by an
  athlete in that grade.

The baseline is stored separately from the mark that stands, so recomputing never
overwrites it. Clearing the results, deleting an event or resetting the season
falls back to the typed-in mark rather than losing it — a hand-entered record
survives all of them.

A result that beats a typed-in mark takes over and the record remembers what it
beat ("12.10, beating 13.50 by Chan Tai Man (2018)"). Improving the current
holder's own mark is treated as a correction and does not pretend somebody was
beaten. Which way is better follows the event: a **track** time is
lower-is-better, a **field** distance or height is higher-is-better.

### Entering students for them

An administrator can enter a student in events when the student cannot do it
themselves, and update those entries afterwards:

```
GET    /api/admin/students/{studentId}/enrollments             # entries + quota
POST   /api/admin/students/{studentId}/enrollments/{eventId}    # enter them
DELETE /api/admin/students/{studentId}/enrollments/{eventId}    # remove an entry
```

The quota is enforced **against the student, not the administrator** — an admin
cannot exceed it either, and the refusal says which category is full. Removing an
entry frees the place immediately, and entering a student again **revives an entry
they had withdrawn from** rather than refusing it, so a student who opted out can be
put back in.

### Viewing the programme by date

```
GET /api/events/dates            # every date that has events, with its count
GET /api/events?date=2026-10-01  # one day's programme
```

`date` composes with the existing `onlyEnabled`, `sex` and `category` filters, so a
multi-day meeting can be worked through a day at a time. The date picker marks
today and the days already past.

### Personal and house champions

```
GET /api/championships          # the tables, plus the placings behind them
GET /api/events/{id}/standings  # one event's placings
```

Two tables are produced. The **personal championship** totals individual events;
the **house championship** totals every event, relays included.

| Placing | Individual | Relay |
|---------|-----------:|------:|
| 1st | 9 | 30 |
| 2nd | 6 | 20 |
| 3rd | 3 | 10 |
| 4th–8th | 1 each | 1 each |
| 9th and below | 0 | 0 |

Three rules decide who gets what:

- **Where an event ran a final, the final decides the points** and the heats are
  qualifying only. A final that has been drawn but not yet run does *not* suppress
  the heats — until somebody runs it, the heat marks stand.
- **Relay points count for the house only**, so the personal championship is
  decided on individual events.
- Nothing outside the top eight scores, and the whole scale is editable (below).

### Settings — entry limits and points

```
GET  /api/settings               # anyone signed in
PUT  /api/admin/settings         # ADMIN — omit a field to leave it alone
POST /api/admin/settings/reset   # ADMIN — back to the documented defaults
```

One settings row holds how many events a student may enter — **2 track (徑項) and
1 field (田項)** by default, which used to be hard-coded — and every point value
above. Changing the limits takes effect immediately: every event reports the new
limit, and the entry quota enforces it. The row is created with these defaults the
first time it is read, so a fresh database behaves as it always did.

### Accounts

**There is no public sign-up.** Self-registration was removed, and
`POST /api/auth/register` now returns 404.

```
POST   /api/admin/users?role=ADMIN|MANAGER|USER   # ADMIN
GET    /api/admin/users/roles                     # ADMIN
GET    /api/users                                 # ADMIN or MANAGER
PATCH  /api/users/{id}/enable?enabled=            # ADMIN
DELETE /api/users/{id}                            # ADMIN
```

Only an administrator creates staff accounts. **Student accounts are not created
here** — they arrive through the register import, which also derives each
password. Asking for the `STUDENT` role here is refused with a message saying so.

### Input helpers

An **input helper** (`HELPER`) is a trusted volunteer on the day: they key in the
marks a heat or a final produced, and print the marking sheets the helpers write
on. That is the whole of it — a helper fills the programme in and cannot change
it. They may reach **only** these:

```
GET    /api/events/{id}/marks            # read the mark sheet (HEAT or FINAL)
POST   /api/events/{id}/marks            # save the grid
GET    /api/groups/{groupId}/sheet.pdf   # one marking sheet
GET    /api/events/{id}/sheets.pdf       # every sheet of an event
GET    /api/sheets.pdf                   # the whole-school print run
```

and everything else is refused, in particular the event CRUD, the heats and the
draw, the settings, the student register and its upload, the users, the backups,
the season reset, the relay teams and the recording of results. There is **no new
URL family** for the role: the mark and sheet endpoints already exist and the
`@PreAuthorize` on `MarkEntryController` and `EventGroupController` admits
`HELPER` alongside `ADMIN` and `MANAGER`.

Note the ordering trap on `GET /api/events/{id}/sheets.pdf`: an earlier public
`GET /api/events/**` rule matches it first, so on the URL layer that download
looks open. It is the controller annotation that closes it, which is why both
layers have to name the role.

`HELPER` is a value on the `users.role` **MySQL ENUM**, and `ddl-auto=update`
never widens an existing ENUM — so on an existing database the role needs
`backend/db/migration/helper-role-migration.sql` *and* a restart before a helper
account can be created or sign in. Without it the insert is truncated (the
account becomes a `USER`) or refused with *"Data truncated for column 'role'"*.

### Marking sheets

One sheet per heat or final, with **student id / name / grade / record / remark**.
The record and remark columns are left blank for the helper. The sheet is padded
out to the group size, so a late entry still has a line, and a final's sheet is
headed `組別 Group: Final … 決賽 Final` so it cannot be mistaken for another heat.

A **final**'s sheet carries one column more, **初賽 Heat**, between the grade and
the record boxes: the heat performance that earned the athlete their place, read
the way every other result reads (`11.86s`, `1.04.123s`), or **ABS** / **DQ** when
the heat produced no number. The record boxes below it stay blank for the final.
A **heat** sheet is unchanged — no such column, same columns and same paper.

A **field** sheet gets three attempt boxes under one `成績 Record (M)` heading
instead of a single record column, because a field event gives three attempts and
the best counts. A track sheet keeps its single column, headed in seconds.

### Units and attempts

Marks are written the way an athletics programme writes them: **`M`** for a field
event (a distance or a height) and **`s`** for a track one (a time). The unit is
carried on the event itself, so the entry page and the mark-entry grid both show
it without anyone typing it per athlete. Marks recorded before the units were
shortened are rewritten once on the next start.

**A field event gives every athlete three attempts, and the best one becomes their
result.** The attempts are kept as well as the best, so a marking sheet can be
checked against the system afterwards.

```jsonc
// POST /api/events/9/marks — a field row, the second throw the best
{ "stage": "HEAT",
  "rows": [ { "userId": 61, "unit": "M", "attempts": [8.20, 11.45, 9.90] } ] }

// POST /api/events/2/marks — a track row, a single time (unchanged)
{ "stage": "HEAT", "rows": [ { "userId": 61, "mark": 12.34, "unit": "s" } ] }
```

A missed attempt is sent as `null` (or simply left off the end) and is **ignored
rather than counted as zero**. The best of what is left becomes `mark`, which is
what the placings, the school records and the championships all read — so an
athlete is ranked on their best throw, and a record is set by it, with no special
handling anywhere downstream.

`GET /api/events/{id}/marks` reports `attemptCount` (3 for a field event, 1 for a
track one) and `fieldEvent`, so the UI knows how many boxes to draw, and returns
each row's `attempts` in order.

---

## Grades

| Grade | Age on the sport day |
|-------|---------------------|
| **C** | 14 or below |
| **B** | 15 – 16 |
| **A** | 17 or above |

Age is measured in whole years on a **reference date**, which is the sport day
rather than the upload date. Set it explicitly so grades are frozen for the whole
season:

```yaml
app:
  grade:
    reference-date: "2026-11-06"   # blank = today
```

Grades are stored on each student, so moving the sport day does not silently
change existing records — run **Admin → Students → Recompute grades** (or
`POST /api/admin/students/recompute-grades?referenceDate=...`) to move the whole
school onto the new date.

---

## Form, class and house

Everywhere a student is listed — the register, the mark grid, the relay rosters, the
marking sheets, the championship tables — the row carries the **form**, the **class**
and the **house**, and the house also as its short code:

| field | example | meaning |
|-------|---------|---------|
| `className` | `5A` | the class as the register writes it |
| `form` | `5` | the leading run of digits of the class; null when the class names no form |
| `house` | `Red` | the house in full, exactly as it is stored |
| `houseCode` | `R` | `R` Red, `Y` Yellow, `B` Blue, `G` Green; null for any other house |

Both derivations live on `Student` — `Student.formOf(className)` and
`Student.houseCodeOf(house)` — and nowhere else, so two listings cannot disagree
about what form `5A` is or which letter `Red` has. The form is read numerically, so
`10B` is Form 10 and not Form 1. The house is matched trimmed and
case-insensitively, so `red`, ` Red ` and `RED` are all `R`, and the **stored house
keeps its full name** — only the code is derived.

A house that is not one of the four yields **null**, not its first letter: a school
may keep a fifth house, and `Black` reading `B` — which is Blue's — would put a
wrong house on a sheet. An unknown house is honestly blank rather than plausibly
wrong.

---

## Uploading the student register

**Admin → Students**, or:

```bash
curl -X POST http://localhost:8080/api/admin/students/upload \
     -H "Authorization: Bearer <admin-token>" \
     -F "file=@sportday-sample-data/students-600.csv" \
     -F "referenceDate=2026-10-01"
```

Accepts **.csv** and **.xlsx**. The import is idempotent — matching student ids are
updated, not duplicated.

Headings are matched loosely, so all of these work:

```
studentId, Student ID, student_id, 學號, 学号
name, Full Name, 姓名
dob, Date Of Birth, 出生日期
sex, Gender, 性別
className, Class, Form, 班別
classNumber, Class No, 班號, 座號
house, 社            (optional)
```

Dates may be written `2010-03-15`, `2010/3/15`, `15/3/2010`, `20100315` or
`2010.3.15`.

**Bad rows never fail the file.** They come back with their spreadsheet row number
so they can be corrected and re-uploaded:

```json
{
  "totalRows": 600, "created": 598, "updated": 0, "failed": 2,
  "gradeCounts": { "C": 240, "B": 198, "A": 162 },
  "errors": [
    { "rowNumber": 11, "studentId": "S0010", "message": "Unrecognised date of birth '3 Jan 2010' — use YYYY-MM-DD." }
  ]
}
```

---

## Sample data

`sportday-sample-data/` holds **600 students** in both CSV and XLSX, verified and
regenerable — see [its README](sportday-sample-data/README.md).

You can also generate them from the app (**Admin → Students → Generate 600 sample
students**) or the API:

```bash
curl -X POST "http://localhost:8080/api/admin/students/sample?count=600" \
     -H "Authorization: Bearer <admin-token>"
```

---

## Marking sheet font

Student and event names are usually Chinese, so the sheets need a Unicode TrueType
font. The system looks for one in this order:

1. a font bundled at `backend/src/main/resources/fonts/cjk.ttf`
2. `app.pdf.font-path` (or set `SPORTDAY_PDF_FONT`)
3. well-known system font locations — Noto Sans HK/TC/SC, SimHei, Arial Unicode
   on Windows; Droid Sans Fallback, AR PL UMing/UKai on Linux; Arial Unicode on macOS

At startup it logs which font it chose and whether it covers Chinese:

```
Marking-sheet font: NotoSansHK-Thin (unicode/CJK capable)
Marking-sheet font resolved from: Windows Noto Sans HK [C:/Windows/Fonts/NotoSansHK-VF.ttf]
```

Two things worth knowing:

- **TrueType collections (`.ttc`) cannot be used** — this includes the usual
  Noto CJK, Microsoft JhengHei and PingFang packages. Ship a single-font `.ttf`,
  or point `app.pdf.font-path` at one.
- A font that *loads* is not automatically a font that *renders correctly*.
  `PdfFontGlyphMappingTest` renders sample text through two independent PDF
  implementations and compares the ink, so a font that maps Chinese code points
  onto the wrong glyphs fails the build instead of quietly printing the wrong
  names.

---

## API reference

### Authentication

| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/auth/login` | Public | Sign in — students use their student id |

`POST /api/auth/register` has been **removed** and returns 404. Accounts are created
by an administrator; see [Accounts](#accounts).

### Events

| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/events?onlyEnabled=&sex=&category=&date=&seasonId=` | Public | List events — the filters compose, e.g. the enabled boys' field events in one school year |
| GET | `/api/events/{id}` | Public | Event detail |
| POST | `/api/events` | Admin/Manager | Create (enabled by default) |
| PUT | `/api/events/{id}` | Admin/Manager | Update |
| PATCH | `/api/events/{id}/enable?enabled=` | Admin/Manager | Enable / disable |
| DELETE | `/api/events/{id}` | Admin | Delete with its entries and results |
| POST | `/api/events/defaults?eventDate=` | Admin | Create the standard catalogue |

### Entries

| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/enrollments/{eventId}` | Authenticated | Enter an event |
| DELETE | `/api/enrollments/{eventId}` | Authenticated | Withdraw |
| POST | `/api/enrollments/{eventId}/re-enroll` | Authenticated | Re-enter after withdrawing |
| GET | `/api/enrollments/my` | Authenticated | My confirmed entries |
| GET | `/api/enrollments/my/all` | Authenticated | Full history |
| GET | `/api/enrollments/my/quota` | Authenticated | Remaining 徑項/田項 allowance |
| GET | `/api/enrollments/check/{eventId}` | Authenticated | Am I entered? |
| GET | `/api/enrollments/event/{eventId}` | Admin/Manager | Entries for one event |

### Heats and marking sheets

| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/events/{eventId}/groups` | Authenticated | The heats and the final of an event (`?includeRosters=true` to get the athletes in the same request) |
| GET | `/api/groups/{groupId}` | Authenticated | One group with its roster |
| POST | `/api/events/{eventId}/groups/allocate?shuffle=` | Admin/Manager | Allocate / re-allocate heats (discards any final) |
| DELETE | `/api/events/{eventId}/groups` | Admin/Manager | Remove all groups, including the final |
| GET | `/api/events/{eventId}/final?limit=` | Admin/Manager | Who would qualify for the final on the heat marks — changes nothing |
| POST | `/api/events/{eventId}/final?limit=` | Admin/Manager | Draw or re-draw the final (the top 8 by default) |
| DELETE | `/api/events/{eventId}/final` | Admin/Manager | Remove the final and the marks recorded in it |
| GET | `/api/groups/{groupId}/sheet.pdf` | Admin/Manager/Helper | One marking sheet (A5 or A4), heats and final alike — a final's sheet waits for the draw |
| GET | `/api/events/{eventId}/sheets.pdf` | Admin/Manager/Helper | Every group of an event, one per page — held back until a final the event will run has been drawn |
| GET | `/api/sheets.pdf?sex=&category=` | Admin/Manager/Helper | Whole-school print run |

### Mark entry

The mark-entry grid reads every athlete competing in a stage of an event — with their group,
lane and any mark already recorded — filtered by group and/or grade, and saves the whole grid
back in one request. `stage` selects `HEAT` (the default) or `FINAL`, and the two keep
separate marks.

| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/events/{eventId}/marks?stage=&groupId=&grade=` | Admin/Manager/Helper | Grid rows plus the group and grade filter options |
| POST | `/api/events/{eventId}/marks` | Admin/Manager/Helper | Save the grid in one request |

#### The final waits for the heat results

A final is drawn **from** the heat marks, so it cannot be worked on before it exists. Asking
for `stage: FINAL` — reading the grid or saving it — is refused with **409** until the draw
has run, and so is an event's print run, because a final marking sheet is the final's field
and there is no field yet. One rule answers all three, `FinalStageGuard`, which is the same
rule `FinalQualificationService` applies when it refuses to draw without heat results.

The refusal says which of the three cases it is:

| The event | `finalState` | The 409 says |
|-----------|--------------|--------------|
| an 800M, a hurdles race, a relay, a field event | `NONE` | *"… is run straight to a final, so there is no final to enter marks for or print a sheet from. Only 60M, 100M, 200M and 400M can be split into heats and a final."* |
| a sprint set to run straight to a final | `DIRECT` | *"… is set to run direct to a final, so there is no final to work on. Untick \"direct to final\" on the event first."* |
| a sprint running heats, draw not yet run | `NOT_DRAWN` | *"The final has not been drawn yet. Record the heat marks first, then draw the final."* |
| a sprint whose final has been drawn | `DRAWN` | — the final's grid and sheet are live |

`GET /api/events/{eventId}/marks` carries `finalState` (and `finalDrawn`, kept for
compatibility) so a client can gate its own final button rather than wait for a 409 to find
out. `finalDrawn` alone cannot tell "no final because this event has none" from "no final
yet" — both read `false` — so read `finalState`.

```jsonc
// POST /api/events/2/marks
{
  "rows": [
    { "userId": 12, "mark": 8.123, "notes": "PB" },   // stored (unit falls back to the event default)
    { "userId": 13, "mark": 8.456, "unit": "seconds" },
    { "userId": 14, "mark": null },                    // left untouched
    { "userId": 15, "clear": true }                    // mark removed
  ]
}
```

```jsonc
// response
{
  "eventId": 2, "eventName": "Boys 60M",
  "saved": 2, "cleared": 1, "skipped": 1, "failed": 0,
  "results": [ /* the leaderboard, fastest first */ ],
  "errors": []
}
```

Rows are independent, so one bad athlete never loses the rest of the batch. An athlete who is
not entered, a negative or absurd mark, and the same athlete twice in one save are each
reported in `errors` while the good rows are still stored.

### Admin — students

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/admin/students/upload` | Upload a CSV/XLSX register |
| POST | `/api/admin/students/upload/roster` | Upload the year's **complete** list; students it omits are locked (`dryRun=true` rehearses it) |
| PATCH | `/api/admin/students/{studentId}/lock` | Lock or unlock one student |
| POST | `/api/admin/students/lock-missing` | Lock everyone left out of the most recent upload |
| POST | `/api/admin/students/sample?count=600` | Generate sample students |
| GET | `/api/admin/students` | List / filter the register |
| GET | `/api/admin/students/{studentId}` | One student |
| DELETE | `/api/admin/students/{studentId}` | Remove a student, their account, entries and results |
| GET | `/api/admin/students/summary` | Counts by grade and sex |
| GET | `/api/admin/students/credentials.csv` | Login credentials sheet |
| GET | `/api/admin/students/template.csv` | Empty upload template |
| GET | `/api/admin/students/sample.csv?count=600` | The sample register as CSV |
| POST | `/api/admin/students/recompute-grades` | Recompute grades for a new sport day |
| GET | `/api/admin/students/grade-rule` | The grade and password rules |
| GET | `/api/admin/students/{studentId}/enrollments` | A student's entries and remaining quota |
| POST | `/api/admin/students/{studentId}/enrollments/{eventId}` | Enter the student in an event for them |
| DELETE | `/api/admin/students/{studentId}/enrollments/{eventId}` | Remove the student's entry |

### Records, championships, settings and accounts

| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/events/past` | Authenticated | Events already held, most recent first |
| GET | `/api/events/dates` | Public | Every date that has events, with its count |
| GET | `/api/seasons` | Authenticated | Every school year, newest first, with its event count |
| GET | `/api/events/{id}/results.pdf` | Authenticated | One event's results, for the board |
| GET | `/api/results.pdf` | Authenticated | Every event's results in one document |
| GET | `/api/seasons/current` | Authenticated | The year students may enter |
| POST | `/api/admin/seasons` | Admin | Set up a year, optionally copying a programme |
| PUT | `/api/admin/seasons/{id}` | Admin | A year's date, name, notes, enrolment switch |
| POST | `/api/admin/seasons/{id}/activate` | Admin | Open entries for it, closing the others |
| DELETE | `/api/admin/seasons/{id}` | Admin | Delete a year, only while it has no events |
| GET | `/api/events/{eventId}/standings` | Authenticated | One event's placings and what each place is worth |
| GET | `/api/championships` | Authenticated | Personal and house tables, plus the placings behind them |
| GET | `/api/records` | Authenticated | Every record, including events with nothing recorded yet |
| PUT | `/api/admin/records/{recordId}` | Admin | Type a record in by hand, or replace one |
| DELETE | `/api/admin/records/{recordId}/baseline` | Admin | Clear the typed-in mark, leaving the record to the results |
| POST | `/api/admin/records/seed` | Admin | Create the records an event should have |
| GET | `/api/settings` | Authenticated | Entry limits and the points scale |
| PUT | `/api/admin/settings` | Admin | Change any of them; omitted fields keep their value |
| POST | `/api/admin/settings/reset` | Admin | Restore the documented defaults |
| POST | `/api/admin/records/recompute` | Admin | Rebuild every record from the results |
| GET | `/api/admin/users/roles` | Admin | The roles an admin may hand out |
| POST | `/api/admin/users?role=` | Admin | Create a staff account (students come from the register import) |

`POST /api/auth/register` **no longer exists** and returns 404.

### Other admin

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/admin/season/reset` | Clear entries, heats, the final and results; keeps students, events and hand-entered records. Writes a restorable backup file first, and refuses to run at all if it cannot |
| GET | `/api/admin/backups` | Every season backup on disk, newest first, with its size, when it was taken and the counts out of its header |
| GET | `/api/admin/backups/{name}` | Download one backup file. A name that would resolve outside the backup directory is refused with 400 |
| POST | `/api/admin/backups/{name}/restore` | **Destructive** — overwrite the current entries, heats, final places, marks and record baselines with the file's |
| POST | `/api/admin/managers` | Create a manager account |
| GET | `/api/users`, `PATCH /api/users/{id}/enable`, `DELETE /api/users/{id}` | Staff account management |
| GET | `/api/admin/relay-events/drafts` | The draft relay events — the events built around their hand-made teams. The only listing a draft appears in |
| POST | `/api/admin/relay-events/drafts` | Create a draft relay event together with the teams it is built around |
| PUT | `/api/admin/relay-events/{draftEventId}/teams/move` | Move a draft's teams onto the real relay event, all of them or none. Body `{ "targetEventId": N }` |
| DELETE | `/api/admin/relay-events/{draftEventId}/teams` | Discard a draft's teams deliberately, so the empty draft can be deleted |

---

## Backing up before a reset

A season reset is one-way: it deletes every entry, heat, final place and recorded
mark the school has. So it writes a **restorable backup first**, and if that file
cannot be written — the directory missing and uncreatable, a disk error, a value
that will not serialise — the reset **does not run** and says why. Nothing is
deleted on a failed backup; there is no catch-and-continue on that path.

The response names the file and its size, so the administrator can put it
somewhere safe straight away:

```jsonc
// POST /api/admin/season/reset
{
  "backupFile": "sportday-season-20261004-162135.json",
  "backupBytes": 1843221,
  "backupWrittenAt": "2026-10-04 16:21:35",
  "enrollmentsRemoved": 1038,
  "finalPlacesRemoved": 96,
  "groupsRemoved": 158,
  "resultsRemoved": 1206,
  "recordsKept": 112,
  "eventsReformatted": 24,
  "studentsKept": "unchanged",
  "eventsKept": "unchanged"
}
```

Set `app.backup.dir` to put the files somewhere else:

```yaml
app:
  backup:
    dir: "backups/"   # relative to the working directory unless absolute
```

Files go to that directory — **`backups/`** by default — which is created if it is
missing. It holds register data and is ignored by git, exactly like `db-backup/`. A
file is named `sportday-season-yyyyMMdd-HHmmss.json`, so a listing sorts
chronologically as plain text and a backup identifies itself by name.

Each file is one JSON document: a small **header** — the app, the format version,
when it was taken and the row counts — followed by the season itself, so a file can
be identified and believed before the body is read:

```jsonc
{
  "app" : "SportDay",
  "version" : 1,
  "header" : {
    "app" : "SportDay",
    "version" : 1,
    "writtenAt" : "2026-10-04T16:21:35",
    "counts" : {
      "enrollments" : 1038,
      "finalEntries" : 96,
      "groups" : 158,
      "results" : 1206,
      "records" : 112
    },
    "note" : "Entries, heats, final places, marks and school records as they stood before a season reset. Students, events and settings are not included."
  },
  "groups" : [ {
    "id" : 7, "eventId" : 2, "groupNumber" : 1, "stage" : "HEAT",
    "capacity" : 8, "athleteCount" : 8, "createdAt" : "2026-10-01T08:30:00"
  } ],
  "enrollments" : [ {
    "id" : 91, "userId" : 12, "eventId" : 2, "status" : "CONFIRMED",
    "groupId" : 7, "lane" : 3, "enrolledAt" : "2026-09-20T09:00:00"
  } ],
  "finalEntries" : [ {
    "id" : 4, "groupId" : 8, "userId" : 12, "lane" : 1, "seed" : 1,
    "seedMark" : 11.204, "seedUnit" : "s", "createdAt" : "2026-10-01T11:00:00"
  } ],
  "results" : [ {
    "id" : 33, "userId" : 12, "eventId" : 2, "stage" : "HEAT",
    "mark" : 11.204, "unit" : "s", "attempt1" : 11.204, "notes" : "PB",
    "outcome" : "RESULT", "recordedAt" : "2026-10-01T09:15:00"
  } ],
  "records" : [ {
    "id" : 5, "eventType" : "RUN_100M", "sex" : "MALE", "grade" : "B",
    "manualMark" : 11.500, "manualUnit" : "s", "manualHolderName" : "Chan Tai Man",
    "manualAchievedOn" : "2018-05-01", "mark" : 11.204, "unit" : "s",
    "source" : "RESULT", "holderName" : "Athlete 12", "holderUserId" : 12,
    "achievedOn" : "2026-10-01", "resultId" : 33, "eventId" : 2,
    "previousMark" : 11.500, "previousHolderName" : "Chan Tai Man",
    "previousAchievedOn" : "2018-05-01"
  } ]
}
```

Timestamps are ISO-8601 text, so the file reads as well as it parses, and an absent
value is simply left out.

It covers **what the reset destroys** — entries, groups, results and the final's
field — **and the school records**, which a reset keeps: a record whose mark came
from a result falls back to its typed-in baseline, and a school that wants that
undone needs the records in the file too. The students, the event catalogue, the
school years and the settings are deliberately **not** in it: a reset leaves those
alone, so a backup of one season's competition data has no business restoring them.

### Putting one back

`POST /api/admin/backups/{name}/restore` **overwrites current data**. It clears the
entries, heats, final places and marks, and the parts of the school records a
result holds, then writes the file's rows back in an order that keeps every foreign
key satisfied:

1. the records let go of their results and events, which are about to be deleted;
2. the final places and the entries go — both point at a group;
3. the groups go;
4. only then the results, which nothing points at;
5. inserts run the other way round: **groups first**, so their generated ids are
   known, then the entries and final places pointing at them, then the results;
6. the records' typed-in **baselines** are put back, and every record is recomputed
   from the restored results — which is also what re-points a record at the result
   that holds it now, because a restored result has a new id.

A row whose student or event is no longer in the system is **skipped and counted**
rather than invented, and the response says how many of each:

```jsonc
{
  "restoredFrom": "sportday-season-20261004-162135.json",
  "groupsRestored": 158, "enrollmentsRestored": 1038, "finalEntriesRestored": 96,
  "resultsRestored": 1206, "recordsRestored": 112,
  "skipped": { "enrollments": 0, "finalEntries": 0, "results": 0 },
  "recordsRecomputed": 112, "eventsReformatted": 24, "restoredAt": "2026-10-04T17:02:11"
}
```

---

## Tests

### Unit tests

```bash
cd backend
mvn clean compile     # wipe and build main sources
mvn test              # run the tests against what was just built
```

630 tests covering the grade bands and their boundaries, the password rule, the
group sizes, sheet sizes and default units for every event type, the register
reader (headings, encodings, date spellings, BOM, quoted fields, bad rows), the
sample generator's invariants, the marking-sheet PDFs — page size, page count, the
five columns and glyph accuracy — the heat/final draw (track ranked fastest first,
field longest first, ties broken by student id, the top 8 only, withdrawn athletes
excluded, and re-drawing clearing the old final), the points rule, the school
records (both directions, per grade, what a record remembers, a record whose
results have gone, and the hand-entered baseline standing on its own, being beaten
by a result and surviving a rebuild), the championships (final-decides, relay
points to the house only, and a custom scale), the school years (unique years,
copying a programme, activating one year closing the others, refusing to delete a
year with events, and adopting events from before years existed) and locking
(locking an account with the student, unlocking, keeping the history, and filtering
the register), and the school's name and title heading a marking sheet without
pushing it onto a second page, every event type's unit (`s` for track, `M` for
every field event), and the best of three field attempts — including a missed
attempt being ignored, the first attempt winning, and the placings, results and
records all reading the best — plus the old spelled-out unit being normalised, so a
stored mark cannot drift back to words, which events may be split into heats and a
final (the default, the four splittable types, and a final needing both conditions),
and grade eligibility (the starting rules, a missing rule meaning allowed, a cell
being closed and reopened, and the grid counting each grade's events).

The relay work is covered on its own: the teams derived from the roster and the teams
made **by hand** out of chosen students (that a derive never matches, renames,
re-keys or prunes one), the applicant list beside them, the mark grid's one row per
team and the marking sheet's one line per team, the rename rule and a teacher's scope
over it — and, for a relay event built around its teams, that a **draft** is excluded
from the programme, the dates and the past-events list while a real event is not;
that a draft's teams and legs move onto the target event and the draft is left empty;
that a name already used in the target refuses the move and **nothing moves**; that a
move onto a non-relay, or a runner outside the target's grade, is refused; and that a
draft's teams cannot be destroyed as a side effect of deleting the draft.

> Run those as two commands. A single `mvn clean test` can fail with
> `package com.sportday.entity does not exist` on Windows even though the classes
> are there — see [Troubleshooting](#confusing-cannot-find-symbol-or-noclassdeffounderror-for-our-own-classes).

`PdfSheetServiceTest` also renders the A5 and A4 sheets to
`backend/target/pdf-preview/*.png` so the layout can be inspected.

### End-to-end smoke test

With the backend running:

```bash
python backend/scripts/smoke_test.py
```

There is a second script that fills a whole school day and checks every step of it —
the one to run after a rebuild, or after wiping the data:

```bash
python backend/scripts/full_retest.py
```

It uploads the 600-strong register if the system is empty, enters athletes in **every
event** in the programme, allocates every event's groups, records a mark for every
athlete entered (three attempts for a field event, minutes and seconds for a race over
400M), draws the finals the sprints have earned, records those, and then reads every
event's results, placings and PDF back. It respects the rules the application
enforces — division, grade eligibility and the entry quota — so a failure means the
rules and the data disagree, not that the script took a shortcut. Unlike the smoke test
it leaves the data in place, so the school ends up with a populated system. Last run:
112 events, 1005 entries, 1005 marks, 24 finals, 0 failures.

453 checks over real HTTP: admin login, season reset, the event catalogue and its
group/sheet sizes, the event filters, the 600-student import, student login with
the derived password, the 2-track/1-field quota including the refusals, heat
allocation at 8 and 24 per group, CSV **and** XLSX register upload with row-level
error reporting, deleting a student, the marking-sheet PDFs (page size, page
count, embedded CJK font, not public), the mark-entry grid (heat and grade
filtering, bulk save, clearing, and the refusal of an athlete who is not entered,
a negative mark and a duplicated athlete), the whole heat → final flow (marks
recorded for all 41 heat runners, the top 8 previewed and drawn, the final seeded
into lanes 1–8 while the heats keep every runner, heat and final marks staying
independent, a non-qualifier refused a final mark, the final's own A5 sheet,
re-drawing, and removing the final), self-registration being gone, an admin
creating and removing a staff account, the editable entry limits and points, a
record being built, beaten and remembering what it beat, the placings and both
championship tables (including the final taking over the scoring once it is run),
the past-event list, an administrator entering a student in events and having the
quota refuse the one that would exceed it, reviving an entry the student had
withdrawn from, the same quota agreeing with what the student sees, a record typed
in by hand and later beaten by a result, filtering the programme by date and moving
an event to another date, setting the school's details, adding a school year and
copying a programme into it, opening a past year closing the others, refusing
entry once entries are closed while an admin can still add a late one, rehearsing a
full-roster upload and seeing exactly who would be locked, applying it, a locked
student being refused sign-in and entry, unlocking, restoring a returning student
by re-uploading them, every field event reporting `M` and every track event `s`,
three attempts saved and the best taken as the result (with a miss ignored, the
first attempt winning, the placings and results agreeing, and clearing removing
the whole set), the direct-to-final default (only the four sprints splittable, a new
event direct, a final refused until the box is unticked, an existing split left
alone), one event per grade (the 112-event catalogue with A=40/B=38/C=34, no C-grade
1500M, 5000M or 110M hurdles and a C-grade 100M hurdles instead, a C-grade student
refused an A-grade event by both their own endpoint and the admin-on-behalf one, and
the 400s for a create with no grade or a grade the type does not run), the
small-field rule (8 or fewer switches to direct and says it was automatic, an admin's
untick, and the rule re-applying after the next entry change), and a long race timed
as 2 minutes 15 seconds (stored as 135 seconds, read back as 2:15, and 1 minute 75
seconds refused without disturbing the time already saved), and the whole-school
print run. It resets the season first and cleans up after itself, so it can be run
repeatedly. Artifacts go to `artifacts/`.

The register checks pin the grade reference date (`GRADE_REFERENCE_DATE`), because a
grade comes from a date of birth **as at a date**: the shipped 600-strong register
splits 240/198/162 as at 2026-10-03 but 239/199/162 one day later, when a single
student turns fifteen and moves from the C grade to B. Without the pin the check
would fail on a birthday rather than on a change.

---

## Migrating an existing database

`spring.jpa.hibernate.ddl-auto=update` creates the new tables and columns
(`students`, `event_groups`, `events.category/sex/group_size`,
`enrollments.event_group_id/lane`, `sport_day_settings`, `event_records`)
automatically. It cannot widen an existing
MySQL `ENUM` or relax a `NOT NULL`, so a database created by the previous version
needs one script first:

```bash
docker exec -i sportday-mysql mysql -usportday -psportday123 -D sportday \
  < backend/db/migration/revamp-migration.sql
```

It adds the `STUDENT` role, allows student accounts to have no email address, and
adds `RUN_60M` to the event types. Events carried over from the old schema are
backfilled with a category, division and group size on the next start; because the
old schema had no divisions, they are assigned to the **boys'** division and should
be re-assigned from the admin event page.

The heat/final split needs a second script:

```bash
docker exec -i sportday-mysql mysql -usportday -psportday123 -D sportday \
  < backend/db/migration/final-stage-migration.sql
```

It adds the `stage` columns to `event_groups` and `event_results`, claims every
existing row as a heat, and replaces the unique key on `event_results` with
`(user_id, event_id, stage)` so an athlete can hold both a heat and a final mark.
The order matters there: the old key backs the `user_id` foreign key, so the new
key is created **before** the old one is dropped, or MySQL refuses to drop it.
The script is idempotent, and the application also backfills any null `stage` on
startup, so a database that misses the script still shows its heat marks.

Settings and school records need a third:

```bash
docker exec -i sportday-mysql mysql -usportday -psportday123 -D sportday \
  < backend/db/migration/records-settings-migration.sql
```

It creates `sport_day_settings` and `event_records`. It is only needed where
`ddl-auto` is not `update`, or to apply the schema ahead of a deploy; the settings
row itself is seeded with the defaults on first read, and the records are rebuilt
from the results with `POST /api/admin/records/recompute`.

Records that every event has by default need a fourth:

```bash
docker exec -i sportday-mysql mysql -usportday -psportday123 -D sportday \
  < backend/db/migration/record-baseline-migration.sql
```

It makes `event_records.mark` nullable — a record row now exists before anybody has
competed — and adds `manual_mark`, `manual_unit`, `manual_holder_name` and
`manual_achieved_on` for the mark an administrator types in, plus `holder_name`.
Hibernate adds the new columns itself but cannot relax the `NOT NULL`, so this one
is needed even where `ddl-auto` is `update`. It is idempotent.

School years need a fifth:

```bash
docker exec -i sportday-mysql mysql -usportday -psportday123 -D sportday \
  < backend/db/migration/seasons-migration.sql
```

It creates `seasons`, adds `events.season_id`, and adds the school's own details
(`school_name`, `school_name_zh`, `address`, `principal`, `sport_day_title`) to
`sport_day_settings`. Events that predate years are adopted into the year of their
own date on the next start, so the year picker is never empty.

Field attempts need a sixth:

```bash
docker exec -i sportday-mysql mysql -usportday -psportday123 -D sportday \
  < backend/db/migration/field-attempts-migration.sql
```

It adds `attempt_1`, `attempt_2` and `attempt_3` to `event_results` for a field
athlete's three attempts, and rewrites the stored units to the short `M` and `s`.
`mark` still holds the best attempt, so nothing downstream changes. The
application also rewrites the units on start, so a database that misses the script
still reads correctly — it only needs this one for the attempt columns.

The direct-to-final flag needs a seventh:

```bash
docker exec -i sportday-mysql mysql -usportday -psportday123 -D sportday \
  < backend/db/migration/direct-to-final-migration.sql
```

It adds `events.direct_to_final` and preserves what each event is already doing: a
60/100/200/400 that has heats drawn keeps them and its final, everything else runs
straight to a final. The column is deliberately added **without** a default first —
`ADD COLUMN ... DEFAULT b'1'` fills existing rows immediately, so a backfill keyed
on `IS NULL` would never match and every split sprint would silently become direct.

Grade eligibility needs an eighth:

```bash
docker exec -i sportday-mysql mysql -usportday -psportday123 -D sportday \
  < backend/db/migration/grade-eligibility-migration.sql
```

It creates `event_grade_rules` — one row per event type and grade — and opens
everything to every grade except the long distances. The application seeds the same
rules on start, so this script is only needed where `ddl-auto` is validate, or to
apply the schema ahead of a deploy. Note the column names differ either side of the
join: an event's type is `events.type` but `event_grade_rules.event_type`.

The small-field rule needs a ninth, which only adds a column:

```bash
docker exec -i sportday-mysql mysql -usportday -psportday123 -D sportday \
  < backend/db/migration/small-field-final-migration.sql
```

`events.direct_to_final_auto` records that the system switched an event to direct to
final because the field is no bigger than a final would be. It needs no backfill:
NULL is exactly right for "the school's own setting".

The 100M hurdles needs a tenth, and is the only one that rewrites a column rather
than adding one:

```bash
docker exec -i sportday-mysql mysql -usportday -psportday123 -D sportday \
  < backend/db/migration/hurdles-100m-migration.sql
```

`events.type` is a MySQL ENUM, so the new value has to be added to the column before
anything can be stored as a 100M hurdles — the insert fails with *"Data truncated for
column 'type'"* otherwise. The script rewrites the ENUM in full rather than appending
to it, which is safe because the stored values are names, not positions. It then adds
the two events and the grade rules that decide who may enter them, since the
application only creates a catalogue when it does not already have one.

A brand new database needs none of this.

---

## Troubleshooting

### `Lookup method resolution failed` / `NoClassDefFoundError: org/apache/poi/...`

```
Error creating bean with name 'studentService' ... constructor parameter 7:
Error creating bean with name 'studentImportParser': Lookup method resolution failed
Caused by: java.lang.NoClassDefFoundError: org/apache/poi/ss/usermodel/Cell
```

**Apache POI is not on your run classpath.** POI is only used for the optional
`.xlsx` import — CSV, the core format, needs nothing extra — so this is almost
always a stale build rather than a code problem:

- **Running from an IDE:** re-import the Maven project (IntelliJ: *Reload All
  Maven Projects*; VS Code: *Java: Clean Java Language Server Workspace*). The
  IDE caches the module classpath, so a dependency added to `pom.xml` is invisible
  to it until you re-import.
- **Running from the command line:** `mvn clean package`, then run the jar, or
  use `mvn spring-boot:run`.
- **Check:** `mvn dependency:tree | findstr poi` should list `org.apache.poi:poi-ooxml`.

This failure mode can no longer stop the application: POI is confined to one
non-bean class (`StudentXlsxReader`) that is reached by name, so if POI really is
missing the service still starts, CSV import still works, and an `.xlsx` upload
returns a 400 explaining exactly what to install. `StudentImportParserTest` fails
the build if a POI type ever creeps back into the parser's API.

### Confusing `cannot find symbol` or `NoClassDefFoundError` for our own classes

Two different things produce this, and both are build-environment problems rather
than code problems.

**1. A running JVM holds `target/`.** Windows will not let `mvn clean` delete a
directory a live process is using, and an interrupted incremental compile can
leave a half-populated `target/classes`. Stop the backend first, and use the
two-step recipe — it is the reliable one on Windows:

```bash
mvn clean compile     # wipe and compile main sources
mvn test              # run the tests against what was just built
mvn package -DskipTests
```

`useIncrementalCompilation` is turned off in `pom.xml` to reduce the window for
this, but a bare `mvn clean test` in a single invocation can still fail with
`package com.sportday.entity does not exist` even though `target/classes/...class`
is plainly there.

**2. `clean` and `test`/`package` in the same invocation.** Occasionally the
newly recreated `target/classes` is not visible to the test compile that follows
inside the same build, giving exactly those "package does not exist" errors. Run
the phases as separate commands, as above.

**Always check the artifact before running it** — a package step that silently
produced nothing is the failure behind the placeholder error in the next section:

```bash
jar tf backend/target/sportday-backend-1.0.0.jar | grep BOOT-INF/classes/application.yml
```

### Starting the JVM immediately after a Maven build

Maven rewrites `target/classes/application.yml` during `process-resources`, and the
Spring Boot jar plugin then packages it. If that write lands late you get a jar with
no configuration in it, and starting it fails with
`Could not resolve placeholder 'app.jwt.secret'`. Verify the jar contains
`BOOT-INF/classes/application.yml` (command above) and rebuild if it does not.

---

## Project layout

```
sportday/
├── backend/                     Spring Boot 4.1 (Java 25)
│   ├── src/main/java/com/sportday/
│   │   ├── controller/          REST controllers
│   │   ├── service/             business logic, incl. grading, grouping, PDF
│   │   ├── entity/              JPA entities and enums
│   │   ├── repository/          Spring Data repositories
│   │   ├── dto/                 request/response shapes
│   │   ├── security/            JWT authentication
│   │   └── config/              security, OpenAPI, first-run bootstrap
│   ├── src/test/java/           102 unit tests
│   ├── scripts/smoke_test.py    end-to-end HTTP test
│   └── db/migration/            SQL for databases from the previous version
├── frontend/                    Next.js 16 web app
├── sportday-sample-data/        600-student sample register (CSV + XLSX)
├── mobile/                      Android app (not part of this revamp — see below)
├── artifacts/                   output of the smoke test
└── docker-compose.yml           MySQL 8
```

---

## Roles

| Role | Can do |
|------|--------|
| `STUDENT` | Sign in with a student id, enter events, see own entries and quota |
| `USER` | Legacy staff account |
| `MANAGER` | Everything an admin can do for events and heats, mark entry, and printing marking sheets |
| `ADMIN` | Everything, including the student register and deleting events |

---

## Not in this revamp

The Android app in `mobile/` still targets the old API shape — it predates the
student register, the track/field split and the quota rules, so it will need a
follow-up pass. The web app and the backend are complete.
