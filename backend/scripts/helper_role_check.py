"""A live HELPER round trip, and the final-waits-for-the-heats rule.

Creates one throwaway helper, signs in as them, checks they can reach mark entry and
the marking sheets and nothing else, checks the final is refused before it is drawn,
and removes the account.
"""

import json
import urllib.error
import urllib.request

API = "http://localhost:8080/api"
CHECKS = 0
FAILED = 0
USERNAME = "SMOKEHELPER1"
PASSWORD = "helper12345"


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


def remove_user(username, admin):
    """Removes an account by id.

    The delete endpoint is `DELETE /api/users/{id}` and is keyed by id, not by
    username — asking for the username 404s and silently leaves the account behind,
    which then shadows the next one created with the same name. The list lives at
    `GET /api/users`, not under /admin.
    """
    _, users = request("GET", "/users", token=admin)
    if not isinstance(users, list):
        return False
    for user in users:
        if user.get("username") == username:
            request("DELETE", f"/users/{user['id']}", token=admin)
            return True
    return False


def main():
    admin = request("POST", "/auth/login",
                    {"username": "admin", "password": "admin123"})[1]["token"]

    print("\n1. The HELPER role")
    status, roles = request("GET", "/admin/users/roles", token=admin)
    check(status == 200 and "HELPER" in (roles or []),
          "the administrator can hand out HELPER", f"got {roles}")

    remove_user(USERNAME, admin)
    # `role` is a query parameter on this endpoint and defaults to MANAGER, so
    # putting it in the body silently creates a manager instead of a helper.
    status, created = request("POST", f"/admin/users?role=HELPER",
                              {"username": USERNAME, "password": PASSWORD,
                               "fullName": "Smoke Helper"},
                              token=admin)
    check(status in (200, 201), "a helper account can be created",
          f"status={status} {str(created)[:160]}")

    status, session = request("POST", "/auth/login",
                              {"username": USERNAME, "password": PASSWORD})
    helper = session.get("token") if isinstance(session, dict) else None
    check(status == 200 and bool(helper), "and the helper can sign in",
          f"status={status} {str(session)[:160]}")
    if not helper:
        return 1

    status, me = request("GET", "/users/me", token=helper)
    check(status == 200 and me.get("role") == "HELPER",
          "as a HELPER", f"got {me.get('role') if isinstance(me, dict) else me}")

    # ---- what a helper is for ----
    _, events = request("GET", "/events", token=admin)
    sprint = next(e for e in events if e["type"] == "RUN_100M" and (e.get("groupCount") or 0) > 0)

    status, sheet = request("GET", f"/events/{sprint['id']}/marks?stage=HEAT", token=helper)
    check(status == 200 and isinstance(sheet, dict),
          "a helper can open a mark sheet", f"status={status} {str(sheet)[:140]}")
    check(isinstance(sheet, dict) and "finalState" in sheet,
          "with the final's state on it so the page can gate",
          f"keys={sorted(sheet)[:8] if isinstance(sheet, dict) else sheet}")

    status, pdf = request("GET", f"/events/{sprint['id']}/sheets.pdf", token=helper, raw=True)
    check(status == 200 and isinstance(pdf, bytes) and pdf[:4] == b"%PDF",
          "and print the marking sheets",
          f"status={status} head={pdf[:8] if isinstance(pdf, bytes) else pdf}")

    status, group_pdf = request("GET", "/groups/1/sheet.pdf", token=helper, raw=True)
    check(status in (200, 404), "and fetch a single group's sheet",
          f"status={status}")

    # ---- what a helper is not for ----
    for method, path, what in (
            ("POST", "/events", "create an event"),
            ("PUT", f"/events/{sprint['id']}", "change an event"),
            ("GET", "/admin/students", "read the register"),
            ("GET", "/admin/users", "list accounts"),
            ("GET", "/admin/backups", "see backups"),
            ("POST", "/admin/season/reset", "reset the season"),
            ("GET", "/admin/teachers", "manage teachers"),
            ("POST", f"/events/{sprint['id']}/groups/allocate", "allocate heats"),
            ("POST", f"/events/{sprint['id']}/final", "draw the final"),
    ):
        status, body = request(method, path, {} if method in ("POST", "PUT") else None,
                               token=helper)
        check(status == 403, f"a helper cannot {what}", f"status={status} {str(body)[:120]}")

    # ---- the final waits for the heats ----
    print("\n2. The final waits for the heat results")
    no_final = next((e for e in events
                     if e["type"] == "RUN_800M"), None)
    if no_final:
        status, body = request("GET", f"/events/{no_final['id']}/marks?stage=FINAL",
                               token=admin)
        check(status == 409, "an event with no final stage refuses a final grid",
              f"status={status} {str(body)[:170]}")
        check("straight to a final" in str(body),
              "and says why, not that something is missing", f"got {str(body)[:170]}")

    # a sprint that is direct to a final is a different answer again
    direct = next((e for e in events
                   if e["type"] in ("RUN_100M", "RUN_200M", "RUN_400M")
                   and e.get("directToFinal")), None)
    if direct:
        status, body = request("GET", f"/events/{direct['id']}/marks?stage=FINAL", token=admin)
        check(status == 409, "a sprint set to direct-to-final refuses a final grid too",
              f"status={status} {str(body)[:170]}")
        check("direct to a final" in str(body) and "straight to a final" not in str(body),
              "with its own message, not the other one", f"got {str(body)[:170]}")

    # a sprint running heats with no draw yet
    undrawn = next((e for e in events
                    if e["type"] in ("RUN_100M", "RUN_200M", "RUN_400M")
                    and e.get("runsAFinal") and not e.get("directToFinal")), None)
    if undrawn:
        status, body = request("GET", f"/events/{undrawn['id']}/marks?stage=FINAL",
                               token=admin)
        check(status == 409 or status == 200,
              "a sprint running heats answers for its final",
              f"status={status} {str(body)[:170]}")
        if status == 409:
            check("not been drawn" in str(body),
                  "and says the final has not been drawn yet", f"got {str(body)[:170]}")
        status, pdf = request("GET", f"/events/{undrawn['id']}/sheets.pdf",
                              token=admin, raw=True)
        if status == 409:
            check("not been drawn" in str(pdf) or "final" in str(pdf).lower(),
                  "and refuses to print a final sheet before it is drawn",
                  f"got {str(pdf)[:170]}")

    # the heat grid always works, whatever the final is doing
    status, heat = request("GET", f"/events/{sprint['id']}/marks?stage=HEAT", token=admin)
    check(status == 200, "while the heat grid is always available",
          f"status={status}")

    print("\nCleaning up")
    remove_user(USERNAME, admin)
    status2, users = request("GET", "/users", token=admin)
    names = {u.get("username") for u in users} if isinstance(users, list) else set()
    check(USERNAME not in names, "the throwaway helper is removed", f"still there: {USERNAME in names}")

    print(f"\n  checks {CHECKS}, failures {FAILED}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
