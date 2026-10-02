# SportDay sample student register

600 generated students for testing and demonstrations, in the exact format the
admin upload screen expects.

| File | What it is |
|------|------------|
| `students-600.csv` | The register as CSV — UTF-8 **with BOM**, CRLF, so Excel opens the Chinese names correctly |
| `students-600.xlsx` | The same 600 rows as an Excel workbook, built from the CSV |
| `make_sample_data.py` | Re-checks every invariant and rebuilds the XLSX from the CSV |

## Columns

| Column | Example | Notes |
|--------|---------|-------|
| `studentId` | `S0001` | Also the login username. Sequential `S0001`–`S0600` |
| `name` | `陳大文` | Traditional Chinese, 2–3 characters, all 600 distinct |
| `dob` | `2010-03-15` | ISO date. The grade is derived from this |
| `sex` | `M` / `F` | Exactly 300 of each |
| `className` | `5A` | Form + class letter, e.g. `1A`–`6D` |
| `classNumber` | `12` | Register number, unique **within** a class, running 1..N |
| `house` | `Red` / `Blue` / `Green` / `Yellow` | Exactly 150 each |

## Login credentials

```
username = studentId
password = yyyyMMdd(dob) + className + classNumber        (no separators)
```

Student `S0001`, born 2010-03-15, class `5A`, class number `12` → `201003155A12`.

An administrator can download the whole credentials sheet from
**Admin → Students → Credentials CSV** (`GET /api/admin/students/credentials.csv`).

## How the 600 students are shaped

The population is deliberately built so every grade band, sex and house is
populated and every class register is legal.

| Grade | Age on the sport day | Forms | Students |
|-------|---------------------|-------|----------|
| **C** | 14 or below | 1–2 | 240 |
| **B** | 15–16 | 3–4 | 198 |
| **A** | 17 or above | 5–6 | 162 |

Classes are grouped into forms of four (`1A`–`1D` … `6A`–`6D`), giving
**24 classes**, and **no class ever holds two grade bands**. Class sizes come out
between 20 and 30, comfortably inside a real register of 35.

### Why the class names are `1A`–`6D` and not `1A`–`6C`

An earlier sketch of this data used three classes per form (`1A`, `1B`, `1C`, …).
That cannot satisfy "class number is unique within a class, up to 35 students":
600 students in 18 classes averages 33 per class, and because the grade bands
overlap at Form 5 (a 16-year-old and a 17-year-old can both be in Form 5), those
three Form 5 classes would each have to hold 60 students. Widening each form to
four classes keeps every register legal while still grouping a whole form into
one grade band.

## Regenerating

The CSV is the **single source of truth** and is produced by the backend itself,
so the committed file, the file the API serves, and the students created by the
"Generate 600 sample students" button are the same 600 records:

```bash
# from a running backend
curl -H "Authorization: Bearer <admin-token>" \
     "http://localhost:8080/api/admin/students/sample.csv?count=600" \
     -o sportday-sample-data/students-600.csv
```

Then rebuild the workbook and re-check everything:

```bash
python sportday-sample-data/make_sample_data.py
```

That prints the counts and fails loudly if any invariant is broken. It also
round-trips the workbook against the CSV to prove they match row for row.

Use a different sport day by passing the reference date the grades are computed
against (default `2026-10-01`):

```bash
python sportday-sample-data/make_sample_data.py --reference-date 2026-11-06
```

> Grades are stored per student, so changing the sport day does **not** silently
> change existing records. Use **Admin → Students → Recompute grades** (or
> `POST /api/admin/students/recompute-grades?referenceDate=...`) to move the whole
> school onto a new date.

## Uploading it

**Admin → Students → choose file → Upload.** Both files work. The import is
idempotent: re-uploading updates the students with matching ids rather than
creating duplicates, and each student's password is re-derived from their record
so it can never drift out of sync.

Bad rows do not fail the file — they are reported back with their spreadsheet row
number so they can be fixed and re-uploaded. Column headings are matched loosely,
so `Student ID`, `student_id`, `學號` and `学号` all work, and the common date
spellings (`2010-03-15`, `2010/3/15`, `15/3/2010`, `20100315`, …) are all
understood.
