"""Finds backend methods whose name appears only at its own declaration.

A conservative first pass: anything reported still needs a human look, because
Spring calls controllers and repository methods by reflection and Lombok generates
accessors. It is a shortlist, not a verdict.
"""

import pathlib
import re

ROOT = pathlib.Path(r"D:\dev\sportday\backend\src")
MAIN = sorted((ROOT / "main" / "java").rglob("*.java"))
TEST = sorted((ROOT / "test" / "java").rglob("*.java"))

BLOB = "\n".join(p.read_text(encoding="utf-8") for p in MAIN + TEST)

DECL = re.compile(
    r"^\s{4}(?:public|protected)\s+"
    r"(?!class\b|record\b|enum\b|interface\b)"
    r"(?:static\s+)?(?:final\s+)?"
    r"[\w<>,\[\]\.\? ]+?\s+(\w+)\s*\(",
    re.M,
)

SKIP = {"main", "toString", "equals", "hashCode", "canEqual", "builder",
        "from", "get", "set", "read", "write"}

found = []
for path in MAIN:
    text = path.read_text(encoding="utf-8")
    for match in DECL.finditer(text):
        name = match.group(1)
        if name in SKIP:
            continue
        hits = len(re.findall(r"\b" + re.escape(name) + r"\s*\(", BLOB))
        if hits <= 1:
            found.append((path.name, name))

print(f"{len(found)} method(s) whose name appears only at its declaration:")
for file_name, name in found:
    print(f"   {file_name:<44} {name}()")
