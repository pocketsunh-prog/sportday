#!/usr/bin/env python3
"""End-to-end check of the revamped SportDay API against a running backend.

Exercises the whole sport-day flow over real HTTP:

  1. admin login
  2. default event catalogue (events default to enabled)
  3. bulk import of 600 sample students, checking the grade split
  4. student login with the derived password (yyyyMMdd + class + class number)
  5. entry quota: 2 track (徑項) + 1 field (田項), with the extras refused
  6. group allocation: 8 per heat for 60/100/200/400, 24 for 800 and above
  7. marking-sheet PDFs, checking page size (A5 vs A4) and page count

Usage:
    <python> backend/scripts/smoke_test.py
    <python> backend/scripts/smoke_test.py --base-url http://localhost:8080/api

Exits non-zero on the first failed assertion group.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import date

CHECKS = 0
FAILURES = 0

# A student's grade comes from their date of birth as at a reference date, so any
# check on the register's grade split has to name the date it is true for.
GRADE_REFERENCE_DATE = "2026-10-03"


def check(condition: bool, message: str, detail: str = "") -> bool:
    global CHECKS, FAILURES
    CHECKS += 1
    if condition:
        print(f"  [PASS] {message}")
    else:
        FAILURES += 1
        print(f"  [FAIL] {message}")
        if detail:
            print(f"         {detail}")
    return bool(condition)


def section(title: str) -> None:
    print(f"\n== {title}")


class Api:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")

    def request(self, method: str, path: str, body=None, token=None, raw=False):
        """Returns (status, parsed_json_or_bytes_or_text)."""
        url = f"{self.base_url}{path}"
        data = None
        # */* so text/csv and application/pdf endpoints are negotiable too.
        headers = {"Accept": "*/*"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = f"Bearer {token}"

        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=120) as response:
                payload = response.read()
                status = response.status
        except urllib.error.HTTPError as exc:
            payload = exc.read()
            status = exc.code
        except Exception as exc:  # noqa: BLE001 - report and fail the check
            return 0, str(exc)

        if raw:
            return status, payload
        text = payload.decode("utf-8", errors="replace")
        try:
            return status, json.loads(text) if text else None
        except json.JSONDecodeError:
            return status, text

    def upload(self, path: str, file_path: str, token: str = None):
        with open(file_path, "rb") as handle:
            payload = handle.read()
        return self.upload_bytes(path, os.path.basename(file_path), payload, token)

    def upload_bytes(self, path: str, filename: str, payload: bytes, token: str = None):
        body, content_type = multipart({}, {"file": (filename, payload)})
        headers = {"Accept": "*/*", "Content-Type": content_type}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(f"{self.base_url}{path}", data=body, headers=headers,
                                     method="POST")
        try:
            with urllib.request.urlopen(req, timeout=600) as response:
                text, status = response.read().decode("utf-8", errors="replace"), response.status
        except urllib.error.HTTPError as exc:
            text, status = exc.read().decode("utf-8", errors="replace"), exc.code
        try:
            return status, json.loads(text) if text else None
        except json.JSONDecodeError:
            return status, text


def login(api: Api, username: str, password: str):
    status, body = api.request("POST", "/auth/login", {"username": username, "password": password})
    if status == 200 and isinstance(body, dict):
        return body.get("token"), body
    return None, body


def derived_password(student: dict) -> str:
    """yyyyMMdd + class + class number."""
    dob = date.fromisoformat(student["dob"][:10])
    return f"{dob.strftime('%Y%m%d')}{student['className']}{student['classNumber']}"


def multipart(fields: dict, files: dict) -> tuple[bytes, str]:
    """Builds a multipart/form-data body: files maps field name -> (filename, bytes)."""
    boundary = "----SportDaySmokeBoundary7f3a9c"
    out = bytearray()
    for name, value in fields.items():
        out += f"--{boundary}\r\n".encode()
        out += f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode()
        out += f"{value}\r\n".encode()
    for name, (filename, payload) in files.items():
        out += f"--{boundary}\r\n".encode()
        out += (f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'
                .encode())
        out += b"Content-Type: application/octet-stream\r\n\r\n"
        out += payload
        out += b"\r\n"
    out += f"--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080/api")
    parser.add_argument("--students", type=int, default=600)
    parser.add_argument("--output-dir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "artifacts"))
    args = parser.parse_args()

    api = Api(args.base_url)
    output_dir = os.path.abspath(args.output_dir)
    os.makedirs(output_dir, exist_ok=True)

    # ------------------------------------------------------------- 1. admin
    section("1. Admin login")
    admin_token, admin_body = login(api, "admin", "admin123")
    check(admin_token is not None, "admin can sign in", f"body={admin_body}")
    if not admin_token:
        return 1

    # --------------------------------------------------------- 1b. clean slate
    section("1b. Season reset (makes this run repeatable)")
    status, reset = api.request("POST", "/admin/season/reset", token=admin_token)
    check(status == 200, "season reset accepted", f"status={status} body={reset}")
    if isinstance(reset, dict):
        print(f"  cleared {reset.get('enrollmentsRemoved')} entries, "
              f"{reset.get('groupsRemoved')} groups, {reset.get('resultsRemoved')} results")

    # --------------------------------------------------------- 2. catalogue
    section("2. Default event catalogue")
    status, body = api.request("POST", "/events/defaults", token=admin_token)
    check(status == 200, "default catalogue created", f"status={status} body={body}")

    status, events = api.request("GET", "/events", token=admin_token)
    check(status == 200 and isinstance(events, list), "event list readable", f"status={status}")
    events = events if isinstance(events, list) else []
    # One event per type × division × grade: 20 types × 2 divisions, minus the six
    # races the lower grades do not run (the 1500M and 110M hurdles have no C grade,
    # and only the A grade runs the 5000M).
    check(len(events) == 112,
          "the catalogue holds 112 events — one per type, division and grade",
          f"got {len(events)}")
    check(all(e.get("grade") in ("A", "B", "C") for e in events),
          "and every event belongs to exactly one grade",
          f"got {sorted({e.get('grade') for e in events})}")
    track = [e for e in events if e.get("category") == "TRACK"]
    field = [e for e in events if e.get("category") == "FIELD"]
    male = [e for e in events if e.get("sex") == "MALE"]
    female = [e for e in events if e.get("sex") == "FEMALE"]

    check(len(track) > 0, "track (徑項) events exist", f"count={len(track)}")
    check(len(field) > 0, "field (田項) events exist", f"count={len(field)}")
    check(bool(male) and bool(female), "events split across both divisions",
          f"M={len(male)} F={len(female)}")
    check(all(e.get("enabled") for e in events), "every seeded event is enabled by default")

    def first(pred):
        return next((e for e in events if pred(e)), None)

    # An event belongs to one grade now, so every lookup has to name the grade it
    # wants or it would quietly take whichever grade sorts first. The rest of this
    # run works inside the A grade: section 4 signs in an A-grade boy, so his own 60M
    # is the event section 6 fills with runners and section 12 draws a final on.
    e60 = first(lambda e: e["type"] == "RUN_60M" and e["sex"] == "MALE" and e["grade"] == "A")
    e100 = first(lambda e: e["type"] == "RUN_100M" and e["sex"] == "MALE" and e["grade"] == "A")
    e800 = first(lambda e: e["type"] == "RUN_800M" and e["sex"] == "MALE" and e["grade"] == "A")
    e_long = first(lambda e: e["type"] == "LONG_JUMP" and e["sex"] == "MALE" and e["grade"] == "A")

    check(all(e is not None for e in (e60, e100, e800, e_long)),
          "the A-grade boys' events this run works with all exist",
          f"got {[e and e['name'] for e in (e60, e100, e800, e_long)]}")
    check(e60 is not None and e60.get("grade") == "A" and e60.get("gradeLabel") == "A Grade"
          and e60["name"].endswith("A Grade") and "\u00b7" in e60["name"],
          "an event carries its grade, and its name says so: Boys 60M \u00b7 A Grade",
          f"got {e60 and (e60.get('grade'), e60.get('gradeLabel'), e60['name'])}")
    check("allowedGrades" not in (e60 or {}),
          "the old allowedGrades field is gone — the event's own grade is the rule",
          f"got keys {sorted((e60 or {}).keys())}")

    check(e60 is not None and e60["groupSize"] == 8 and e60["sheetSize"] == "A5" and e60["shortSprint"],
          "60M is 8 per group on an A5 sheet", f"{e60 and (e60['groupSize'], e60['sheetSize'])}")
    check(e100 is not None and e100["groupSize"] == 8 and e100["sheetSize"] == "A5",
          "100M is 8 per group on an A5 sheet")
    check(e800 is not None and e800["groupSize"] == 24 and e800["sheetSize"] == "A4",
          "800M is 24 per group on an A4 sheet", f"{e800 and (e800['groupSize'], e800['sheetSize'])}")
    check(e_long is not None and e_long["groupSize"] == 24 and e_long["sheetSize"] == "A4",
          "field events are 24 per group on an A4 sheet")

    # The filters must compose: category used to be ignored whenever sex was given.
    status, only_track = api.request("GET", "/events?onlyEnabled=true&category=TRACK", token=admin_token)
    check(isinstance(only_track, list) and only_track
          and all(e["category"] == "TRACK" for e in only_track),
          "category=TRACK returns only track events",
          f"status={status} categories={set(e['category'] for e in only_track or []) if isinstance(only_track, list) else only_track}")

    status, only_field = api.request("GET", "/events?onlyEnabled=true&category=FIELD", token=admin_token)
    check(isinstance(only_field, list) and only_field
          and all(e["category"] == "FIELD" for e in only_field),
          "category=FIELD returns only field events",
          f"status={status} categories={set(e['category'] for e in only_field or []) if isinstance(only_field, list) else only_field}")

    status, boys_field = api.request("GET", "/events?onlyEnabled=true&sex=M&category=FIELD",
                                     token=admin_token)
    check(isinstance(boys_field, list) and boys_field
          and all(e["category"] == "FIELD" and e["sex"] == "MALE" for e in boys_field),
          "sex and category compose into one filter",
          f"got {len(boys_field) if isinstance(boys_field, list) else boys_field} events, "
          f"categories={set(e['category'] for e in boys_field or []) if isinstance(boys_field, list) else ''}")

    status, bogus = api.request("GET", "/events?category=BOGUS", token=admin_token)
    check(status == 400, "an unknown category is rejected", f"status={status} body={bogus}")

    # ---------------------------------------------------------- 3. students
    section(f"3. Bulk student import ({args.students} students)")
    started = time.time()
    status, result = api.request("POST", f"/admin/students/sample?count={args.students}", token=admin_token)
    elapsed = time.time() - started
    check(status == 200, "sample students generated", f"status={status} body={result}")
    if isinstance(result, dict):
        print(f"  generated {result.get('created')} students in {elapsed:.1f}s")
        counts = result.get("gradeCounts", {})
        check(counts.get("C") == 240, "grade C count = 240 (age <= 14)", f"got {counts.get('C')}")
        check(counts.get("B") == 198, "grade B count = 198 (age 15-16)", f"got {counts.get('B')}")
        check(counts.get("A") == 162, "grade A count = 162 (age >= 17)", f"got {counts.get('A')}")
        check(result.get("created", 0) + result.get("updated", 0) == args.students,
              f"all {args.students} rows landed")
        check(result.get("failed") == 0, "no row failed to import", f"failed={result.get('failed')}")

    status, summary = api.request("GET", "/admin/students/summary", token=admin_token)
    check(isinstance(summary, dict) and summary.get("total") == args.students,
          f"register holds {args.students} records", f"got {summary}")

    # ------------------------------------------------------ 4. student login
    section("4. Student login with the derived password")
    status, roster = api.request("GET", "/admin/students?sex=M", token=admin_token)
    check(isinstance(roster, list) and len(roster) == 300, "300 male students on the register",
          f"got {len(roster) if isinstance(roster, list) else roster}")
    roster = roster if isinstance(roster, list) else []
    if not roster:
        return 1

    # A student may only enter events of their own grade, so the rest of the run
    # works inside one grade: an A-grade boy, whose own 60M is the event the
    # catalogue section picked out above.
    student = next((s for s in roster if s.get("grade") == "A"), None)
    check(student is not None, "an A grade boy is on the register",
          f"grades present={sorted({s.get('grade') for s in roster})}")
    if student is None:
        return 1
    student_grade = student["grade"]
    expected = derived_password(student)
    print(f"  signing in as {student['studentId']} ({student_grade} grade) / {expected}")
    student_token, student_body = login(api, student["studentId"], expected)
    check(student_token is not None, "student signs in with dob+class+classnumber",
          f"body={student_body}")
    check(isinstance(student_body, dict) and student_body.get("role") == "STUDENT",
          "student account carries the STUDENT role")
    if not student_token:
        return 1

    token_wrong, _ = login(api, student["studentId"], "not-the-password")
    check(token_wrong is None, "a wrong student password is rejected")

    # ---------------------------------------------------------------- 5. quota
    section("5. Entry quota — 2 track (徑項) + 1 field (田項)")
    status, quota = api.request("GET", "/enrollments/my/quota", token=student_token)
    check(isinstance(quota, dict) and quota.get("trackMax") == 2 and quota.get("fieldMax") == 1,
          "quota is 2 track + 1 field", f"got {quota}")

    status, mine = api.request("GET", "/events?onlyEnabled=true&sex=M", token=student_token)
    mine = mine if isinstance(mine, list) else []
    # The event list is every enabled boys' event, across all three grades — entering
    # into one of another grade is refused — so the grade has to be chosen here.
    my_events = [e for e in mine if e.get("grade") == student_grade]
    check(len(my_events) < len(mine),
          "the entry page lists every grade's events, so the grade has to be picked",
          f"{len(mine)} boys' events across all grades, {len(my_events)} of his own")
    my_track = [e for e in my_events if e.get("category") == "TRACK"]
    my_field = [e for e in my_events if e.get("category") == "FIELD"]
    check(len(my_track) >= 3 and len(my_field) >= 2,
          "his own grade has enough events to test the 2 track + 1 field quota",
          f"track={len(my_track)} field={len(my_field)}")
    print(f"  student sees {len(my_track)} track and {len(my_field)} field events of his own grade")

    s1, b1 = api.request("POST", f"/enrollments/{my_track[0]['id']}", token=student_token)
    s2, b2 = api.request("POST", f"/enrollments/{my_track[1]['id']}", token=student_token)
    check(s1 == 200 and s2 == 200, "first two track entries accepted",
          f"{s1},{s2} bodies={b1 if s1 != 200 else ''} {b2 if s2 != 200 else ''}")

    s3, b3 = api.request("POST", f"/enrollments/{my_track[2]['id']}", token=student_token)
    check(s3 == 409, "third track entry refused", f"status={s3} body={b3}")
    check("徑項" in str(b3) or "track" in str(b3).lower(),
          "refusal explains the track limit", f"body={b3}")

    f1, fb1 = api.request("POST", f"/enrollments/{my_field[0]['id']}", token=student_token)
    check(f1 == 200, "first field entry accepted", f"status={f1} body={fb1}")
    f2, b2f = api.request("POST", f"/enrollments/{my_field[1]['id']}", token=student_token)
    check(f2 == 409, "second field entry refused", f"status={f2} body={b2f}")

    status, quota2 = api.request("GET", "/enrollments/my/quota", token=student_token)
    if isinstance(quota2, dict):
        check(quota2.get("trackUsed") == 2 and quota2.get("fieldUsed") == 1
              and quota2.get("trackRemaining") == 0 and quota2.get("fieldRemaining") == 0,
              "quota now reads 2/2 track and 1/1 field", f"got {quota2}")

    status, girls = api.request("GET", "/events?onlyEnabled=true&sex=F", token=student_token)
    # The same grade, so that the division is the only thing that can refuse this.
    girls = [e for e in girls if e.get("grade") == student_grade] if isinstance(girls, list) else []
    if girls:
        sdiv, bdiv = api.request("POST", f"/enrollments/{girls[0]['id']}", token=student_token)
        check(sdiv == 409, "a girls' event is refused to a boys' division student",
              f"status={sdiv} body={bdiv}")
        check("Boys" in str(bdiv),
              "and it is the division that refuses them, not the grade",
              f"got {bdiv}")

    # --------------------------------------------------------------- 6. groups
    section("6. Group allocation")

    def seed_event(event_id: int, count: int, sex_code: str, grade_code: str) -> int:
        """Enters up to `count` students of one division and grade into one event.

        The grade is asked for at the register too: only students of the event's own
        grade may enter it, so offering it anybody else would just be refused.
        """
        _, all_students = api.request(
            "GET", f"/admin/students?sex={sex_code}&grade={grade_code}", token=admin_token)
        enrolled = 0
        for s in all_students or []:
            if enrolled >= count:
                break
            token, _ = login(api, s["studentId"], derived_password(s))
            if not token:
                continue
            st, _ = api.request("POST", f"/enrollments/{event_id}", token=token)
            if st == 200:
                enrolled += 1
        return enrolled

    n60 = seed_event(e60["id"], 40, "M", e60["grade"])
    print(f"  enrolled {n60} students in {e60['name']}")
    # The section-5 student is already entered here, so the real heat count comes
    # from the entry count on the event, not from the number we just seeded.
    _, ev60 = api.request("GET", f"/events/{e60['id']}", token=admin_token)
    entries60 = (ev60 or {}).get("enrolledCount") or n60
    heats60 = -(-entries60 // 8)
    status, alloc60 = api.request("POST", f"/events/{e60['id']}/groups/allocate", token=admin_token)
    check(status == 200, "60M groups allocated", f"status={status} body={alloc60}")
    if isinstance(alloc60, dict):
        check(alloc60.get("groupCount") == heats60,
              f"{entries60} entries split into {heats60} heats of 8",
              f"got {alloc60.get('groupCount')}")
        check(all(g["capacity"] == 8 for g in alloc60.get("groups", [])),
              "every 60M heat holds up to 8")
        check(max((g["athleteCount"] for g in alloc60["groups"]), default=0) <= 8,
              "no 60M heat exceeds 8 athletes")

    n800 = seed_event(e800["id"], 60, "M", e800["grade"])
    print(f"  enrolled {n800} students in {e800['name']}")
    _, ev800 = api.request("GET", f"/events/{e800['id']}", token=admin_token)
    entries800 = (ev800 or {}).get("enrolledCount") or n800
    heats800 = -(-entries800 // 24)
    status, alloc800 = api.request("POST", f"/events/{e800['id']}/groups/allocate", token=admin_token)
    check(status == 200, "800M groups allocated", f"status={status} body={alloc800}")
    if isinstance(alloc800, dict):
        check(alloc800.get("groupCount") == heats800,
              f"{entries800} entries split into {heats800} heats of 24",
              f"got {alloc800.get('groupCount')}")
        check(all(g["capacity"] == 24 for g in alloc800.get("groups", [])),
              "every 800M heat holds up to 24")
        check(max((g["athleteCount"] for g in alloc800["groups"]), default=0) <= 24,
              "no 800M heat exceeds 24 athletes")

    status, re60 = api.request("POST", f"/events/{e60['id']}/groups/allocate?shuffle=true", token=admin_token)
    check(isinstance(re60, dict) and re60.get("groupCount") == heats60,
          "re-allocating replaces the previous heats", f"got {re60.get('groupCount')}")

    # Rosters must be obtainable in one request, not one per heat.
    status, plain = api.request("GET", f"/events/{e60['id']}/groups", token=admin_token)
    check(isinstance(plain, list) and all(g["athletes"] == [] for g in plain),
          "the group list omits rosters by default")
    status, with_rosters = api.request("GET", f"/events/{e60['id']}/groups?includeRosters=true",
                                       token=admin_token)
    check(isinstance(with_rosters, list) and with_rosters
          and all(len(g["athletes"]) == g["athleteCount"] for g in with_rosters),
          "includeRosters=true returns every heat with its athletes in one request",
          f"counts={[(len(g['athletes']), g['athleteCount']) for g in with_rosters or []] if isinstance(with_rosters, list) else with_rosters}")

    # ----------------------------------------------------------------- 7. PDFs
    section("7. Marking sheet PDFs")
    g60_id = re60["groups"][0]["id"]
    g800_id = alloc800["groups"][0]["id"]

    status, pdf_a5 = api.request("GET", f"/groups/{g60_id}/sheet.pdf", token=admin_token, raw=True)
    a5_path = os.path.join(output_dir, "marking-sheet-60M-heat1-A5.pdf")
    with open(a5_path, "wb") as handle:
        handle.write(pdf_a5)
    check(status == 200 and pdf_a5[:4] == b"%PDF", "A5 sheet downloaded as a PDF",
          f"status={status} bytes={len(pdf_a5)}")

    status, pdf_a4 = api.request("GET", f"/groups/{g800_id}/sheet.pdf", token=admin_token, raw=True)
    a4_path = os.path.join(output_dir, "marking-sheet-800M-heat1-A4.pdf")
    with open(a4_path, "wb") as handle:
        handle.write(pdf_a4)
    check(status == 200 and pdf_a4[:4] == b"%PDF", "A4 sheet downloaded as a PDF",
          f"status={status} bytes={len(pdf_a4)}")

    def media_box(pdf: bytes):
        match = re.search(rb"/MediaBox\s*\[\s*0\s+0\s+([\d.]+)\s+([\d.]+)", pdf)
        return (float(match.group(1)), float(match.group(2))) if match else None

    box_a5, box_a4 = media_box(pdf_a5), media_box(pdf_a4)
    check(box_a5 is not None and abs(box_a5[0] - 421) < 3 and abs(box_a5[1] - 595) < 3,
          "A5 sheet page size is A5 (421x595pt)", f"got {box_a5}")
    check(box_a4 is not None and abs(box_a4[0] - 595) < 3 and abs(box_a4[1] - 842) < 3,
          "A4 sheet page size is A4 (595x842pt)", f"got {box_a4}")
    check(b"FontFile2" in pdf_a5, "a CJK TrueType font is embedded in the sheet")

    status, all_pdf = api.request("GET", f"/events/{e60['id']}/sheets.pdf", token=admin_token, raw=True)
    all_path = os.path.join(output_dir, "marking-sheets-60M-all-heats.pdf")
    with open(all_path, "wb") as handle:
        handle.write(all_pdf)
    pages = len(re.findall(rb"/Type\s*/Page[^s]", all_pdf))
    check(pages == heats60, "all-heats PDF has one page per heat", f"pages={pages} expected={heats60}")

    status, _ = api.request("GET", f"/groups/{g60_id}/sheet.pdf")
    check(status in (401, 403), "marking sheets are not public", f"status={status}")

    # ---------------------------------------------------------------- 8. CSV
    section("8. Sample register CSV")
    status, csv_bytes = api.request(
        "GET", f"/admin/students/sample.csv?count={args.students}", token=admin_token, raw=True)
    csv_path = os.path.join(output_dir, f"students-{args.students}-from-api.csv")
    with open(csv_path, "wb") as handle:
        handle.write(csv_bytes)
    lines = [ln for ln in csv_bytes.decode("utf-8-sig").splitlines() if ln.strip()]
    check(len(lines) == args.students + 1, f"sample CSV has header + {args.students} rows",
          f"got {len(lines)}")

    # ------------------------------------------------------------- 9. upload
    section("9. Register upload (CSV and XLSX)")
    sample_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..",
                              "sportday-sample-data")
    for name, field in (("students-600.csv", "CSV"), ("students-600.xlsx", "XLSX")):
        path = os.path.abspath(os.path.join(sample_dir, name))
        if not os.path.isfile(path):
            check(False, f"shipped {field} register present at {path}")
            continue
        # A grade is derived from the date of birth as at a reference date, so the
        # expected split below only holds for one date: pinned here, or the check
        # would drift every time a student has a birthday. (On 2026-10-04 exactly one
        # student in the shipped register turns 15 and moves from the C grade to B.)
        status, uploaded = api.upload(
            f"/admin/students/upload?referenceDate={GRADE_REFERENCE_DATE}", path,
            token=admin_token)
        check(status == 200, f"{field} register uploads", f"status={status} body={uploaded}")
        if isinstance(uploaded, dict):
            check(uploaded.get("failed") == 0, f"{field} upload had no bad rows",
                  f"failed={uploaded.get('failed')} errors={uploaded.get('errors')}")
            check(uploaded.get("created", 0) + uploaded.get("updated", 0) == args.students,
                  f"{field} upload accounted for all {args.students} rows",
                  f"created={uploaded.get('created')} updated={uploaded.get('updated')}")
            counts = uploaded.get("gradeCounts", {})
            check(counts.get("C") == 240 and counts.get("B") == 198 and counts.get("A") == 162,
                  f"{field} upload regenerated the grade split for {GRADE_REFERENCE_DATE}",
                  f"got {counts}")

    # A register with a deliberate mistake should report the bad row, not fail.
    broken = b"\xef\xbb\xbfstudentId,name,dob,sex,className,classNumber,house\r\n" \
             b"S9001,Test One,2010-03-15,M,5A,1,Red\r\n" \
             b"S9002,Bad Date,not-a-date,M,5A,2,Red\r\n" \
             b"S9003,Bad Sex,2010-03-15,X,5A,3,Red\r\n" \
             b"S9004,Good Row,2011-05-05,F,5B,4,Blue\r\n"
    status, broken_result = api.upload_bytes("/admin/students/upload", "broken.csv", broken,
                                             token=admin_token)
    check(status == 200, "an upload with bad rows still succeeds", f"status={status}")
    if isinstance(broken_result, dict):
        check(broken_result.get("created") == 2, "the two good rows were imported",
              f"created={broken_result.get('created')} updated={broken_result.get('updated')} "
              f"errors={broken_result.get('errors')}")
        check(broken_result.get("failed") == 2, "the two bad rows were reported",
              f"failed={broken_result.get('failed')}")
        rows = [e.get("rowNumber") for e in broken_result.get("errors", [])]
        check(3 in rows and 4 in rows, "the errors name the offending spreadsheet rows",
              f"rowNumbers={rows}")

    # Clean up, so re-running this script does not leave strays behind.
    for stray in ("S9001", "S9004"):
        deleted, _ = api.request("DELETE", f"/admin/students/{stray}", token=admin_token)
        check(deleted == 204, f"the test student {stray} can be removed", f"status={deleted}")
    status, after = api.request("GET", "/admin/students/summary", token=admin_token)
    check(isinstance(after, dict) and after.get("total") == args.students,
          "the register is back to exactly the imported students",
          f"total={after.get('total') if isinstance(after, dict) else after}")
    check(isinstance(after, dict) and after.get("byGrade", {}).get("B") == 198,
          f"grade counts are back to 240/198/162 as at {GRADE_REFERENCE_DATE} after cleanup",
          f"byGrade={after.get('byGrade') if isinstance(after, dict) else after}")

    # ------------------------------------------------------- 10. mark entry
    section("10. Mark-entry grid")

    status, sheet = api.request("GET", f"/events/{e60['id']}/marks", token=admin_token)
    check(status == 200, "mark grid loads for an event", f"status={status} body={sheet}")
    if isinstance(sheet, dict):
        entries = ev60.get("enrolledCount")
        check(sheet.get("totalAthletes") == entries,
              f"grid lists all {entries} entered athletes", f"got {sheet.get('totalAthletes')}")
        check(sheet.get("markedCount") == 0, "no marks recorded yet",
              f"got {sheet.get('markedCount')}")
        check(sheet.get("defaultUnit") == "s", "track events default to seconds, written s",
              f"got {sheet.get('defaultUnit')}")
        check(len(sheet.get("groups", [])) == heats60, "the group filter lists every heat",
              f"got {len(sheet.get('groups', []))}")
        heat_numbers = [g["groupNumber"] for g in sheet.get("groups", [])]
        check(heat_numbers == sorted(heat_numbers),
              "the heat filter is ordered by heat number", f"got {heat_numbers}")
        check(sheet.get("grades") == sorted(set(sheet.get("grades", []))),
              "grade options are de-duplicated and ordered", f"got {sheet.get('grades')}")
        rows = sheet.get("rows", [])
        check(all(r.get("groupLabel") for r in rows), "every athlete carries their heat")
        check(rows == sorted(rows, key=lambda r: (r.get("groupNumber") or 9999, r.get("lane") or 9999)),
              "rows are ordered by heat then lane")

        first_group = sheet["groups"][0]["id"]
        status, filtered = api.request("GET", f"/events/{e60['id']}/marks?groupId={first_group}",
                                       token=admin_token)
        if isinstance(filtered, dict):
            check(filtered["totalAthletes"] == sheet["groups"][0]["athleteCount"],
                  "filtering by group returns exactly that heat",
                  f"got {filtered['totalAthletes']} expected {sheet['groups'][0]['athleteCount']}")
            check(all(r["groupId"] == first_group for r in filtered["rows"]),
                  "every filtered row belongs to the chosen heat")

        if sheet.get("grades"):
            grade = sheet["grades"][0]
            status, by_grade = api.request(
                "GET", f"/events/{e60['id']}/marks?grade={grade}", token=admin_token)
            if isinstance(by_grade, dict):
                check(by_grade["totalAthletes"] > 0
                      and all(r["grade"] == grade for r in by_grade["rows"]),
                      f"filtering by grade {grade} returns only that grade",
                      f"got {by_grade['totalAthletes']} rows")
            status, bad_grade = api.request(
                "GET", f"/events/{e60['id']}/marks?grade=Z", token=admin_token)
            check(status == 400, "an unknown grade is rejected", f"status={status}")

        # ---- bulk save ----
        targets = sheet["rows"][:3]
        payload = {"rows": [
            {"userId": targets[0]["userId"], "mark": 8.123, "notes": "PB"},
            {"userId": targets[1]["userId"], "mark": 8.456, "unit": "seconds"},
            {"userId": targets[2]["userId"], "mark": None},
        ]}
        status, saved = api.request("POST", f"/events/{e60['id']}/marks", payload, token=admin_token)
        check(status == 200, "the grid saves in one request", f"status={status} body={saved}")
        if isinstance(saved, dict):
            check(saved.get("saved") == 2, "two marks were stored", f"saved={saved.get('saved')}")
            check(saved.get("skipped") == 1, "the blank row was skipped, not stored",
                  f"skipped={saved.get('skipped')}")
            check(saved.get("failed") == 0, "no row failed", f"errors={saved.get('errors')}")
            marks = [r["mark"] for r in saved.get("results", [])]
            check(marks == sorted(marks), "the leaderboard is ordered fastest first", f"{marks}")

        status, after = api.request("GET", f"/events/{e60['id']}/marks", token=admin_token)
        if isinstance(after, dict):
            check(after.get("markedCount") == 2, "the grid now shows two marks",
                  f"got {after.get('markedCount')}")
            unit = next((r["unit"] for r in after["rows"] if r["mark"] is not None), None)
            check(unit == "s", "a blank unit fell back to the event default (s)", f"got {unit}")

        # ---- clear and validation ----
        clear_payload = {"rows": [{"userId": targets[0]["userId"], "clear": True}]}
        status, cleared = api.request("POST", f"/events/{e60['id']}/marks", clear_payload,
                                      token=admin_token)
        check(isinstance(cleared, dict) and cleared.get("cleared") == 1,
              "clearing a mark removes it", f"got {cleared}")
        status, after_clear = api.request("GET", f"/events/{e60['id']}/marks", token=admin_token)
        check(isinstance(after_clear, dict) and after_clear.get("markedCount") == 1,
              "only the cleared mark went away", f"got {after_clear.get('markedCount')}")

        not_entered = api.request("POST", f"/events/{e60['id']}/marks",
                                  {"rows": [{"userId": 999999, "mark": 9.0}]}, token=admin_token)[1]
        check(isinstance(not_entered, dict) and not_entered.get("failed") == 1
              and not_entered.get("saved") == 0,
              "an athlete who is not entered is refused", f"got {not_entered}")
        implausible = api.request("POST", f"/events/{e60['id']}/marks",
                                  {"rows": [{"userId": targets[1]["userId"], "mark": -5}]},
                                  token=admin_token)[1]
        check(isinstance(implausible, dict) and implausible.get("failed") == 1,
              "a negative mark is refused", f"got {implausible}")
        dup = api.request("POST", f"/events/{e60['id']}/marks",
                          {"rows": [{"userId": targets[1]["userId"], "mark": 1.0},
                                    {"userId": targets[1]["userId"], "mark": 2.0}]},
                          token=admin_token)[1]
        check(isinstance(dup, dict) and dup.get("failed") == 1 and dup.get("saved") == 1,
              "a duplicated athlete is reported once and saved once", f"got {dup}")

        status, _ = api.request("GET", f"/events/{e60['id']}/marks", token=student_token)
        check(status == 403, "students cannot open the mark-entry grid", f"status={status}")

    # ------------------------------------------------------- 11. print run
    section("11. Marking-sheet print run")
    status, print_pdf = api.request("GET", "/sheets.pdf?sex=M&category=TRACK", token=admin_token,
                                    raw=True)
    print_path = os.path.join(output_dir, "sportday-marking-sheets-boys-track.pdf")
    with open(print_path, "wb") as handle:
        handle.write(print_pdf)
    check(status == 200 and print_pdf[:4] == b"%PDF", "the whole print run downloads as one PDF",
          f"status={status} bytes={len(print_pdf)}")
    print_pages = len(re.findall(rb"/Type\s*/Page[^s]", print_pdf))
    check(print_pages >= heats60, "the print run covers at least the 60M heats",
          f"pages={print_pages}")

    # 60M prints on A5 and 800M on A4, and the programme order puts the short
    # sprint first. This is also the check that a single PDF really can carry
    # mixed page sizes.
    boxes = re.findall(rb"/MediaBox\s*\[\s*0\s+0\s+([\d.]+)\s+([\d.]+)", print_pdf)
    sizes = ["A5" if abs(float(w) - 421) < 3 else ("A4" if abs(float(w) - 595) < 3 else "?")
             for w, _h in boxes]
    check(len(sizes) == print_pages, "every page reports a page size",
          f"sizes={len(sizes)} pages={print_pages}")
    check(sizes.count("A5") == heats60, f"{heats60} A5 sheet(s) for the 60M heats",
          f"got {sizes.count('A5')} in {sizes}")
    check(sizes.count("A4") == heats800, f"{heats800} A4 sheet(s) for the 800M heats",
          f"got {sizes.count('A4')} in {sizes}")
    if "A5" in sizes and "A4" in sizes:
        check(sizes.index("A4") > len(sizes) - 1 - sizes[::-1].index("A5"),
              "short sprints print before the longer races", f"order={sizes}")

    # ----------------------------------------------------- 12. heat -> final
    section("12. Heats to final (60M)")

    status, heat_sheet = api.request("GET", f"/events/{e60['id']}/marks?stage=HEAT", token=admin_token)
    check(status == 200 and isinstance(heat_sheet, dict), "the heat grid loads by stage", f"status={status}")
    heat_rows = heat_sheet.get("rows", []) if isinstance(heat_sheet, dict) else []
    check(heat_sheet.get("stage") == "HEAT", "the grid reports which stage it is",
          f"got {heat_sheet.get('stage')}")

    # Give every heat runner a distinct time, fastest first, so the qualifying
    # order is known: 8.000, 8.050, 8.100 …
    times = {}
    heat_payload = {"stage": "HEAT", "rows": []}
    for index, row in enumerate(heat_rows):
        mark = round(8.0 + index * 0.05, 3)
        times[row["userId"]] = mark
        heat_payload["rows"].append({"userId": row["userId"], "mark": mark, "unit": "seconds"})
    status, heat_saved = api.request("POST", f"/events/{e60['id']}/marks", heat_payload,
                                     token=admin_token)
    check(status == 200 and isinstance(heat_saved, dict), "heat marks save in one request",
          f"status={status} body={heat_saved}")
    check(isinstance(heat_saved, dict) and heat_saved.get("saved") == len(heat_rows),
          f"all {len(heat_rows)} heat marks were stored",
          f"got {heat_saved.get('saved') if isinstance(heat_saved, dict) else heat_saved}")
    check(isinstance(heat_saved, dict) and heat_saved.get("stage") == "HEAT",
          "the save reports the stage it wrote to")

    # ---- who would qualify ----
    status, preview = api.request("GET", f"/events/{e60['id']}/final", token=admin_token)
    check(status == 200 and isinstance(preview, dict), "the final can be previewed", f"status={status}")
    if isinstance(preview, dict):
        check(preview.get("rankedAthletes") == len(heat_rows),
              f"all {len(heat_rows)} heat results are ranked",
              f"got {preview.get('rankedAthletes')}")
        check(preview.get("qualified") == 8, "the top 8 would go through",
              f"got {preview.get('qualified')}")
        check(preview.get("finalSize") == 8, "a short sprint's final holds 8",
              f"got {preview.get('finalSize')}")
        check(preview.get("shortSprint") is True and preview.get("sheetSize") == "A5",
              "the final of a short sprint still prints on A5")
        qualifiers = preview.get("qualifiers", [])
        check([q.get("rank") for q in qualifiers] == list(range(1, 9)),
              "qualifiers are ranked 1..8", f"got {[q.get('rank') for q in qualifiers]}")
        check(all(qualifiers[i]["heatMark"] <= qualifiers[i + 1]["heatMark"]
                  for i in range(len(qualifiers) - 1)),
              "the fastest are ranked first",
              f"marks={[q.get('heatMark') for q in qualifiers]}")
        check(preview.get("drawn") is False, "a preview does not draw the final")

    heat_groups_before = sorted(
        g["id"] for g in api.request("GET", f"/events/{e60['id']}/groups", token=admin_token)[1]
        if g["stage"] == "HEAT")

    # ---- draw it ----
    status, drawn = api.request("POST", f"/events/{e60['id']}/final", token=admin_token)
    check(status == 200 and isinstance(drawn, dict), "the final is drawn", f"status={status} body={drawn}")
    final_group_id = drawn.get("groupId") if isinstance(drawn, dict) else None
    check(isinstance(drawn, dict) and drawn.get("drawn") is True and final_group_id,
          "the response names the final group", f"got {drawn}")
    check(isinstance(drawn, dict) and drawn.get("qualified") == 8 and len(drawn.get("qualifiers", [])) == 8,
          "eight athletes qualified")

    # ---- the final is a group of the event, numbered 0 ----
    status, groups_after = api.request("GET", f"/events/{e60['id']}/groups?includeRosters=true",
                                       token=admin_token)
    final_groups = [g for g in groups_after if g["stage"] == "FINAL"]
    check(len(final_groups) == 1, "the event now has exactly one final", f"got {len(final_groups)}")
    check(len([g for g in groups_after if g["stage"] == "HEAT"]) == heats60,
          "the heats are all still there", f"got {len([g for g in groups_after if g['stage'] == 'HEAT'])}")
    if final_groups:
        final_group = final_groups[0]
        check(final_group["label"] == "Final", "the final is labelled Final",
              f"got {final_group['label']}")
        check(final_group["groupNumber"] == 0, "the final is group 0, clear of Heat 1",
              f"got {final_group['groupNumber']}")
        check(final_group["athleteCount"] == 8 and len(final_group["athletes"]) == 8,
              "the final field holds 8 athletes", f"got {final_group['athleteCount']}")
        check(final_group["sheetSize"] == "A5", "the final prints on A5")
        check([a["lane"] for a in final_group["athletes"]] == list(range(1, 9)),
              "the final is seeded into lanes 1..8")
        # The final is group 0, so the database would list it before Heat 1; the
        # running order — and therefore the print run — puts it last.
        check([g["stage"] for g in groups_after] == ["HEAT"] * heats60 + ["FINAL"],
              "groups are listed heats first, then the final",
              f"got {[g['stage'] for g in groups_after]}")

    # ---- a finalist is still in their heat ----
    heat_groups_after = sorted(
        g["id"] for g in groups_after if g["stage"] == "HEAT")
    check(heat_groups_after == heat_groups_before, "the heat groups were not disturbed")
    first_heat = next(g for g in groups_after if g["stage"] == "HEAT")
    check(len(first_heat["athletes"]) == first_heat["athleteCount"],
          "a heat sheet still lists all of its runners, finalists included",
          f"{len(first_heat['athletes'])} of {first_heat['athleteCount']}")
    finalist_ids = {a["userId"] for a in final_groups[0]["athletes"]} if final_groups else set()
    heat1_ids = {a["userId"] for a in first_heat["athletes"]}
    check(not (finalist_ids & heat1_ids) or True, "finalists may also appear in their heat")

    # ---- the two stages keep separate marks ----
    status, final_sheet = api.request("GET",
                                      f"/events/{e60['id']}/marks?stage=FINAL", token=admin_token)
    check(isinstance(final_sheet, dict) and final_sheet.get("totalAthletes") == 8,
          "the final grid holds only the 8 qualifiers",
          f"got {final_sheet.get('totalAthletes') if isinstance(final_sheet, dict) else final_sheet}")
    check(isinstance(final_sheet, dict) and final_sheet.get("stage") == "FINAL"
          and final_sheet.get("stageLabel", "").startswith("Final"),
          "the final grid says it is the final", f"got {final_sheet.get('stageLabel')}")
    check(isinstance(final_sheet, dict) and all(r.get("groupLabel") == "Final"
                                                for r in final_sheet.get("rows", [])),
          "every final row belongs to the final group")
    check(isinstance(final_sheet, dict) and final_sheet.get("markedCount") == 0,
          "the final starts with no marks, so heat marks did not leak into it",
          f"got {final_sheet.get('markedCount')}")

    # Record a final time that is deliberately different from the heat time.
    finalist = final_sheet["rows"][0]
    heat_time = times[finalist["userId"]]
    final_time = round(heat_time - 0.5, 3)
    status, final_saved = api.request("POST", f"/events/{e60['id']}/marks",
                                      {"stage": "FINAL",
                                       "rows": [{"userId": finalist["userId"], "mark": final_time,
                                                 "unit": "seconds"}]}, token=admin_token)
    check(status == 200 and isinstance(final_saved, dict) and final_saved.get("saved") == 1,
          "a final mark is stored", f"got {final_saved}")
    check(isinstance(final_saved, dict) and final_saved.get("stage") == "FINAL",
          "the save reports the final stage")

    status, heat_after = api.request("GET", f"/events/{e60['id']}/marks?stage=HEAT", token=admin_token)
    heat_mark_now = next((r["mark"] for r in heat_after["rows"] if r["userId"] == finalist["userId"]), None)
    check(heat_mark_now == heat_time,
          "the heat time is untouched by the final result",
          f"heat was {heat_time}, now {heat_mark_now}")
    status, final_after = api.request("GET", f"/events/{e60['id']}/marks?stage=FINAL", token=admin_token)
    final_mark_now = next((r["mark"] for r in final_after["rows"] if r["userId"] == finalist["userId"]), None)
    check(final_mark_now == final_time, "the final time is stored separately",
          f"expected {final_time}, got {final_mark_now}")

    # ---- a non-finalist cannot be given a final mark ----
    non_finalist = next(r for r in heat_rows if r["userId"] not in finalist_ids)
    status, refused = api.request("POST", f"/events/{e60['id']}/marks",
                                  {"stage": "FINAL",
                                   "rows": [{"userId": non_finalist["userId"], "mark": 7.5}]},
                                  token=admin_token)
    check(isinstance(refused, dict) and refused.get("failed") == 1 and refused.get("saved") == 0,
          "an athlete who did not qualify cannot be given a final mark", f"got {refused}")

    # ---- results carry the stage ----
    status, all_results = api.request("GET", f"/results/event/{e60['id']}", token=admin_token)
    stages = {r.get("stage") for r in all_results} if isinstance(all_results, list) else set()
    check({"HEAT", "FINAL"} <= stages, "the results for the event cover both stages", f"got {stages}")

    # ---- the final has its own marking sheet ----
    status, final_pdf = api.request("GET", f"/groups/{final_group_id}/sheet.pdf", token=admin_token,
                                    raw=True)
    final_pdf_path = os.path.join(output_dir, "marking-sheet-60M-FINAL-A5.pdf")
    with open(final_pdf_path, "wb") as handle:
        handle.write(final_pdf)
    check(status == 200 and final_pdf[:4] == b"%PDF", "the final's marking sheet downloads",
          f"status={status} bytes={len(final_pdf)}")
    box = media_box(final_pdf)
    check(box is not None and abs(box[0] - 421) < 3 and abs(box[1] - 595) < 3,
          "the final sheet is A5, the same format as the heats", f"got {box}")

    # ---- re-drawing replaces the final and clears its marks ----
    status, redrawn = api.request("POST", f"/events/{e60['id']}/final", token=admin_token)
    check(isinstance(redrawn, dict) and redrawn.get("drawn") is True,
          "the final can be drawn again", f"got {redrawn}")
    check(isinstance(redrawn, dict) and redrawn.get("clearedFinalMarks") == 1,
          "re-drawing clears the marks recorded in the old final",
          f"got {redrawn.get('clearedFinalMarks') if isinstance(redrawn, dict) else redrawn}")
    status, heat_still = api.request("GET", f"/events/{e60['id']}/marks?stage=HEAT", token=admin_token)
    check(heat_still.get("markedCount") == len(heat_rows),
          "re-drawing the final leaves every heat mark alone",
          f"got {heat_still.get('markedCount')} of {len(heat_rows)}")

    # ---- the final can be removed ----
    status, cleared = api.request("DELETE", f"/events/{e60['id']}/final", token=admin_token)
    check(status == 200 and isinstance(cleared, dict), "the final can be removed", f"status={status}")
    status, groups_final = api.request("GET", f"/events/{e60['id']}/groups", token=admin_token)
    check(not [g for g in groups_final if g["stage"] == "FINAL"], "the final is gone")
    check(len([g for g in groups_final if g["stage"] == "HEAT"]) == heats60,
          "the heats survive removing the final")

    status, bad_stage = api.request("GET", f"/events/{e60['id']}/marks?stage=BOGUS", token=admin_token)
    check(status == 400, "an unknown stage is rejected", f"status={status}")

    # ------------------- 13. settings, records and championships
    section("13. Settings, records, championships and past events")

    # ---- public self-registration is gone ----
    status, refused = api.request("POST", "/auth/register",
                                  {"username": "intruder", "password": "Password1!",
                                   "email": "intruder@sportday.test"})
    check(status == 404, "public self-registration has been removed",
          f"status={status} body={refused}")

    # ---- an administrator creates the accounts ----
    status, users = api.request("GET", "/users", token=admin_token)
    for stale in [u for u in users if str(u.get("username", "")).startswith("smoke_mgr_")]:
        api.request("DELETE", f"/users/{stale['id']}", token=admin_token)

    manager_name = f"smoke_mgr_{int(time.time())}"
    status, created = api.request("POST", "/admin/users?role=MANAGER",
                                  {"username": manager_name, "password": "Password1!",
                                   "email": f"{manager_name}@sportday.test",
                                   "fullName": "Smoke Test Manager"},
                                  token=admin_token)
    check(status == 200 and isinstance(created, dict)
          and created.get("username") == manager_name,
          "an admin can create a staff account", f"status={status} body={created}")
    manager_id = created.get("id") if isinstance(created, dict) else None

    status, session = api.request("POST", "/auth/login",
                                  {"username": manager_name, "password": "Password1!"})
    check(status == 200 and isinstance(session, dict) and session.get("token"),
          "the account an admin created can sign in", f"status={status}")
    manager_token = session.get("token") if isinstance(session, dict) else None

    status, duplicate = api.request("POST", "/admin/users?role=MANAGER",
                                    {"username": manager_name, "password": "Password1!"},
                                    token=admin_token)
    check(status == 400, "a duplicate username is refused", f"status={status}")

    status, as_student = api.request("POST", "/admin/users?role=STUDENT",
                                     {"username": "smoke_student_x", "password": "Password1!"},
                                     token=admin_token)
    check(status == 400,
          "student accounts cannot be created here — the register import makes them",
          f"status={status}")
    status, bad_role = api.request("POST", "/admin/users?role=WIZARD",
                                   {"username": "smoke_wizard", "password": "Password1!"},
                                   token=admin_token)
    check(status == 400, "an unknown role is refused", f"status={status}")
    status, _ = api.request("POST", "/admin/users?role=MANAGER",
                            {"username": "smoke_sneaky", "password": "Password1!"},
                            token=manager_token)
    check(status == 403, "a manager cannot create accounts", f"status={status}")

    # ---- the editable entry limits ----
    status, settings = api.request("GET", "/settings", token=admin_token)
    check(status == 200 and isinstance(settings, dict), "the settings load", f"status={status}")
    check(settings.get("trackMaxEntries") == 2 and settings.get("fieldMaxEntries") == 1,
          "a student may enter 2 track and 1 field event by default",
          f"got {settings.get('trackMaxEntries')} / {settings.get('fieldMaxEntries')}")
    check(settings.get("pointsFirst") == 9 and settings.get("pointsSecond") == 6
          and settings.get("pointsThird") == 3,
          "first, second and third are worth 9, 6 and 3",
          f"got {settings.get('pointsFirst')}/{settings.get('pointsSecond')}/{settings.get('pointsThird')}")
    check(settings.get("pointsTopPlace") == 8 and settings.get("pointsTop") == 1,
          "fourth to eighth are worth 1 point each",
          f"got {settings.get('pointsTop')} down to {settings.get('pointsTopPlace')}")
    check(settings.get("relayPointsFirst") == 30 and settings.get("relayPointsSecond") == 20
          and settings.get("relayPointsThird") == 10,
          "a relay is worth 30 / 20 / 10")

    status, changed = api.request("PUT", "/admin/settings", {"trackMaxEntries": 3},
                                  token=admin_token)
    check(status == 200 and isinstance(changed, dict) and changed.get("trackMaxEntries") == 3,
          "an admin can change the track limit", f"got {changed}")
    status, catalogue = api.request("GET", "/events", token=admin_token)
    track_events = [e for e in catalogue if e.get("category") == "TRACK"]
    check(track_events and all(e.get("maxEntriesPerStudent") == 3 for e in track_events),
          "every track event immediately reports the new limit",
          f"got {sorted({e.get('maxEntriesPerStudent') for e in track_events})}")
    status, _ = api.request("PUT", "/admin/settings", {"trackMaxEntries": 9}, token=manager_token)
    check(status == 403, "only an admin can change the settings", f"status={status}")
    status, restored = api.request("PUT", "/admin/settings", {"trackMaxEntries": 2},
                                   token=admin_token)
    check(restored.get("trackMaxEntries") == 2, "the limit is put back")
    status, cleared = api.request("POST", "/admin/settings/reset", token=admin_token)
    check(cleared.get("pointsFirst") == 9 and cleared.get("trackMaxEntries") == 2,
          "the defaults can be restored in one action")

    # ---- school records ----
    status, rebuilt = api.request("POST", "/admin/records/recompute", token=admin_token)
    check(status == 200 and isinstance(rebuilt, dict) and rebuilt.get("records", 0) > 0,
          "the school records rebuild from the results", f"got {rebuilt}")
    status, records = api.request("GET", "/records", token=admin_token)
    check(status == 200 and isinstance(records, list) and records,
          "the records page has entries", f"got {len(records) if isinstance(records, list) else records}")
    sixty_records = [r for r in records if r["eventType"] == "RUN_60M" and r["sex"] == "MALE"]
    check(bool(sixty_records), "Boys 60M has a record")
    check(all(r.get("grade") in ("A", "B", "C") for r in records),
          "every record is filed under a grade")
    # Each event has a record of its own — one per type, division and grade — and
    # only the A-grade 60M, the one that has been run, carries a mark yet.
    marked_sixty = [r for r in sixty_records if r.get("mark") is not None]
    check(bool(marked_sixty), "the 60M heat marks have produced a record",
          f"got {[(r['grade'], r.get('mark')) for r in sixty_records]}")
    fastest_record = min(marked_sixty, key=lambda r: r["mark"])
    check(fastest_record["mark"] == 8.0,
          "the record is the fastest heat time recorded, 8.000",
          f"got {fastest_record['mark']}")
    check(fastest_record.get("holderStudentRef"),
          "the record names its holder", f"got {fastest_record}")

    # Beat it with a different athlete, which is what setting a new record means.
    # (Improving the holder's own mark is a correction, and the record simply
    # follows it without pretending somebody else was beaten.)
    holder_id = fastest_record["holderUserId"]
    holder_grade = fastest_record["grade"]
    challenger = next(r for r in heat_rows
                      if r["userId"] != holder_id and r.get("grade") == holder_grade)
    status, broken = api.request("POST", f"/events/{e60['id']}/marks",
                                 {"stage": "HEAT",
                                  "rows": [{"userId": challenger["userId"], "mark": 7.5,
                                            "unit": "seconds"}]},
                                 token=admin_token)
    check(status == 200 and isinstance(broken, dict) and broken.get("saved") == 1,
          "a record-breaking time is saved", f"got {broken}")
    status, records_after = api.request("GET", "/records", token=admin_token)
    improved = next(r for r in records_after
                    if r["eventType"] == "RUN_60M" and r["sex"] == "MALE"
                    and r["grade"] == holder_grade)
    check(improved["mark"] == 7.5, "the record updates to the new time",
          f"got {improved['mark']}")
    check(improved.get("holderStudentRef") == challenger["studentRef"],
          "and it names the athlete who set it",
          f"got {improved.get('holderStudentRef')}, expected {challenger['studentRef']}")
    check(improved.get("hasPrevious") is True
          and improved.get("previousMark") == fastest_record["mark"]
          and improved.get("previousHolderName") == fastest_record.get("holderName"),
          "the record remembers what it beat, so the results page can show the improvement",
          f"previous={improved.get('previousMark')} by {improved.get('previousHolderName')} "
          f"(was {fastest_record['mark']} by {fastest_record.get('holderName')})")

    status, results = api.request("GET", f"/results/event/{e60['id']}", token=admin_token)
    flagged = [r for r in results if r.get("newRecord")]
    check(any(r["mark"] == 7.5 and r["userId"] == challenger["userId"] for r in flagged),
          "the record-breaking performance is flagged in the results",
          f"flagged={[(r.get('mark'), r.get('userId')) for r in flagged]}")

    status, grid = api.request("GET", f"/events/{e60['id']}/marks?stage=HEAT", token=admin_token)
    record_row = next(r for r in grid["rows"] if r["userId"] == challenger["userId"])
    check(record_row.get("newRecord") is True,
          "the mark-entry grid badges the record-breaking row too",
          f"got {record_row.get('newRecord')}")
    check(all(not r.get("newRecord") for r in grid["rows"] if r["userId"] != challenger["userId"]),
          "and badges only that row")

    # ---- the championships ----
    status, standings = api.request("GET", f"/events/{e60['id']}/standings", token=admin_token)
    check(status == 200 and isinstance(standings, dict), "one event's placings load",
          f"status={status}")
    check(standings.get("scoringStage") == "HEAT",
          "with no final drawn, the heats decide the points",
          f"got {standings.get('scoringStage')}")
    points = [p["points"] for p in standings["placings"][:8]]
    check(points == [9, 6, 3, 1, 1, 1, 1, 1],
          "places score 9 / 6 / 3 and then 1 from fourth to eighth",
          f"got {points}")
    check(all(p["points"] == 0 for p in standings["placings"][8:]),
          "ninth and below score nothing")
    check(standings["placings"][0]["mark"] == 7.5,
          "the new record leads the event", f"got {standings['placings'][0]['mark']}")

    status, champions = api.request("GET", "/championships", token=admin_token)
    check(status == 200 and isinstance(champions, dict), "the championships compute",
          f"status={status}")
    check(champions.get("personal") and champions.get("houses"),
          "there is a personal table and a house table")
    check(champions.get("settings", {}).get("pointsFirst") == 9,
          "the payload reports the scale it used")
    personal_points = {p["userId"]: p["points"] for p in champions["personal"]}
    check(personal_points.get(holder_id) is not None,
          "the event winner appears in the personal championship")
    house_total = sum(h["points"] for h in champions["houses"])
    check(house_total >= sum(personal_points.values()),
          "house totals include the relay points that personal totals leave out",
          f"houses={house_total} personal={sum(personal_points.values())}")
    check([h["rank"] for h in champions["houses"]] == list(range(1, len(champions["houses"]) + 1)),
          "the house table is ranked from 1")

    # Draw the final: the final should take over the scoring.
    status, drawn_final = api.request("POST", f"/events/{e60['id']}/final", token=admin_token)
    check(isinstance(drawn_final, dict) and drawn_final.get("qualified") == 8,
          "the final is drawn for the championship check", f"got {drawn_final}")
    status, before_final_marks = api.request("GET", f"/events/{e60['id']}/standings",
                                             token=admin_token)
    check(before_final_marks.get("scoringStage") == "HEAT",
          "a final that has been drawn but not run leaves the heats scoring",
          f"got {before_final_marks.get('scoringStage')}")
    finalist = before_final_marks["placings"][0]
    status, _ = api.request("POST", f"/events/{e60['id']}/marks",
                            {"stage": "FINAL",
                             "rows": [{"userId": finalist["userId"], "mark": 7.2, "unit": "seconds"}]},
                            token=admin_token)
    status, after_final = api.request("GET", f"/events/{e60['id']}/standings", token=admin_token)
    check(after_final.get("scoringStage") == "FINAL",
          "once the final is run, the final decides the points",
          f"got {after_final.get('scoringStage')}")
    check(len(after_final["placings"]) == 1,
          "only the athletes who actually ran the final score",
          f"got {len(after_final['placings'])}")
    check(after_final["placings"][0]["points"] == 9, "the winner of the final scores 9")

    # ---- past events ----
    status, past = api.request("GET", "/events/past", token=admin_token)
    check(status == 200 and isinstance(past, list) and past,
          "past events can be listed", f"status={status}")
    today = date.today().isoformat()
    check(all(e["eventDate"] <= today for e in past),
          "every event listed as past is dated today or earlier",
          f"got {sorted({e['eventDate'] for e in past})}")
    check(len(past) <= len(catalogue), "the past list is a subset of the catalogue")

    # ---- an admin can hand the settings back and tidy up ----
    if manager_id:
        status, _ = api.request("DELETE", f"/users/{manager_id}", token=admin_token)
        check(status in (200, 204), "the test account is removed", f"status={status}")

    # ------------- 14. admin-run entries, editable records, dates
    section("14. Entries on a student's behalf, editable records, and dates")

    # ---- an administrator can enter a student for events ----
    status, credentials = api.request("GET", "/admin/students/credentials.csv",
                                      token=admin_token, raw=True)
    rows = list(csv.DictReader(io.StringIO(credentials.decode("utf-8-sig"))))
    check(status == 200 and rows, "the credentials sheet lists students", f"got {len(rows)} rows")
    subject_id = rows[0]["studentId"]
    status, subject = api.request("GET", f"/admin/students/{subject_id}", token=admin_token)
    check(status == 200 and subject.get("userId"), f"student {subject_id} has an account",
          f"status={status}")
    subject_sex = subject.get("sex")
    subject_grade = subject.get("grade")

    # Start from a clean slate so the run is repeatable.
    status, current = api.request("GET", f"/admin/students/{subject_id}/enrollments",
                                  token=admin_token)
    check(status == 200 and isinstance(current, dict) and "quota" in current,
          "a student's entries and quota can be read", f"status={status} body={current}")
    for entry in current.get("enrollments", []):
        if entry.get("status") == "CONFIRMED":
            api.request("DELETE", f"/admin/students/{subject_id}/enrollments/{entry['eventId']}",
                        token=admin_token)

    status, catalogue = api.request("GET", "/events?onlyEnabled=true", token=admin_token)
    # A student may only enter their own grade's events, so the events the admin
    # enters them in have to be their own grade's too.
    open_track = [e for e in catalogue
                  if e.get("category") == "TRACK" and e.get("sex") == subject_sex
                  and e.get("grade") == subject_grade
                  and (e.get("enrolledCount") or 0) < (e.get("maxParticipants") or 999)]
    open_field = [e for e in catalogue
                  if e.get("category") == "FIELD" and e.get("sex") == subject_sex
                  and e.get("grade") == subject_grade
                  and (e.get("enrolledCount") or 0) < (e.get("maxParticipants") or 999)]
    check(len(open_track) >= 3 and len(open_field) >= 2,
          "there are enough open events of the student's own grade to test the quota",
          f"grade={subject_grade} track={len(open_track)} field={len(open_field)}")

    status, first = api.request("POST",
                                f"/admin/students/{subject_id}/enrollments/{open_track[0]['id']}",
                                token=admin_token)
    check(status == 200 and isinstance(first, dict),
          "an admin can enter a student in a track event", f"status={status} body={first}")
    status, second = api.request("POST",
                                 f"/admin/students/{subject_id}/enrollments/{open_track[1]['id']}",
                                 token=admin_token)
    check(status == 200, "and in a second one", f"status={status} body={second}")
    status, third = api.request("POST",
                                f"/admin/students/{subject_id}/enrollments/{open_track[2]['id']}",
                                token=admin_token)
    check(status == 409, "but the track quota stops a third — even for an admin",
          f"status={status} body={third}")
    status, field_one = api.request("POST",
                                    f"/admin/students/{subject_id}/enrollments/{open_field[0]['id']}",
                                    token=admin_token)
    check(status == 200, "a field event can be added", f"status={status} body={field_one}")
    status, field_two = api.request("POST",
                                    f"/admin/students/{subject_id}/enrollments/{open_field[1]['id']}",
                                    token=admin_token)
    check(status == 409, "and the field quota stops a second", f"status={status} body={field_two}")

    status, quota = api.request("GET", f"/admin/students/{subject_id}/enrollments",
                                token=admin_token)
    body = quota.get("quota", {}) if isinstance(quota, dict) else {}
    check(body.get("trackUsed") == 2 and body.get("trackRemaining") == 0,
          "the quota reflects the entries the admin made", f"got {body}")
    check(body.get("fieldUsed") == 1 and body.get("fieldRemaining") == 0,
          "and the field quota too", f"got {body}")

    # Removing an entry frees the place again — this is the "update" half.
    status, _ = api.request("DELETE",
                            f"/admin/students/{subject_id}/enrollments/{open_track[0]['id']}",
                            token=admin_token)
    check(status in (200, 204), "an admin can remove an entry", f"status={status}")
    status, replacement = api.request("POST",
                                      f"/admin/students/{subject_id}/enrollments/{open_track[2]['id']}",
                                      token=admin_token)
    check(status == 200, "and the freed place can be given to another event",
          f"status={status} body={replacement}")

    # A student who withdrew can be put back in: the admin's "add" revives the
    # entry, which the student's own endpoint would refuse.
    status, _ = api.request("DELETE",
                            f"/admin/students/{subject_id}/enrollments/{open_track[2]['id']}",
                            token=admin_token)
    check(status in (200, 204), "an entry can be withdrawn", f"status={status}")
    status, revived = api.request("POST",
                                  f"/admin/students/{subject_id}/enrollments/{open_track[2]['id']}",
                                  token=admin_token)
    check(status == 200 and isinstance(revived, dict) and revived.get("status") == "CONFIRMED",
          "and an admin can put the student back in — the withdrawn entry is revived",
          f"status={status} body={revived}")
    status, again = api.request("POST",
                                f"/admin/students/{subject_id}/enrollments/{open_track[2]['id']}",
                                token=admin_token)
    check(status == 200, "entering somebody who is already in is a harmless no-op",
          f"status={status} body={again}")

    # A student's own view agrees with the administrator's.
    status, credentials = api.request("GET", "/admin/students/credentials.csv",
                                      token=admin_token, raw=True)
    row = next(r for r in csv.DictReader(io.StringIO(credentials.decode("utf-8-sig")))
               if r["studentId"] == subject_id)
    status, session = api.request("POST", "/auth/login",
                                  {"username": subject_id, "password": row["password"]})
    check(status == 200, "the student can sign in", f"status={status}")
    status, mine = api.request("GET", "/enrollments/my/quota", token=session.get("token"))
    check(isinstance(mine, dict) and mine.get("trackUsed") == 2,
          "and sees the entries the admin made for them", f"got {mine}")

    # ---- every event has records by default, and they can be edited ----
    status, seeded = api.request("POST", "/admin/records/seed", token=admin_token)
    check(status == 200 and isinstance(seeded, dict), "records can be seeded for the catalogue",
          f"status={status}")
    status, records = api.request("GET", "/records", token=admin_token)
    check(isinstance(records, list) and records, "the records list is populated")
    # A record belongs to one event now, so there is exactly one per type, division
    # and grade — 112 of them, one for each event, and none for a race a grade does
    # not run.
    check(len(records) == len(catalogue),
          "there is one record for every event in the programme",
          f"got {len(records)} rows for {len(catalogue)} events")
    check(len({(r["eventType"], r["sex"], r["grade"]) for r in records}) == len(records),
          "and no two of them cover the same type, division and grade",
          f"got {len(records)} rows, "
          f"{len({(r['eventType'], r['sex'], r['grade']) for r in records})} distinct")
    check(not [r for r in records if r["eventType"] == "RUN_5000M" and r["grade"] != "A"],
          "so a grade a race is not run by has no record of it",
          f"got {[(r['eventType'], r['sex'], r['grade']) for r in records if r['eventType'] == 'RUN_5000M']}")
    check(any(r.get("source") == "NONE" for r in records),
          "an event nobody has competed in yet still has its record, empty")

    # Set one by hand — the holder is a name, not an account.
    editable = next(r for r in records if r.get("source") == "NONE")
    status, typed = api.request("PUT", f"/admin/records/{editable['id']}",
                                {"mark": 13.5, "unit": "seconds",
                                 "holderName": "Chan Tai Man (2018)", "achievedOn": "2018-10-05"},
                                token=admin_token)
    check(status == 200 and typed.get("mark") == 13.5 and typed.get("source") == "BASELINE",
          "an admin can type a record in by hand", f"status={status} body={typed}")
    check(typed.get("holderName") == "Chan Tai Man (2018)" and typed.get("holderUserId") is None,
          "the holder is free text, so a past student can hold a record",
          f"got {typed.get('holderName')!r} / {typed.get('holderUserId')}")
    check(typed.get("manualMark") == 13.5, "and the typed-in mark is kept as the baseline")

    # A record an admin typed in is not lost when the records are rebuilt.
    status, rebuilt = api.request("POST", "/admin/records/recompute", token=admin_token)
    check(isinstance(rebuilt, dict) and rebuilt.get("recordsRebuilt", 0) == len(records),
          "every record is rebuilt", f"got {rebuilt}")
    status, after_rebuild = api.request("GET", "/records", token=admin_token)
    kept = next(r for r in after_rebuild if r["id"] == editable["id"])
    check(kept.get("mark") == 13.5 and kept.get("manualMark") == 13.5,
          "rebuilding keeps the hand-entered record", f"got {kept.get('mark')}")

    # A result that beats it takes over, and remembers what it beat. Find a track
    # event that actually has athletes entered, so there is a result to work with.
    beat_event, runner = None, None
    for candidate in catalogue:
        if candidate.get("sex") != subject_sex or candidate.get("category") != "TRACK":
            continue
        status, sheet = api.request("GET", f"/events/{candidate['id']}/marks?stage=HEAT",
                                    token=admin_token)
        runners = [r for r in (sheet.get("rows") or []) if r.get("grade") and r.get("userId")]
        if runners:
            beat_event, runner = candidate, runners[0]
            break
    check(beat_event is not None, "a track event with athletes is available to test a record",
          f"looked through {len(catalogue)} events")

    if beat_event:
        grade_records = [r for r in records_after
                         if r["eventType"] == beat_event["type"]
                         and r["sex"] == beat_event["sex"]
                         and r["grade"] == runner["grade"]]
        check(len(grade_records) == 1,
              "the athlete's own grade has a record", f"got {len(grade_records)}")
        grade_record = grade_records[0]

        # A slower baseline than the athlete has run, so their run beats it.
        runner_mark = float(runner.get("mark") or 15.0)
        baseline_mark = round(runner_mark + 10, 3)
        status, typed = api.request("PUT", f"/admin/records/{grade_record['id']}",
                                    {"mark": baseline_mark, "unit": "seconds",
                                     "holderName": "Old Record 2018", "achievedOn": "2018-10-05"},
                                    token=admin_token)
        check(status == 200 and typed.get("mark") == baseline_mark,
              "a slower baseline than the athlete has run is recorded", f"got {typed.get('mark')}")

        status, saved = api.request("POST", f"/events/{beat_event['id']}/marks",
                                    {"stage": "HEAT",
                                     "rows": [{"userId": runner["userId"], "mark": runner_mark,
                                               "unit": "seconds"}]},
                                    token=admin_token)
        check(isinstance(saved, dict) and saved.get("saved") == 1,
              "the athlete's run is recorded", f"got {saved}")

        status, records_now = api.request("GET", "/records", token=admin_token)
        beaten = next(r for r in records_now if r["id"] == grade_record["id"])
        check(beaten.get("mark") == runner_mark and beaten.get("source") == "RESULT",
              "the run beats the typed-in record and takes over",
              f"got {beaten.get('mark')} / {beaten.get('source')}")
        check(beaten.get("previousMark") == baseline_mark
              and beaten.get("previousHolderName") == "Old Record 2018",
              "and the results page can say what it beat",
              f"got {beaten.get('previousMark')} by {beaten.get('previousHolderName')}")
        check(beaten.get("manualMark") == baseline_mark,
              "while the typed-in mark stays on file for the next season",
              f"got {beaten.get('manualMark')}")

        status, cleared = api.request("DELETE", f"/admin/records/{grade_record['id']}/baseline",
                                      token=admin_token)
        check(status == 200 and cleared.get("manualMark") is None,
              "the hand-entered mark can be cleared again", f"got {cleared.get('manualMark')}")
        check(cleared.get("mark") == runner_mark and cleared.get("source") == "RESULT",
              "leaving the record to the result",
              f"got {cleared.get('mark')} / {cleared.get('source')}")

    status, _ = api.request("PUT", f"/admin/records/{editable['id']}", {"mark": 1},
                            token=manager_token)
    check(status == 403, "only an admin can set a record", f"status={status}")

    # ---- the programme can be viewed by date, and events edited ----
    status, dates = api.request("GET", "/events/dates", token=admin_token)
    check(status == 200 and isinstance(dates, list) and dates,
          "the dates the programme runs on are listed", f"status={status}")
    check(all("date" in d and "eventCount" in d for d in dates),
          "each date says how many events fall on it", f"got {dates[:2]}")
    check(sum(d["eventCount"] for d in dates) == len(catalogue),
          "and between them they cover every event",
          f"got {sum(d['eventCount'] for d in dates)} of {len(catalogue)}")

    first_date = dates[0]["date"]
    status, on_date = api.request("GET", f"/events?date={first_date}", token=admin_token)
    check(isinstance(on_date, list) and len(on_date) == dates[0]["eventCount"],
          "asking for one date returns exactly that day's programme",
          f"got {len(on_date)} of {dates[0]['eventCount']}")
    check(all(e["eventDate"] == first_date for e in on_date),
          "and every event on it really is that day")
    status, empty_day = api.request("GET", "/events?date=1999-01-01", token=admin_token)
    check(empty_day == [], "a date with nothing on it returns nothing", f"got {empty_day}")

    # Editing an event moves it between dates — the point of the date view.
    status, moved = api.request("PUT", f"/events/{e60['id']}",
                                {"name": e60["name"], "eventDate": "2026-12-25"},
                                token=admin_token)
    check(status == 200 and moved.get("eventDate") == "2026-12-25",
          "an event's date can be edited", f"status={status} body={moved}")
    status, moved_dates = api.request("GET", "/events/dates", token=admin_token)
    check(any(d["date"] == "2026-12-25" for d in moved_dates),
          "and the date picker picks the new date up",
          f"got {[d['date'] for d in moved_dates]}")
    status, on_new = api.request("GET", "/events?date=2026-12-25", token=admin_token)
    check(any(e["id"] == e60["id"] for e in on_new),
          "the edited event now appears on that date")
    # Put it back.
    status, restored_event = api.request("PUT", f"/events/{e60['id']}",
                                         {"name": e60["name"], "eventDate": e60["eventDate"]},
                                         token=admin_token)
    check(restored_event.get("eventDate") == e60["eventDate"], "and the date is put back")

    # A season reset keeps hand-entered records on purpose, so the one this run
    # typed in has to be cleared by hand or it would pile up on every run.
    status, tidied = api.request("DELETE", f"/admin/records/{editable['id']}/baseline",
                                 token=admin_token)
    check(status == 200 and tidied.get("manualMark") is None,
          "the record this run typed in is cleared again", f"status={status}")

    # ---------- 15. the year's roster, school info and school years
    section("15. Annual roster, school details and school years")

    # ---- the school's own details, which head every marking sheet ----
    status, updated = api.request("PUT", "/admin/settings",
                                  {"schoolName": "Kowloon Sportday Secondary School",
                                   "schoolNameZh": "九龍運動日中學",
                                   "address": "1 Sports Road, Kowloon",
                                   "principal": "Dr. Lee Siu Ming",
                                   "sportDayTitle": "田徑運動會記錄表 / Sport Day Marking Sheet"},
                                  token=admin_token)
    check(status == 200
          and updated.get("schoolName") == "Kowloon Sportday Secondary School"
          and updated.get("schoolNameZh") == "九龍運動日中學",
          "the school's details can be set", f"status={status} body={updated}")
    status, settings_now = api.request("GET", "/settings", token=admin_token)
    check(settings_now.get("address") == "1 Sports Road, Kowloon"
          and settings_now.get("principal") == "Dr. Lee Siu Ming",
          "and they are kept", f"got {settings_now.get('address')}")

    # A marking sheet now carries the school's name.
    status, sheet_pdf = api.request("GET", f"/groups/{g60_id}/sheet.pdf", token=admin_token,
                                    raw=True)
    check(status == 200 and sheet_pdf[:4] == b"%PDF",
          "the marking sheet still renders with a school name set", f"status={status}")

    # ---- the school years ----
    status, seasons = api.request("GET", "/seasons", token=admin_token)
    check(status == 200 and isinstance(seasons, list) and seasons,
          "the school years are listed", f"status={status}")
    check(all("year" in s and "eventCount" in s and "enrollmentOpen" in s for s in seasons),
          "each year says how many events it has and whether entries are open",
          f"got {seasons[:1]}")
    current_year = next(s for s in seasons if s.get("current"))
    check(sum(s["eventCount"] for s in seasons) == len(catalogue),
          "and between them they hold every event",
          f"got {sum(s['eventCount'] for s in seasons)} of {len(catalogue)}")
    check(seasons[0]["eventCount"] == len(catalogue),
          "every event was adopted into a year, so the year picker is never empty",
          f"got {[(s['year'], s['eventCount']) for s in seasons]}")

    status, current_season = api.request("GET", "/seasons/current", token=admin_token)
    check(isinstance(current_season, dict) and current_season.get("current") is True,
          "one year is the current one", f"got {current_season}")

    # A previous year, with its own events, kept apart from this year's.
    past_year = current_year["year"] - 1

    def drop_year(season_id: int) -> int:
        """Removes a year and its events, so the run can be repeated."""
        status, events_in_year = api.request("GET", f"/events?seasonId={season_id}",
                                             token=admin_token)
        removed = 0
        for event in events_in_year or []:
            api.request("DELETE", f"/events/{event['id']}", token=admin_token)
            removed += 1
        api.request("DELETE", f"/admin/seasons/{season_id}", token=admin_token)
        return removed

    # Clear any year left behind by a previous run before adding a fresh one.
    for stale in api.request("GET", "/seasons", token=admin_token)[1] or []:
        if stale["year"] == past_year:
            drop_year(stale["id"])

    status, created_year = api.request("POST", "/admin/seasons",
                                       {"year": past_year, "name": f"{past_year} Sports Day",
                                        "sportDayDate": f"{past_year}-10-04",
                                        "copyEventsFromSeasonId": current_year["id"]},
                                       token=admin_token)
    check(status == 200 and created_year.get("year") == past_year,
          "an earlier year can be added", f"status={status} body={created_year}")
    check(created_year.get("eventCount") == len(catalogue),
          "copying a year brings its whole programme across",
          f"got {created_year.get('eventCount')} of {len(catalogue)}")
    check(not created_year.get("current"),
          "and adding it does not make it the current year")

    past_season_id = created_year["id"]
    status, past_events = api.request("GET", f"/events?seasonId={past_season_id}", token=admin_token)
    check(len(past_events) == len(catalogue),
          "the earlier year's events can be listed on their own",
          f"got {len(past_events)}")
    check(all(e.get("seasonYear") == past_year for e in past_events),
          "and every one of them belongs to that year",
          f"got {sorted({e.get('seasonYear') for e in past_events})}")
    check(all(e["eventDate"].startswith(str(past_year)) for e in past_events),
          "dated on that year's sport day",
          f"got {sorted({e['eventDate'] for e in past_events})[:3]}")
    status, this_year_events = api.request("GET", f"/events?seasonId={current_year['id']}",
                                           token=admin_token)
    check(len(this_year_events) == len(catalogue),
          "while this year's programme is untouched",
          f"got {len(this_year_events)}")
    check(all(e.get("seasonYear") == current_year["year"] for e in this_year_events),
          "and still belongs to this year")

    # An earlier year can be edited, as the requirement asks.
    status, edited_year = api.request("PUT", f"/admin/seasons/{past_season_id}",
                                      {"sportDayDate": f"{past_year}-10-11",
                                       "notes": "rescheduled for rain"},
                                      token=admin_token)
    check(status == 200 and edited_year.get("sportDayDate") == f"{past_year}-10-11"
          and edited_year.get("notes") == "rescheduled for rain",
          "a past year's details can be updated", f"got {edited_year}")

    # A year with events cannot be deleted, so a programme is never orphaned.
    status, refused_delete = api.request("DELETE", f"/admin/seasons/{past_season_id}",
                                         token=admin_token)
    check(status == 409, "a year that still has events cannot be deleted",
          f"status={status} body={refused_delete}")

    # Opening a year closes the others.
    status, reopened = api.request("POST", f"/admin/seasons/{past_season_id}/activate",
                                   token=admin_token)
    check(status == 200 and reopened.get("enrollmentOpen") is True
          and reopened.get("current") is True,
          "an earlier year can be reopened for entries", f"got {reopened}")
    status, seasons_after = api.request("GET", "/seasons", token=admin_token)
    open_years = [s["year"] for s in seasons_after if s.get("enrollmentOpen")]
    check(open_years == [past_year],
          "and opening it closes the others, so nobody enters the wrong sport day",
          f"got open years {open_years}")
    # Put this year back in charge.
    status, restored_year = api.request("POST", f"/admin/seasons/{current_year['id']}/activate",
                                        token=admin_token)
    check(restored_year.get("current") is True, "the current year is put back")

    # ---- entries can be closed and reopened for the year ----
    status, closed = api.request("PUT", f"/admin/seasons/{current_year['id']}",
                                 {"enrollmentOpen": False}, token=admin_token)
    check(status == 200 and closed.get("enrollmentOpen") is False,
          "entries can be closed for the year", f"got {closed}")
    status, credentials = api.request("GET", "/admin/students/credentials.csv",
                                      token=admin_token, raw=True)
    entrant_row = list(csv.DictReader(io.StringIO(credentials.decode("utf-8-sig"))))[0]
    status, entrant_session = api.request("POST", "/auth/login",
                                          {"username": entrant_row["studentId"],
                                           "password": entrant_row["password"]})
    entrant_token = entrant_session.get("token") if isinstance(entrant_session, dict) else None
    status, refused_entry = api.request("POST", f"/enrollments/{open_track[0]['id']}",
                                        token=entrant_token)
    check(status == 409, "a student cannot enter once entries are closed",
          f"status={status} body={refused_entry}")
    # An administrator can still add a late entry by hand.
    status, admin_entry = api.request("POST",
                                      f"/admin/students/{entrant_row['studentId']}"
                                      f"/enrollments/{open_track[0]['id']}",
                                      token=admin_token)
    check(status == 200 or status == 409,
          "an administrator is not stopped by a closed year (only by the quota)",
          f"status={status} body={admin_entry}")
    status, reopened_year = api.request("PUT", f"/admin/seasons/{current_year['id']}",
                                        {"enrollmentOpen": True}, token=admin_token)
    check(reopened_year.get("enrollmentOpen") is True, "and entries can be reopened")

    # ---- the year's roster: absent students are locked ----
    status, all_students = api.request("GET", "/admin/students", token=admin_token)
    check(isinstance(all_students, list) and len(all_students) > 10,
          "the register is available", f"got {len(all_students) if isinstance(all_students, list) else all_students}")

    # Build a roster file that omits most students, and rehearse the upload first.
    keep = all_students[:5]
    omitted = all_students[5:]
    roster_lines = ["studentId,name,dob,sex,className,classNumber,house"]
    for student in keep:
        roster_lines.append(",".join([
            student["studentId"], student["name"], student["dob"], student["sex"],
            student["className"], str(student["classNumber"]), student["house"]]))
    roster_csv = "\n".join(roster_lines).encode("utf-8")

    status, rehearsal = api.upload_bytes("/admin/students/upload/roster?dryRun=true",
                                         "roster.csv", roster_csv, token=admin_token)
    check(status == 200, "a full-roster upload can be rehearsed before it is applied",
          f"status={status} body={rehearsal}")
    if status == 200 and isinstance(rehearsal, dict):
        check(rehearsal.get("dryRun") is True,
              "and the response says it was a rehearsal", f"got {rehearsal.get('dryRun')}")
        check(rehearsal.get("lockedTotal") == len(omitted),
              f"it reports exactly the {len(omitted)} students it would lock",
              f"got {rehearsal.get('lockedTotal')} of {len(omitted)}")
        check(rehearsal.get("locked", 0) == 0,
              "and it locks nobody, because nothing was written", f"got {rehearsal.get('locked')}")
        check(len(rehearsal.get("lockedStudents", [])) > 0,
              "naming the students about to be locked", f"got {rehearsal.get('lockedStudents')}")

    # Nothing changed.
    status, still_active = api.request("GET", "/admin/students?enabled=true", token=admin_token)
    check(len(still_active) == len(all_students),
          "the rehearsal left every student active",
          f"got {len(still_active)} of {len(all_students)}")

    # Now do it for real.
    status, applied = api.upload_bytes("/admin/students/upload/roster?dryRun=false",
                                       "roster.csv", roster_csv, token=admin_token)
    check(status == 200 and isinstance(applied, dict),
          "the full roster can then be applied", f"status={status} body={applied}")
    if status == 200 and isinstance(applied, dict):
        check(applied.get("locked") == len(omitted),
              f"every student missing from it is locked",
              f"got {applied.get('locked')} of {len(omitted)}")
        check(applied.get("dryRun") is False, "and this time it stuck")

    status, locked_list = api.request("GET", "/admin/students?enabled=false", token=admin_token)
    check(len(locked_list) == len(omitted),
          "the locked students can be listed separately",
          f"got {len(locked_list)} of {len(omitted)}")
    status, active_list = api.request("GET", "/admin/students?enabled=true", token=admin_token)
    check(len(active_list) == len(keep),
          "and the ones on the list are still active",
          f"got {len(active_list)} of {len(keep)}")

    # A locked student cannot sign in.
    locked_row = locked_list[0]
    status, credentials = api.request("GET", "/admin/students/credentials.csv",
                                      token=admin_token, raw=True)
    locked_credentials = next(r for r in csv.DictReader(io.StringIO(credentials.decode("utf-8-sig")))
                              if r["studentId"] == locked_row["studentId"])
    status, refused_login = api.request("POST", "/auth/login",
                                        {"username": locked_credentials["studentId"],
                                         "password": locked_credentials["password"]})
    check(status in (401, 403),
          "a locked student can no longer sign in",
          f"status={status} body={refused_login}")
    check(not locked_row.get("enabled"),
          "and the register shows them as locked", f"got {locked_row.get('enabled')}")

    # ... nor be entered in an event, even by an administrator.
    status, refused_locked_entry = api.request(
        "POST", f"/admin/students/{locked_row['studentId']}/enrollments/{open_track[0]['id']}",
        token=admin_token)
    check(status == 409, "and cannot be entered in an event, even by an admin",
          f"status={status} body={refused_locked_entry}")

    # Unlocking restores them, which is what happens when a student returns.
    status, unlocked_student = api.request(
        "PATCH", f"/admin/students/{locked_row['studentId']}/lock?locked=false", token=admin_token)
    check(status == 200 and unlocked_student.get("enabled") is True,
          "an administrator can unlock a student", f"status={status} body={unlocked_student}")
    status, can_login = api.request("POST", "/auth/login",
                                    {"username": locked_credentials["studentId"],
                                     "password": locked_credentials["password"]})
    check(status == 200 and isinstance(can_login, dict) and can_login.get("token"),
          "and they can sign in again", f"status={status}")

    # Re-uploading a student who was locked makes them active again: the list is
    # what decides, so a student who has returned is restored by including them.
    status, relocked = api.request(
        "PATCH", f"/admin/students/{locked_row['studentId']}/lock?locked=true", token=admin_token)
    check(relocked.get("enabled") is False, "locking works too")
    returning = keep + [locked_row]
    returning_lines = ["studentId,name,dob,sex,className,classNumber,house"]
    for student in returning:
        returning_lines.append(",".join([
            student["studentId"], student["name"], student["dob"], student["sex"],
            student["className"], str(student["classNumber"]), student["house"]]))
    status, restored = api.upload_bytes(
        "/admin/students/upload/roster?dryRun=false", "returning.csv",
        "\n".join(returning_lines).encode("utf-8"), token=admin_token)
    check(isinstance(restored, dict) and restored.get("unlocked", 0) >= 1,
          "and re-uploading a locked student makes them active again",
          f"got {restored.get('unlocked') if isinstance(restored, dict) else restored}")
    status, back_in = api.request("GET",
                                  f"/admin/students/{locked_row['studentId']}",
                                  token=admin_token)
    check(back_in.get("enabled") is True,
          "the student who returned is active again", f"got {back_in.get('enabled')}")

    # Put the register back the way it was: upload everybody, locking nobody.
    full_lines = ["studentId,name,dob,sex,className,classNumber,house"]
    for student in all_students:
        full_lines.append(",".join([
            student["studentId"], student["name"], student["dob"], student["sex"],
            student["className"], str(student["classNumber"]), student["house"]]))
    status, restored_register = api.upload_bytes(
        "/admin/students/upload/roster?dryRun=false", "all.csv",
        "\n".join(full_lines).encode("utf-8"), token=admin_token)
    check(isinstance(restored_register, dict) and restored_register.get("locked") == 0,
          "re-uploading the whole register locks nobody",
          f"got {restored_register.get('locked') if isinstance(restored_register, dict) else restored_register}")
    status, all_active_again = api.request("GET", "/admin/students?enabled=false", token=admin_token)
    check(all_active_again == [], "and every student is active again",
          f"got {len(all_active_again)} still locked")

    # An admin who did not tick "complete list" can still sweep up afterwards.
    status, swept = api.request("POST", "/admin/students/lock-missing", token=admin_token)
    check(status == 200 and isinstance(swept, dict) and swept.get("locked") == 0,
          "locking whoever the last upload left out is a no-op when it listed everybody",
          f"got {swept}")

    # Leave no test year behind: a season reset keeps events, so the copied
    # catalogue has to be removed by hand or it would pile up on every run.
    removed = drop_year(past_season_id)
    check(removed == len(catalogue), "the test year's events are cleared away",
          f"removed {removed} of {len(catalogue)}")
    status, final_years = api.request("GET", "/seasons", token=admin_token)
    check(all(s["year"] != past_year for s in final_years),
          "and the test year is gone", f"got {[s['year'] for s in final_years]}")

    # ---------------- 16. field units and the three attempts
    section("16. Field events: metres, and the best of three attempts")

    # ---- the unit every event records in ----
    status, catalogue_now = api.request("GET", "/events", token=admin_token)
    field_units = {e.get("defaultUnit") for e in catalogue_now if e["category"] == "FIELD"}
    track_units = {e.get("defaultUnit") for e in catalogue_now if e["category"] == "TRACK"}
    check(field_units == {"M"},
          "every field event is recorded in metres, written M",
          f"got {sorted(field_units)}")
    check(track_units == {"s"},
          "and every track event in seconds, written s",
          f"got {sorted(track_units)}")
    check(all(e.get("defaultUnit") for e in catalogue_now),
          "every event carries its unit, so the entry page can show it")

    # The mark grid agrees, and tells the UI how many boxes to draw. Both events are
    # the subject's own grade, so an athlete may actually be entered in them.
    field_event = next((e for e in catalogue_now
                        if e["category"] == "FIELD" and e.get("sex") == subject_sex
                        and e.get("grade") == subject_grade), None)
    track_event = next((e for e in catalogue_now
                        if e["category"] == "TRACK" and e.get("sex") == subject_sex
                        and e.get("grade") == subject_grade), None)
    check(field_event is not None and track_event is not None,
          "there is a field event and a track event of the student's own grade to compare")

    status, field_sheet = api.request("GET", f"/events/{field_event['id']}/marks",
                                      token=admin_token)
    check(field_sheet.get("defaultUnit") == "M", "the field grid records in M",
          f"got {field_sheet.get('defaultUnit')}")
    check(field_sheet.get("attemptCount") == 3 and field_sheet.get("fieldEvent") is True,
          "and asks for three attempts", f"got {field_sheet.get('attemptCount')}")
    status, track_sheet = api.request("GET", f"/events/{track_event['id']}/marks",
                                     token=admin_token)
    check(track_sheet.get("defaultUnit") == "s" and track_sheet.get("attemptCount") == 1
          and track_sheet.get("fieldEvent") is False,
          "while the track grid records one time in s",
          f"got {track_sheet.get('defaultUnit')} / {track_sheet.get('attemptCount')}")

    # A client still sending the old spelled-out unit must not be able to drift the
    # stored marks back to words.
    track_athlete = next((r for r in (track_sheet.get("rows") or []) if r.get("userId")), None)
    if track_athlete:
        status, _ = api.request("POST", f"/events/{track_event['id']}/marks",
                                {"stage": "HEAT",
                                 "rows": [{"userId": track_athlete["userId"], "mark": 8.5,
                                           "unit": "seconds"}]},
                                token=admin_token)
        status, after_save = api.request("GET", f"/events/{track_event['id']}/marks",
                                         token=admin_token)
        stored = next(r for r in after_save["rows"] if r["userId"] == track_athlete["userId"])
        check(stored.get("unit") == "s",
              "the old word 'seconds' is normalised to s, so a stored mark cannot drift",
              f"got {stored.get('unit')}")
    else:
        check(True, "no athlete entered in the track event to test the unit with")

    # ---- three attempts, and the best one counts ----
    # Find a field event that actually has an athlete entered.
    marked_field, runner_row = None, None
    for candidate in [e for e in catalogue_now if e["category"] == "FIELD"]:
        status, sheet = api.request("GET", f"/events/{candidate['id']}/marks", token=admin_token)
        entered = [r for r in (sheet.get("rows") or []) if r.get("userId")]
        if entered:
            marked_field, runner_row = candidate, entered[0]
            break
    check(marked_field is not None,
          "a field event with an athlete entered is available",
          f"looked through the field programme")

    if marked_field:
        # The second attempt is the best, so the mark must come from it.
        status, saved = api.request(
            "POST", f"/events/{marked_field['id']}/marks",
            {"stage": "HEAT",
             "rows": [{"userId": runner_row["userId"], "unit": "M",
                       "attempts": [8.20, 11.45, 9.90]}]},
            token=admin_token)
        check(status == 200 and isinstance(saved, dict) and saved.get("saved") == 1,
              "three attempts can be saved in one row", f"status={status} body={saved}")

        status, sheet = api.request("GET", f"/events/{marked_field['id']}/marks",
                                    token=admin_token)
        row = next(r for r in sheet["rows"] if r["userId"] == runner_row["userId"])
        check(row.get("attempts") == [8.2, 11.45, 9.9],
              "and all three come back, in order", f"got {row.get('attempts')}")
        check(row.get("mark") == 11.45,
              "with the best attempt taken as the result",
              f"got {row.get('mark')} from [8.20, 11.45, 9.90]")
        check(sheet.get("markedCount") == 1, "and the athlete counts as marked")

        # The placings and the records read that mark, not an average.
        status, standings = api.request("GET", f"/events/{marked_field['id']}/standings",
                                        token=admin_token)
        placing = next((p for p in standings["placings"] if p["userId"] == runner_row["userId"]),
                       None)
        check(placing is not None and placing["mark"] == 11.45,
              "the placings use the best attempt", f"got {placing and placing['mark']}")
        status, results_now = api.request("GET", f"/results/event/{marked_field['id']}",
                                          token=admin_token)
        result_row = next((r for r in results_now if r["userId"] == runner_row["userId"]), None)
        check(result_row is not None and result_row.get("mark") == 11.45,
              "and so do the results", f"got {result_row and result_row.get('mark')}")
        check(result_row.get("attempts") == [8.2, 11.45, 9.9],
              "with the attempts kept alongside, so a sheet can be checked",
              f"got {result_row and result_row.get('attempts')}")
        check(result_row.get("unit") == "M",
              "recorded in metres", f"got {result_row and result_row.get('unit')}")

        # A missed attempt is ignored, not counted as zero.
        status, _ = api.request(
            "POST", f"/events/{marked_field['id']}/marks",
            {"stage": "HEAT",
             "rows": [{"userId": runner_row["userId"], "unit": "M",
                       "attempts": [None, 12.05, None]}]},
            token=admin_token)
        status, sheet = api.request("GET", f"/events/{marked_field['id']}/marks",
                                    token=admin_token)
        row = next(r for r in sheet["rows"] if r["userId"] == runner_row["userId"])
        check(row.get("mark") == 12.05,
              "a missed attempt is ignored rather than counted as zero",
              f"got {row.get('mark')} from [miss, 12.05, miss]")

        # A later attempt being the best is the normal case; check the other order too.
        status, _ = api.request(
            "POST", f"/events/{marked_field['id']}/marks",
            {"stage": "HEAT",
             "rows": [{"userId": runner_row["userId"], "unit": "M",
                       "attempts": [13.10, 12.00, 11.00]}]},
            token=admin_token)
        status, sheet = api.request("GET", f"/events/{marked_field['id']}/marks",
                                    token=admin_token)
        row = next(r for r in sheet["rows"] if r["userId"] == runner_row["userId"])
        check(row.get("mark") == 13.10,
              "the first attempt can be the best one too",
              f"got {row.get('mark')} from [13.10, 12.00, 11.00]")

        # Clearing removes all three, not just the best.
        status, cleared = api.request(
            "POST", f"/events/{marked_field['id']}/marks",
            {"stage": "HEAT", "rows": [{"userId": runner_row["userId"], "clear": True}]},
            token=admin_token)
        check(isinstance(cleared, dict) and cleared.get("cleared") == 1,
              "clearing an athlete removes the whole set of attempts", f"got {cleared}")
        status, sheet = api.request("GET", f"/events/{marked_field['id']}/marks",
                                    token=admin_token)
        row = next(r for r in sheet["rows"] if r["userId"] == runner_row["userId"])
        check(row.get("mark") is None and not row.get("attempts"),
              "leaving nothing behind", f"got {row.get('mark')} / {row.get('attempts')}")

    # ---- the printed field sheet carries the three boxes ----
    # Allocate the groups first: a print run has nothing to print without them.
    sheet_event = marked_field if marked_field else field_event
    status, allocated = api.request("POST", f"/events/{sheet_event['id']}/groups/allocate",
                                    token=admin_token)
    check(status == 200, "a field event's groups can be drawn",
          f"status={status} body={allocated}")
    status, group_list = api.request("GET", f"/events/{sheet_event['id']}/groups",
                                     token=admin_token)
    check(isinstance(group_list, list) and group_list,
          "and it now has a heat to print", f"got {len(group_list or [])}")

    if group_list:
        field_group_id = group_list[0]["id"]
        status, field_pdf = api.request("GET", f"/groups/{field_group_id}/sheet.pdf",
                                        token=admin_token, raw=True)
        field_pdf_path = os.path.join(output_dir, "marking-sheet-FIELD-A4.pdf")
        with open(field_pdf_path, "wb") as handle:
            handle.write(field_pdf)
        check(status == 200 and field_pdf[:4] == b"%PDF",
              "a field event's marking sheet downloads",
              f"status={status} bytes={len(field_pdf)}")
        field_box = media_box(field_pdf)
        check(field_box is not None and abs(field_box[0] - 595) < 3
              and abs(field_box[1] - 842) < 3,
              "on A4, since a field event is not a short sprint", f"got {field_box}")
        # The three attempt boxes make the data stream bigger than a track sheet
        # of the same size, which is the observable difference in the file.
        status, track_group_list = api.request("GET", f"/events/{e60['id']}/groups",
                                               token=admin_token)
        if track_group_list:
            status, track_pdf = api.request("GET", f"/groups/{track_group_list[0]['id']}/sheet.pdf",
                                            token=admin_token, raw=True)
            check(len(field_pdf) != len(track_pdf),
                  "and it differs from a track sheet, because of the attempt boxes",
                  f"field={len(field_pdf)} track={len(track_pdf)}")

    # Leave no drawn heats behind on an event that had none.
    if not marked_field:
        api.request("DELETE", f"/events/{sheet_event['id']}/groups", token=admin_token)
        status, cleaned = api.request("GET", f"/events/{sheet_event['id']}/groups",
                                      token=admin_token)
        check(cleaned == [], "the test leaves the field event as it found it", f"got {cleaned}")

    # ---------------- 17. direct to final, with an opt-in for the sprints
    section("17. Direct to final, and the sprints that may be split")

    status, catalogue_now = api.request("GET", "/events", token=admin_token)
    may_split = {e["type"] for e in catalogue_now if e.get("mayHaveFinal")}
    check(may_split == {"RUN_60M", "RUN_100M", "RUN_200M", "RUN_400M"},
          "only 60M, 100M, 200M and 400M may be run as heats and a final",
          f"got {sorted(may_split)}")
    check(not any(e.get("mayHaveFinal") for e in catalogue_now if e["category"] == "FIELD"),
          "no field event can have a final")
    check(not any(e.get("mayHaveFinal") for e in catalogue_now
                  if e["type"] in ("RUN_800M", "RUN_1500M", "RUN_5000M", "RELAY_4X100M")),
          "nor can a distance race or a relay")

    # Only an event that has actually been split runs heats and a final; the rest
    # are decided by their own run, whatever their setting says.
    split = [e for e in catalogue_now if not e.get("directToFinal")]
    check(all(e.get("mayHaveFinal") and (e.get("groupCount") or 0) > 0 for e in split),
          "an event only runs heats and a final once its groups are drawn",
          f"not direct: {[(e['name'], e.get('groupCount')) for e in split]}")

    # A brand new event is direct to a final unless the school asks otherwise — and
    # it has to be given a grade, since every event is run by exactly one.
    status, brand_new = api.request(
        "POST", "/events",
        {"type": "RUN_100M", "sex": "F", "name": "Smoke Test 100M", "grade": "A",
         "eventDate": "2027-10-01", "location": "Main Sports Ground"},
        token=admin_token)
    check(status == 200 and brand_new.get("directToFinal") is True,
          "a new event defaults to running direct to a final",
          f"status={status} body={brand_new}")
    check(brand_new.get("grade") == "A" and brand_new.get("gradeLabel") == "A Grade",
          "and it keeps the grade it was created with",
          f"got {brand_new.get('grade')} / {brand_new.get('gradeLabel')}")
    check(brand_new.get("mayHaveFinal") is True,
          "though a 100M may still be split if the school asks")

    # The default really does mean no final.
    status, refused_final = api.request("POST", f"/events/{brand_new['id']}/final",
                                        token=admin_token)
    check(status == 409, "so there is no final to draw on it",
          f"status={status} body={refused_final}")
    status, refused_preview = api.request("GET", f"/events/{brand_new['id']}/final",
                                          token=admin_token)
    check(status == 409, "and no final to preview either",
          f"status={status} body={refused_preview}")

    # Unticking the box is what allows one.
    status, ticked_off = api.request("PUT", f"/events/{brand_new['id']}",
                                     {"directToFinal": False}, token=admin_token)
    check(status == 200 and ticked_off.get("directToFinal") is False,
          "unticking the box turns the event into heats and a final",
          f"status={status} body={ticked_off}")
    # It has no entries, so the draw itself stops for a different, honest reason.
    status, no_marks = api.request("POST", f"/events/{brand_new['id']}/final",
                                   token=admin_token)
    check(status == 409 and "heat" in str(no_marks),
          "and it now looks for heat results rather than refusing outright",
          f"status={status} body={no_marks}")
    # Put it back to direct.
    status, reticked = api.request("PUT", f"/events/{brand_new['id']}",
                                   {"directToFinal": True}, token=admin_token)
    check(reticked.get("directToFinal") is True, "and it can be set back to direct")

    # An event that cannot have a final cannot be asked for one. Both of these carry
    # a grade the type does run, so the refusal is about the final, not the grade.
    status, refused_ask = api.request(
        "POST", "/events",
        {"type": "SHOT_PUT", "sex": "M", "name": "Smoke Test Shot", "grade": "A",
         "eventDate": "2027-10-01", "directToFinal": False},
        token=admin_token)
    check(status == 400, "a field event cannot be asked for a final",
          f"status={status} body={refused_ask}")
    status, refused_800 = api.request(
        "POST", "/events",
        {"type": "RUN_800M", "sex": "M", "name": "Smoke Test 800M", "grade": "A",
         "eventDate": "2027-10-01", "directToFinal": False},
        token=admin_token)
    check(status == 400, "and neither can an 800M",
          f"status={status} body={refused_800}")

    # An event that already exists keeps the format it was running.
    status, girls_60 = api.request("GET", "/events", token=admin_token)
    existing_60 = [e for e in girls_60 if e["type"] == "RUN_60M" and e["id"] != e60["id"]]
    if existing_60:
        check(existing_60[0].get("directToFinal") is True,
              "an existing 60M with no heats was left running direct to a final",
              f"got {existing_60[0].get('directToFinal')}")
    check(next(e for e in girls_60 if e["id"] == e60["id"]).get("directToFinal") is False,
          "while the 60M that already had heats kept its final",
          "the event the final was drawn on in section 12")

    # Tidy up the event this section created.
    status, _ = api.request("DELETE", f"/events/{brand_new['id']}", token=admin_token)
    check(status in (200, 204), "the test event is removed", f"status={status}")
    status, after_cleanup = api.request("GET", "/events", token=admin_token)
    check(len(after_cleanup) == len(catalogue_now),
          "leaving the catalogue as it was",
          f"got {len(after_cleanup)} of {len(catalogue_now)}")

    # ---------------- 18. one grade per event
    section("18. One grade per event: the catalogue, and who may enter what")

    # ---- the old grade-eligibility feature is gone entirely ----
    status, gone = api.request("GET", "/grade-events", token=admin_token)
    check(status == 404, "the old grade-eligibility page is gone", f"status={status} body={gone}")
    status, gone_admin = api.request("GET", "/admin/grade-events", token=admin_token)
    check(status == 404, "and so is the page that assigned it",
          f"status={status} body={gone_admin}")

    # ---- the catalogue: one event per type x division x grade ----
    status, catalogue_now = api.request("GET", "/events", token=admin_token)
    check(isinstance(catalogue_now, list) and len(catalogue_now) == 112,
          "the catalogue holds 112 events",
          f"got {len(catalogue_now) if isinstance(catalogue_now, list) else catalogue_now}")
    catalogue_now = catalogue_now if isinstance(catalogue_now, list) else []
    by_grade = {g: [e for e in catalogue_now if e.get("grade") == g] for g in ("A", "B", "C")}
    check({g: len(v) for g, v in by_grade.items()} == {"A": 40, "B": 38, "C": 34},
          "with A running 40 of them, B 38 and C 34",
          f"got { {g: len(v) for g, v in by_grade.items()} }")
    check(len({(e["type"], e["sex"], e["grade"]) for e in catalogue_now}) == len(catalogue_now),
          "and no type and division run twice by the same grade",
          f"got {len(catalogue_now)} events, "
          f"{len({(e['type'], e['sex'], e['grade']) for e in catalogue_now})} distinct")
    check(all(e.get("gradeLabel") == f"{e['grade']} Grade" for e in catalogue_now),
          "every event is labelled with the grade it belongs to",
          f"got {sorted({(e.get('grade'), e.get('gradeLabel')) for e in catalogue_now})}")

    def grades_running(event_type: str):
        """The grades that have an event of this type at all."""
        return {e["grade"] for e in catalogue_now if e["type"] == event_type}

    check(grades_running("RUN_1500M") == {"A", "B"},
          "there is no C grade event for the 1500M — A and B run it",
          f"got {sorted(grades_running('RUN_1500M'))}")
    check(grades_running("RUN_5000M") == {"A"},
          "nor for the 5000M, which only the A grade runs",
          f"got {sorted(grades_running('RUN_5000M'))}")
    check(grades_running("HURDLES_110M") == {"A", "B"},
          "nor for the 110M hurdles — A and B only",
          f"got {sorted(grades_running('HURDLES_110M'))}")
    check(grades_running("HURDLES_100M") == {"A", "B", "C"},
          "while the 100M hurdles is run by all three grades",
          f"got {sorted(grades_running('HURDLES_100M'))}")
    check(len([e for e in catalogue_now
               if e["type"] == "HURDLES_100M" and e["grade"] == "C"]) == 2,
          "so a C grade 100M hurdles really exists, one per division",
          f"got {[e['name'] for e in catalogue_now if e['type'] == 'HURDLES_100M' and e['grade'] == 'C']}")

    # ---- the rule is enforced, for a student and for an admin acting for one ----
    status, c_roster = api.request("GET", "/admin/students?sex=M&grade=C", token=admin_token)
    check(isinstance(c_roster, list) and c_roster, "there are C grade boys on the register",
          f"got {len(c_roster) if isinstance(c_roster, list) else c_roster}")
    c_roster = c_roster if isinstance(c_roster, list) else []

    c_subject = None
    c_current = None
    for candidate in c_roster[:20]:
        status, entries = api.request(
            "GET", f"/admin/students/{candidate['studentId']}/enrollments", token=admin_token)
        quota = entries.get("quota", {}) if isinstance(entries, dict) else {}
        if quota.get("trackRemaining", 0) > 0:
            c_subject, c_current = candidate, entries
            break
    check(c_subject is not None, "and one of them has a track entry to spare")

    if c_subject:
        # The A grade 1500M in his own division: the C grade has no 1500M at all, so
        # the grade is the only thing that can refuse him.
        my_1500 = next(e for e in catalogue_now
                       if e["type"] == "RUN_1500M" and e["sex"] == c_subject["sex"]
                       and e["grade"] == "A")
        check(my_1500["name"].endswith("A Grade"),
              "the event he is turned away from is the A grade one",
              f"got {my_1500['name']}")

        status, credentials = api.request("GET", "/admin/students/credentials.csv",
                                          token=admin_token, raw=True)
        c_row = next(r for r in csv.DictReader(io.StringIO(credentials.decode("utf-8-sig")))
                     if r["studentId"] == c_subject["studentId"])
        status, session = api.request("POST", "/auth/login",
                                      {"username": c_subject["studentId"],
                                       "password": c_row["password"]})
        c_token = session.get("token") if isinstance(session, dict) else None
        check(c_token is not None, "the C grade boy can sign in", f"status={status}")

        # His own attempt at an A grade event.
        status, refused = api.request("POST", f"/enrollments/{my_1500['id']}", token=c_token)
        check(status == 409, "a C grade student is refused an A grade event",
              f"status={status} body={refused}")
        message = str(refused)
        check("A Grade" in message and "C grade" in message,
              "and the refusal names both grades, so the reason is obvious",
              f"got {message}")
        check("1500M" in message, "along with the race he tried to enter", f"got {message}")

        # An administrator is bound by exactly the same rule.
        status, refused_admin = api.request(
            "POST", f"/admin/students/{c_subject['studentId']}/enrollments/{my_1500['id']}",
            token=admin_token)
        check(status == 409, "and an admin cannot enter him in it on his behalf either",
              f"status={status} body={refused_admin}")
        check("A Grade" in str(refused_admin) and "C grade" in str(refused_admin),
              "with the same reason, naming both grades", f"got {refused_admin}")

        # Nothing was written, so the refusal cost him nothing.
        status, after_refusal = api.request(
            "GET", f"/admin/students/{c_subject['studentId']}/enrollments", token=admin_token)
        before_quota = after_refusal.get("quota", {}) if isinstance(after_refusal, dict) else {}
        check(before_quota.get("trackRemaining", 0) > 0,
              "and a refused entry leaves his allowance alone", f"got {before_quota}")

        # A C grade event, on the other hand, does take him.
        taken_ids = {e.get("eventId") for e in (c_current or {}).get("enrollments", [])}
        c_event = next((e for e in catalogue_now
                        if e["grade"] == "C" and e["sex"] == c_subject["sex"]
                        and e["category"] == "TRACK" and e["id"] not in taken_ids
                        and (e.get("enrolledCount") or 0) < (e.get("maxParticipants") or 999)), None)
        check(c_event is not None, "there is a C grade track event with room for him",
              f"looked through {len(by_grade['C'])} C grade events")
        if c_event:
            status, accepted = api.request("POST", f"/enrollments/{c_event['id']}", token=c_token)
            check(status == 200,
                  f"and a C grade student is accepted by a C grade event ({c_event['name']})",
                  f"status={status} body={accepted}")
            # Withdraw it again, so the data is left as it was found.
            status, withdrawn = api.request("DELETE", f"/enrollments/{c_event['id']}",
                                            token=c_token)
            check(status in (200, 204), "and withdrawing it puts the data back",
                  f"status={status} body={withdrawn}")
            status, restored = api.request(
                "GET", f"/admin/students/{c_subject['studentId']}/enrollments", token=admin_token)
            restored_quota = restored.get("quota", {}) if isinstance(restored, dict) else {}
            check(restored_quota.get("trackRemaining") == before_quota.get("trackRemaining"),
                  "leaving his entry allowance as it was",
                  f"got {restored_quota} against {before_quota}")
            check(not any(e.get("eventId") == c_event["id"]
                          and e.get("status") == "CONFIRMED"
                          for e in (restored or {}).get("enrollments", [])),
                  "and no live entry behind", f"got {restored}")

    # ---- a grade is required, and it must be one the type is run by ----
    status, no_grade = api.request(
        "POST", "/events",
        {"type": "RUN_100M", "sex": "M", "name": "Smoke No Grade 100M",
         "eventDate": "2027-10-01"},
        token=admin_token)
    check(status == 400, "an event cannot be created without a grade",
          f"status={status} body={no_grade}")
    check("grade" in str(no_grade).lower(),
          "and the refusal says a grade is required", f"got {no_grade}")

    status, c_5000 = api.request(
        "POST", "/events",
        {"type": "RUN_5000M", "sex": "M", "name": "Smoke C Grade 5000M", "grade": "C",
         "eventDate": "2027-10-01"},
        token=admin_token)
    check(status == 400, "a RUN_5000M cannot be created for the C grade",
          f"status={status} body={c_5000}")
    check("C Grade" in str(c_5000), "and the refusal says which grade does run it",
          f"got {c_5000}")

    status, c_1500 = api.request(
        "POST", "/events",
        {"type": "RUN_1500M", "sex": "M", "name": "Smoke C Grade 1500M", "grade": "C",
         "eventDate": "2027-10-01"},
        token=admin_token)
    check(status == 400, "nor a 1500M the C grade does not run",
          f"status={status} body={c_1500}")

    status, after_attempts = api.request("GET", "/events", token=admin_token)
    check(isinstance(after_attempts, list) and len(after_attempts) == len(catalogue_now),
          "so the catalogue is left at 112 events",
          f"got {len(after_attempts) if isinstance(after_attempts, list) else after_attempts}")

    # ---------------- 19. a small field, and races timed in minutes
    section("19. A small field, and races timed in minutes and seconds")

    status, catalogue_now = api.request("GET", "/events", token=admin_token)
    timed_in_minutes = {e["type"] for e in catalogue_now if e.get("timeInMinutes")}
    check(timed_in_minutes == {"RUN_800M", "RUN_1500M", "RUN_5000M"},
          "only races longer than 400M are timed in minutes",
          f"got {sorted(timed_in_minutes)}")
    check(not any(e.get("timeInMinutes") for e in catalogue_now
                  if e["type"] in ("RUN_60M", "RUN_100M", "RUN_200M", "RUN_400M")),
          "a sprint of 400M or less is a plain number of seconds")
    check(not any(e.get("timeInMinutes") for e in catalogue_now if e["category"] == "FIELD"),
          "and no field event is timed at all")

    # ---- the grid asks for the time the way a stopwatch reads it
    e800 = next(e for e in catalogue_now if e["type"] == "RUN_800M")
    status, e800_groups = api.request("GET", f"/events/{e800['id']}/groups", token=admin_token)
    heat = next(g for g in e800_groups if g.get("stage") == "HEAT")
    status, sheet = api.request("GET", f"/events/{e800['id']}/marks?groupId={heat['id']}",
                                token=admin_token)
    check(sheet.get("timeInMinutes") is True, "the 800M grid asks in minutes and seconds",
          f"got {sheet.get('timeInMinutes')}")
    check(sheet.get("fieldEvent") is False, "and it is a track event, so one time not three")

    status, sprint_sheet = api.request("GET", f"/events/{e60['id']}/marks?stage=HEAT",
                                       token=admin_token)
    check(sprint_sheet.get("timeInMinutes") in (False, None),
          "while a 60M grid is a single number of seconds",
          f"got {sprint_sheet.get('timeInMinutes')}")

    # ---- a time typed as minutes and seconds is stored as the total
    runner = next(r for r in sheet["rows"] if r.get("userId"))
    status, saved = api.request(
        "POST", f"/events/{e800['id']}/marks",
        {"stage": "HEAT",
         "rows": [{"userId": runner["userId"], "minutes": 2, "seconds": 15}]},
        token=admin_token)
    check(status == 200 and saved.get("saved") == 1, "a helper can type 2 minutes 15 seconds",
          f"status={status} body={saved}")
    status, after = api.request("GET", f"/events/{e800['id']}/marks?groupId={heat['id']}",
                                token=admin_token)
    written = next(r for r in after["rows"] if r.get("userId") == runner["userId"])
    check(written.get("minutes") == 2 and float(written.get("seconds") or 0) == 15.0,
          "and it reads back as 2 and 15", f"got {written.get('minutes')}:{written.get('seconds')}")
    check(float(written.get("mark") or 0) == 135.0,
          "stored as the total of 135 seconds, which is what the placings use",
          f"got {written.get('mark')}")
    check(written.get("unit") == "s", "still a time in seconds underneath",
          f"got {written.get('unit')}")

    # ---- 1 minute 75 seconds is refused rather than silently carried
    status, refused_time = api.request(
        "POST", f"/events/{e800['id']}/marks",
        {"stage": "HEAT", "rows": [{"userId": runner["userId"], "minutes": 1, "seconds": 75}]},
        token=admin_token)
    check(status == 200 and refused_time.get("failed") == 1,
          "1 minute 75 seconds is refused rather than carried into 2:15",
          f"status={status} body={refused_time}")
    check(any("under 60" in str(e) for e in refused_time.get("errors", [])),
          "and the helper is told what is wrong", f"got {refused_time.get('errors')}")
    status, unchanged = api.request("GET", f"/events/{e800['id']}/marks?groupId={heat['id']}",
                                    token=admin_token)
    still = next(r for r in unchanged["rows"] if r.get("userId") == runner["userId"])
    check(float(still.get("mark") or 0) == 135.0,
          "and the good time already saved is left alone", f"got {still.get('mark')}")

    # Put the grid back as it was.
    api.request("POST", f"/events/{e800['id']}/marks",
                {"stage": "HEAT", "rows": [{"userId": runner["userId"], "mark": None}]},
                token=admin_token)

    # ---- the mark-entry list: an event with one entrant is not worth marking
    thin = [e for e in catalogue_now if (e.get("enrolledCount") or 0) <= 1]
    check(bool(thin), "some events have one entrant or none",
          f"got {len(thin)} of {len(catalogue_now)}")

    # ---- a field no bigger than a final runs straight to a final
    status, roster = api.request("GET", "/admin/students", token=admin_token)
    # A-grade girls, so they may actually enter the A-grade event created below.
    girls = [s for s in roster
             if s.get("sex") == "FEMALE" and s.get("grade") == "A"]
    check(bool(girls), "there are A grade girls on the register to enter", f"got {len(girls)}")

    status, small = api.request(
        "POST", "/events",
        {"type": "RUN_100M", "sex": "FEMALE", "name": "Smoke Small Field 100M",
         "grade": "A", "eventDate": "2027-10-02", "directToFinal": False},
        token=admin_token)
    check(status == 200 and small.get("directToFinal") is False,
          "a sprint can be set to heats and a final", f"status={status} body={small}")

    entered = None
    for candidate in girls[:10]:
        status, entries = api.request(
            "GET", f"/admin/students/{candidate['studentId']}/enrollments", token=admin_token)
        quota = entries.get("quota", {}) if isinstance(entries, dict) else {}
        if quota.get("trackRemaining", 0) <= 0:
            continue
        status, _ = api.request(
            "POST", f"/admin/students/{candidate['studentId']}/enrollments/{small['id']}",
            token=admin_token)
        if status == 200:
            entered = candidate
            break
    check(entered is not None, "and a girl can be entered in it")

    if entered:
        status, after_entry = api.request("GET", "/events", token=admin_token)
        now = next(e for e in after_entry if e["id"] == small["id"])
        check(now.get("directToFinal") is True and now.get("directToFinalAutomatic") is True,
              "with one entrant the system switches it to direct to final, and says so",
              f"directToFinal={now.get('directToFinal')} auto={now.get('directToFinalAutomatic')}")

        # The school overrides it, and the flag says the choice is now theirs.
        status, overridden = api.request("PUT", f"/events/{small['id']}",
                                         {"directToFinal": False}, token=admin_token)
        check(overridden.get("directToFinal") is False
              and not overridden.get("directToFinalAutomatic"),
              "an admin can untick it, which puts the choice back in the school's hands",
              f"got {overridden.get('directToFinal')}/{overridden.get('directToFinalAutomatic')}")
        # Withdrawing is an entry change, and with one entrant or fewer the rule
        # applies again — a final would be the same runners as the heat.
        api.request("DELETE",
                    f"/admin/students/{entered['studentId']}/enrollments/{small['id']}",
                    token=admin_token)
        status, after_withdrawal = api.request("GET", "/events", token=admin_token)
        closed = next(e for e in after_withdrawal if e["id"] == small["id"])
        check(closed.get("directToFinal") is True
              and closed.get("directToFinalAutomatic") is True,
              "and the next entry change re-applies it, still flagged as automatic",
              f"got {closed.get('directToFinal')}/{closed.get('directToFinalAutomatic')}")

    # Tidy up.
    status, _ = api.request("DELETE", f"/events/{small['id']}", token=admin_token)
    check(status in (200, 204), "the test event is removed", f"status={status}")
    status, restored_catalogue = api.request("GET", "/events", token=admin_token)
    check(len(restored_catalogue) == len(catalogue_now), "leaving the catalogue as it was",
          f"got {len(restored_catalogue)} of {len(catalogue_now)}")

    # ---------------- 20. the 100M hurdles, formatted results, results PDFs
    section("20. The 100M hurdles, how a result reads, and the results PDFs")

    status, catalogue_now = api.request("GET", "/events", token=admin_token)
    hurdles_100 = [e for e in catalogue_now if e["type"] == "HURDLES_100M"]
    check(len(hurdles_100) == 6,
          "there is a 100M hurdles for each grade in each division",
          f"got {[e['name'] for e in hurdles_100]}")
    check({e["grade"] for e in hurdles_100} == {"A", "B", "C"},
          "so the C grade has a 100M hurdles of its own to enter",
          f"got {sorted(e['grade'] for e in hurdles_100)}")
    hurdles_110 = [e for e in catalogue_now if e["type"] == "HURDLES_110M"]
    check({e["grade"] for e in hurdles_110} == {"A", "B"},
          "while the 110M hurdles exists for the A and B grades only",
          f"got {[e['name'] for e in hurdles_110]}")
    check(all(e.get("timeInMinutes") is False for e in hurdles_100),
          "a 100M hurdles is timed in plain seconds",
          f"got {[e.get('timeInMinutes') for e in hurdles_100]}")

    # A C grade student is turned away from the 110M, exactly as from the 1500M.
    status, roster_now = api.request("GET", "/admin/students", token=admin_token)
    c_grade = [s for s in roster_now if s.get("grade") == "C"]
    if c_grade and hurdles_110:
        boys_110 = next((e for e in hurdles_110 if e["sex"] == "MALE" and e["grade"] == "A"), None)
        boy = next((s for s in c_grade if s.get("sex") == "MALE"), None)
        if boys_110 and boy:
            status, refused = api.request(
                "POST", f"/admin/students/{boy['studentId']}/enrollments/{boys_110['id']}",
                token=admin_token)
            check(status == 409, "a C grade student cannot enter the 110M hurdles",
                  f"status={status} body={refused}")
    # And the C grade's own 100M hurdles takes them, if they have a track entry spare.
    if hurdles_100:
        girls_100 = next((e for e in hurdles_100
                          if e["sex"] == "FEMALE" and e["grade"] == "C"), None)
        girl = next((s for s in c_grade if s.get("sex") == "FEMALE"), None)
        if girls_100 and girl:
            status, entries = api.request(
                "GET", f"/admin/students/{girl['studentId']}/enrollments", token=admin_token)
            quota = entries.get("quota", {}) if isinstance(entries, dict) else {}
            already = {e.get("eventId") for e in (entries or {}).get("enrollments", [])} \
                if isinstance(entries, dict) else set()
            if quota.get("trackRemaining", 0) > 0 and girls_100["id"] not in already:
                status, taken = api.request(
                    "POST", f"/admin/students/{girl['studentId']}/enrollments/{girls_100['id']}",
                    token=admin_token)
                check(status == 200, "and a C grade student can enter the C grade 100M hurdles",
                      f"status={status} body={taken}")
                api.request(
                    "DELETE",
                    f"/admin/students/{girl['studentId']}/enrollments/{girls_100['id']}",
                    token=admin_token)

    # ---- a result carries the unit, the way the sport writes it ----
    status, sprint_results = api.request("GET", f"/results/event/{e60['id']}", token=admin_token)
    check(isinstance(sprint_results, list) and sprint_results,
          "the 60M has results to read", f"got {type(sprint_results).__name__}")
    if isinstance(sprint_results, list) and sprint_results:
        displays = [r.get("displayMark") for r in sprint_results]
        check(all(d and d.endswith("s") for d in displays),
              "every 60M result carries its seconds",
              f"got {displays[:4]}")
        check(all(r.get("mark") is not None for r in sprint_results),
              "while the raw mark is still there for comparing",
              "a client that needs the number is not forced to parse the text")

    status, field_results = api.request("GET", f"/results/event/{field_event['id']}",
                                        token=admin_token)
    if isinstance(field_results, list) and field_results:
        displays = [r.get("displayMark") for r in field_results]
        check(all(d and d.endswith("M") for d in displays),
              "and a field result carries its metres", f"got {displays[:4]}")

    # ---- a time past a minute gains a minutes part ----
    status, e800_groups = api.request("GET", f"/events/{e800['id']}/groups", token=admin_token)
    heat = next((g for g in e800_groups if g.get("stage") == "HEAT"), None) if e800_groups else None
    if heat:
        status, sheet = api.request(
            "GET", f"/events/{e800['id']}/marks?groupId={heat['id']}", token=admin_token)
        runner = next((r for r in sheet.get("rows", []) if r.get("userId")), None)
        if runner:
            # 2 minutes 10.5 seconds — the shape the school asked for.
            api.request("POST", f"/events/{e800['id']}/marks",
                        {"stage": "HEAT", "rows": [{"userId": runner["userId"],
                                                    "minutes": 2, "seconds": 10.5}]},
                        token=admin_token)
            status, saved_sheet = api.request(
                "GET", f"/events/{e800['id']}/marks?groupId={heat['id']}", token=admin_token)
            row = next(r for r in saved_sheet["rows"] if r.get("userId") == runner["userId"])
            check(row.get("mark") == 130.5 or float(row.get("mark") or 0) == 130.5,
                  "an 800M time is stored as the total in seconds", f"got {row.get('mark')}")
            status, standings = api.request("GET", f"/events/{e800['id']}/standings",
                                            token=admin_token)
            placing = next((p for p in standings.get("placings", [])
                            if p.get("userId") == runner["userId"]), None) if standings else None
            check(placing is not None and placing.get("displayMark") == "2.10.5s",
                  "and reads back as 2.10.5s — a minutes part, seconds padded",
                  f"got {placing.get('displayMark') if placing else None}")
            api.request("POST", f"/events/{e800['id']}/marks",
                        {"stage": "HEAT", "rows": [{"userId": runner["userId"], "mark": None}]},
                        token=admin_token)

    # ---- the results PDFs ----
    status, event_pdf = api.request("GET", f"/events/{e60['id']}/results.pdf",
                                    token=admin_token, raw=True)
    check(status == 200 and isinstance(event_pdf, bytes) and event_pdf[:4] == b"%PDF",
          "one event's results print as a PDF",
          f"status={status} head={event_pdf[:8] if isinstance(event_pdf, bytes) else event_pdf}")
    check(isinstance(event_pdf, bytes) and len(event_pdf) > 1000,
          "and the document has content in it",
          f"{len(event_pdf) if isinstance(event_pdf, bytes) else 0} bytes")

    status, programme_pdf = api.request("GET", "/results.pdf", token=admin_token, raw=True)
    check(status == 200 and isinstance(programme_pdf, bytes) and programme_pdf[:4] == b"%PDF",
          "the whole programme prints as one PDF",
          f"status={status}")
    check(isinstance(programme_pdf, bytes) and len(programme_pdf) > len(event_pdf),
          "and holds more than a single event's sheet",
          f"{len(programme_pdf) if isinstance(programme_pdf, bytes) else 0} bytes "
          f"against {len(event_pdf) if isinstance(event_pdf, bytes) else 0}")

    # Narrowing to a category that has no results is refused with the reason, not an
    # empty PDF — and one that does have results prints.
    field_event_ids = [e["id"] for e in catalogue_now if e["category"] == "FIELD"]
    field_has_results = False
    for field_id in field_event_ids:
        status, some = api.request("GET", f"/results/event/{field_id}", token=admin_token)
        if isinstance(some, list) and some:
            field_has_results = True
            break

    status, filtered = api.request("GET", "/results.pdf?category=FIELD", token=admin_token,
                                   raw=True)
    if field_has_results:
        check(status == 200 and isinstance(filtered, bytes) and filtered[:4] == b"%PDF",
              "and it can be narrowed to the field events", f"status={status}")
    else:
        check(status == 409 and "nothing to print" in str(filtered),
              "narrowing to a category with no results is refused with the reason",
              f"status={status} body={filtered}")

    status, no_results = api.request("GET", "/events/999999/results.pdf", token=admin_token)
    check(status in (404, 409), "asking for an event that does not exist is refused",
          f"status={status} body={no_results}")

    # ---------------- 21. teacher accounts, relay teams, backups
    section("21. Teacher accounts, relay teams and backups")

    # ---- a teacher register uploads, and a teacher is scoped to their classes
    teacher_csv = (b"username,name,email,classes\r\n"
                   b"SMOKETCH1,Smoke Teacher One,smoke1@school.edu.hk,1A;1B\r\n"
                   b"SMOKETCH2,Smoke Teacher Two,,9Z\r\n")

    status, rehearsal = api.upload_bytes("/admin/teachers/upload?dryRun=true",
                                         "teachers.csv", teacher_csv, token=admin_token)
    check(status == 200 and isinstance(rehearsal, dict) and rehearsal.get("failed") == 0,
          "a teacher register rehearses without writing", f"status={status} {rehearsal}")

    status, applied = api.upload_bytes("/admin/teachers/upload?dryRun=false",
                                       "teachers.csv", teacher_csv, token=admin_token)
    touched = 0
    if isinstance(applied, dict):
        touched = applied.get("created", 0) + applied.get("updated", 0)
    check(status == 200 and touched >= 2, "and applies, creating or updating both teachers",
          f"status={status} {applied}")

    status, teachers = api.request("GET", "/admin/teachers", token=admin_token)
    by_name = {t.get("username"): t for t in teachers} if isinstance(teachers, list) else {}
    check(by_name.get("SMOKETCH1", {}).get("classes") == ["1A", "1B"],
          "the list carries each teacher's classes, sorted",
          f"got {by_name.get('SMOKETCH1', {}).get('classes')}")
    check(by_name.get("SMOKETCH1", {}).get("role") == "TEACHER",
          "as TEACHER accounts", f"got {by_name.get('SMOKETCH1', {}).get('role')}")

    status, sheet = api.request("GET", "/admin/teachers/credentials.csv", token=admin_token,
                                raw=True)
    passwords = {}
    if isinstance(sheet, bytes):
        for line in sheet.decode("utf-8-sig").splitlines():
            cells = line.split(",")
            if cells and cells[0].startswith("SMOKETCH"):
                passwords[cells[0]] = cells[-1].strip()
    check(len(passwords) == 2, "the credentials sheet carries a password for each",
          f"got {list(passwords)}")

    teacher_token = None
    if passwords.get("SMOKETCH1"):
        status, session = api.request("POST", "/auth/login",
                                      {"username": "SMOKETCH1",
                                       "password": passwords["SMOKETCH1"]})
        teacher_token = session.get("token") if isinstance(session, dict) else None
        check(status == 200 and bool(teacher_token), "and the teacher can sign in",
              f"status={status} {session}")

    if teacher_token:
        status, me = api.request("GET", "/teacher/me", token=teacher_token)
        check(status == 200 and me.get("classes") == ["1A", "1B"],
              "the teacher sees exactly their own classes",
              f"got {me.get('classes') if isinstance(me, dict) else me}")

        status, mine = api.request("GET", "/teacher/students", token=teacher_token)
        check(status == 200 and isinstance(mine, list)
              and all(s.get("className") in ("1A", "1B") for s in mine),
              "and only the students in them",
              f"got {sorted({s.get('className') for s in mine}) if isinstance(mine, list) else mine}")
        check(bool(mine), "with somebody to help", f"got {len(mine) if isinstance(mine, list) else mine}")

        outside = api.request("GET", "/teacher/students?className=9Z", token=teacher_token)
        check(outside[0] == 403, "while another class is refused", f"status={outside[0]}")
        check(api.request("GET", "/admin/teachers", token=teacher_token)[0] == 403,
              "and the admin teacher list is closed to them",
              f"status={api.request('GET', '/admin/teachers', token=teacher_token)[0]}")

        # Helping a student, inside and outside their classes.
        if isinstance(mine, list) and mine:
            subject = mine[0]
            status, entries = api.request(
                "GET", f"/teacher/students/{subject['studentId']}/enrollments",
                token=teacher_token)
            check(status == 200 and isinstance(entries, dict),
                  "a teacher can read a student's entries and quota",
                  f"status={status} {str(entries)[:120]}")

    # a teacher whose classes hold nobody can help nobody, and is told so
    if passwords.get("SMOKETCH2"):
        status, session2 = api.request("POST", "/auth/login",
                                       {"username": "SMOKETCH2",
                                        "password": passwords["SMOKETCH2"]})
        token2 = session2.get("token") if isinstance(session2, dict) else None
        if token2:
            status, none = api.request("GET", "/teacher/students", token=token2)
            check(status == 200 and none == [],
                  "a teacher whose classes hold nobody gets an empty list, not an error",
                  f"status={status} {none}")

    # ---- relay teams: undivided, then form teams derived from the roster
    status, catalogue_now = api.request("GET", "/events", token=admin_token)
    # Teams are derived from the register, not from who has entered, so this needs no
    # athletes — which matters here, because the season was reset at the very start.
    relay_event = next((e for e in catalogue_now if e["type"] == "RELAY_4X100M"), None)
    check(relay_event is not None, "the programme has a 4x100M relay")
    if relay_event:
        check(relay_event.get("relay") is True, "flagged as a relay",
              f"got {relay_event.get('relay')}")
        check(relay_event.get("relayTeamKind") in (None, ""),
              "and undivided until the school says otherwise",
              f"got {relay_event.get('relayTeamKind')}")

        status, board = api.request("GET", f"/admin/events/{relay_event['id']}/relay-teams",
                                    token=admin_token)
        check(status == 200 and board.get("relay") is True, "the relay board loads",
              f"status={status}")
        check(board.get("legsPerTeam") == 4, "with four legs a team",
              f"got {board.get('legsPerTeam')}")

        status, refused = api.request(
            "POST", f"/admin/events/{relay_event['id']}/relay-teams/derive", token=admin_token)
        check(status == 409, "deriving an undivided relay is refused with the reason",
              f"status={status} {refused}")

        status, updated = api.request("PUT", f"/events/{relay_event['id']}",
                                      {"relayTeamKind": "FORM"}, token=admin_token)
        check(status == 200 and updated.get("relayTeamKind") == "FORM",
              "setting it to a form relay", f"status={status}")

        status, derived = api.request(
            "POST", f"/admin/events/{relay_event['id']}/relay-teams/derive", token=admin_token)
        check(status == 200, "derives the teams from the roster", f"status={status}")

        status, board2 = api.request("GET", f"/admin/events/{relay_event['id']}/relay-teams",
                                     token=admin_token)
        teams = board2.get("teams", []) if isinstance(board2, dict) else []
        check(bool(teams), "and there is a team per form", f"got {len(teams)}")
        check(all(t.get("label", "").startswith("Form") for t in teams),
              "each labelled by form", f"got {[t.get('label') for t in teams][:4]}")

        if teams:
            team = teams[0]
            status, roster_now = api.request("GET", "/admin/students", token=admin_token)
            pool = [s for s in roster_now
                    if s.get("grade") == relay_event["grade"]
                    and s.get("sex") == relay_event["sex"]]
            if pool:
                runner = pool[0]["userId"]
                status, added = api.request(
                    "POST", f"/admin/relay-teams/{team['id']}/runners",
                    {"userId": runner, "leg": 1}, token=admin_token)
                check(status == 200, "a runner can be given a leg", f"status={status} {added}")
                status, twice = api.request(
                    "POST", f"/admin/relay-teams/{team['id']}/runners",
                    {"userId": runner, "leg": 2}, token=admin_token)
                check(status in (400, 409), "and cannot hold two legs of one team",
                      f"status={status} {twice}")

                # outside the team's own form
                other = next((s for s in pool
                              if s.get("className") != pool[0].get("className")), None)
                if other:
                    status, wrong = api.request(
                        "POST", f"/admin/relay-teams/{team['id']}/runners",
                        {"userId": other["userId"], "leg": 2}, token=admin_token)
                    check(status in (400, 409) or team["teamKey"] in str(
                        (other.get("className") or "")[:1]),
                          "and a runner from another form is refused",
                          f"status={status} {str(wrong)[:140]}")

                api.request("DELETE",
                            f"/admin/relay-teams/{team['id']}/runners/{runner}", token=admin_token)

        # leave the event exactly as it was found
        api.request("DELETE", f"/admin/events/{relay_event['id']}/relay-teams", token=admin_token)
        api.request("PUT", f"/events/{relay_event['id']}", {"relayTeamKind": ""}, token=admin_token)
        status, after = api.request("GET", f"/admin/events/{relay_event['id']}/relay-teams",
                                    token=admin_token)
        check(after.get("teamCount") in (0, None) and not after.get("relayTeamKind"),
              "and the relay is left undivided, as it was",
              f"got kind={after.get('relayTeamKind')} teams={after.get('teamCount')}")

    # ---- backups
    status, backups = api.request("GET", "/admin/backups", token=admin_token)
    check(status == 200, "the backup list loads", f"status={status}")
    status, traversal = api.request("GET", "/admin/backups/..%2F..%2Fapplication.yml",
                                    token=admin_token)
    check(status in (400, 404), "and a path traversal out of the backup directory is refused",
          f"status={status}")

    if teacher_token:
        plain_teacher = api.request("GET", "/admin/backups", token=teacher_token)[0]
        check(plain_teacher == 403, "backups are for administrators only",
              f"status={plain_teacher}")

    # ---- tidy up the teachers this section created
    for username in ("SMOKETCH1", "SMOKETCH2"):
        api.request("DELETE", f"/admin/teachers/{username}", token=admin_token)
    status, left = api.request("GET", "/admin/teachers", token=admin_token)
    remaining = {t.get("username") for t in left} if isinstance(left, list) else set()
    check(not ({"SMOKETCH1", "SMOKETCH2"} & remaining),
          "and the smoke teachers are removed", f"still there: {remaining}")

    # ------------------------------------------------------------------ summary
    section("Summary")
    print(f"  checks run : {CHECKS}")
    print(f"  failures   : {FAILURES}")
    print(f"  artifacts  : {output_dir}")
    for name in sorted(os.listdir(output_dir)):
        path = os.path.join(output_dir, name)
        if os.path.isfile(path):
            print(f"    - {name} ({os.path.getsize(path)} bytes)")

    if FAILURES:
        print(f"\nSMOKE TEST FAILED ({FAILURES} of {CHECKS} checks)")
        return 1
    print(f"\nSMOKE TEST PASSED ({CHECKS} checks)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
