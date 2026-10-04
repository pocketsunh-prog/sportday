"""Cheap structural check on the README after a run of scripted edits."""

import pathlib
import re

TEXT = pathlib.Path(r"D:\dev\sportday\README.md").read_text(encoding="utf-8")
LINES = TEXT.split("\n")

wide = [(i + 1, line) for i, line in enumerate(LINES) if line.count("|") > 8]
print(f"table rows with too many pipes: {len(wide)}")
for number, line in wide[:4]:
    print(f"   {number}: {line[:120]}")

# A heading or list item that lost its newline shows up as a lower-case word running
# straight into a capitalised one.
glued = [
    (i + 1, line)
    for i, line in enumerate(LINES)
    if re.search(r"[a-z)][A-Z][a-z]{3,}", line) and "|" not in line and "`" not in line
]
print(f"possible glued lines: {len(glued)}")
for number, line in glued[:5]:
    print(f"   {number}: {line[:130]}")

print(f"total lines: {len(LINES)}")
