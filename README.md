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
| 3 | A student may enter **2 track (徑項)** and **1 field (田項)** event | `EnrollmentService`, `EventCategory.getMaxEntriesPerStudent()` |
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
| 22 | **Which grades may enter which events**, assigned on a page — by default C does not run the 1500M/5000M, nor B the 5000M | `EventGradeRule`, `GradeEligibilityService` |
| 23 | A sprint with **8 or fewer entered** switches itself to direct to final — a final would be the same athletes as the heat | `FinalQualificationService.syncFinalFormat` |
| 24 | A race **longer than 400M** is timed in **minutes and seconds**; the mark is still stored in seconds | `EventType.usesMinutesAndSeconds` |
| 25 | Mark entry lists only events with **more than one athlete** entered | `app/admin/marks` |

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
record, remark — with record and remark left blank.

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
| **Category** | `TRACK` (徑項) or `FIELD` (田項). Fixed by the event type |
| **Sex division** | `MALE` or `FEMALE` — the event is run once per division |
| **Group size** | Athletes per heat. **8** for 60/100/200/400, **24** for 800 and above and for every field event. Overridable per event |
| **Sheet size** | Follows the group size: **A5** for 60/100/200/400, **A4** otherwise |
| **Enabled** | New events are created enabled. Disabling closes the event to new entries but keeps existing entries, heats and results |

### Entry rules

A student may hold at most **2 track** entries and **1 field** entry. An entry is
refused when:

- the event is disabled — *"This event is closed — it has been disabled by the organiser."*
- the student is already entered
- the event is in the other sex division
- the track or field quota is used up — *"You have already entered 2 徑項 Track event(s), which is the maximum (徑項: 2)."*
- the event is full

The web app shows the remaining quota (`徑項 1/2 · 田項 0/1`) and surfaces the
server's message whenever an entry is refused.

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

### Which grades may enter which events

Not every event is for everybody. Out of the box:

| Grade | May enter |
|-------|-----------|
| A | everything — 38 events |
| B | everything except the 5000M — 36 |
| C | one fewer again: no 1500M, no 5000M — 34 |

The sprints, the 800M, the hurdles, the relays and every field event are open to all
three grades. Only the long distances are restricted.

```
GET  /api/grade-events                # the event-by-grade grid, with each grade's count
PUT  /api/admin/grade-events          # [{eventType, grade, allowed}, …] — the cells that changed
POST /api/admin/grade-events/reset    # back to the school's defaults
```

The rules are stored per **event type**, not per event: Boys 1500M and Girls 1500M
are the same race in two divisions, so the school sets the rule once. A **missing
rule means allowed**, so an event type added later is open to every grade until
somebody says otherwise — and an event whose only rule is a refusal still reports all
the *other* grades as allowed, rather than locking the race for everyone.

The page shows how many events each grade ends up with, because that is the number a
school checks against. Each event also carries `allowedGrades`, so the entry list can
leave out what a student's grade cannot enter.

Entry is refused server-side for a grade that is not allowed — including when an
administrator enters a student on their behalf, so the rule cannot be worked around
by doing it for them:

> `C Grade does not enter Boys 1500M. The events open to that grade are on the entry list — the organiser sets this on the grade assignment page.`

Changing a rule affects **who may enter from then on**. It does not remove entries
already made, which is deliberate: a withdrawal is a decision for the school, not a
side effect of editing a grid.

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

Entries rising again bring the final back, but only if the system was the one that
took it away. An event the school chose to run straight to a final stays that way
however large the field becomes.

The rule is deliberately one-sided: a school that untickes the box with six entered
is overruled on the next entry change, because the final really would be pointless.
What the flag protects is the *other* direction.

### Mark entry lists what is worth marking

The event picker on the mark-entry page shows only events with **more than one
athlete entered**. An event with nobody, or with one, is not worth a sheet — in the
live register that is 36 of 38 events — so they are left out and the page says how
many were hidden.

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

### Marking sheets

One sheet per heat or final, with **student id / name / grade / record / remark**.
The record and remark columns are left blank for the helper. The sheet is padded
out to the group size, so a late entry still has a line, and a final's sheet is
headed `組別 Group: Final … 決賽 Final` so it cannot be mistaken for another heat.

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
| GET | `/api/groups/{groupId}/sheet.pdf` | Admin/Manager | One marking sheet (A5 or A4), heats and final alike |
| GET | `/api/events/{eventId}/sheets.pdf` | Admin/Manager | Every group of an event, one per page |
| GET | `/api/sheets.pdf?sex=&category=` | Admin/Manager | Whole-school print run |

### Mark entry

The mark-entry grid reads every athlete competing in a stage of an event — with their group,
lane and any mark already recorded — filtered by group and/or grade, and saves the whole grid
back in one request. `stage` selects `HEAT` (the default) or `FINAL`, and the two keep
separate marks.

| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/events/{eventId}/marks?stage=&groupId=&grade=` | Admin/Manager | Grid rows plus the group and grade filter options |
| POST | `/api/events/{eventId}/marks` | Admin/Manager | Save the grid in one request |

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
| GET | `/api/grade-events` | Authenticated | The event-by-grade grid, with each grade's event count |
| PUT | `/api/admin/grade-events` | Admin | Assign which grades may enter which events |
| POST | `/api/admin/grade-events/reset` | Admin | Back to the school's starting rules |
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
| POST | `/api/admin/season/reset` | Clear entries, heats, the final and results; keeps students, events and hand-entered records |
| POST | `/api/admin/managers` | Create a manager account |
| GET | `/api/users`, `PATCH /api/users/{id}/enable`, `DELETE /api/users/{id}` | Staff account management |

---

## Tests

### Unit tests

```bash
cd backend
mvn clean compile     # wipe and build main sources
mvn test              # run the tests against what was just built
```

272 tests covering the grade bands and their boundaries, the password rule, the
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

389 checks over real HTTP: admin login, season reset, the event catalogue and its
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
alone), grade eligibility (the C grade refused the 1500M and 5000M, the B grade the
5000M, each grade's event count, an administrator bound by the same rule, reopening
a cell letting the entry through, and the reset restoring the defaults), the
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
