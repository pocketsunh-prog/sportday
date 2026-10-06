"""Rebuild the relay data as form-class relays and grade-house relays.

A form-class relay is scoped to a FORM and takes one team per class ACROSS that
form's grades (1A, 1B, 1C, 1D). A grade-house relay is scoped to a grade and
takes one team per grade x house (C Grade Green). Both must be READY -- at least
two teams, every team holding four runners -- or the readiness gate keeps them
out of mark entry and the print run.

Written as a file rather than a shell one-liner: passing quotes through a
PowerShell here-string mangled them repeatedly earlier in this session.
"""

import json
import urllib.error
import urllib.request

BASE = "http://localhost:8080/api"

# event id -> the form it becomes. The six FORM relays cover Forms 1 to 6, so a
# reader sees the whole form range in the data, not one lucky form.
FORM_OF = {166: "1", 168: "2", 172: "3", 174: "4", 170: "5", 176: "6"}


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        with urllib.request.urlopen(
                urllib.request.Request(BASE + path, data=data,
                                       headers=headers, method=method),
                timeout=300) as r:
            raw = r.read()
            return r.status, json.loads(raw.decode("utf-8")) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw.decode("utf-8"))
        except Exception:
            return e.code, raw[:200]


def msg(body):
    return body.get("message") if isinstance(body, dict) else str(body)[:160]


status, login = req("POST", "/auth/login",
                    {"username": "admin", "password": "admin123"})
token = login["token"]

status, students = req("GET", "/admin/students", token=token)
status, events = req("GET", "/events", token=token)
relays = {e["id"]: e for e in events
          if e["type"] in ("RELAY_4X100M", "RELAY_4X400M")}

for event_id in sorted(FORM_OF):
    event = relays[event_id]
    form = FORM_OF[event_id]
    sex, grade = event["sex"], event["grade"]

    # A form relay's name says the form, because its grade no longer decides who
    # may run. The dash is ASCII: the live names carry a mangled one.
    name = "%s %s Relay - Form %s" % (
        "Boys" if sex == "MALE" else "Girls",
        "4x100M" if event["type"] == "RELAY_4X100M" else "4x400M", form)

    status, body = req("PUT", "/events/%d" % event_id,
                       {"relayTeamKind": "FORM", "form": form, "name": name},
                       token=token)
    if status != 200:
        print("  %d: setting the form failed %s %s" % (event_id, status, msg(body)))
        continue

    # Clear, then derive, so the teams are the form's classes and nothing stale.
    req("DELETE", "/admin/events/%d/relay-teams" % event_id, token=token)
    status, derived = req("POST", "/admin/events/%d/relay-teams/derive" % event_id,
                          {}, token=token)
    if status != 200:
        print("  %d: derive failed %s %s" % (event_id, status, msg(derived)))
        continue

    board = derived.get("board") or {}
    teams = board.get("teams") or []
    filled, short = 0, []
    for team in teams:
        key = team.get("teamKey")
        pool = [s for s in students
                if s.get("sex") == sex and s.get("className") == key]
        if len(pool) < 4:
            short.append("%s(%d)" % (key, len(pool)))
            continue
        ok = 0
        for leg, student in enumerate(pool[:4], start=1):
            st, _ = req("POST", "/admin/relay-teams/%d/runners" % team["id"],
                        {"userId": student["userId"], "leg": leg}, token=token)
            if st == 200:
                ok += 1
        if ok == 4:
            filled += 1
        else:
            short.append("%s(%d added)" % (key, ok))

    labels = [t.get("label") for t in teams]
    print("  %d Form %s: %d teams %s -> %d filled with four"
          % (event_id, form, len(teams), labels[:6], filled))
    if short:
        print("       short and left unfilled: %s" % ", ".join(short))

print()
print("=== the 12 relays now ===")
for event_id in sorted(relays):
    status, board = req("GET", "/admin/events/%d/relay-teams" % event_id, token=token)
    if not isinstance(board, dict):
        print("  %d: unreadable %s" % (event_id, status))
        continue
    teams = board.get("teams") or []
    sizes = [len(t.get("members") or []) for t in teams]
    ready = len(teams) >= 2 and bool(sizes) and all(s >= 4 for s in sizes)
    print("  %-22s kind=%-5s form=%-4s grade=%s %-6s teams=%d runners=%d READY=%s"
          % (relays[event_id]["name"][:22], board.get("relayTeamKind"),
             board.get("form"), board.get("grade"), board.get("sex"),
             len(teams), sum(sizes), ready))
