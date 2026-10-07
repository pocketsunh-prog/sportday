/**
 * One place where a student's **form, class and house** are written down.
 *
 * The same three facts are shown on the register, the relay board, the mark grid
 * and the marking sheets, so the string logic lives here rather than being copied
 * into each screen — which is how the register and a team sheet end up disagreeing
 * about whether `5D` is Form 5 or `5` and whether a house is `Red` or `R`.
 *
 * Every function takes the *fields* rather than a whole DTO, because the API
 * carries these facts on six different shapes (`StudentDTO`, `UserDTO`,
 * `EnrollmentDTO`, `MarkRowDTO`, `RelayTeamMemberDTO`, and the relay applicant)
 * and none of them is the canonical one.
 *
 * ## The house code
 *
 * `house` is the register's own spelling of the house — `Red`, `Yellow`, `Blue`,
 * `Green` — and `houseCode` is its single letter: `R`, `Y`, `B`, `G`. The server
 * derives the code in one place (`Student.houseCodeOf`) and **omits it
 * altogether** for a house it does not recognise, so `Black` arrives with no code
 * rather than the `B` of Blue. Nothing here invents a letter from the name: a
 * missing code is simply not printed, because a wrong house letter on a marking
 * sheet is worse than no letter at all.
 */

/** What every one of these helpers needs off a DTO: two names and a code. */
export interface StudentLike {
  className?: string | null;
  classNumber?: number | null;
  classLabel?: string | null;
  house?: string | null;
  houseCode?: string | null;
}

/** A value's trimmed text, or `''` when it is missing. */
function text(value: string | null | undefined): string {
  return (value ?? '').trim();
}

/**
 * The class a student is in, as the school writes it: `5D 8`.
 *
 * The server's own `classLabel` wins when it is there, because it is built from
 * the register row itself; the fallback is the same two fields joined, so a DTO
 * that omits the label (a `MarkRowDTO`, for instance) still reads the same way.
 */
export function classText(student: StudentLike): string {
  const label = text(student.classLabel);
  if (label) return label;
  const name = text(student.className);
  const number = student.classNumber;
  if (name && number !== null && number !== undefined) return `${name} ${number}`;
  return name;
}

/** The bare form a class belongs to — `5` for `5D` — or `null` when it names none. */
export function formOf(student: StudentLike): string | null {
  const name = text(student.className);
  let end = 0;
  while (end < name.length && name[end] >= '0' && name[end] <= '9') end += 1;
  if (end === 0) return null;
  let first = 0;
  while (first < end - 1 && name[first] === '0') first += 1;
  return name.slice(first, end);
}

/**
 * The form as a label: `Form 5`, or `-` when the class names no form at all.
 *
 * The wording is the one i18n key every screen uses — `students.formValue` — so
 * the register, the relay board and the mark grid cannot word it differently. Any
 * translator works (`useI18n().t`); without one the English wording is used, which
 * is what a non-React caller gets.
 */
export function formText(
  student: StudentLike,
  translate?: (key: 'students.formValue', vars: { form: string }) => string
): string {
  const form = formOf(student);
  if (form === null) return '-';
  return translate
    ? translate('students.formValue', { form })
    : `Form ${form}`;
}

/**
 * The house with its short code: `Red (R)`, `Yellow (Y)`.
 *
 * A house the server gave no code for is shown by its name alone (`Black`), and a
 * student in no house at all is `-`. The code is never derived from the name —
 * see the note at the top of this file.
 */
export function houseText(student: StudentLike): string {
  const house = text(student.house);
  const code = text(student.houseCode);
  if (!house) return '-';
  return code ? `${house} (${code})` : house;
}
