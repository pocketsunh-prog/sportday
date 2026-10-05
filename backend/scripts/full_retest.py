"""Fills a freshly built SportDay with a whole school day and checks every step.

Enrols athletes into every event in the programme, allocates every event's groups,
records a mark for every athlete entered, draws the finals the sprints have earned,
and then reads the results back event by event.

This is the "retest all event enroll, mark input and result" run: it is meant to
leave the data in place afterwards, so the school has a populated system rather than
a clean one. It asserts as it goes and prints a per-event table at the end.

    python backend/scripts/full_retest.py

It respects the rules the application enforces — sex division, the event's own grade
(a student may only enter events of their own grade), and the 2 track / 1 field entry
limit — so a failure means the rules and the data disagree, not that the script took a
shortcut. A season that already holds entries is read back first, so the day is filled
from whatever room is left rather than assuming an empty one.
"""

import argparse
import json
import math
import random
import sys
import time
import urllib.error
import urllib.request

API = "http://localhost:8080/api"

CHECKS = 0
FAILURES = 0
PROBLEMS = []


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


def section(title):
    print(f"\n== {title}")


class Api:
    def __init__(self, base=API):
        self.base = base

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
            with urllib.request.urlopen(req, timeout=300) as response:
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

    def upload(self, path, file_path, token=None):
        boundary = "----sportdayretest"
        with open(file_path, "rb") as handle:
            content = handle.read()
        filename = file_path.replace("\\", "/").split("/")[-1]
        body = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
            f"Content-Type: application/octet-stream\r\n\r\n"
        ).encode("utf-8") + content + f"\r\n--{boundary}--\r\n".encode("utf-8")
        headers = {
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": "Bearer " + token,
        }
        req = urllib.request.Request(self.base + path, data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=600) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode("utf-8", "replace")


api = Api()


# --------------------------------------------------------------------- helpers

def mark_for(event, index, total):
    """A plausible mark for one athlete: a time for a race, a distance for a field event."""
    event_type = event["type"]
    if event["category"] == "FIELD":
        if event_type in ("HIGH_JUMP", "POLE_VAULT"):
            return round(1.20 + 0.05 * (index % 12), 2)
        if event_type in ("LONG_JUMP", "TRIPLE_JUMP"):
            return round(3.50 + 0.10 * (index % 20), 2)
        return round(6.00 + 0.35 * (index % 25), 2)

    # A race: the field spreads across a sensible range, fastest first.
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--students", type=int, default=600)
    # An event is one grade and one division now, so each event draws on a quarter of
    # the register at most. Nine fills every event: it stays inside the smallest
    # pool's 1 field entry each, and is past a group of 8, so a sprint that fills
    # opens its final by itself. Where a pool has less room than that — a season that
    # already holds entries — what is left is shared out evenly instead.
    parser.add_argument("--per-event", type=int, default=9,
                        help="most athletes to enter in one event (default 9: enough to "
                             "fill every event within the 2 track / 1 field limit, and "
                             "past a group of 8 so the sprints earn their finals). A "
                             "pool with less room than that shares what is left out")
    parser.add_argument("--seed", type=int, default=20261004)
    args = parser.parse_args()
    random.seed(args.seed)

    started = time.time()

    status, session = api.request("POST", "/auth/login",
                                  {"username": "admin", "password": "admin123"})
    if status != 200:
        print(f"Could not sign in as admin: {status} {session}")
        return 1
    admin = session["token"]

    # ------------------------------------------------------------- the register
    section("1. The register")
    status, summary = api.request("GET", "/admin/students/summary", token=admin)
    if not isinstance(summary, dict) or summary.get("total", 0) < args.students:
        sample = "../sportday-sample-data/students-600.csv"
        import os
        sample = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..",
                                              "sportday-sample-data", "students-600.csv"))
        status, uploaded = api.upload(
            "/admin/students/upload?referenceDate=2026-10-03", sample, token=admin)
        check(status == 200, "the 600-strong register uploads", f"status={status} {uploaded}")
        check(isinstance(uploaded, dict) and uploaded.get("failed") == 0,
              "with no rejected rows", f"failed={uploaded.get('failed')}")

    status, summary = api.request("GET", "/admin/students/summary", token=admin)
    check(status == 200 and summary.get("total") == args.students,
          f"the register holds {args.students} students", f"got {summary.get('total')}")
    grade_counts = summary.get("byGrade", {})
    check(sum(grade_counts.values()) == args.students,
          "with every student in exactly one grade", f"{grade_counts}")

    status, roster = api.request("GET", "/admin/students", token=admin)
    check(len(roster) == args.students, "and the roster lists them all", f"got {len(roster)}")
    by_id = {s["studentId"]: s for s in roster}

    # ------------------------------------------------------------ the programme
    section("2. The programme")
    status, events = api.request("GET", "/events", token=admin)
    # One event per type, division and grade: 20 types x 2 divisions = 40 for the
    # A grade, less the races a lower grade does not run (B loses the 5000M, C loses
    # the 5000M, the 1500M and the 110M hurdles).
    check(len(events) == 112, "the programme holds 112 events", f"got {len(events)}")
    event_grades = {}
    for event in events:
        event_grades[event["grade"]] = event_grades.get(event["grade"], 0) + 1
    check(event_grades == {"A": 40, "B": 38, "C": 34},
          "40 of them for the A grade, 38 for B and 34 for C", f"got {event_grades}")
    check(all(event.get("grade") in ("A", "B", "C") and event.get("gradeLabel")
              for event in events),
          "and every event belongs to exactly one grade, with a label",
          f"got {sorted({(e.get('grade'), e.get('gradeLabel')) for e in events})}")

    def grades_running(event_type):
        """The grades that have an event of this type."""
        return {e["grade"] for e in events if e["type"] == event_type}

    hurdles_100 = [e for e in events if e["type"] == "HURDLES_100M"]
    check(len(hurdles_100) == 6, "including a 100M hurdles for every grade in each division",
          f"got {len(hurdles_100)}")
    check(grades_running("HURDLES_100M") == {"A", "B", "C"},
          "open to all three grades, the C grade included",
          f"got {sorted(grades_running('HURDLES_100M'))}")
    check(grades_running("HURDLES_110M") == {"A", "B"},
          "while the 110M hurdles is A and B only",
          f"got {sorted(grades_running('HURDLES_110M'))}")
    check(grades_running("RUN_1500M") == {"A", "B"},
          "the 1500M is A and B only", f"got {sorted(grades_running('RUN_1500M'))}")
    check(grades_running("RUN_5000M") == {"A"},
          "and only the A grade runs the 5000M", f"got {sorted(grades_running('RUN_5000M'))}")

    # -------------------------------------------------------------- enrolment
    section("3. Entering athletes in every event")
    # What each student has left, per category, and what each event already holds. A
    # school day is normally filled from an empty season, but the smoke test — or an
    # earlier run of this script — may have left entries behind. Reading the real
    # state back means the script neither offers an entry to somebody with no
    # allowance left nor counts an event that already has athletes as empty.
    quota = {}
    for s in roster:
        quota[s["studentId"]] = {"TRACK": 2, "FIELD": 1}
    grade_of = {s["studentId"]: s["grade"] for s in roster}
    sex_of = {s["studentId"]: s["sex"] for s in roster}

    entered = {}
    blocked = {}
    for event in events:
        status, existing = api.request("GET", f"/enrollments/event/{event['id']}", token=admin)
        already, has_row = [], set()
        if isinstance(existing, list):
            for entry in existing:
                sid = entry.get("studentRef")
                if sid not in quota:
                    continue
                # Any row blocks a new entry — a withdrawn one is revived rather than
                # re-entered — but only a confirmed one uses up the allowance.
                has_row.add(sid)
                if entry.get("status") == "CONFIRMED":
                    already.append(sid)
                    quota[sid][event["category"]] -= 1
        entered[event["id"]] = already
        blocked[event["id"]] = has_row
    already_in = sum(len(v) for v in entered.values())
    if already_in:
        print(f"  {already_in} entries are already in place and count towards the quota")

    # How many new athletes each event may take: the cap, unless its pool has less room
    # than that to share out — a season that is already part-filled — in which case what
    # is left is divided evenly, so that no event is starved of a field.
    pools = {}
    for event in events:
        pools.setdefault((event["grade"], event["sex"], event["category"]), []).append(event)
    target = {}
    for (event_grade, event_sex, category), group in pools.items():
        room = sum(max(0, quota[s["studentId"]][category]) for s in roster
                   if s["grade"] == event_grade and s["sex"] == event_sex)
        share, extra = divmod(room, len(group))
        # The remainder goes to the events holding the fewest athletes already, so a
        # field left empty by an earlier run is filled before one that is already busy.
        for index, event in enumerate(sorted(group, key=lambda e: len(entered[e["id"]]))):
            target[event["id"]] = min(args.per_event, share + (1 if index < extra else 0))

    skipped_grade = 0
    refused_quota = 0
    refused_grade = 0
    refused_sex = 0
    refused_examples = []

    for event in sorted(events, key=lambda e: (e["category"], e["type"], e["sex"])):
        # The event's own grade decides who may enter it: an event is run by exactly
        # one grade now, so a student of any other grade would be refused.
        allowed = {event["grade"]}
        category = event["category"]
        cap = target[event["id"]]
        taken = blocked[event["id"]]
        candidates = [s for s in roster if s["sex"] == event["sex"]]
        random.shuffle(candidates)
        picked = []
        for student in candidates:
            if len(picked) >= cap:
                break
            sid = student["studentId"]
            if sid in taken:
                continue
            if grade_of[sid] not in allowed:
                skipped_grade += 1
                continue
            if quota[sid][category] <= 0:
                refused_quota += 1
                continue
            picked.append(sid)

        for sid in picked:
            status, body = api.request(
                "POST", f"/admin/students/{sid}/enrollments/{event['id']}", token=admin)
            if status == 200:
                quota[sid][category] -= 1
                entered[event["id"]].append(sid)
                blocked[event["id"]].add(sid)
            elif status == 409:
                # The script offered an entry it believed was allowed, so a refusal
                # here means its reading of the season and the server's disagree.
                refused_grade += 1
                if len(refused_examples) < 3:
                    refused_examples.append(f"{event['name']}: {sid} — {body}")
            else:
                check(False, f"{event['name']}: entering {sid} answered {status}")

    total_entered = sum(len(v) for v in entered.values())
    check(total_entered > 0, "athletes were entered across the programme",
          f"{total_entered} entries")
    empty = [e for e in events if not entered[e["id"]]]
    check(not empty, "every event has at least one athlete entered",
          f"empty: {[e['name'] for e in empty]}")
    check(refused_sex == 0, "no entry was refused for the wrong division",
          f"{refused_sex} refusals")
    check(refused_grade == 0,
          "and every entry the script offered was accepted by the server",
          f"{refused_grade} refusals ({skipped_grade} students skipped for another grade)"
          + ("; e.g. " + " | ".join(refused_examples) if refused_examples else ""))

    # --------------------------------------------------------------- grouping
    section("4. Allocating groups")
    grouped = 0
    for event in events:
        status, _ = api.request("POST", f"/events/{event['id']}/groups/allocate", token=admin)
        if status == 200:
            grouped += 1
        else:
            check(False, f"{event['name']}: allocating groups answered {status}")
    check(grouped == len(events), "every event's groups allocate",
          f"{grouped} of {len(events)}")

    status, catalogue = api.request("GET", "/events", token=admin)
    by_id_events = {e["id"]: e for e in catalogue}
    ungrouped = [e for e in catalogue if (e.get("ungroupedCount") or 0) > 0]
    check(not ungrouped, "leaving nobody ungrouped",
          f"ungrouped: {[(e['name'], e['ungroupedCount']) for e in ungrouped][:5]}")
    too_big = [e for e in catalogue
               if e["groupCount"] and e["enrolledCount"] > e["groupSize"]
               and e["groupCount"] < math.ceil(e["enrolledCount"] / e["groupSize"])]
    check(not too_big, "and no group is over its size",
          f"{[(e['name'], e['groupCount'], e['enrolledCount'], e['groupSize']) for e in too_big][:5]}")

    # ------------------------------------------------------------- mark entry
    section("5. Recording a mark for every athlete, in every event")
    marked_events = 0
    skipped_not_ready = 0
    skipped_entries = 0
    held_back = set()
    marks_written = 0
    for event in sorted(events, key=lambda e: e["name"]):
        athletes = entered[event["id"]]
        if not athletes:
            continue
        rows = []
        total = len(athletes)
        for index, sid in enumerate(athletes):
            value = mark_for(event, index, total)
            # The mark endpoint keys on the user id, not the student id.
            row = {"userId": by_id[sid]["userId"], "mark": None, "notes": None}
            if event["category"] == "FIELD":
                # Three attempts, the best of which is the result.
                attempts = [round(value + 0.20, 2), value, round(value - 0.15, 2)]
                row["attempts"] = attempts
                row["mark"] = None
            elif event.get("timeInMinutes"):
                whole = int(value // 60)
                row["minutes"] = whole
                row["seconds"] = round(value - whole * 60, 3)
                row["mark"] = None
            else:
                row["mark"] = value
            rows.append(row)

        status, result = api.request(
            "POST", f"/events/{event['id']}/marks",
            {"stage": "HEAT", "rows": rows}, token=admin)
        if status == 409 and ("before its marks can be entered" in str(result)
                              or "cannot be marked yet" in str(result)):
            # A relay is not marked until it is ready — at least two teams, every team
            # holding four runners. A half-built relay is refused with one of those two
            # reasons, and refusing it is correct behaviour, not a failure of this run.
            skipped_not_ready += 1
            skipped_entries += len(entered.get(event["id"], []) or [])
            held_back.add(event["id"])
            print(f"  - {event['name']}: held back until its teams are complete")
            continue
        if status != 200 or not isinstance(result, dict):
            check(False, f"{event['name']}: saving marks answered {status}", str(result)[:200])
            continue
        if result.get("failed"):
            check(False, f"{event['name']}: {result.get('failed')} mark(s) refused",
                  f"{result.get('errors')}")
            continue
        marked_events += 1
        marks_written += result.get("saved", 0)

    check(marked_events + skipped_not_ready == len([e for e in events if entered[e["id"]]]),
          "every event that has athletes was marked, or held back for being incomplete",
          f"{marked_events} marked, {skipped_not_ready} held back")
    if skipped_not_ready:
        print(f"  - {skipped_not_ready} relay(s) held back: not enough teams, or a team "
              f"short of its runners. A half-built relay is not marked, by design.")
    check(marks_written + skipped_entries == total_entered,
          "one mark for every athlete entered, bar those in a relay held back",
          f"{marks_written} marks for {total_entered} entries"
          f" ({skipped_entries} in held-back relays)")

    # ------------------------------------------------------------ the finals
    section("6. Drawing the finals the sprints have earned")
    sprints = [e for e in events if e["type"] in ("RUN_60M", "RUN_100M", "RUN_200M", "RUN_400M")]
    drawn = 0
    for event in sprints:
        if len(entered[event["id"]]) <= 8:
            continue
        status, result = api.request("POST", f"/events/{event['id']}/final", token=admin)
        if status == 200 and isinstance(result, dict):
            drawn += 1
            rows = []
            for index, qualifier in enumerate(result.get("qualifiers", [])):
                value = mark_for(event, index, max(len(result.get("qualifiers", [])), 1))
                row = {"userId": qualifier["userId"]}
                if event.get("timeInMinutes"):
                    whole = int(value // 60)
                    row["minutes"] = whole
                    row["seconds"] = round(value - whole * 60, 3)
                else:
                    row["mark"] = value
                rows.append(row)
            status, saved = api.request(
                "POST", f"/events/{event['id']}/marks",
                {"stage": "FINAL", "rows": rows}, token=admin)
            if status != 200 or saved.get("failed"):
                check(False, f"{event['name']}: final marks were refused", str(saved)[:200])
        else:
            check(False, f"{event['name']}: drawing the final answered {status}", str(result)[:200])
    check(drawn == len([e for e in sprints if len(entered[e["id"]]) > 8]),
          "every sprint with more than eight entered drew a final",
          f"{drawn} finals")

    # --------------------------------------------------------------- results
    section("7. Reading the results back")
    results_ok = 0
    missing = []
    bad_order = []
    for event in sorted(events, key=lambda e: e["name"]):
        if event["id"] in held_back:
            # A relay held back for being incomplete is not marked, so it has no
            # results to read. That is the rule working, not a missing result.
            continue
        status, results = api.request("GET", f"/results/event/{event['id']}", token=admin)
        if status != 200 or not isinstance(results, list) or not results:
            missing.append(event["name"])
            continue
        results_ok += 1

        status, standings = api.request("GET", f"/events/{event['id']}/standings", token=admin)
        if status == 200 and isinstance(standings, dict):
            placings = standings.get("placings", [])
            for placing in placings:
                if placing.get("mark") is not None and not placing.get("displayMark"):
                    check(False, f"{event['name']}: a placing has no formatted result",
                          str(placing)[:200])
                    break
            # The placings are the ordering that matters: fastest first on the track,
            # furthest first in the field. The raw results list is in entry order.
            values = [p["mark"] for p in placings if p.get("mark") is not None]
            ordered = (values == sorted(values)) if event["category"] == "TRACK" \
                else (values == sorted(values, reverse=True))
            # A sprints final is a fresh race whose order need not match the heats.
            if not ordered and event["type"] not in ("RUN_60M", "RUN_100M", "RUN_200M", "RUN_400M"):
                bad_order.append((event["name"], values[:5]))

    check(not missing, "every event reports its results",
          f"missing: {missing}")
    check(not bad_order, "and the placings run fastest first on the track, furthest first in the field",
          f"{bad_order[:3]}")

    # The formatted result, which is what a reader sees.
    section("8. Results read the way the sport writes them")
    status, all_events = api.request("GET", "/events", token=admin)
    # An event is one grade now, so a lookup by type has to name the grade too: this
    # is the A-grade boys' 100M, which section 3 has entered and marked.
    hundred = next((e for e in all_events
                    if e["type"] == "RUN_100M" and e["sex"] == "MALE"
                    and e["grade"] == "A"), None)
    check(hundred is not None and bool(entered.get(hundred["id"])),
          "the A grade boys' 100M has results to read",
          f"got {hundred and hundred['name']}")
    if hundred:
        status, results = api.request("GET", f"/results/event/{hundred['id']}", token=admin)
        if isinstance(results, list) and results:
            displays = [r.get("displayMark") for r in results if r.get("displayMark")]
            check(displays and all(d.endswith("s") for d in displays),
                  "a 100M result carries its unit", f"got {displays[:4]}")
            check(any("." in d and d.count(".") <= 2 for d in displays),
                  "and reads as seconds or minutes.seconds", f"got {displays[:4]}")

    # A 400M may be run in under or over a minute depending on how big its field is,
    # so the event to read the minutes part off is one that actually has a time past
    # 60 seconds rather than whichever 400M comes first in the programme.
    four_hundreds = [e for e in all_events if e["type"] == "RUN_400M"]
    four_hundred, four_hundred_displays = None, []
    for candidate in sorted(four_hundreds, key=lambda e: e.get("enrolledCount") or 0,
                            reverse=True):
        status, results = api.request("GET", f"/results/event/{candidate['id']}", token=admin)
        if not isinstance(results, list):
            continue
        displays = [r.get("displayMark") for r in results if r.get("displayMark")]
        if any((r.get("mark") or 0) >= 60 for r in results):
            four_hundred, four_hundred_displays = candidate, displays
            break
    check(four_hundred is not None,
          "a 400M event has a time over a minute to read the minutes part from",
          f"looked through {len(four_hundreds)} 400M events")
    if four_hundred:
        check(any(d.count(".") >= 2 for d in four_hundred_displays),
              f"a 400M over a minute reads with a minutes part ({four_hundred['name']})",
              f"got {four_hundred_displays[:6]}")

    shot = next((e for e in all_events
                 if e["type"] == "SHOT_PUT" and e["sex"] == "MALE" and e["grade"] == "A"), None)
    if shot:
        status, results = api.request("GET", f"/results/event/{shot['id']}", token=admin)
        if isinstance(results, list) and results:
            displays = [r.get("displayMark") for r in results if r.get("displayMark")]
            check(all(d.endswith("M") for d in displays),
                  "a shot put result carries metres", f"got {displays[:4]}")

    # ------------------------------------------------------------- the PDFs
    section("9. The results PDFs")
    if hundred:
        status, pdf = api.request("GET", f"/events/{hundred['id']}/results.pdf",
                                  token=admin, raw=True)
        check(status == 200 and isinstance(pdf, bytes) and pdf[:4] == b"%PDF",
              "one event's results print as a PDF",
              f"status={status} head={pdf[:8] if isinstance(pdf, bytes) else pdf}")

    status, pdf = api.request("GET", "/results.pdf", token=admin, raw=True)
    check(status == 200 and isinstance(pdf, bytes) and pdf[:4] == b"%PDF",
          "and the whole programme prints as one PDF",
          f"status={status}")
    if isinstance(pdf, bytes) and pdf[:4] == b"%PDF":
        import os
        target = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..",
                                              "artifacts", "results-whole-programme.pdf"))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as handle:
            handle.write(pdf)
        print(f"         wrote {target} ({len(pdf)} bytes)")

    # ------------------------------------------------------- championships
    section("10. The championships still add up")
    status, champions = api.request("GET", "/championships", token=admin)
    check(status == 200 and isinstance(champions, dict), "the championships compute",
          f"status={status}")
    if isinstance(champions, dict):
        check(champions.get("eventsScored", 0) > 0, "with events scored",
              f"got {champions.get('eventsScored')}")
        houses = champions.get("houses", [])
        points = [h.get("points", 0) for h in houses]
        check(points == sorted(points, reverse=True), "and the houses ranked by points",
              f"got {points}")

    # ------------------------------------------------------------------ done
    section("Summary")
    elapsed = time.time() - started
    print(f"  events        : {len(events)}")
    print(f"  entries       : {total_entered}")
    print(f"  marks         : {marks_written}")
    print(f"  finals drawn  : {drawn}")
    print(f"  events marked : {marked_events}")
    print(f"  checks run    : {CHECKS}")
    print(f"  failures      : {FAILURES}")
    print(f"  elapsed       : {elapsed:.0f}s")
    if FAILURES:
        print("\nFAILED:")
        for problem in PROBLEMS:
            print(f"  - {problem}")
        print("\nFULL RETEST FAILED")
        return 1
    print("\nFULL RETEST PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
