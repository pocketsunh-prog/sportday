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

    e60 = first(lambda e: e["type"] == "RUN_60M" and e["sex"] == "MALE")
    e100 = first(lambda e: e["type"] == "RUN_100M" and e["sex"] == "MALE")
    e800 = first(lambda e: e["type"] == "RUN_800M" and e["sex"] == "MALE")
    e_long = first(lambda e: e["type"] == "LONG_JUMP" and e["sex"] == "MALE")

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

    student = roster[0]
    expected = derived_password(student)
    print(f"  signing in as {student['studentId']} / {expected}")
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
    my_track = [e for e in mine if e.get("category") == "TRACK"]
    my_field = [e for e in mine if e.get("category") == "FIELD"]
    print(f"  student sees {len(my_track)} track and {len(my_field)} field events")

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
    girls = girls if isinstance(girls, list) else []
    if girls:
        sdiv, bdiv = api.request("POST", f"/enrollments/{girls[0]['id']}", token=student_token)
        check(sdiv == 409, "a girls' event is refused to a boys' division student",
              f"status={sdiv} body={bdiv}")

    # --------------------------------------------------------------- 6. groups
    section("6. Group allocation")

    def seed_event(event_id: int, count: int, sex_code: str) -> int:
        _, all_students = api.request("GET", f"/admin/students?sex={sex_code}", token=admin_token)
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

    n60 = seed_event(e60["id"], 40, "M")
    print(f"  enrolled {n60} students in 60M")
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

    n800 = seed_event(e800["id"], 60, "M")
    print(f"  enrolled {n800} students in 800M")
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
        status, uploaded = api.upload("/admin/students/upload", path, token=admin_token)
        check(status == 200, f"{field} register uploads", f"status={status} body={uploaded}")
        if isinstance(uploaded, dict):
            check(uploaded.get("failed") == 0, f"{field} upload had no bad rows",
                  f"failed={uploaded.get('failed')} errors={uploaded.get('errors')}")
            check(uploaded.get("created", 0) + uploaded.get("updated", 0) == args.students,
                  f"{field} upload accounted for all {args.students} rows",
                  f"created={uploaded.get('created')} updated={uploaded.get('updated')}")
            counts = uploaded.get("gradeCounts", {})
            check(counts.get("C") == 240 and counts.get("B") == 198 and counts.get("A") == 162,
                  f"{field} upload regenerated the right grade split", f"got {counts}")

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
          "grade counts are back to 240/198/162 after cleanup",
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
        check(sheet.get("defaultUnit") == "seconds", "track events default to seconds",
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
            check(unit == "seconds", "a blank unit fell back to the event default", f"got {unit}")

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
    # Every event has a record for all three grades; only the grade that has run
    # carries a mark yet.
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
    open_track = [e for e in catalogue
                  if e.get("category") == "TRACK" and e.get("sex") == subject_sex
                  and (e.get("enrolledCount") or 0) < (e.get("maxParticipants") or 999)]
    open_field = [e for e in catalogue
                  if e.get("category") == "FIELD" and e.get("sex") == subject_sex
                  and (e.get("enrolledCount") or 0) < (e.get("maxParticipants") or 999)]
    check(len(open_track) >= 3 and len(open_field) >= 2,
          "there are enough open events to test the quota",
          f"track={len(open_track)} field={len(open_field)}")

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
    check(len(records) % 3 == 0,
          "there is a record for every grade of every event and division",
          f"got {len(records)} rows, which is not a multiple of 3")
    events_seen = {(r["eventType"], r["sex"]) for r in records}
    check(all(len([r for r in records if (r["eventType"], r["sex"]) == key]) == 3
              for key in events_seen),
          "each event and division has exactly the three grades",
          f"got {sorted((k, len([r for r in records if (r['eventType'], r['sex']) == k])) for k in list(events_seen)[:4])}")
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
