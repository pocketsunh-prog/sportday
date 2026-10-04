"""The full backup round trip, live.

Takes the season reset (which must write a backup first), proves the reset emptied
the sporting data, then restores that backup and proves everything came back.

This is destructive while it runs — it is the season reset and a restore — but it
ends with the data restored, which is the point: the unit tests prove this against
fakes, and this proves it against the real database and its foreign keys.

    python backend/scripts/backup_round_trip_check.py

If it ever leaves the season empty, `full_retest.py` regenerates a whole school day.
"""

import json
import urllib.error
import urllib.request

API = "http://localhost:8080/api"
CHECKS = 0
FAILED = 0


def check(condition, message, detail=""):
    global CHECKS, FAILED
    CHECKS += 1
    print(("  [PASS] " if condition else "  [FAIL] ") + message)
    if not condition:
        FAILED += 1
        if detail:
            print(f"         {detail}")


def request(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Accept": "*/*"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(API + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=600) as response:
            raw = response.read().decode()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def season_counts(token):
    """What the season reset touches, read back from the API."""
    _, events = request("GET", "/events", token=token)
    entries = sum(e.get("enrolledCount") or 0 for e in events)
    results = 0
    with_results = 0
    for event in events:
        status, rows = request("GET", f"/results/event/{event['id']}", token=token)
        if status == 200 and isinstance(rows, list) and rows:
            results += len(rows)
            with_results += 1
    return {"events": len(events), "entries": entries, "results": results,
            "eventsWithResults": with_results}


def main():
    token = request("POST", "/auth/login",
                    {"username": "admin", "password": "admin123"})[1]["token"]

    print("\nBefore")
    before = season_counts(token)
    print(f"  {before}")
    if before["results"] == 0:
        print("  Nothing to back up — run full_retest.py first.")
        return 1

    status, backups_before = request("GET", "/admin/backups", token=token)
    names_before = {b.get("name") for b in backups_before} if isinstance(backups_before, list) else set()
    print(f"  {len(names_before)} backup file(s) on disk")

    # ------------------------------------------------------- the reset itself
    print("\nReset")
    status, summary = request("POST", "/admin/season/reset", token=token)
    check(status == 200, "the season reset runs", f"status={status} {str(summary)[:200]}")
    if not isinstance(summary, dict):
        return 1

    check(bool(summary.get("backupFile")), "and reports the backup it wrote",
          f"got {summary.get('backupFile')}")
    check((summary.get("backupBytes") or 0) > 0, "which has content in it",
          f"got {summary.get('backupBytes')} bytes")
    # The reset clears every enrollment row, withdrawn ones included, while
    # enrolledCount counts only the confirmed ones — so it removes at least as many.
    check(summary.get("enrollmentsRemoved", 0) >= before["entries"],
          "having removed every entry, withdrawn rows included",
          f"removed {summary.get('enrollmentsRemoved')}, of which {before['entries']} were confirmed")
    check(summary.get("resultsRemoved", 0) == before["results"],
          "and every result", f"{summary.get('resultsRemoved')} of {before['results']}")

    status, backups_after = request("GET", "/admin/backups", token=token)
    names = [b.get("name") for b in backups_after] if isinstance(backups_after, list) else []
    check(summary.get("backupFile") in names, "and the file is listed",
          f"{summary.get('backupFile')} not in {names[:4]}")
    check(len(names) == len(names_before) + 1, "exactly one new file",
          f"{len(names_before)} -> {len(names)}")

    print("\nAfter the reset")
    emptied = season_counts(token)
    print(f"  {emptied}")
    check(emptied["entries"] == 0, "the season holds no entries", f"got {emptied['entries']}")
    check(emptied["results"] == 0, "and no results", f"got {emptied['results']}")
    check(emptied["events"] == before["events"],
          "while the catalogue is kept, as documented",
          f"{emptied['events']} vs {before['events']}")

    # ------------------------------------------------------------- the restore
    print("\nRestore")
    name = summary["backupFile"]
    status, restored = request("POST", f"/admin/backups/{name}/restore", token=token)
    check(status == 200, "the backup restores", f"status={status} {str(restored)[:250]}")

    after = season_counts(token)
    print(f"  {after}")
    check(after["entries"] == before["entries"],
          "every entry is back", f"{after['entries']} of {before['entries']}")
    check(after["results"] == before["results"],
          "every result is back", f"{after['results']} of {before['results']}")
    check(after["eventsWithResults"] == before["eventsWithResults"],
          "across the same events", f"{after['eventsWithResults']} of {before['eventsWithResults']}")

    # A sampled mark has to be a real performance, not just a row count.
    _, events = request("GET", "/events", token=token)
    sample = None
    for event in events:
        status, rows = request("GET", f"/results/event/{event['id']}", token=token)
        if status == 200 and isinstance(rows, list) and rows:
            with_mark = [r for r in rows if r.get("mark") is not None]
            if with_mark:
                sample = (event["name"], with_mark[0])
                break
    check(sample is not None, "and the marks carry real values")
    if sample:
        print(f"  {sample[0]}: {sample[1].get('displayMark')} ({sample[1].get('mark')})")

    print(f"\n  checks {CHECKS}, failures {FAILED}")
    if FAILED:
        print("\n  The season may be short of data — run full_retest.py to rebuild it.")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
