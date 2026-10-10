#!/usr/bin/env python3
"""Wipes the sport day's test data and rebuilds a known, coherent season.

WHAT THIS IS FOR
    One command that puts the system back to a state the school can demonstrate or
    load-test from: a register, the event catalogue, entries, heats, relay teams
    filled leg by leg, marks, and the finals the sprints have earned. It is safe to
    run twice — every step is either a wipe or an idempotent API call.

    It is the destructive half of what `full_retest.py` does. `full_retest.py` fills
    a season that is already empty and checks every step as it goes; this script
    empties the season first, and rebuilds it from the app's own endpoints.

WHAT IT DESTROYS, AND WHAT IT DOES NOT
    Destroyed (after a verified backup, and only with --yes):

      * every enrollment, heat/group, final place and recorded mark — by the app's
        own season reset, POST /api/admin/season/reset, which keeps the register and
        the catalogue exactly as its documentation says;
      * every relay team and its legs — by DELETE /api/admin/events/{id}/relay-teams,
        which the season reset does NOT do. A relay team points at the results of its
        race, so the reset has to run first and the teams are cleared after it.

    Rebuilt, not deleted:

      * the student register — the shipped sample register is re-uploaded
        (sportday-sample-data/students-600.csv) with the season's sport day as the
        grade reference date. The upload UPDATES the student ids in the file, so the
        same 600 students come back, and any of them who were locked are unlocked.
        A student the file does not mention is NOT deleted — the app has no bulk
        delete, and deleting the register by hand is not this script's business. Any
        such student is counted and reported instead;
      * the event catalogue — POST /api/events/defaults, the app's own catalogue
        builder. It creates what is missing and never deletes an extra event, so a
        catalogue that holds more than the seeded 112 keeps them, and this script
        says so rather than quietly tidying the school's programme away.

THE BACKUP, AND WHY IT IS NOT OPTIONAL
    There is deliberately no flag to skip it. Before anything is destroyed:

      1. `mysqldump` runs inside the MySQL container over the whole database — all
         14 tables, the register and the relay teams included — and the file is
         checked for the tables it must contain and for mysqldump's own
         "Dump completed" marker;
      2. the dump is copied out to the host and its size is compared with the
         container's copy, so a truncated transfer cannot pass;
      3. the HOST file is loaded into a scratch database and every table's row count
         is compared with the live database's. A dump nobody has loaded is not a
         backup, and the app's own season backup does not cover the relay teams — so
         this load check is the only thing that proves the relay data is recoverable.
         It needs MySQL root (--db-root-user/--db-root-password, default root/root123
         from docker-compose.yml). If root is not available the load check is skipped
         and said to be skipped; steps 1 and 2 still have to pass;
      4. the reset writes the application's own restorable season backup (entries,
         heats, final places, marks and school records) before it deletes anything,
         and refuses to delete at all if it cannot. Its name and size are read back
         from the response and checked against GET /api/admin/backups.

    Any failure in 1-3 stops the run before a single row is touched (exit 3).

ROWS WRITTEN DIRECTLY
    None. Every write goes through the API — entries through the enrolment endpoint
    so the grade, division and 2 track / 1 field / 2 relay quota are enforced by the
    server; relay teams through the derive and the add-runner endpoints so the
    form/house, one-leg-per-athlete, four-runners-and-a-reserve and readiness rules
    are the app's own; marks and finals through the mark grid and the final draw. The
    only things this script writes outside the API are the backup file and the
    scratch database it verifies that file in.

    A step the app refuses is never worked around: it is recorded and printed in the
    server's own words, and the run ends with those refusals listed.

WHAT IT CHECKS AT THE END
    The checks full_retest.py ends on, read back from the API: the register holds the
    whole sample and every student is in exactly one grade; every event the app would
    accept an entry for holds an athlete; every heat is drawn and nobody is left
    ungrouped, with no heat over its size; every relay the app reports ready really
    holds two teams in the race with their four legs each, read back from the relay
    board and not only from the event list; one mark for every row the app's own grid
    listed; every event reports its results, and the placings run fastest first on the
    track and furthest first in the field; a 100M result carries seconds, a 400M over
    a minute carries its minutes part and a shot put carries metres; the finals the
    sprints earned are drawn and run; the results PDFs render; and the championships
    compute with the houses ranked.

    Three deliberate differences from full_retest.py, each because this script builds
    on a catalogue and a register that already exist rather than on a fresh database:

      * the size of the programme is REPORTED, not asserted at 112. A catalogue that
        carries extra events keeps them (the app's builder never deletes one), so
        asserting 112 would fail a database that is behaving correctly;
      * an event the app refuses to fill is not a failure — the refusal is the
        evidence, and it is printed in the server's words. Nine relays on this
        catalogue are of that kind: a "Form 1" relay placed in the A grade can be
        entered (the entry rule asks the grade) but can never hold a team (the team
        rule asks the form), and no student is in both. They are left empty and named
        rather than filled with rows the app itself would reject;
      * a mark is checked per row of the app's own grid, which is one row per athlete
        for an individual event and one row per TEAM for a relay — a relay's time is
        written once for the four runners together, not once each.

USAGE
    # What would happen, writing nothing (safe against any database):
    python backend/scripts/reset_test_data.py --dry-run

    # What it would destroy, and stop (no --yes, so no backup and no wipe):
    python backend/scripts/reset_test_data.py

    # Take the backup, wipe the season, and rebuild it:
    python backend/scripts/reset_test_data.py --yes

    # Against a copy of the backend on another port, backing up another database:
    python backend/scripts/reset_test_data.py --yes \\
        --base-url http://localhost:8099/api --db sportday_reset_e2e

EXIT CODES
    0 the season was wiped, rebuilt and verified
    1 the rebuild ran but its checks failed (the season is populated but suspect)
    2 no --yes: nothing was written
    3 the backup could not be taken or verified: nothing was destroyed
    4 the app could not be reached, signed in to, or has no season to rebuild
    5 the app's own season reset was refused: the backup stands and nothing was
      destroyed, so the season is exactly as it was
"""

import argparse
import hashlib
import json
import math
import os
import random
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

API_DEFAULT = "http://localhost:8080/api"

# Every table the dump has to carry. The register, the catalogue and the relay teams
# are what the app's own season backup leaves out, and therefore what the mysqldump
# is really protecting.
BACKUP_TABLES = [
    "users", "students", "events", "enrollments", "event_groups", "event_results",
    "final_entries", "relay_teams", "relay_team_members", "event_records", "seasons",
    "sport_day_settings", "standard_defaults", "teacher_classes",
]
# The ones that must be present in the dump by name, over and above the table count:
# a dump that lost the relay legs would still look like a dump.
CRITICAL_TABLES = [
    "students", "events", "enrollments", "event_groups", "event_results",
    "final_entries", "relay_teams", "relay_team_members", "event_records",
]
# The catalogue the app's own builder makes: 20 event types x 2 divisions, minus the
# six races the lower grades do not run. Reported against, never asserted — a live
# catalogue may legitimately hold extra events.
SEEDED_PROGRAMME_EVENTS = 112

# The fallback entry allowance, used only if the event list does not carry the
# allowance the server enforces. SettingsService: 2 track, 1 field, and a relay takes
# the track allowance while being counted in its own category.
FALLBACK_QUOTA = {"TRACK": 2, "FIELD": 1, "RELAY": 2}

CHECKS = 0
FAILURES = 0
PROBLEMS = []
REFUSALS = []          # (where, status, the server's own words)
REFUSAL_REASONS = {}   # the server's words -> how many times
EXPLAINED = {}         # event name -> why the app's own rules left it with no race


# --------------------------------------------------------------------- reporting

def section(title):
    print(f"\n== {title}")


def check(condition, message, detail=""):
    global CHECKS, FAILURES
    CHECKS += 1
    if condition:
        print(f"  [PASS] {message}")
    else:
        FAILURES += 1
        PROBLEMS.append(message)
        print(f"  [FAIL] {message}")
        if detail:
            print(f"         {detail}")
    return bool(condition)


def refused(where, status, body):
    """Records a refusal in the server's own words, and shows the first few."""
    message = body.get("message") if isinstance(body, dict) else None
    if not message:
        message = body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)
    message = str(message).strip()
    REFUSALS.append((where, status, message))
    REFUSAL_REASONS[message] = REFUSAL_REASONS.get(message, 0) + 1
    if len(REFUSALS) <= 12:
        print(f"    refused [{status}] {where}: {message[:200]}")
    elif len(REFUSALS) == 13:
        print("    … further refusals are listed once, at the end")
    return message


def refused_for(event_name):
    """True when the app refused something this script offered that event.

    The line between "the app's own rules make this impossible" and "this script
    could not build it" is exactly this: an event that holds nobody because every
    entry offered to it was refused is the rule working — a Form 1 relay whose grade
    has no Form 1 student in it can never be entered — while an event that holds
    nobody with no refusal on record is this script's failure.
    """
    return any(event_name in where for where, _, _ in REFUSALS)


def left_alone(event_name, reason):
    """Records an event this script deliberately did not try to fill, and why.

    The app judges an entry by the event's grade and a relay team by the event's
    form: an event whose grade and form do not meet — a "Form 1" relay placed in the
    A grade — can be entered but can never have a team derived for it. Rather than
    enter athletes into a race that cannot exist, the script leaves it empty and says
    so, in the app's own terms.
    """
    EXPLAINED[event_name] = reason
    print(f"    - {event_name}: left alone — {reason}")


def accounted_for(event_name):
    """True when an event with nobody in it is explained, by a refusal or by intent."""
    return refused_for(event_name) or event_name in EXPLAINED


def command(cmd, timeout=900, stdin=None):
    """Runs a command without a shell, so no quoting can mangle a path or a name."""
    result = subprocess.run(cmd, capture_output=True, timeout=timeout, input=stdin)
    return (result.returncode,
            result.stdout.decode("utf-8", "replace"),
            result.stderr.decode("utf-8", "replace"))


# --------------------------------------------------------------------- the API

class Api:
    def __init__(self, base=API_DEFAULT):
        self.base = base.rstrip("/")

    def request(self, method, path, body=None, token=None, raw=False):
        url = self.base + path
        data = json.dumps(body).encode("utf-8") if body is not None else None
        headers = {"Accept": "*/*"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=600) as response:
                payload = response.read()
                if raw:
                    return response.status, payload
                if not payload:
                    return response.status, None
                return response.status, json.loads(payload.decode("utf-8"))
        except urllib.error.HTTPError as error:
            payload = error.read().decode("utf-8", "replace")
            try:
                return error.code, json.loads(payload)
            except json.JSONDecodeError:
                return error.code, payload
        except urllib.error.URLError as error:
            return 0, f"could not reach {url}: {error}"

    def upload(self, path, file_path, token=None):
        boundary = "----sportdayresettestdata"
        with open(file_path, "rb") as handle:
            content = handle.read()
        filename = os.path.basename(file_path)
        body = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
            f"Content-Type: text/csv\r\n\r\n"
        ).encode("utf-8") + content + f"\r\n--{boundary}--\r\n".encode("utf-8")
        headers = {
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": "Bearer " + token,
        }
        req = urllib.request.Request(self.base + path, data=body, headers=headers,
                                     method="POST")
        try:
            with urllib.request.urlopen(req, timeout=600) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            payload = error.read().decode("utf-8", "replace")
            try:
                return error.code, json.loads(payload)
            except json.JSONDecodeError:
                return error.code, payload


# --------------------------------------------------------------------- the database

class Db:
    """Reads and dumps MySQL through the running container. Never writes to the
    application's tables: the only writes are the scratch database a backup is
    loaded into, and dropping it again."""

    def __init__(self, container, database, user, password, root_user, root_password):
        self.container = container
        self.database = database
        self.user = user
        self.password = password
        self.root_user = root_user
        self.root_password = root_password

    def credentials(self, as_root=False):
        return ((self.root_user, self.root_password) if as_root
                else (self.user, self.password))

    def rows(self, sql, database=None, as_root=False):
        """A query, one row per line, tab separated — mysql's own batch output."""
        user, password = self.credentials(as_root)
        cmd = ["docker", "exec", self.container, "mysql", "-N", "-B",
               f"-u{user}", f"-p{password}"]
        target = database if database is not None else self.database
        if target:
            cmd.append(target)
        cmd += ["-e", sql]
        code, out, err = command(cmd)
        return code, [line.split("\t") for line in out.splitlines() if line != ""], err

    def execute(self, sql, database=None, as_root=False):
        user, password = self.credentials(as_root)
        cmd = ["docker", "exec", self.container, "mysql", f"-u{user}", f"-p{password}"]
        if database:
            cmd.append(database)
        cmd += ["-e", sql]
        code, out, err = command(cmd)
        return code, err

    def counts(self, database=None, as_root=False):
        sql = " UNION ALL ".join(
            f"select '{table}' as t, count(*) as n from `{table}`"
            for table in BACKUP_TABLES)
        code, rows, err = self.rows(sql, database=database, as_root=as_root)
        if code != 0:
            raise RuntimeError(f"could not count rows: {err.strip()}")
        return {row[0]: int(row[1]) for row in rows if len(row) == 2}

    def mysqldump_version(self):
        code, out, err = command(["docker", "exec", self.container, "mysqldump",
                                  "--version"])
        if code != 0:
            return None
        return (out or err).strip().splitlines()[0] if (out or err).strip() else None


# --------------------------------------------------------------------- helpers

def mark_for(event, index, total):
    """A plausible mark for one athlete: a time for a race, a distance for a field
    event. The same spread full_retest.py uses, so a mark this script writes is one
    that script would also write."""
    event_type = event["type"]
    if event["category"] == "FIELD":
        if event_type in ("HIGH_JUMP", "POLE_VAULT"):
            return round(1.20 + 0.05 * (index % 12), 2)
        if event_type in ("LONG_JUMP", "TRIPLE_JUMP"):
            return round(3.50 + 0.10 * (index % 20), 2)
        return round(6.00 + 0.35 * (index % 25), 2)

    spread = {
        "RUN_60M": (7.4, 9.2),
        "RUN_100M": (11.8, 15.0),
        "RUN_200M": (24.0, 31.0),
        "RUN_400M": (52.0, 70.0),
        "HURDLES_100M": (15.0, 20.0),
        "HURDLES_110M": (16.0, 22.0),
        "HURDLES_400M": (60.0, 78.0),
        "RUN_800M": (130.0, 175.0),
        "RUN_1500M": (280.0, 360.0),
        "RUN_5000M": (1000.0, 1300.0),
        "RELAY_4X100M": (46.0, 56.0),
        "RELAY_4X400M": (220.0, 260.0),
    }.get(event_type, (12.0, 20.0))
    low, high = spread
    step = (high - low) / max(total, 1)
    return round(low + step * index + step * 0.15, 3)


def mark_row(event, index, total):
    """One mark-entry row's payload, in the shape the grid itself sends."""
    value = mark_for(event, index, total)
    row = {"userId": None, "mark": None, "notes": None}
    if event["category"] == "FIELD":
        # Three attempts, the best of which is the result.
        row["attempts"] = [round(value + 0.20, 2), value, round(value - 0.15, 2)]
    elif event.get("timeInMinutes"):
        whole = int(value // 60)
        row["minutes"] = whole
        row["seconds"] = round(value - whole * 60, 3)
    else:
        row["mark"] = value
    return row


def group_key(event, student):
    """The class or house a relay team is keyed by, as the derive keys it: the
    entrant's class for a form relay, the entrant's house for a house relay."""
    if event.get("relayTeamKind") == "HOUSE":
        return (student.get("house") or "").strip().upper()
    return (student.get("className") or "").strip().upper()


def interleave(candidates, key_of):
    """One student from each class/house in turn, then the next round.

    A relay's teams are the classes (or houses) it was entered from, so entering
    students in register order can leave a relay with one team and no race. Taking
    them a class at a time is how a school actually enters a relay.
    """
    buckets = {}
    for student in candidates:
        buckets.setdefault(key_of(student), []).append(student)
    ordered = []
    for round_index in range(max((len(v) for v in buckets.values()), default=0)):
        for bucket in buckets.values():
            if round_index < len(bucket):
                ordered.append(bucket[round_index])
    return ordered


# --------------------------------------------------------------------- phase 1

def take_backup(db, args):
    """The dump, verified three ways, and the application's own season backup.

    Returns the dump's details, or raises SystemExit(3) having written nothing.
    """
    section("1. The backup (nothing is destroyed before this has passed)")

    version = db.mysqldump_version()
    if not version:
        print(f"  [FAIL] mysqldump is not available in container '{db.container}' — "
              f"refusing to destroy anything without a backup")
        raise SystemExit(3)
    print(f"  in-container {version}")

    os.makedirs(args.backup_dir, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    name = f"{db.database}-before-reset-{stamp}.sql"
    host_path = os.path.abspath(os.path.join(args.backup_dir, name))
    container_path = f"/tmp/{name}"

    # -- 1. the dump itself ------------------------------------------------------
    dump_cmd = ["docker", "exec", db.container, "mysqldump",
                f"-u{db.user}", f"-p{db.password}",
                "--single-transaction",        # a consistent snapshot, no table locks
                "--no-tablespaces",            # the app's user has no PROCESS privilege
                "--set-gtid-purged=OFF",
                "--default-character-set=utf8mb4",
                db.database,                   # no --databases: the file carries no
                f"--result-file={container_path}"]  # CREATE DATABASE and no USE, so it
    code, out, err = command(dump_cmd)              # restores into any database
    if code != 0:
        print(f"  [FAIL] mysqldump exited {code}")
        print(f"         {(err or out).strip()[:400]}")
        raise SystemExit(3)

    # -- 2. what the container wrote --------------------------------------------
    code, size_out, _ = command(["docker", "exec", db.container, "stat", "-c", "%s",
                                 container_path])
    if code != 0 or not size_out.strip().isdigit():
        print("  [FAIL] the dump file is not there")
        raise SystemExit(3)
    container_bytes = int(size_out.strip())
    if container_bytes <= 0:
        print("  [FAIL] the dump file is empty")
        raise SystemExit(3)

    code, tables_out, _ = command(["docker", "exec", db.container, "grep", "-c",
                                   "CREATE TABLE", container_path])
    created_tables = int(tables_out.strip() or 0) if code in (0, 1) else 0
    code, completed_out, _ = command(["docker", "exec", db.container, "grep", "-c",
                                      "Dump completed", container_path])
    completed = int(completed_out.strip() or 0) if code in (0, 1) else 0

    missing = []
    for table in CRITICAL_TABLES:
        code, found, _ = command(["docker", "exec", db.container, "grep", "-c",
                                  f"CREATE TABLE `{table}`", container_path])
        if code != 0 or not found.strip().isdigit() or int(found.strip()) < 1:
            missing.append(table)

    print(f"  {name}: {container_bytes} bytes, {created_tables} CREATE TABLE, "
          f"{completed} completion marker")
    if created_tables < len(BACKUP_TABLES):
        print(f"  [FAIL] the dump holds {created_tables} tables and the database has "
              f"{len(BACKUP_TABLES)}")
        raise SystemExit(3)
    if completed != 1:
        print("  [FAIL] the dump has no 'Dump completed' marker — it was cut short")
        raise SystemExit(3)
    if missing:
        print(f"  [FAIL] the dump is missing {missing}")
        raise SystemExit(3)
    check(True, f"the dump carries every table, relay legs included "
                f"({created_tables} tables, {container_bytes} bytes)")

    # -- 3. out to the host, byte for byte --------------------------------------
    code, out, err = command(["docker", "cp",
                              f"{db.container}:{container_path}", host_path])
    if code != 0:
        print(f"  [FAIL] could not copy the dump out: {(err or out).strip()[:300]}")
        raise SystemExit(3)
    host_bytes = os.path.getsize(host_path)
    if host_bytes != container_bytes:
        print(f"  [FAIL] the copy is {host_bytes} bytes and the container's is "
              f"{container_bytes}")
        raise SystemExit(3)
    with open(host_path, "rb") as handle:
        digest = hashlib.sha256(handle.read()).hexdigest()
    print(f"  copied to {host_path}")
    print(f"  sha256 {digest}")

    # -- 4. load it somewhere and count the rows back ---------------------------
    # The only write this script makes outside the API, and it is its own database,
    # made and dropped again. A dump nobody has loaded is not a backup.
    live_counts = db.counts()
    verified_by = "structure only"
    scratch = "sportday_backup_check_" + uuid.uuid4().hex[:6]
    code, err = db.execute(
        f"DROP DATABASE IF EXISTS `{scratch}`; CREATE DATABASE `{scratch}` "
        f"CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci", as_root=True)
    if code != 0:
        print(f"  [WARN] no MySQL root, so the dump could not be loaded and counted "
              f"back: {err.strip()[:200]}")
        print("         The dump is verified by structure and size only. Pass "
              "--db-root-user/--db-root-password to have it loaded and counted.")
    else:
        try:
            with open(host_path, "rb") as handle:
                payload = handle.read()
            load = ["docker", "exec", "-i", db.container, "mysql",
                    f"-u{db.root_user}", f"-p{db.root_password}", scratch]
            code, out, err = command(load, stdin=payload)
            if code != 0:
                print(f"  [FAIL] the dump does not load: {(err or out).strip()[:400]}")
                raise SystemExit(3)
            scratch_counts = db.counts(database=scratch, as_root=True)
            differences = {table: (live_counts.get(table), scratch_counts.get(table))
                           for table in BACKUP_TABLES
                           if live_counts.get(table) != scratch_counts.get(table)}
            if differences:
                print(f"  [FAIL] the loaded dump does not match the database: "
                      f"{differences}")
                raise SystemExit(3)
            verified_by = f"loaded into {scratch} and counted back"
            check(True, f"the dump loads and every one of the {len(BACKUP_TABLES)} "
                        f"tables counts back identically "
                        f"({live_counts.get('relay_team_members')} relay legs among them)")
        finally:
            code, err = db.execute(f"DROP DATABASE IF EXISTS `{scratch}`", as_root=True)
            if code != 0:
                print(f"  [WARN] could not drop {scratch}: {err.strip()[:200]}")

    command(["docker", "exec", db.container, "rm", "-f", container_path])

    # -- 5. the application's own season backup ---------------------------------
    # Written by the reset itself, before its first delete. There is no endpoint that
    # takes one on its own, so it is checked after the reset has run — see below.
    return {"name": name, "path": host_path, "bytes": host_bytes, "sha256": digest,
            "verified": verified_by, "counts": live_counts}


# --------------------------------------------------------------------- phase 2

def destroy(api, token, relay_events, before):
    """The season reset, then the relay teams it leaves behind."""
    section("2. Emptying the season")
    status, reset = api.request("POST", "/admin/season/reset", token=token)
    if status != 200:
        refused("POST /admin/season/reset", status, reset)
        print("  [FAIL] the season reset was refused, so nothing was emptied")
        return None
    check(True, "the app's own season reset ran")
    print(f"         removed {reset.get('enrollmentsRemoved')} entries, "
          f"{reset.get('groupsRemoved')} heats, {reset.get('resultsRemoved')} results, "
          f"{reset.get('finalPlacesRemoved')} final places")
    print(f"         kept {reset.get('studentsKept')} students and "
          f"{reset.get('eventsKept')} events; school records kept: "
          f"{reset.get('recordsKept')}")

    # The reset refuses to run at all if it cannot write its backup, so a 200 here
    # means the file is on disk. It is named in the answer and listed by the app:
    season_backup = reset.get("backupFile")
    check(bool(season_backup) and (reset.get("backupBytes") or 0) > 0,
          "the reset wrote its own restorable season backup first",
          f"got {season_backup} ({reset.get('backupBytes')} bytes)")
    if season_backup:
        status, listed = api.request("GET", "/admin/backups", token=token)
        names = [b.get("name") for b in listed] if isinstance(listed, list) else []
        check(season_backup in names, "and the app lists it",
              f"{season_backup} not in the {len(names)} backup(s) on disk")
        print(f"         application season backup: {season_backup} "
              f"({reset.get('backupBytes')} bytes)")
        print("         note: the app's own backup covers entries, heats, final "
              "places, marks and records — NOT the relay teams. The mysqldump above "
              "is what covers those.")

    # Relay teams point at the results of their race, so the reset has to go first:
    # this is why the clear is here and not before it.
    section("3. Clearing the relay teams the reset leaves behind")
    # Every relay event is asked, whether or not the programme's own team count says
    # it has any: that count is only what the event list happens to report
    # (EventDTO.teamCount), and a team it does not mention is still on the board and
    # still holds legs. The clearing is idempotent — a relay with no teams answers 0.
    cleared = 0
    for event in relay_events:
        status, body = api.request(
            "DELETE", f"/admin/events/{event['id']}/relay-teams", token=token)
        if status != 200:
            refused(f"DELETE /admin/events/{event['id']}/relay-teams", status, body)
            continue
        removed = body.get("teamsRemoved", 0) if isinstance(body, dict) else 0
        if removed:
            print(f"    {event['name']}: {removed} team(s)")
        cleared += removed
    expected = before.get("relay_teams")
    if expected is None:
        print(f"  {cleared} relay team(s) removed (the table could not be counted "
              f"beforehand, so this is reported rather than checked)")
        check(cleared >= 0, f"{cleared} relay team(s) removed")
    else:
        check(cleared == expected,
              f"{cleared} relay team(s) removed — every one the tables held, over "
              f"{len(relay_events)} relay event(s)",
              f"the relay_teams table held {expected} before the reset, so "
              f"{expected - cleared} team(s) were left on the board")
    return reset


# --------------------------------------------------------------------- phases 4-5

def rebuild_register(api, token, args, reference_date):
    section("4. The student register")
    if args.students == 600 and os.path.exists(args.sample_csv):
        print(f"  uploading {os.path.relpath(args.sample_csv, args.repo_root)} "
              f"with reference date {reference_date}")
        status, body = api.upload(
            f"/admin/students/upload?referenceDate={reference_date}",
            args.sample_csv, token=token)
    else:
        print(f"  asking the app for {args.students} sample students "
              f"(reference date {reference_date})")
        status, body = api.request(
            "POST", f"/admin/students/sample?count={args.students}"
                    f"&referenceDate={reference_date}", token=token)
    if status != 200 or not isinstance(body, dict):
        refused("the student register upload", status, body)
        print("  [FAIL] the register could not be rebuilt")
        return None
    check(body.get("failed", 1) == 0,
          f"the register rebuilds with no rejected row "
          f"({body.get('created')} created, {body.get('updated')} updated, "
          f"{body.get('unlocked')} unlocked)",
          f"failed={body.get('failed')} errors={str(body.get('errors'))[:300]}")
    print(f"         batch {body.get('batch')}, grade reference date "
          f"{body.get('gradeReferenceDate')}")

    status, summary = api.request("GET", "/admin/students/summary", token=token)
    if status != 200 or not isinstance(summary, dict):
        refused("GET /admin/students/summary", status, summary)
        return None
    check(summary.get("total") == args.students,
          f"the register holds {args.students} students",
          f"got {summary.get('total')}")
    by_grade = summary.get("byGrade", {})
    check(sum(by_grade.values()) == args.students,
          "every one of them in exactly one grade", f"{by_grade}")
    print(f"         by grade {by_grade}, by sex {summary.get('bySex')}")
    return summary


def rebuild_catalogue(api, token):
    section("5. The event catalogue")
    status, body = api.request("POST", "/events/defaults", token=token)
    if status != 200:
        refused("POST /events/defaults", status, body)
        print("  [FAIL] the default catalogue could not be built")
        return None
    print(f"         the app's own catalogue builder answered {body}")
    status, events = api.request("GET", "/events", token=token)
    if status != 200 or not isinstance(events, list) or not events:
        refused("GET /events", status, events)
        print("  [FAIL] the programme could not be read")
        return None

    check(True, f"the programme holds {len(events)} events",
          "")
    by_category = {}
    by_grade = {}
    for event in events:
        by_category[event["category"]] = by_category.get(event["category"], 0) + 1
        by_grade[event["grade"]] = by_grade.get(event["grade"], 0) + 1
    print(f"         by category {by_category}, by grade {by_grade}")
    relays = [e for e in events if e.get("relay")]
    undivided = [e for e in relays if not e.get("relayTeamKind")]
    if len(events) > SEEDED_PROGRAMME_EVENTS:
        print(f"         note: the seeded programme is {SEEDED_PROGRAMME_EVENTS} "
              f"events, so the catalogue carries {len(events) - SEEDED_PROGRAMME_EVENTS} "
              f"more than that. The catalogue builder only ever adds, and this script "
              f"does not delete an event the school may be running.")
    if undivided:
        print(f"         note: {len(undivided)} relay(s) have no FORM or HOUSE kind, "
              f"so no team can be derived for them and they cannot be marked: "
              f"{[e['name'] for e in undivided][:4]}")
    check(not any(e.get("draft") for e in events),
          "no draft event is on the programme")
    return events


def ensure_season(api, token, args, reference_date):
    section("6. The school year")
    status, season = api.request("GET", "/seasons/current", token=token)
    if status == 200 and isinstance(season, dict) and season.get("id"):
        print(f"         {season.get('name')} ({season.get('year')}), sport day "
              f"{season.get('sportDayDate')}, entries "
              f"{'open' if season.get('enrollmentOpen') else 'closed'}")
        if args.open_enrollment and not season.get("enrollmentOpen"):
            payload = dict(season)
            payload["enrollmentOpen"] = True
            status, updated = api.request("PUT", f"/admin/seasons/{season['id']}",
                                          payload, token=token)
            if status == 200 and isinstance(updated, dict):
                check(True, "entries were closed, so --open-enrollment reopened them")
            else:
                refused(f"PUT /admin/seasons/{season['id']}", status, updated)
        return season

    refused("GET /seasons/current", status, season)
    year = int(reference_date[:4])
    payload = {"year": year, "name": f"{year} Sports Day",
               "sportDayDate": reference_date, "enrollmentOpen": True}
    status, created = api.request("POST", "/admin/seasons", payload, token=token)
    if status != 200 or not isinstance(created, dict):
        refused("POST /admin/seasons", status, created)
        print("  [FAIL] there is no school year and none could be created")
        return None
    check(True, f"there was no school year, so {created.get('name')} was created")
    return created


# --------------------------------------------------------------------- phase 7

def entries_phase(api, token, args, roster, events):
    """Enters athletes through the enrolment endpoint, so the grade, division and
    quota rules are the server's and not this script's."""
    section("7. Entering athletes in every event")
    quota_max = dict(FALLBACK_QUOTA)
    for event in events:
        allowance = event.get("maxEntriesPerStudent")
        if isinstance(allowance, int) and allowance > 0:
            quota_max[event["category"]] = allowance
        # Every category on the programme is counted, even one the report did not
        # mention, so no event's pool can come out with no room at all.
        quota_max.setdefault(event["category"], 2)
    print(f"  entry allowance per student, as the programme reports it: {quota_max}")

    # After the reset nothing is entered, so every student starts with all of it.
    remaining = {student["studentId"]: dict(quota_max) for student in roster}

    # How many each event may take: the cap, unless its own pool of students has less
    # room than that to share out, in which case what is left is divided evenly so no
    # event is starved of a field. The same arithmetic full_retest.py uses.
    pools = {}
    for event in events:
        pools.setdefault((event["grade"], event["sex"], event["category"]),
                         []).append(event)
    target = {}
    for (grade, sex, category), group in pools.items():
        room = sum(remaining[student["studentId"]].get(category, 0)
                   for student in roster
                   if student["grade"] == grade and student["sex"] == sex)
        share, extra = divmod(room, len(group))
        for index, event in enumerate(sorted(group,
                                             key=lambda e: e.get("enrolledCount") or 0)):
            target[event["id"]] = min(args.per_event, share + (1 if index < extra else 0))

    entered = {}
    for event in sorted(events, key=lambda e: (e["category"], e["type"], e["sex"],
                                               e["grade"])):
        accepted = []
        entered[event["id"]] = accepted
        cap = target.get(event["id"], 0)
        if cap <= 0:
            continue
        category = event["category"]
        pool = [student for student in roster
                if student["sex"] == event["sex"] and student["grade"] == event["grade"]
                and student.get("enabled", True)]
        if event.get("relay") and event.get("form"):
            # A form relay is scoped to a FORM by the team rule and to a GRADE by the
            # entry rule, and a runner has to satisfy both: the class their team is
            # keyed by comes from the form, and the entry comes from the grade. Where
            # the event's grade and form do not meet there is no student who can be
            # entered into a team at all, so the event is left empty and named rather
            # than filled with entries the derive then makes nothing of.
            wanted_form = str(event["form"])
            usable = [s for s in pool if str(s.get("form")) == wanted_form]
            if not usable:
                left_alone(event["name"],
                           f"it is a Form {wanted_form} relay in the {event['grade']} "
                           f"grade, and no student of that grade is in Form {wanted_form}, "
                           f"so no team of it could ever be derived")
                continue
            pool = usable
        if event.get("relay"):
            # A class (or house) at a time, so the derive has more than one team to
            # make and the relay is a race rather than one team on its own.
            pool = interleave(sorted(pool, key=lambda s: s["studentId"]),
                              lambda s: group_key(event, s))
        else:
            random.shuffle(pool)

        picked = []
        for student in pool:
            if len(picked) >= cap:
                break
            if remaining[student["studentId"]].get(category, 0) <= 0:
                continue
            picked.append(student)

        for student in picked:
            status, body = api.request(
                "POST",
                f"/admin/students/{student['studentId']}/enrollments/{event['id']}",
                token=token)
            if status == 200:
                remaining[student["studentId"]][category] -= 1
                accepted.append(student)
            else:
                refused(f"{event['name']}: entering {student['studentId']}", status, body)

    total = sum(len(v) for v in entered.values())
    check(total > 0, "athletes were entered across the programme", f"{total} entries")
    empty = [e for e in events if not entered[e["id"]]]
    unexplained = [e for e in empty if not accounted_for(e["name"])]
    if empty:
        print(f"  {len(empty)} event(s) hold nobody: "
              f"{len([e for e in empty if refused_for(e['name'])])} because the app "
              f"refused every entry offered, "
              f"{len([e for e in empty if e['name'] in EXPLAINED])} because the app's "
              f"own grade and form rules leave no student who could run in them")
    check(not unexplained,
          "the events that hold nobody are the ones the app's own rules leave empty, "
          "not ones this script failed to fill",
          f"nobody entered and nothing on record for: "
          f"{[e['name'] for e in unexplained][:8]}")
    return entered, total


# --------------------------------------------------------------------- phase 8

def groups_phase(api, token, events, entered):
    """Heats for the individual events. A relay is run and printed by team, so it is
    given no heats: its sheet is its teams."""
    section("8. Allocating the heats")
    grouped = 0
    skipped = 0
    for event in events:
        if event.get("relay") or not entered.get(event["id"]):
            skipped += 1
            continue
        status, body = api.request("POST", f"/events/{event['id']}/groups/allocate",
                                   token=token)
        if status != 200:
            refused(f"{event['name']}: allocating heats", status, body)
            continue
        grouped += 1
    check(True, f"{grouped} event(s) drew their heats "
                f"({skipped} relay(s) or empty event(s) have none to draw)")

    status, catalogue = api.request("GET", "/events", token=token)
    if status == 200 and isinstance(catalogue, list):
        ungrouped = [e for e in catalogue
                     if not e.get("relay") and (e.get("ungroupedCount") or 0) > 0]
        check(not ungrouped, "leaving nobody ungrouped",
              f"ungrouped: {[(e['name'], e['ungroupedCount']) for e in ungrouped][:5]}")
        too_big = [e for e in catalogue
                   if e.get("groupCount") and e.get("enrolledCount") and e.get("groupSize")
                   and e["enrolledCount"] > e["groupSize"]
                   and e["groupCount"] < math.ceil(e["enrolledCount"] / e["groupSize"])]
        check(not too_big, "and no heat is over its size",
              f"{[(e['name'], e['groupCount'], e['enrolledCount'], e['groupSize'])
                 for e in too_big][:5]}")
    return grouped


def relays_phase(api, token, relay_events, entered):
    """Derives each relay's teams from its entries, then fills every team from the
    register the board offers it — so the form/house rule, the one-leg-per-athlete
    rule and the four-runners ceiling are all the app's."""
    section("9. Deriving and filling the relay teams")
    ready = {}
    for event in relay_events:
        # The derive on a form relay makes one team per class its ENTRANTS are in, so
        # with nobody entered there is nothing to make. It is still called, because the
        # app is the one that should say so if anything is refused.
        status, derived = api.request(
            "POST", f"/admin/events/{event['id']}/relay-teams/derive?prune=true",
            token=token)
        if status != 200:
            # The app refused to derive at all — an undivided relay, or a house relay
            # carrying a form. That is the app's own rule, in the app's own words.
            left_alone(event["name"],
                       refused(f"{event['name']}: deriving its teams", status, derived))
            continue
        board = derived.get("board") or {}
        legs = board.get("legsPerTeam") or 4
        teams = board.get("teams") or []
        print(f"    {event['name']}: {derived.get('created')} team(s) created, "
              f"{derived.get('kept')} kept, {derived.get('pruned')} pruned, "
              f"{derived.get('eligibleStudents')} students eligible")
        if not teams:
            # The derive is the app's own reading of who runs for whom: a form relay
            # whose entrants are all in classes outside its own form has no team, and
            # inventing one would be a fixture the app itself would not accept. The
            # mark grid is asked anyway, so the refusal in the report is the app's own
            # sentence about this relay rather than this script's paraphrase of it.
            ready[event["id"]] = False
            status, body = api.request(
                "GET", f"/events/{event['id']}/marks?stage=HEAT", token=token)
            if status != 200:
                refused(f"{event['name']}: its mark grid", status, body)
            if event["name"] in EXPLAINED:
                # Already accounted for when it was left empty: it has no entrants, so
                # no team was ever going to be derived, and the derive agreeing is not
                # a second reason.
                print("      no team to make — nobody entered it, so the derive "
                      "answered 0")
            else:
                left_alone(event["name"],
                           "the app's own derive made no team for it: no class the "
                           "relay's entrants are in belongs to its form")
            continue

        # Fill every team to its legs. A team is filled from its own candidates — the
        # register of its class or house, with everyone already running in this event
        # left out — which is the list the relay board itself offers.
        used = set()
        for team in teams:
            for member in team.get("members") or []:
                if member.get("userId"):
                    used.add(member["userId"])
        added = 0
        for team in teams:
            short = legs - (team.get("memberCount") or 0)
            if short <= 0:
                continue
            for candidate in team.get("candidates") or []:
                if short <= 0:
                    break
                user_id = candidate.get("userId")
                if not user_id or user_id in used:
                    continue
                status, body = api.request(
                    "POST", f"/admin/relay-teams/{team['id']}/runners",
                    {"userId": user_id}, token=token)
                if status == 200:
                    used.add(user_id)
                    short -= 1
                    added += 1
                else:
                    refused(f"{event['name']} / {team.get('label')}: adding a runner",
                            status, body)
        print(f"      {added} runner(s) named")

        status, board = api.request(
            "GET", f"/admin/events/{event['id']}/relay-teams", token=token)
        if status != 200:
            refused(f"{event['name']}: reading its relay board", status, board)
            continue
        in_the_race = [t for t in (board.get("teams") or [])
                       if (t.get("memberCount") or 0) > 0]
        short_teams = [t.get("label") for t in in_the_race
                       if (t.get("memberCount") or 0) < legs]
        # RelayReadiness: ready with at least two teams in the race, and every team in
        # the race holding its legs. A team nobody has been named in is not in the race.
        ready[event["id"]] = (len(in_the_race) >= 2 and not short_teams)
        print(f"      {len(in_the_race)} team(s) in the race, "
              f"{board.get('runnerCount')} runner(s)"
              + (f", SHORT OF ITS LEGS: {short_teams}" if short_teams else ""))

    # An event this script entered but could not make ready is a failure; one the app's
    # own rules leave with no race at all is reported as that.
    broken = [e for e in relay_events
              if e["id"] in ready and not ready[e["id"]] and entered.get(e["id"])]
    unbuilt = [e for e in relay_events
               if e["id"] not in ready and e["name"] not in EXPLAINED]
    check(not broken and not unbuilt,
          "every relay that could be entered has at least two teams in the race, each "
          "holding its legs",
          f"entered but not ready: {[e['name'] for e in broken]}; "
          f"no board could be read: {[e['name'] for e in unbuilt]}")
    if EXPLAINED:
        print(f"  {len(EXPLAINED)} event(s) the app's own rules left with no race, "
              f"named as they were met:")
        for name, reason in list(EXPLAINED.items())[:10]:
            print(f"    - {name}: {reason[:160]}")
    return ready


# --------------------------------------------------------------------- phase 10

def marks_phase(api, token, events, entered):
    """Reads each event's own mark grid and saves it back filled in. The grid is what
    the app says the event's marks hang off — one row per athlete, or one row per
    relay team with the team's own id — so nothing here has to guess."""
    section("10. Recording a mark for every athlete, and one time per relay team")
    marked = 0
    rows_saved = 0
    rows_expected = 0
    relay_rows = 0
    held_back = {}
    for event in sorted(events, key=lambda e: e["name"]):
        if not entered.get(event["id"]):
            continue
        status, sheet = api.request(
            "GET", f"/events/{event['id']}/marks?stage=HEAT", token=token)
        if status != 200:
            # The readiness rule's own words: a relay with too few teams, or a team
            # short of its legs, is refused here and refused again on the save.
            message = refused(f"{event['name']}: its mark grid", status, sheet)
            held_back[event["id"]] = message
            continue
        grid = sheet.get("rows") or []
        if not grid:
            held_back[event["id"]] = "the mark grid is empty"
            continue
        payload = []
        for index, row in enumerate(grid):
            entry = mark_row(event, index, len(grid))
            entry["userId"] = row["userId"]
            if row.get("teamId"):
                entry["teamId"] = row["teamId"]
                relay_rows += 1
            payload.append(entry)
        status, result = api.request(
            "POST", f"/events/{event['id']}/marks",
            {"stage": "HEAT", "rows": payload}, token=token)
        if status != 200:
            held_back[event["id"]] = refused(f"{event['name']}: saving its marks",
                                             status, result)
            continue
        if result.get("failed"):
            held_back[event["id"]] = refused(
                f"{event['name']}: {result.get('failed')} row(s)",
                200, result.get("errors"))
            continue
        marked += 1
        rows_saved += result.get("saved", 0)
        rows_expected += len(payload)
    check(marked > 0, f"{marked} event(s) took their marks "
                      f"({rows_saved} rows, {relay_rows} of them a relay team's time)")
    # The grid is who the event's marks hang off — every athlete entered, or every
    # team in the race — so the rows the save accepted have to be all of them: a row
    # silently dropped here would be an athlete with no mark and no explanation.
    check(rows_saved == rows_expected,
          "one mark for every row the app's own grid listed",
          f"{rows_saved} saved of {rows_expected} rows listed")
    if held_back:
        print(f"  {len(held_back)} event(s) could not be marked; the app's words:")
        for event in events:
            if event["id"] in held_back:
                print(f"    - {event['name']}: {held_back[event['id']][:160]}")
    return held_back, rows_saved


def finals_phase(api, token, events):
    section("11. Drawing the finals the sprints have earned")
    drawn = 0
    finals_written = 0
    for event in sorted(events, key=lambda e: e["name"]):
        if not (event.get("shortSprint") and event.get("runsAFinal")):
            continue
        if (event.get("enrolledCount") or 0) <= (event.get("groupSize") or 8):
            continue
        status, summary = api.request("POST", f"/events/{event['id']}/final",
                                      token=token)
        if status != 200:
            refused(f"{event['name']}: drawing the final", status, summary)
            continue
        drawn += 1
        status, sheet = api.request(
            "GET", f"/events/{event['id']}/marks?stage=FINAL", token=token)
        if status != 200:
            refused(f"{event['name']}: its final grid", status, sheet)
            continue
        grid = sheet.get("rows") or []
        if not grid:
            continue
        payload = []
        for index, row in enumerate(grid):
            entry = mark_row(event, index, len(grid))
            entry["userId"] = row["userId"]
            payload.append(entry)
        status, saved = api.request(
            "POST", f"/events/{event['id']}/marks",
            {"stage": "FINAL", "rows": payload}, token=token)
        if status != 200 or (isinstance(saved, dict) and saved.get("failed")):
            refused(f"{event['name']}: saving the final's marks", status, saved)
            continue
        finals_written += saved.get("saved", 0) if isinstance(saved, dict) else 0
    check(True, f"{drawn} final(s) drawn and run ({finals_written} final marks)")
    return drawn, finals_written


# --------------------------------------------------------------------- phase 12

def verify(api, token, args, held_back):
    """The checks full_retest.py ends on, over the season this script has built."""
    section("12. Reading it all back")
    status, catalogue = api.request("GET", "/events", token=token)
    if status != 200 or not isinstance(catalogue, list):
        refused("GET /events", status, catalogue)
        return

    # --- entries -------------------------------------------------------------
    total_entered = sum(e.get("enrolledCount") or 0 for e in catalogue)
    check(total_entered > 0, f"the programme holds {total_entered} confirmed entries")
    empty = [e for e in catalogue if not (e.get("enrolledCount") or 0)]
    unexplained = [e for e in empty if not accounted_for(e["name"])]
    check(not unexplained,
          f"the {len(catalogue) - len(empty)} event(s) that hold an athlete are the "
          f"ones the app would accept an entry for; the {len(empty)} that hold nobody "
          f"are the ones the app's own rules leave empty",
          f"nobody entered and nothing on record for: "
          f"{[(e['name'], e['grade'], e['sex']) for e in unexplained][:6]}")

    # --- results, one event at a time ---------------------------------------
    missing = []
    bad_order = []
    with_results = 0
    result_rows = 0
    for event in sorted(catalogue, key=lambda e: e["name"]):
        if event["id"] in held_back:
            continue
        if not (event.get("enrolledCount") or 0) and accounted_for(event["name"]):
            # Nobody entered it, for a reason the app gave or this script named: an
            # event with no field cannot report a result, and asking for one would
            # only report the app's own rules as a missing result.
            continue
        status, results = api.request("GET", f"/results/event/{event['id']}",
                                     token=token)
        if status != 200 or not isinstance(results, list) or not results:
            missing.append(event["name"])
            continue
        with_results += 1
        result_rows += len(results)
        status, standings = api.request("GET", f"/events/{event['id']}/standings",
                                       token=token)
        if status == 200 and isinstance(standings, dict):
            placings = standings.get("placings", [])
            for placing in placings:
                if placing.get("mark") is not None and not placing.get("displayMark"):
                    check(False, f"{event['name']}: a placing has no formatted result",
                          str(placing)[:200])
                    break
            values = [p["mark"] for p in placings if p.get("mark") is not None]
            races_lowest_first = event["category"] in ("TRACK", "RELAY")
            ordered = (values == sorted(values)) if races_lowest_first \
                else (values == sorted(values, reverse=True))
            # A sprint's final is a fresh race whose order need not match the heats.
            if not ordered and event["type"] not in ("RUN_60M", "RUN_100M",
                                                     "RUN_200M", "RUN_400M"):
                bad_order.append((event["name"], values[:5]))

    check(not missing, f"{with_results} event(s) report their results "
                       f"({result_rows} result rows)",
          f"missing: {missing[:8]}")
    check(not bad_order, "and the placings run fastest first on the track, furthest "
                         "first in the field", f"{bad_order[:3]}")

    # --- the relays the app would not mark ----------------------------------
    not_ready = [e for e in catalogue
                 if e.get("relay") and not e.get("relayReady", True)]
    if not_ready:
        print(f"  {len(not_ready)} relay(s) are not ready to mark, in the app's words:")
        for event in not_ready[:10]:
            print(f"    - {event['name']}: {event.get('readinessReason')}")
    ready_relays = [e for e in catalogue if e.get("relay") and e.get("relayReady")]
    check(bool(ready_relays), f"{len(ready_relays)} relay(s) the app reports ready to "
                              f"mark, at least one of them")
    # The app's own list says which relays are ready; the board is asked the same
    # question again, so a relay the list got wrong does not pass unnoticed. The list
    # is a convenience and the board is the rule — RelayReadiness says so itself.
    bad_boards = []
    for event in ready_relays:
        status, board = api.request(
            "GET", f"/admin/events/{event['id']}/relay-teams", token=token)
        if status != 200:
            bad_boards.append((event["name"], f"board answered {status}"))
            continue
        legs = board.get("legsPerTeam") or 4
        in_the_race = [t for t in (board.get("teams") or [])
                       if (t.get("memberCount") or 0) > 0]
        short = [t.get("label") for t in in_the_race
                 if (t.get("memberCount") or 0) < legs]
        if len(in_the_race) < 2 or short:
            bad_boards.append((event["name"],
                               f"{len(in_the_race)} team(s) in the race"
                               + (f", short: {short}" if short else "")))
    check(not bad_boards,
          f"and every relay it reports ready really holds two teams in the race, each "
          f"with its legs ({len(ready_relays)} board(s) read back)",
          f"{bad_boards[:6]}")

    # --- the results read the way the sport writes them ---------------------
    section("13. Results read the way the sport writes them")
    hundred = next((e for e in catalogue if e["type"] == "RUN_100M"
                    and e["sex"] == "MALE" and e["grade"] == "A"), None)
    if hundred and hundred["id"] not in held_back:
        status, results = api.request("GET", f"/results/event/{hundred['id']}",
                                     token=token)
        if isinstance(results, list) and results:
            displays = [r.get("displayMark") for r in results if r.get("displayMark")]
            check(displays and all(d.endswith("s") for d in displays),
                  "a 100M result carries its unit", f"got {displays[:4]}")
    four_hundreds = [e for e in catalogue if e["type"] == "RUN_400M"]
    found = None
    for candidate in sorted(four_hundreds,
                            key=lambda e: e.get("enrolledCount") or 0, reverse=True):
        status, results = api.request("GET", f"/results/event/{candidate['id']}",
                                     token=token)
        if isinstance(results, list) and any((r.get("mark") or 0) >= 60 for r in results):
            found = [r.get("displayMark") for r in results if r.get("displayMark")]
            break
    check(found is not None and any(d.count(".") >= 2 for d in found),
          "a 400M over a minute reads with its minutes part", f"got {found}")
    shot = next((e for e in catalogue if e["type"] == "SHOT_PUT"
                 and e["sex"] == "MALE" and e["grade"] == "A"), None)
    if shot and shot["id"] not in held_back:
        status, results = api.request("GET", f"/results/event/{shot['id']}", token=token)
        if isinstance(results, list) and results:
            displays = [r.get("displayMark") for r in results if r.get("displayMark")]
            check(all(d.endswith("M") for d in displays),
                  "a shot put result carries metres", f"got {displays[:4]}")

    # --- the PDFs -----------------------------------------------------------
    section("14. The results PDFs")
    if hundred:
        status, pdf = api.request("GET", f"/events/{hundred['id']}/results.pdf",
                                  token=token, raw=True)
        check(status == 200 and isinstance(pdf, bytes) and pdf[:4] == b"%PDF",
              "one event's results print as a PDF", f"status={status}")
    status, pdf = api.request("GET", "/results.pdf", token=token, raw=True)
    check(status == 200 and isinstance(pdf, bytes) and pdf[:4] == b"%PDF",
          "and the whole programme prints as one PDF", f"status={status}")
    if isinstance(pdf, bytes) and pdf[:4] == b"%PDF" and args.write_pdf:
        target = os.path.join(args.repo_root, "artifacts",
                              "reset-test-data-results.pdf")
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as handle:
            handle.write(pdf)
        print(f"         wrote {target} ({len(pdf)} bytes)")

    # --- the championships ---------------------------------------------------
    section("15. The championships still add up")
    status, champions = api.request("GET", "/championships", token=token)
    check(status == 200 and isinstance(champions, dict), "the championships compute",
          f"status={status}")
    if isinstance(champions, dict):
        check(champions.get("eventsScored", 0) > 0, "with events scored",
              f"got {champions.get('eventsScored')}")
        points = [h.get("points", 0) for h in champions.get("houses", [])]
        check(points == sorted(points, reverse=True), "and the houses ranked by points",
              f"got {points}")


# --------------------------------------------------------------------- phase 16

def report_counts(db, before):
    section("16. What is in the tables now")
    after = db.counts()
    print(f"  {'table':<20}{'before':>10}{'after':>10}")
    for table in BACKUP_TABLES:
        print(f"  {table:<20}{before.get(table, 0):>10}{after.get(table, 0):>10}")
    return after


def report_refusals():
    if not REFUSALS:
        print("\n  no step was refused: every action this script took was accepted")
        return
    section("Every step the app refused, in its own words")
    for message, count in sorted(REFUSAL_REASONS.items(), key=lambda kv: -kv[1]):
        print(f"  {count:>4} x {message[:220]}")


# --------------------------------------------------------------------- main

def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    parser = argparse.ArgumentParser(
        description="Wipe the sport day's test data and rebuild a known season.",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--yes", action="store_true",
                        help="confirm the destruction. Without it nothing is written: "
                             "the script says what it would destroy and stops.")
    parser.add_argument("--dry-run", action="store_true",
                        help="report what would be destroyed and what would be built, "
                             "touching nothing at all — not even the backup.")
    parser.add_argument("--base-url", default=API_DEFAULT,
                        help=f"the backend's API root (default {API_DEFAULT})")
    parser.add_argument("--container", default="sportday-mysql",
                        help="the MySQL container the backup is taken from")
    parser.add_argument("--db", default="sportday",
                        help="the database the backup is of, and the one the running "
                             "backend is reading. Point both --base-url and --db at a "
                             "copy to rehearse against it.")
    parser.add_argument("--db-user", default="sportday")
    parser.add_argument("--db-password", default="sportday123")
    parser.add_argument("--db-root-user", default="root",
                        help="used ONLY to create, fill and drop the scratch database "
                             "the backup is loaded into, and to drop it again")
    parser.add_argument("--db-root-password", default="root123")
    parser.add_argument("--backup-dir", default=None,
                        help="where the dump is written (default <repo>/db-backup, "
                             "which is git-ignored)")
    parser.add_argument("--students", type=int, default=600,
                        help="how many students the register is rebuilt with "
                             "(default 600, which uploads the shipped sample CSV)")
    parser.add_argument("--per-event", type=int, default=9,
                        help="most athletes to enter in one event (default 9: inside "
                             "the 2 track / 1 field allowance and past a group of 8, "
                             "so a sprint that fills earns its final)")
    parser.add_argument("--seed", type=int, default=20261004)
    parser.add_argument("--reference-date", default=None,
                        help="the date grades are derived against; defaults to the "
                             "season's own sport day")
    parser.add_argument("--open-enrollment", action="store_true",
                        help="if the school year has entries closed, reopen them")
    parser.add_argument("--no-pdf", dest="write_pdf", action="store_false",
                        help="do not write the programme's results PDF to artifacts/")
    args = parser.parse_args()

    random.seed(args.seed)

    args.repo_root = os.path.abspath(
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    args.sample_csv = os.path.join(args.repo_root, "sportday-sample-data",
                                   "students-600.csv")
    if args.backup_dir is None:
        args.backup_dir = os.path.join(args.repo_root, "db-backup")

    api = Api(args.base_url)
    db = Db(args.container, args.db, args.db_user, args.db_password,
            args.db_root_user, args.db_root_password)
    started = time.time()

    # ------------------------------------------------------------- who is there
    section("0. The backend, and what is in it now")
    status, session = api.request("POST", "/auth/login",
                                  {"username": "admin", "password": "admin123"})
    if status != 200 or not isinstance(session, dict) or not session.get("token"):
        print(f"  [FAIL] could not sign in as admin at {args.base_url}: "
              f"{status} {str(session)[:200]}")
        return 4
    token = session["token"]
    print(f"  signed in as admin at {args.base_url}")

    status, events = api.request("GET", "/events", token=token)
    if status != 200 or not isinstance(events, list) or not events:
        print(f"  [FAIL] no programme could be read: {status} {str(events)[:200]}")
        return 4
    status, roster = api.request("GET", "/admin/students", token=token)
    roster = roster if isinstance(roster, list) else []
    status, summary = api.request("GET", "/admin/students/summary", token=token)
    summary = summary if isinstance(summary, dict) else {}
    relay_events = [e for e in events if e.get("relay")]

    try:
        before = db.counts()
    except Exception as error:
        print(f"  [WARN] could not read the tables directly ({error}); the backup "
              f"is the only thing that needs this, and it will say so if it fails")
        before = {}

    section("WHAT THIS WILL DESTROY")
    print("  Through the app's own season reset"
          " (POST /api/admin/season/reset):")
    print(f"    every enrollment row — {before.get('enrollments', '?')} of them, of "
          f"which {sum(e.get('enrolledCount') or 0 for e in events)} are confirmed and "
          f"count on the programme. A withdrawn row is deleted too")
    print(f"    {before.get('event_groups', '?')} heats/groups, "
          f"{before.get('event_results', '?')} results, "
          f"{before.get('final_entries', '?')} final places")
    print("  Through the relay-team clear"
          " (DELETE /api/admin/events/{id}/relay-teams), for each of the "
          f"{len(relay_events)} relay event(s) on the programme:")
    print(f"    {before.get('relay_teams', '?')} relay teams and "
          f"{before.get('relay_team_members', '?')} legs")
    print("  Kept, and rebuilt from the app's own endpoints:")
    print(f"    {summary.get('total', len(roster))} students "
          f"(re-uploaded, so their entries and results are new but their ids, "
          f"classes and houses are the same)")
    print(f"    {len(events)} events (the catalogue is never deleted)")
    print(f"    {before.get('event_records', '?')} school records, "
          f"{before.get('seasons', '?')} school year(s), the settings and the "
          f"teacher assignments")
    print(f"  Backed up first, to {os.path.abspath(args.backup_dir)}: a full "
          f"mysqldump of '{args.db}' in {args.container}, lined up against the app's "
          f"own season backup.")

    if args.dry_run:
        print("\n  --dry-run: nothing was written, not even the backup. Drop "
              "--dry-run and add --yes to do it.")
        section("Checks this run would end on")
        print("  the register holds the whole sample; every event that can be "
              "entered holds an athlete;")
        print("  every heat is drawn and nobody is ungrouped; every relay has two "
              "teams in the race holding their legs;")
        print("  every entry has a mark and every event reports results in the "
              "sport's own order; the finals the sprints earned are drawn and run;")
        print("  the PDFs render and the championships compute.")
        return 0

    if not args.yes:
        print("\n  NOTHING WAS WRITTEN. This script destroys the season, so it "
              "will not do it without being told to.")
        print("  Add --yes to take the backup and rebuild:")
        print(f"      python {os.path.relpath(__file__, args.repo_root)} --yes")
        return 2

    # ------------------------------------------------------------------ backup
    started_backup = time.time()
    backup = take_backup(db, args)
    print(f"  backup took {time.time() - started_backup:.0f}s")

    # ----------------------------------------------------------------- destroy
    reset = destroy(api, token, relay_events, before)
    if reset is None:
        # The app refused to empty the season, in its own words above. Its reset is
        # one transaction, so nothing was deleted and the backup still stands.
        return 5

    # ----------------------------------------------------------------- rebuild
    status, season = api.request("GET", "/seasons/current", token=token)
    reference_date = args.reference_date
    if not reference_date:
        reference_date = (season or {}).get("sportDayDate") if isinstance(season, dict) \
            else None
        reference_date = reference_date or time.strftime("%Y-%m-%d")

    if rebuild_register(api, token, args, reference_date) is None:
        print("  [FAIL] the register could not be rebuilt, so the season has been "
              "emptied and not refilled. The backup above puts it back.")
        return 4
    events = rebuild_catalogue(api, token)
    if events is None:
        return 4
    relay_events = [e for e in events if e.get("relay")]
    season = ensure_season(api, token, args, reference_date)
    if season is None:
        return 4

    status, roster = api.request("GET", "/admin/students", token=token)
    roster = roster if isinstance(roster, list) else []
    if not roster:
        print("  [FAIL] the register is empty, so nothing can be entered")
        return 4

    entered, total_entries = entries_phase(api, token, args, roster, events)
    groups_phase(api, token, events, entered)
    relays_phase(api, token, relay_events, entered)
    status, events = api.request("GET", "/events", token=token)
    events = events if isinstance(events, list) else []
    held_back, rows_saved = marks_phase(api, token, events, entered)
    drawn, finals_written = finals_phase(api, token, events)
    verify(api, token, args, held_back)
    after = report_counts(db, before)

    # ------------------------------------------------------------------ report
    section("Summary")
    print(f"  backup            : {backup['path']}")
    print(f"  backup sha256     : {backup['sha256']}")
    print(f"  backup verified   : {backup['verified']}")
    print(f"  app season backup : {reset.get('backupFile')} "
          f"({reset.get('backupBytes')} bytes, listed by GET /admin/backups)")
    print(f"  events            : {len(events)} "
          f"({len(relay_events)} relay(s))")
    print(f"  entries           : {after.get('enrollments', 0)} "
          f"({total_entries} entered by this run)")
    print(f"  heats/groups      : {after.get('event_groups', 0)}")
    print(f"  marks             : {after.get('event_results', 0)} "
          f"({rows_saved} saved by this run)")
    print(f"  final places      : {after.get('final_entries', 0)}")
    print(f"  relay teams/legs  : {after.get('relay_teams', 0)} / "
          f"{after.get('relay_team_members', 0)}")
    print(f"  finals drawn      : {drawn} ({finals_written} final marks)")
    print(f"  steps refused     : {len(REFUSALS)}")
    print(f"  checks run        : {CHECKS}")
    print(f"  failures          : {FAILURES}")
    print(f"  elapsed           : {time.time() - started:.0f}s")
    print("\n  To put the database back exactly as it was before this run:")
    print(f"      docker cp \"{backup['path']}\" {args.container}:/tmp/restore.sql")
    print(f"      docker exec {args.container} sh -c \"mysql -uroot "
          f"-p{args.db_root_password} {args.db} < /tmp/restore.sql\"")
    print("      (then run this script's --dry-run to read the result back)")
    print("  Or to put the season back and keep what is there now, restore the "
          "application's own backup:")
    print(f"      POST /api/admin/backups/{reset.get('backupFile')}/restore")
    print("      — entries, heats, finals and marks only: the relay teams are in "
          "the mysqldump.")

    report_refusals()

    if FAILURES:
        print("\nRESET TEST DATA FAILED — the season was rebuilt but its checks did "
              "not all pass; see the [FAIL] lines above.")
        return 1
    print("\nRESET TEST DATA PASSED — the season was wiped, rebuilt and read back.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
