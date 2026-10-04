"""Live check of the teacher, relay and backup features.

Creates one throwaway teacher (and removes it), derives one relay event's teams and
puts a runner in a team, and inspects what the backup endpoints offer. Everything it
creates, it removes or reports.
"""

import io
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


def request(method, path, body=None, token=None, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Accept": "*/*"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(API + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=300) as response:
            payload = response.read()
            if raw:
                return response.status, payload
            return response.status, (json.loads(payload.decode()) if payload else None)
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def upload(path, filename, content, token):
    boundary = "----sportdaycheck"
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        "Content-Type: text/csv\r\n\r\n"
    ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
    headers = {"Content-Type": f"multipart/form-data; boundary={boundary}",
               "Authorization": "Bearer " + token}
    req = urllib.request.Request(API + path, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=300) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def main():
    admin = request("POST", "/auth/login",
                    {"username": "admin", "password": "admin123"})[1]["token"]

    # ---------------------------------------------------- 1. teacher accounts
    print("\n1. Teacher accounts")
    csv = (b"username,name,email,classes\r\n"
           b"CHECKTEACH1,Check Teacher,check1@school.edu.hk,1A;1B\r\n"
           b"CHECKTEACH2,Other Teacher,,9Z\r\n")

    status, dry = upload("/admin/teachers/upload?dryRun=true", "c.csv", csv, admin)
    check(status == 200, "a teacher register rehearses with dryRun", f"status={status} {dry}")
    if isinstance(dry, dict):
        check(dry.get("failed") == 0, "with no bad rows", f"got {dry.get('failed')} {dry.get('errors')}")

    status, roster_before = request("GET", "/admin/teachers", token=admin)
    before = len(roster_before) if isinstance(roster_before, list) else -1

    status, applied = upload("/admin/teachers/upload?dryRun=false", "c.csv", csv, admin)
    check(status == 200 and isinstance(applied, dict), "and applies", f"status={status} {applied}")
    # A rerun finds the teachers already there and updates them, which is the
    # documented behaviour, so count both.
    touched = 0
    if isinstance(applied, dict):
        touched = applied.get("created", 0) + applied.get("updated", 0)
    check(isinstance(applied, dict) and touched >= 2,
          "creating or updating the teacher accounts",
          f"created={applied.get('created') if isinstance(applied, dict) else applied} "
          f"updated={applied.get('updated') if isinstance(applied, dict) else ''}")

    status, roster = request("GET", "/admin/teachers", token=admin)
    names = {t.get("username"): t for t in roster} if isinstance(roster, list) else {}
    check("CHECKTEACH1" in names, "the teacher is on the list", f"got {list(names)[:5]}")
    check(names.get("CHECKTEACH1", {}).get("classes") == ["1A", "1B"],
          "with their classes, sorted",
          f"got {names.get('CHECKTEACH1', {}).get('classes')}")

    # the credentials export gives us a password to sign in with
    status, creds = request("GET", "/admin/teachers/credentials.csv", token=admin, raw=True)
    check(status == 200, "the credentials export downloads", f"status={status}")
    password = None
    if isinstance(creds, bytes):
        text = creds.decode("utf-8-sig")
        for line in text.splitlines():
            if line.startswith("CHECKTEACH1"):
                password = line.split(",")[-1].strip()
    check(password is not None, "and carries a password for the teacher", f"line missing")

    if password:
        status, session = request("POST", "/auth/login",
                                  {"username": "CHECKTEACH1", "password": password})
        check(status == 200, "the teacher can sign in", f"status={status} {session}")
        teacher = session.get("token") if isinstance(session, dict) else None

        status, me = request("GET", "/teacher/me", token=teacher)
        check(status == 200 and me.get("classes") == ["1A", "1B"],
              "and their own classes come back", f"got {me.get('classes') if isinstance(me, dict) else me}")

        status, students = request("GET", "/teacher/students", token=teacher)
        check(status == 200 and isinstance(students, list),
              "they can list the students in those classes", f"status={status}")
        if isinstance(students, list) and students:
            check(all(s.get("className") in ("1A", "1B") for s in students),
                  "and only those classes",
                  f"got {sorted({s.get('className') for s in students})}")
        status, outside = request("GET", "/teacher/students?className=9Z", token=teacher)
        check(status == 403, "another class is refused with 403", f"status={status} {outside}")

        # the second teacher has a class no student is in, so they can help nobody
        status, creds2 = request("GET", "/admin/teachers/credentials.csv", token=admin, raw=True)
        pw2 = None
        for line in creds2.decode("utf-8-sig").splitlines():
            if line.startswith("CHECKTEACH2"):
                pw2 = line.split(",")[-1].strip()
        if pw2:
            status, s2 = request("POST", "/auth/login", {"username": "CHECKTEACH2", "password": pw2})
            t2 = s2.get("token") if isinstance(s2, dict) else None
            status, none = request("GET", "/teacher/students", token=t2)
            check(status == 200 and none == [],
                  "a teacher whose classes hold nobody sees an empty list, not an error",
                  f"status={status} {none}")

        check(request("GET", "/admin/teachers", token=teacher)[0] == 403,
              "and a teacher cannot reach the admin teacher list",
              f"status={request('GET', '/admin/teachers', token=teacher)[0]}")

    # ------------------------------------------------------------- 2. relays
    print("\n2. Relay teams")
    events = request("GET", "/events", token=admin)[1]
    relay = next(e for e in events if e["type"] == "RELAY_4X100M"
                 and (e.get("enrolledCount") or 0) > 4)
    print(f"  {relay['name']} (id {relay['id']}), {relay['enrolledCount']} entered")

    status, board = request("GET", f"/admin/events/{relay['id']}/relay-teams", token=admin)
    check(status == 200 and board.get("relay") is True, "the relay board loads",
          f"status={status} {board if status != 200 else ''}")
    check(board.get("legsPerTeam") == 4, "with four legs a team", f"got {board.get('legsPerTeam')}")

    # an undivided relay has no teams until a kind is set
    if board.get("relayTeamKind") is None:
        check(board.get("teamCount") in (0, None),
              "an undivided relay offers no teams yet", f"got {board.get('teamCount')}")
        status, refused = request("POST", f"/admin/events/{relay['id']}/relay-teams/derive",
                                  token=admin)
        check(status in (400, 409),
              "and deriving without a kind is refused with a reason",
              f"status={status} {refused}")

        status, updated = request("PUT", f"/events/{relay['id']}",
                                  {"relayTeamKind": "FORM"}, token=admin)
        check(status == 200 and updated.get("relayTeamKind") == "FORM",
              "setting it to a form relay", f"status={status} {updated}")
        status, derived = request("POST", f"/admin/events/{relay['id']}/relay-teams/derive",
                                  token=admin)
        check(status == 200, "derives the teams from the roster", f"status={status} {derived}")
        status, board2 = request("GET", f"/admin/events/{relay['id']}/relay-teams", token=admin)
        teams = board2.get("teams", []) if isinstance(board2, dict) else []
        check(len(teams) > 0, "and there is at least one form team", f"got {len(teams)}")
        check(all(t.get("label", "").startswith("Form") for t in teams),
              "each labelled by form", f"got {[t.get('label') for t in teams][:4]}")
        check(all(len(t.get("members", [])) == 0 for t in teams),
              "with no runners picked yet", "the school picks them")

        # put one runner in the first team
        if teams:
            team = teams[0]
            candidates = request("GET", f"/admin/students", token=admin)[1]
            pool = [s for s in candidates
                    if s.get("grade") == relay["grade"] and s.get("sex") == relay["sex"]]
            if pool:
                status, added = request("POST", f"/admin/relay-teams/{team['id']}/runners",
                                        {"userId": pool[0]["userId"], "leg": 1}, token=admin)
                check(status == 200, "a runner can be put in a team", f"status={status} {added}")
                status, again = request("POST", f"/admin/relay-teams/{team['id']}/runners",
                                        {"userId": pool[0]["userId"], "leg": 2}, token=admin)
                check(status in (400, 409), "and the same runner cannot hold two legs",
                      f"status={status} {again}")
                # leave it clean
                request("DELETE",
                        f"/admin/relay-teams/{team['id']}/runners/{pool[0]['userId']}", token=admin)
                request("DELETE", f"/admin/events/{relay['id']}/relay-teams", token=admin)
                request("PUT", f"/events/{relay['id']}", {"relayTeamKind": ""}, token=admin)
                print("  (relay left as it was: teams removed, kind cleared)")

    # ------------------------------------------------------------ 3. backups
    print("\n3. Backups")
    status, backups = request("GET", "/admin/backups", token=admin)
    check(status == 200, "the backup list loads", f"status={status}")
    check(isinstance(backups, (list, dict)), "and returns something listable",
          f"got {type(backups).__name__}")
    status, traversal = request("GET", "/admin/backups/..%2F..%2Fapplication.yml", token=admin)
    check(status in (400, 404), "a path traversal is refused", f"status={status} {traversal}")

    # ---------------------------------------------------------------- tidying
    print("\nCleaning up")
    for username in ("CHECKTEACH1", "CHECKTEACH2"):
        status, _ = request("DELETE", f"/admin/teachers/{username}", token=admin)
        if status not in (200, 204):
            print(f"  note: {username} not removed via API (status {status})")
    status, after = request("GET", "/admin/teachers", token=admin)
    remaining = {t.get("username") for t in after} if isinstance(after, list) else set()
    check(not ({"CHECKTEACH1", "CHECKTEACH2"} & remaining),
          "the throwaway teachers are gone", f"still there: {remaining}")

    print(f"\n  checks {CHECKS}, failures {FAILED}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
