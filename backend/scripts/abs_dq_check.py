"""A live ABS/DQ round trip, which no agent could exercise.

Marks an athlete ABS, checks the placings, results and record behave, then puts the
row back exactly as it was. Read the output for what actually changed.
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
        with urllib.request.urlopen(req, timeout=300) as response:
            raw = response.read().decode()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def main():
    token = request("POST", "/auth/login",
                    {"username": "admin", "password": "admin123"})[1]["token"]

    # A track event that ran heats, has marks, and whose heat stage we can edit.
    _, events = request("GET", "/events", token=token)
    event = next(e for e in events
                 if e["type"] == "RUN_100M" and (e.get("enrolledCount") or 0) > 2
                 and (e.get("groupCount") or 0) > 0)
    print(f"\n{event['name']} (id {event['id']}), {event['enrolledCount']} entered")

    status, sheet = request("GET", f"/events/{event['id']}/marks?stage=HEAT", token=token)
    marked = [r for r in sheet["rows"] if r.get("mark") is not None]
    check(bool(marked), "the heat has marks to work with", f"{len(marked)} marked")
    if not marked:
        return 1
    victim = marked[0]
    original = victim["mark"]
    print(f"  working on {victim.get('studentRef')} holding {original}")

    # ---- ABS ----
    status, saved = request("POST", f"/events/{event['id']}/marks",
                            {"stage": "HEAT", "rows": [{"userId": victim["userId"],
                                                        "outcome": "ABS"}]}, token=token)
    check(status == 200 and saved.get("failed") == 0, "an athlete can be marked ABS",
          f"status={status} {saved}")

    status, after = request("GET", f"/events/{event['id']}/marks?stage=HEAT", token=token)
    row = next(r for r in after["rows"] if r["userId"] == victim["userId"])
    check(row.get("outcome") == "ABS", "the sheet reads back ABS", f"got {row.get('outcome')}")
    check(row.get("mark") is None, "with no mark", f"got {row.get('mark')}")
    check(not row.get("attempts"), "and no attempts", f"got {row.get('attempts')}")

    status, results = request("GET", f"/results/event/{event['id']}", token=token)
    entry = next((r for r in results if r["userId"] == victim["userId"]), None)
    check(entry is not None and entry.get("displayMark") == "ABS",
          "the results list shows ABS in place of a mark",
          f"got {entry.get('displayMark') if entry else None}")

    status, standings = request("GET", f"/events/{event['id']}/standings", token=token)
    placings = standings.get("placings", [])
    placed = [p for p in placings if p.get("place", 0) > 0]
    unplaced = [p for p in placings if not p.get("place")]
    mine = [p for p in placings if p["userId"] == victim["userId"]]
    check(bool(mine) and mine[0].get("place") == 0 and mine[0].get("points") == 0,
          "and the athlete is not placed and scores nothing",
          f"got {mine[0] if mine else None}")
    check(bool(unplaced) and unplaced[-1]["userId"] == victim["userId"],
          "appearing after the placed athletes",
          f"unplaced tail: {[p.get('studentRef') for p in unplaced[-3:]]}")
    check(all(p.get("displayMark") != "ABS" for p in placed),
          "and never among the placed")

    # ---- DQ ----
    request("POST", f"/events/{event['id']}/marks",
            {"stage": "HEAT", "rows": [{"userId": victim["userId"], "outcome": "DQ"}]},
            token=token)
    status, after_dq = request("GET", f"/events/{event['id']}/marks?stage=HEAT", token=token)
    row = next(r for r in after_dq["rows"] if r["userId"] == victim["userId"])
    check(row.get("outcome") == "DQ", "the outcome can be changed to DQ",
          f"got {row.get('outcome')}")

    # ---- back to a mark ----
    status, restored = request("POST", f"/events/{event['id']}/marks",
                               {"stage": "HEAT", "rows": [{"userId": victim["userId"],
                                                           "mark": original}]}, token=token)
    status, back = request("GET", f"/events/{event['id']}/marks?stage=HEAT", token=token)
    row = next(r for r in back["rows"] if r["userId"] == victim["userId"])
    check(row.get("outcome") == "RESULT" and float(row.get("mark") or 0) == float(original),
          "and a real mark can be put back, as RESULT",
          f"got outcome={row.get('outcome')} mark={row.get('mark')}")

    print(f"\n  checks {CHECKS}, failures {FAILED}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
