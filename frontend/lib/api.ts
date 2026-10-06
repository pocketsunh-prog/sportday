const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api';

/* ------------------------------------------------------------------ *
 * Shared literals
 * ------------------------------------------------------------------ */

/**
 * The account roles. `TEACHER` is the staff account that carries a set of
 * classes: it may enter or withdraw a student in one of those classes and
 * nothing else — no register upload, no locking, no credentials sheet.
 */
export type Role = 'ADMIN' | 'MANAGER' | 'USER' | 'STUDENT' | 'TEACHER' | 'HELPER';
/**
 * The three families of event. `RELAY` is its own family rather than a track
 * event: a relay is run and scored by team and never by an individual, and it is
 * divided into teams one per grade × house or one per form and class. Its marks
 * are still times — `defaultUnitForCategory` returns `s` for it — so only the
 * field family is measured.
 */
export type EventCategory = 'TRACK' | 'FIELD' | 'RELAY';
export type EventSex = 'MALE' | 'FEMALE';
export type SheetSize = 'A5' | 'A4';
/** Short division code used by the query strings (`?sex=M`) and by `UserDTO.gender`. */
export type SexCode = 'M' | 'F';
export type Grade = 'A' | 'B' | 'C';
/**
 * Which part of a short sprint a marking sheet belongs to. Heats are 1..N;
 * the final is a single extra group drawn from the top finishers, with its own
 * marks and its own sheet.
 */
export type MarkStage = 'HEAT' | 'FINAL';
/**
 * What a sheet recorded for an athlete: `RESULT` when a mark was produced,
 * `ABS` when they were absent and `DQ` when they were disqualified. An ABS/DQ
 * line carries no mark and no attempts, and is exempt from every numeric rule.
 */
export type MarkOutcome = 'RESULT' | 'ABS' | 'DQ';

export const CATEGORY_LABELS: Record<EventCategory, string> = {
  TRACK: '徑項 Track',
  FIELD: '田項 Field',
  RELAY: '接力 Relay',
};

/**
 * The event types the standard catalogue covers. `groupSize` / `sheetSize`
 * follow the backend rule: lane-based short sprints run 8 per heat on A5
 * sheets, everything else runs 24 per heat on A4 sheets.
 */
export const EVENT_TYPE_OPTIONS: Array<{
  value: string;
  label: string;
  category: EventCategory;
}> = [
  { value: 'RUN_60M', label: '60M', category: 'TRACK' },
  { value: 'RUN_100M', label: '100M', category: 'TRACK' },
  { value: 'RUN_200M', label: '200M', category: 'TRACK' },
  { value: 'RUN_400M', label: '400M', category: 'TRACK' },
  { value: 'RUN_800M', label: '800M', category: 'TRACK' },
  { value: 'RUN_1500M', label: '1500M', category: 'TRACK' },
  { value: 'RUN_5000M', label: '5000M', category: 'TRACK' },
  { value: 'HURDLES_110M', label: '110M Hurdles', category: 'TRACK' },
  { value: 'HURDLES_400M', label: '400M Hurdles', category: 'TRACK' },
  { value: 'RELAY_4X100M', label: '4x100M Relay', category: 'RELAY' },
  { value: 'RELAY_4X400M', label: '4x400M Relay', category: 'RELAY' },
  { value: 'SHOT_PUT', label: 'Shot Put', category: 'FIELD' },
  { value: 'DISCUSSION_THROW', label: 'Discus', category: 'FIELD' },
  { value: 'JAVELIN_THROW', label: 'Javelin', category: 'FIELD' },
  { value: 'HAMMER_THROW', label: 'Hammer', category: 'FIELD' },
  { value: 'LONG_JUMP', label: 'Long Jump', category: 'FIELD' },
  { value: 'HIGH_JUMP', label: 'High Jump', category: 'FIELD' },
  { value: 'TRIPLE_JUMP', label: 'Triple Jump', category: 'FIELD' },
  { value: 'POLE_VAULT', label: 'Pole Vault', category: 'FIELD' },
];

export function eventTypeLabel(type: string): string {
  return EVENT_TYPE_OPTIONS.find(option => option.value === type)?.label || type.replace(/_/g, ' ');
}

export function eventTypeCategory(type: string): EventCategory {
  return EVENT_TYPE_OPTIONS.find(option => option.value === type)?.category || 'TRACK';
}

/**
 * How an event is measured: `M` for the field events and `s` for the track — and
 * for a relay, which is a race and is timed like one.
 * This is the rule the backend applies to `EventDTO.defaultUnit`, and it stands
 * in for it on the responses that do not spell the unit out (an enrollment, for
 * instance, carries only its category).
 */
export function defaultUnitForCategory(category: EventCategory): string {
  return category === 'FIELD' ? 'M' : 's';
}

/**
 * A field result's attempts on one line, e.g. `8.20 / – / 9.90`.
 *
 * A miss arrives as an explicit `null` and renders as a dash; an attempt that
 * was never taken is left off the end altogether. An empty or missing list
 * renders as nothing at all, which is what a track result wants.
 */
export function formatAttempts(attempts?: Array<number | null> | null): string {
  if (!attempts || attempts.length === 0) return '';
  return attempts.map(attempt => (attempt === null || attempt === undefined ? '–' : String(attempt))).join(' / ');
}

/**
 * The only event types a school may split into heats and a final: the four
 * short sprints. Every other type — every field event, the 800M / 1500M /
 * 5000M, the hurdles and the relays — is decided by its own run.
 *
 * This is the rule behind `EventDTO.mayHaveFinal`.
 */
export const FINAL_EVENT_TYPES: ReadonlyArray<string> = [
  'RUN_60M',
  'RUN_100M',
  'RUN_200M',
  'RUN_400M',
];

/** Whether `type` may be run as heats and a final (`EventDTO.mayHaveFinal`). */
export function mayHaveFinalForType(type: string): boolean {
  return FINAL_EVENT_TYPES.includes(type);
}

/* ------------------------------------------------------------------ *
 * The final waits for the heat results
 * ------------------------------------------------------------------ */

/**
 * The state of an event's final, as the server reports it on
 * `MarkSheetDTO.finalState` — the one field a screen gates its final controls
 * on, because it is the precise answer where `runsAFinal` is only a proxy.
 *
 * - `NONE` — the event has no final stage at all: an 800M, a hurdles race, a
 *   relay or any field event. There is no final to offer.
 * - `DIRECT` — the event could be split and the school runs it straight to a
 *   final instead. Again there is no final to offer.
 * - `NOT_DRAWN` — heats are in play and the draw has not run. The final exists
 *   in principle but cannot be worked on: the heat results come first.
 * - `DRAWN` — the final exists; its marks and its sheet are live.
 */
export type FinalState = 'NONE' | 'DIRECT' | 'NOT_DRAWN' | 'DRAWN';

const FINAL_STATES: ReadonlyArray<FinalState> = ['NONE', 'DIRECT', 'NOT_DRAWN', 'DRAWN'];

/** Reads a `finalState` off the wire, or `null` when it is absent or unrecognised. */
export function asFinalState(value: string | null | undefined): FinalState | null {
  return value && (FINAL_STATES as ReadonlyArray<string>).includes(value)
    ? (value as FinalState)
    : null;
}

/**
 * Whether the final stage may be offered for an event in this state.
 *
 * Only `DRAWN` qualifies — the server refuses a final mark grid and a final
 * sheet with a 409 in every other state, so offering the stage would only lead
 * a helper into a control that cannot be worked on.
 */
export function canWorkFinal(state: FinalState | null | undefined): boolean {
  return state === 'DRAWN';
}

/**
 * Whether the final belongs in the stage picker at all.
 *
 * `NONE` and `DIRECT` have no final stage, so the option is left out entirely
 * rather than shown greyed; `NOT_DRAWN` has one that is simply not ready yet,
 * so it is shown disabled with the reason beside it.
 */
export function shouldOfferFinal(state: FinalState | null | undefined): boolean {
  return state === 'DRAWN' || state === 'NOT_DRAWN';
}

/**
 * The state of an event's final worked out from the event itself, for the
 * moment before a sheet has been loaded and its own `finalState` is to hand.
 *
 * `source` is the precise server value when one is available. `finalDrawn` is
 * what the caller already knows from a group list; when it is omitted the type
 * alone is used, which yields `NOT_DRAWN` for a sprint that is not already
 * known to have run its final. That is the safe direction: it withholds the
 * final until the draw is confirmed rather than offering a stage the server
 * would refuse.
 */
export function finalStateForEvent(
  event: Pick<EventDTO, 'type' | 'directToFinal'> | null | undefined,
  options: { source?: FinalState | null; finalDrawn?: boolean | null | undefined } = {}
): FinalState | null {
  if (options.source) return options.source;
  if (!event) return null;
  if (!mayHaveFinalForType(event.type)) return 'NONE';
  if (event.directToFinal) return 'DIRECT';
  if (options.finalDrawn === true) return 'DRAWN';
  if (options.finalDrawn === false) return 'NOT_DRAWN';
  return null;
}

/**
 * How a relay event's teams are divided.
 *
 * - `FORM` — one team per form of the event's own grade and division, so `1A`,
 *   `1B` and `1C` all run for `Form 1`;
 * - `HOUSE` — one team per house within that grade, so the A grade 4x100M has a
 *   Red, a Blue and a Green team.
 *
 * An event with no kind at all is **undivided**: it has no teams, and nothing
 * invents any. That is how every relay in the programme behaves until an
 * administrator divides one.
 */
export type RelayTeamKind = 'FORM' | 'HOUSE';

/** Whether an event type is a relay, i.e. whether it has relay teams at all. */
export function isRelayEventType(type: string | null | undefined): boolean {
  return !!type && type.startsWith('RELAY_');
}

/**
 * The relay team kinds, as the event form offers them. `''` is Undivided — the
 * value that clears a kind on an update, which is why it is spelled out here
 * rather than left as a `null` the server would read as "leave it alone".
 */
export const RELAY_TEAM_KIND_OPTIONS = [
  { value: 'FORM', labelKey: 'relay.kindForm' },
  { value: 'HOUSE', labelKey: 'relay.kindHouse' },
  { value: '', labelKey: 'relay.kindUndivided' },
] as const;

/** Suggested group size / sheet size / sprint flag for a given event type. */
export function sheetDefaultsForType(type: string): {
  groupSize: number;
  sheetSize: SheetSize;
  shortSprint: boolean;
} {
  const isShortSprint = mayHaveFinalForType(type);
  return isShortSprint
    ? { groupSize: 8, sheetSize: 'A5', shortSprint: true }
    : { groupSize: 24, sheetSize: 'A4', shortSprint: false };
}

/* ------------------------------------------------------------------ *
 * Auth / users
 * ------------------------------------------------------------------ */

export interface AuthResponse {
  token: string;
  username: string;
  role: Role;
  fullName: string;
  userId: number;
}

export interface UserDTO {
  id: number;
  username: string;
  email?: string;
  fullName: string;
  age?: number;
  /** Sex code: `M` or `F` for students. */
  gender?: string;
  role: Role;
  enabled: boolean;
  createdAt: string;

  /* ---- the roster record, present for a student account ---- */

  /** The student id, which is also their username. */
  studentRef?: string;
  /** The grade the student competes in: `A`, `B` or `C`. */
  grade?: string;
  gradeLabel?: string;
  className?: string;
  classNumber?: number;
  classLabel?: string;
  /** The form the class belongs to — `5` for `5D`; absent when it names none. */
  form?: string;
  house?: string;
  /** The house's short code — `R`, `Y`, `B`, `G`; absent for another house. */
  houseCode?: string;
}

/* ------------------------------------------------------------------ *
 * Events
 * ------------------------------------------------------------------ */

export interface EventDTO {
  id: number;
  name: string;
  description: string;
  /**
   * The school year (`SeasonDTO.id`) this event belongs to, with its year and
   * name spelled out so a past year is unmistakable in a list.
   */
  seasonId?: number;
  seasonYear?: number;
  seasonName?: string;
  /** Enum name, e.g. `RUN_100M`. */
  type: string;
  /** Human label, e.g. `100M`. */
  typeLabel: string;
  category: EventCategory;
  /** e.g. `徑項 Track`. */
  categoryLabel: string;
  sex: EventSex;
  /** e.g. `男 Boys`. */
  sexLabel: string;
  /**
   * The one grade this event belongs to: `A`, `B` or `C`. An event is never
   * shared between grades — `Boys 100M · A Grade` and `Boys 100M · B Grade` are
   * two events with their own heats, marks, results and placings, so no grade is
   * ever ranked against another. Every event type is not run by every grade: see
   * `gradesForEventType`.
   */
  grade: Grade;
  /** e.g. `A Grade`. */
  gradeLabel: string;
  eventDate: string;
  location: string;
  maxParticipants: number;
  groupSize: number;
  shortSprint: boolean;
  sheetSize: SheetSize;
  /**
   * True when the event is decided by its own run, with no final. Every new
   * event defaults to this, and only the four short sprints may turn it off —
   * see `mayHaveFinal`. A direct-to-final event may still have groups drawn,
   * but those only split the field across the marking sheets.
   */
  directToFinal: boolean;
  /**
   * True only for 60M / 100M / 200M / 400M, i.e. whether `directToFinal` may be
   * unticked at all. Every other type must run straight to a final, and the
   * server refuses a final for it with a 400 (or a 409 on the final endpoints).
   */
  mayHaveFinal: boolean;
  /**
   * True when the *system* set `directToFinal` rather than the school: a short
   * sprint with eight or fewer athletes entered has no room for a final, so it
   * is switched to direct to final for as long as the field stays that small.
   *
   * Absent (the API omits a null) or false means either the school chose the
   * format or the event was always direct, and it must be rendered as before.
   * The system puts the final back by itself once entries rise again, but never
   * undoes a format the school set — unticking the box clears this flag.
   */
  directToFinalAutomatic?: boolean;
  /**
   * True for a race longer than 400M (800M / 1500M / 5000M), whose time a helper
   * reads off a stopwatch: it is written as minutes and seconds, not as a bare
   * count of seconds.
   */
  timeInMinutes?: boolean;
  /**
   * How the event is measured: `M` in the field, `s` on the track. Populated on
   * every event, and always rendered through `label('unit', …)`.
   */
  defaultUnit?: string;
  /**
   * True when this event is a relay — one of the two `RELAY_4X100M` /
   * `RELAY_4X400M` types. Only a relay has relay teams and a relay team kind.
   */
  relay?: boolean;
  /**
   * How the relay's teams are divided, or absent/`null` when the relay is
   * **undivided** — which is how every relay in the programme started, and which
   * means it has no teams at all rather than an empty board.
   *
   * On the way *out* the server sends `FORM`, `HOUSE` or nothing at all. In a
   * request `''` is the one extra value that matters: it is what **clears** a
   * kind, because omitting the field means "leave it alone" and `null` cannot be
   * told from omission once it has been through JSON.
   */
  relayTeamKind?: RelayTeamKind | '' | null;
  /** e.g. `Form`. The server's own English label for `relayTeamKind`. */
  relayTeamKindLabel?: string;
  /**
   * The form this relay is scoped to — `1` for a **Form 1** relay, whose teams
   * are that form's classes across every grade (`1A`, `1B`, `1C`, `1D`), not the
   * classes of its own grade alone. A form is not a grade: a Form 1 relay takes
   * whoever is in Form 1 whatever grade they are, which is how the school asks
   * for it.
   *
   * Only a `FORM` relay may carry one. `null` (or absent) means the older rule —
   * one team per class of the event's own grade. In a request `''` is what
   * **clears** it, exactly as `relayTeamKind` behaves.
   */
  form?: string | '' | null;
  /** e.g. `Form 1`. The server's own label for `form`. */
  formLabel?: string;
  /**
   * The event's **required standard** — the qualifying mark an athlete must
   * reach. Only the track races of 400M and over, and the field events, carry
   * one; a sprint under 400M and a relay carry none.
   *
   * In the event's own unit: seconds on the track, metres in the field. A time
   * meets it at or under, a distance at or over.
   *
   * On the way in, omitting it **leaves it alone** — several callers send partial
   * bodies. To remove one, send `clearStandard: true`; a number cannot say
   * "blank" the way `form` can.
   */
  standard?: number | null;
  /** e.g. `64.123 s`. The standard with its unit. */
  standardLabel?: string;
  /** True to remove the standard, since an omitted number means "leave it alone". */
  clearStandard?: boolean;
  /**
   * True when this event is one that carries a required standard at all: the
   * track races of 400M and over, and every field event.
   *
   * Sent by the server rather than re-derived here, because the list of
   * qualifying types lives in one place and a page that worked it out from type
   * names would eventually disagree with the sheet and the mark grid.
   */
  carriesStandard?: boolean;
  /** Legs in a team — four for a 4x100M. */
  relayTeamSize?: number;
  /** True when a team may also name reserves past its legs. */
  relayReservesAllowed?: boolean;
  /**
   * True when this event may be **marked and printed now**: a relay whose teams
   * are built, or any event that is not a relay.
   *
   * It is the server's own `RelayReadiness` verdict, sent on the event list so a
   * picker can leave a half-built relay out rather than offer it and have the
   * choice refused with a 409. An **individual event is always true** — the rule
   * is about a relay's teams and a race of athletes has none — so nothing about a
   * sprint or a field event changes. A relay with fewer than two teams, or with a
   * team short of its runners, is `false` and carries `readinessReason`.
   *
   * Absent (the API omits a null) means the same as `false` for a relay only if a
   * client checks `=== false`; **check `=== false`**, so an older server that does
   * not send the field shows everything it always did.
   */
  relayReady?: boolean;
  /**
   * Why the event cannot be marked yet, or null/absent when it can — the very
   * sentence the server refuses a direct call with, so a list explains itself in
   * the same words the 409 uses.
   */
  readinessReason?: string | null;
  /**
   * How many runners one team may hold in total: the legs, doubled when reserves
   * are allowed. Null/absent on an event that is not a relay.
   */
  relayMemberCap?: number;
  enabled: boolean;
  createdAt: string;
  enrolledCount: number;
  groupCount: number;
  ungroupedCount: number;
  maxEntriesPerStudent: number;
}

/**
 * The grades the school runs, in programme order.
 */
export const GRADES: ReadonlyArray<Grade> = ['A', 'B', 'C'];

/**
 * The grades that run an event type — the frontend's copy of the server's own
 * `EventType.allowedGrades()`, which is also why the standard catalogue holds
 * 112 events rather than 120:
 *
 * - the 5000M is run by the A grade only;
 * - the 1500M and the 110M hurdles have no C grade, which runs the 100M hurdles
 *   instead;
 * - every other type (including the 100M hurdles) is run by all three grades.
 */
export function gradesForEventType(type: string): Grade[] {
  if (type === 'RUN_5000M') return ['A'];
  if (type === 'RUN_1500M' || type === 'HURDLES_110M') return ['A', 'B'];
  return [...GRADES];
}

/**
 * Whether `grade` may enter `event`: an event belongs to exactly one grade, so
 * the only grade that may enter it is its own. The server enforces the same rule
 * and refuses any other with a 409.
 *
 * A missing `grade` means it could not be read — a student whose profile does
 * not carry one. Everything is then shown, because letting an entry be attempted
 * and refused with the server's own wording is better than hiding events from a
 * student whose grade is simply unknown here.
 */
export function gradeMatchesEvent(
  event: Pick<EventDTO, 'grade'>,
  grade: string | null | undefined
): boolean {
  if (!grade) return true;
  return event.grade === grade;
}

export interface EventQuery {
  onlyEnabled?: boolean;
  /** Short division code, `M` or `F`. */
  sex?: SexCode | '';
  category?: EventCategory | '';
  /** One day of a multi-day meeting, `yyyy-MM-dd`. */
  date?: string;
  /**
   * A school year (`SeasonDTO.id`), narrowing the programme to that one sport
   * day. Composes with the other filters rather than replacing them.
   */
  seasonId?: number | null;
}

/** One day of the programme, as offered by the date picker. */
export interface EventDateDTO {
  /** `yyyy-MM-dd`. */
  date: string;
  eventCount: number;
  isToday: boolean;
  isPast: boolean;
}

export interface EventDefaultsResultDTO {
  created: number;
  eventDate: string;
  includeField: boolean;
  totalEvents: number;
}

/* ------------------------------------------------------------------ *
 * Enrollments
 * ------------------------------------------------------------------ */

export interface EnrollmentDTO {
  id: number;
  eventId: number;
  eventName: string;
  eventType: string;
  eventTypeLabel: string;
  category: EventCategory;
  categoryLabel: string;
  sex: EventSex;
  sexLabel: string;
  eventDate: string;
  location: string;
  /** Present only once heats have been allocated. */
  groupId?: number | null;
  groupNumber?: number | null;
  /** e.g. `Heat 1`. */
  groupLabel?: string | null;
  lane?: number | null;
  sheetSize: SheetSize;
  /**
   * How the event is measured (`M` / `s`), when the response spells it out.
   * An enrollment does not always carry it, so fall back to
   * `defaultUnitForCategory(entry.category)`, which is the same rule.
   */
  defaultUnit?: string;
  /** Numeric student primary key. */
  studentId: number;
  userId?: number;
  /** The student id string, e.g. `S0003`. */
  studentRef: string;
  name: string;
  grade: string;
  className: string;
  classNumber: number;
  /** The form the class belongs to — `5` for `5D`; absent when it names none. */
  form?: string;
  house: string;
  /** The house's short code — `R`, `Y`, `B`, `G`; absent for another house. */
  houseCode?: string;
  status: string;
  enrolledAt: string;
}

export interface QuotaDTO {
  trackUsed: number;
  trackMax: number;
  trackRemaining: number;
  fieldUsed: number;
  fieldMax: number;
  fieldRemaining: number;
  /** Omitted on the endpoints that do not spell the limits out again. */
  rules?: Record<string, number>;
}

/**
 * `GET /admin/students/{studentId}/enrollments` — the entries an administrator
 * manages on a student's behalf, plus the quota the student is measured against.
 *
 * `enrollments` carries the student's whole entry history, so withdrawn entries
 * (`status: 'CANCELLED'`) appear alongside the confirmed ones.
 */
export interface StudentEnrollmentsDTO {
  /** The student id string, e.g. `S0001`. */
  studentId: string;
  /** The login account the entries belong to. */
  userId: number;
  quota: QuotaDTO;
  enrollments: EnrollmentDTO[];
}

/* ------------------------------------------------------------------ *
 * Groups / heats
 * ------------------------------------------------------------------ */

export interface EventGroupDTO {
  id: number;
  eventId: number;
  eventName: string;
  eventTypeLabel: string;
  category: EventCategory;
  categoryLabel: string;
  sex: EventSex;
  sexLabel: string;
  groupNumber: number;
  /** e.g. `Heat 1`. The final is group number 0 with the label `Final`. */
  label: string;
  /** `HEAT` for the numbered heats, `FINAL` for the drawn final. */
  stage: MarkStage;
  /** e.g. `Final 決賽`. */
  stageLabel: string;
  capacity: number;
  athleteCount: number;
  sheetSize: SheetSize;
  /** Always `[]` on list responses; populated by `GET /groups/{id}`. */
  athletes: EnrollmentDTO[];
}

export interface AllocateGroupsResultDTO {
  eventId: number;
  groupCount: number;
  shuffle: boolean;
  groups: EventGroupDTO[];
}

/** One athlete in line for the final, ranked by their heat mark. */
export interface FinalQualifierDTO {
  rank: number;
  userId: number;
  /** The student id string, e.g. `S0056`. */
  studentRef: string;
  name: string;
  grade: string;
  className: string;
  classNumber: number;
  /** The form the class belongs to — `5` for `5D`; absent when it names none. */
  form?: string;
  house: string;
  /** The house's short code — `R`, `Y`, `B`, `G`; absent for another house. */
  houseCode?: string;
  /** The mark that won them the place, and its unit. */
  heatMark: number;
  unit: string;
}

/**
 * The outcome of previewing or drawing the final. `qualifiers` lists the top
 * `finalSize` athletes on both calls, so the preview and the draw render the
 * same table.
 */
export interface FinalSummaryDTO {
  eventId: number;
  eventName: string;
  /** e.g. `60M`. */
  eventTypeLabel: string;
  category: EventCategory;
  sheetSize: SheetSize;
  shortSprint: boolean;
  finalSize: number;
  /** Athletes with a heat mark to rank. */
  rankedAthletes: number;
  qualified: number;
  drawn: boolean;
  /** Present once the final exists. */
  groupId?: number;
  /** e.g. `Final`. */
  groupLabel: string;
  /** Marks wiped by a re-draw. */
  clearedFinalMarks: number;
  qualifiers: FinalQualifierDTO[];
  note?: string | null;
}

/** `DELETE /events/{id}/final` answers with this complement of the summary. */
export interface FinalRemovalDTO {
  eventId: number;
  finalMarksCleared: number;
}

/* ------------------------------------------------------------------ *
 * Results (still used by the results screens)
 * ------------------------------------------------------------------ */

export interface EventResultDTO {
  id: number;
  userId: number;
  username: string;
  fullName: string;
  eventId: number;
  eventName: string;
  /** The best mark. For a field event that is the best of `attempts`. */
  mark: number;
  /** `M` in the field, `s` on the track. */
  unit?: string;
  /**
   * The mark as the sport writes it, with its unit: `14.123s`, `1.04.123s` over
   * a minute, `2.15.5s` for the long distances, `18.12M` in the field. The full
   * stops and the padded seconds are the sport's own notation, so render this
   * through `resultMark()` rather than re-deriving it from `mark` / `unit`.
   */
  displayMark?: string;
  /**
   * A field event's attempts, in order. A miss arrives as an explicit `null`, so
   * the positions line up; an attempt that was never taken is left off the end.
   * A track result carries no list at all.
   */
  attempts?: Array<number | null>;
  notes?: string;
  /** Which sheet the mark was recorded on. */
  stage?: MarkStage;
  /**
   * Always one of the three: `RESULT` when a mark was produced, `ABS` or `DQ`
   * when the athlete was absent or disqualified. An ABS/DQ result has no mark,
   * and `displayMark` reads `ABS` / `DQ` in its place.
   */
  outcome?: MarkOutcome;
  /** True when this mark set a new school record for its division + grade. */
  newRecord?: boolean;
  recordedAt: string;
}

/* ------------------------------------------------------------------ *
 * Mark-entry grid
 * ------------------------------------------------------------------ */

/** One athlete's line in the grid. */
export interface MarkRowDTO {
  userId: number;
  studentRef: string;
  name?: string;
  grade?: string;
  className?: string;
  classNumber?: number;
  /** The form the class belongs to — `5` for `5D`; absent when it names none. */
  form?: string;
  house?: string;
  /** The house's short code — `R`, `Y`, `B`, `G`; absent for another house. */
  houseCode?: string;
  groupId?: number;
  groupNumber?: number;
  groupLabel?: string;
  lane?: number;
  /**
   * The relay team this line stands for, when the line **is a team** rather
   * than an athlete.
   *
   * On a relay whose teams have been derived the grid holds one line per TEAM,
   * because one time is written for the four runners together and not four
   * times: the line carries the team's id and the team's name, and deliberately
   * nothing about who runs for it. An athlete's line carries neither field, and
   * a relay nobody has divided is still the athlete-per-line grid it has always
   * been — so it is the presence of these two fields, not the event's type,
   * that tells the two shapes apart. The backend's own marking sheet decides it
   * the same way (`PdfSheetService.relayLinesOf`: the team labels are either
   * there or they are not).
   */
  teamId?: number;
  /** The team's own name as the school writes it — `B Grade Red`, `5A`. */
  teamLabel?: string;
  resultId?: number;
  /** The best mark. For a field event that is the best of `attempts`. */
  mark?: number;
  /**
   * A race longer than 400M's time the way a stopwatch reads it: the whole
   * minutes and the seconds left over. `mark` still carries the total in
   * seconds, which is what the rest of the app uses.
   */
  minutes?: number;
  seconds?: number;
  /** `M` in the field, `s` on the track. */
  unit?: string;
  /**
   * A field event's attempts, in order. A miss arrives as an explicit `null`, so
   * the positions line up; an attempt that was never taken is left off the end.
   * A track row carries no list at all.
   */
  attempts?: Array<number | null>;
  /**
   * The event's **required standard**, in the event's own unit, or absent for an
   * event that carries none — every sprint under 400M, and every relay. A sprint
   * is not qualifying in this school's programme.
   */
  standard?: number;
  /** e.g. `64.123 s`. The standard with its unit, for the row to show. */
  standardLabel?: string;
  /**
   * True when this row's result fell short of the standard — worked out by the
   * server from the mark and the standard together, so the teacher types nothing
   * and cannot forget or mis-set it. A row with no mark, and an event with no
   * standard, are never below: a blank is not a failure.
   */
  belowStandard?: boolean;
  notes?: string;
  /**
   * What is recorded for this athlete: `RESULT` when a mark was produced, `ABS`
   * or `DQ` when they were absent or disqualified. Absent altogether — the API
   * omits a null — when nothing has been recorded yet, which is why a blank row
   * is not a mark of any kind. An ABS/DQ row has no `mark`.
   */
  outcome?: MarkOutcome;

  /*
   * The heat record, present only on a FINAL grid: the official writing the final
   * down needs to see what the athlete ran in the heats. All three are absent on a
   * heat grid, and absent together when there is no heat record at all.
   */
  heatMark?: number;
  /** `RESULT`, `ABS` or `DQ` — the same vocabulary as `outcome`. */
  heatOutcome?: MarkOutcome;
  /** The heat result ready to print: `7.43s`, `1.04.123s`, `18.12M`, `ABS`, `DQ`. */
  heatDisplayMark?: string;
}

export interface MarkGroupOption {
  id: number;
  groupNumber: number;
  label: string;
  athleteCount: number;
}

export interface MarkSheetDTO {
  eventId: number;
  eventName: string;
  eventType?: string;
  eventTypeLabel?: string;
  category?: string;
  categoryLabel?: string;
  sex?: string;
  sexLabel?: string;
  eventDate?: string;
  location?: string;
  groupSize?: number;
  sheetSize?: string;
  /** Which sheet this is: `HEAT` (default) or `FINAL`. */
  stage: MarkStage;
  /** e.g. `Final 決賽`. */
  stageLabel: string;
  /** Whether a final has been drawn for this event. */
  finalDrawn: boolean;
  /**
   * The precise state of this event's final — see `FinalState`. This is what a
   * screen gates its final controls on: `DRAWN` is the only value the final
   * stage may be offered in, `NOT_DRAWN` means the heat results come first, and
   * `NONE` / `DIRECT` mean there is no final stage at all.
   */
  finalState?: FinalState;
  /** The server's own bilingual wording of `finalState`, ready to display. */
  finalStateLabel?: string;
  /** How many athletes go through, e.g. 8. */
  finalSize: number;
  /** `M` in the field, `s` on the track. */
  defaultUnit?: string;
  /**
   * True for a race longer than 400M, where a time is typed as minutes and
   * seconds — a helper writes 2:15, not 135. The rows then carry `minutes` and
   * `seconds` beside `mark`.
   */
  timeInMinutes?: boolean;
  /** How many attempts a row on this sheet carries: 3 in the field, 1 on the track. */
  attemptCount?: number;
  /** True when this is a field event, i.e. when the three attempts count. */
  fieldEvent?: boolean;
  groups: MarkGroupOption[];
  grades: string[];
  totalAthletes: number;
  markedCount: number;
  rows: MarkRowDTO[];
}

/** One row sent back to the server when the grid is saved. */
export interface MarkEntryInput {
  userId: number;
  /**
   * The relay team the mark belongs to, on a team line — see
   * {@link MarkRowDTO.teamId}. The server needs it to hang the one time off the
   * team rather than off the runner the line happens to be anchored to: sent
   * without it, a relay mark is stored against that runner and no longer found
   * when the team's line is read back. Omitted altogether on an athlete's row,
   * where the mark is the athlete's own.
   */
  teamId?: number;
  /** The single mark a track row is recorded with. */
  mark?: number | null;
  /**
   * A race longer than 400M may send its time as whole minutes and the seconds
   * left over instead of `mark`; the seconds part has to be under 60. When both
   * shapes are sent, these two win and the server works `mark` out as the total
   * in seconds.
   */
  minutes?: number | null;
  seconds?: number | null;
  /**
   * A field row's attempts, in order, of which the server keeps the best. A miss
   * is `null`, and the last attempt may simply be left off rather than padded.
   */
  attempts?: Array<number | null> | null;
  unit?: string | null;
  /**
   * What the helper recorded: `RESULT` for a mark (what an omitted value means),
   * `ABS` for an athlete who did not compete, or `DQ` for one disqualified. An
   * ABS/DQ row needs no mark — its mark and attempts are cleared and every
   * numeric rule is skipped.
   */
  outcome?: MarkOutcome | null;
  notes?: string | null;
  clear?: boolean;
}

export interface BulkMarkResultDTO {
  eventId: number;
  eventName: string;
  /** The sheet the rows were written to. */
  stage?: MarkStage;
  saved: number;
  cleared: number;
  skipped: number;
  failed: number;
  results: EventResultDTO[];
  errors: { userId?: number; message: string }[];
}

/* ------------------------------------------------------------------ *
 * Seasons — one sport day per school year
 * ------------------------------------------------------------------ */

/**
 * One school year, and the sport day that belongs to it.
 *
 * `current` marks the year students may enter; `activateSeason` moves it and
 * closes every other year. `notes` is optional and, like every nullable field
 * on this API, is omitted from the JSON altogether when it has no value.
 */
export interface SeasonDTO {
  id: number;
  year: number;
  name: string;
  /** What the year is called on screen, e.g. `2026 Sports Day`. */
  displayName: string;
  /** `yyyy-MM-dd`. */
  sportDayDate: string;
  /** Whether students may enter events for this year. */
  enrollmentOpen: boolean;
  notes?: string | null;
  eventCount: number;
  /** The year students may enter. Exactly one year is current. */
  current: boolean;
  createdAt: string;
  updatedAt: string;
}

/** The body of `POST /admin/seasons`. */
export interface SeasonInput {
  year: number;
  name: string;
  /** `yyyy-MM-dd`. */
  sportDayDate: string;
  enrollmentOpen?: boolean;
  notes?: string | null;
  /**
   * Copies that year's whole event catalogue into the new one. Omit it to start
   * with an empty programme.
   */
  copyEventsFromSeasonId?: number | null;
}

/** The body of `PUT /admin/seasons/{id}` — omitted fields keep their value. */
export type SeasonUpdate = Partial<{
  year: number;
  name: string;
  sportDayDate: string;
  enrollmentOpen: boolean;
  notes: string | null;
}>;

/* ------------------------------------------------------------------ *
 * Admin: students
 * ------------------------------------------------------------------ */

export interface StudentDTO {
  id: number;
  userId: number;
  /** The student id string, e.g. `S0003`. */
  studentId: string;
  name: string;
  dob: string;
  age: number;
  sex: EventSex;
  sexLabel: string;
  className: string;
  classNumber: number;
  classLabel: string;
  /** The form the class belongs to — `5` for `5D`; absent when it names none. */
  form?: string;
  house: string;
  /** The house's short code — `R`, `Y`, `B`, `G`; absent for another house. */
  houseCode?: string;
  grade: string;
  gradeLabel: string;
  gradeAgeRange: string;
  enabled: boolean;
  importBatch: string;
  createdAt: string;
  updatedAt: string;
}

export interface StudentFilters {
  className?: string;
  sex?: SexCode | '';
  grade?: Grade | '';
  house?: string;
  /**
   * `true` for the active register, `false` for the locked students, or `''`
   * (the default) for both.
   */
  enabled?: boolean | '';
}

export interface StudentUploadErrorDTO {
  rowNumber: number;
  studentId: string;
  message: string;
}

/**
 * One student named in the lock preview of a roster upload: a sample of up to
 * 100 of the students the upload would lock (or has just locked).
 */
export interface LockedStudentDTO {
  /** The student id string, e.g. `S0219`. */
  studentId: string;
  name: string;
  className: string;
  classNumber: number;
}

export interface StudentUploadResultDTO {
  batch: string;
  fileName: string;
  totalRows: number;
  created: number;
  updated: number;
  failed: number;
  gradeReferenceDate: string;
  /** Counts keyed by grade, e.g. `{ A: 12, B: 30, C: 58 }`. */
  gradeCounts: Record<string, number>;
  errors: StudentUploadErrorDTO[];
  /**
   * True when the upload only reported what it would do and changed nothing.
   * The roster endpoint defaults to this, so the lock preview is always safe.
   */
  dryRun?: boolean;
  /** True when the file declared itself the complete list for the year. */
  lockAbsent?: boolean;
  /** Students this upload locked. Stays 0 on a dry run. */
  locked?: number;
  /** Students this upload restored to active — a returning student. */
  unlocked?: number;
  /**
   * Everyone left out of the file, i.e. everyone to be locked. This is the
   * headline count of the preview: it is populated even on a dry run.
   */
  lockedTotal?: number;
  /** A sample of up to 100 of the students named above. */
  lockedStudents?: LockedStudentDTO[];
}

/** The reply of `POST /admin/students/lock-missing`. */
export interface LockMissingResultDTO {
  /** The import batch the locks were worked out from. */
  latestBatch?: string | null;
  /** How many students the most recent upload left out. */
  locked: number;
  /** How many of them were still active and so were locked by this call. */
  lockedNow: number;
}

export interface StudentSummaryDTO {
  total: number;
  byGrade: Record<string, number>;
  bySex: Record<string, number>;
  sexCodes: Record<string, string>;
}

export interface GradeRuleDTO {
  A: string;
  B: string;
  C: string;
  note: string;
  passwordRule: string;
}

export interface RecomputeGradesResultDTO {
  changed: number;
  referenceDate: string;
  byGrade: Record<string, number>;
}

/* ------------------------------------------------------------------ *
 * Admin: teachers
 * ------------------------------------------------------------------ */

/**
 * One teacher account as `GET /admin/teachers` lists it.
 *
 * That response carries the account and **not** the classes — the only response
 * that spells a teacher's classes out is the credentials sheet, which
 * `getTeacherClassMap()` reads. `classes` is therefore optional here: it is
 * populated only when a caller has merged the sheet in.
 */
export interface TeacherDTO {
  username: string;
  name: string;
  /** Omitted from the JSON altogether when the account has no email. */
  email?: string;
  enabled: boolean;
  role: Role;
  /** The classes this teacher may help in, when the caller has them to hand. */
  classes?: string[];
}

/** The reply of `PUT /admin/teachers/{username}/classes`. */
export interface TeacherClassesDTO {
  username: string;
  /** The classes that now stand, sorted. */
  classes: string[];
}

/** One teacher's login details, handed back by an upload that created or re-keyed them. */
export interface TeacherCredentialDTO {
  username: string;
  name: string;
  email?: string;
  /** The classes the teacher was given, in the upload's own order. */
  classes: string[];
  password: string;
  /** True when the password came from the file rather than being generated. */
  supplied: boolean;
}

export interface TeacherUploadErrorDTO {
  rowNumber: number;
  /** The username the row carried, when it had one. */
  username?: string;
  message: string;
}

/**
 * The outcome of `POST /admin/teachers/upload`.
 *
 * A bad row is reported and skipped rather than failing the whole file, so an
 * administrator can fix a handful of rows and re-upload. On a rehearsal
 * (`dryRun`) every count says what *would* happen, and `credentials` is always
 * empty — a rehearsal generates nothing.
 */
export interface TeacherUploadResultDTO {
  /** Identifier for this run, stamped onto every teacher it touched. */
  batch: string;
  fileName: string;
  totalRows: number;
  created: number;
  updated: number;
  failed: number;
  /** True when this was a rehearsal: nothing was written. */
  dryRun: boolean;
  /** Teachers whose class list this upload replaced (created and updated alike). */
  classesAssigned: number;
  /** How the generated passwords are derived, so the rule is not a secret. */
  passwordRule: string;
  /** One row per teacher created, and per teacher whose password the file set. */
  credentials: TeacherCredentialDTO[];
  errors: TeacherUploadErrorDTO[];
}

/* ------------------------------------------------------------------ *
 * Teacher: helping a student
 * ------------------------------------------------------------------ */

/**
 * `GET /teacher/me` — the signed-in teacher's own account and the classes they
 * may help in. An administrator gets every class on the register, because they
 * may help anybody.
 *
 * `classes` is the whole authority a teacher holds: a teacher with none is
 * refused everybody by the server, so a screen must say that plainly rather
 * than render an empty list as though it were a result.
 */
export interface TeacherMeDTO {
  profile: UserDTO;
  role: Role;
  classes: string[];
  /** The server's own wording of the rule behind `classes`. */
  note: string;
}

/* ------------------------------------------------------------------ *
 * Relay teams
 * ------------------------------------------------------------------ */

/**
 * One runner in a relay team: who they are, and which leg they run.
 *
 * The class, house and grade are read from the register when the team is read
 * rather than stored on the leg, so the board always shows where the runner is
 * *now* — which is also what the eligibility rules were judged on.
 */
export interface RelayTeamMemberDTO {
  id: number;
  teamId?: number;
  /** The login account — what entries, results and records are held against. */
  userId?: number;
  /** The student id string, e.g. `S0440`. */
  studentId?: string;
  name?: string;
  className?: string;
  /** e.g. `5D 8`. */
  classLabel?: string;
  /** The form the class belongs to — `5` for `5D`; absent when it names none. */
  form?: string;
  house?: string;
  /** The house's short code — `R`, `Y`, `B`, `G`; absent for another house. */
  houseCode?: string;
  grade?: string;
  /** 1-based leg; leg 1 runs first. */
  leg?: number;
  /** True when this runner is past the race's own legs — a reserve. */
  reserve?: boolean;
}

/**
 * One relay team: whether it is a form or a house team, and the runners down
 * for its legs. `complete` is true once every leg has a runner; reserves are
 * not required to complete it.
 */
export interface RelayTeamDTO {
  id: number;
  eventId?: number;
  eventName?: string;
  /** `FORM` or `HOUSE`. */
  kind?: RelayTeamKind;
  /** e.g. `Form`. */
  kindLabel?: string;
  /** The form number (`"5"`) or the house name (`Red`). */
  teamKey?: string;
  /** What the team is shown as: `Form 5`, or `Red` for a house team. */
  label?: string;
  /**
   * True when that name was typed by hand rather than derived from the register.
   * A re-derive refreshes a derived label and leaves a typed one alone, so a team
   * showing this is one somebody named.
   */
  nameOverridden?: boolean;
  /**
   * True when the team was **made by hand** out of a chosen set of students under
   * a typed name — see `createRelayTeam` — rather than derived from the roster.
   *
   * Such a team is not one class's and not one house's: it carries no `kind` at
   * all and is keyed with its own name, so a later derive can never match it,
   * rename it or prune it. That is what a board has to say out loud, because a
   * teacher who re-derives afterwards will see every derived team refreshed and
   * this one untouched.
   */
  handMade?: boolean;
  /** Legs in this team's race — four for a 4x100M. */
  legCount?: number;
  /** How many runners the team may hold in total, reserves included. */
  memberCap?: number;
  reservesAllowed?: boolean;
  memberCount?: number;
  complete?: boolean;
  /** The runners, leg 1 first, any reserves last. */
  members: RelayTeamMemberDTO[];
}

/**
 * `GET /admin/events/{eventId}/relay-teams` — the relay board of one event.
 *
 * A relay with no `relayTeamKind` is undivided and reports no teams at all:
 * that is a normal answer rather than a refusal, and a screen must say so
 * instead of showing an empty board that looks broken. An event that is not a
 * relay at all is refused by the server with a 400.
 */
export interface RelayEventTeamsDTO {
  eventId: number;
  eventName?: string;
  /** Enum name, e.g. `RELAY_4X100M`. */
  eventType?: string;
  eventTypeLabel?: string;
  /** The division the teams are drawn from: `MALE` or `FEMALE`. */
  sex?: string;
  /** The grade the teams are drawn from: `A`, `B` or `C`. */
  grade?: string;
  /**
   * The **form** the teams are drawn from — `1` for a Form 1 relay whose teams
   * are `1A`, `1B`, `1C` and `1D` — or null when the board is scoped by the
   * event's grade instead.
   *
   * It is the scope of the board: a form relay's classes are that form's, taken
   * across every grade its students are in, so the `grade` above says nothing
   * about who may run. A house board is always grade-scoped and carries no form.
   */
  form?: string | null;
  /** e.g. `Form 1`. The server's own label for `form`. */
  formLabel?: string;
  relayTeamKind?: RelayTeamKind | null;
  relayTeamKindLabel?: string;
  /** True when the event is a relay at all. */
  relay?: boolean;
  /** Legs in a team — four for a 4x100M. */
  legsPerTeam?: number;
  reservesAllowed?: boolean;
  /** How many runners one team may hold in total. */
  memberCap?: number;
  teamCount?: number;
  runnerCount?: number;
  teams: RelayTeamDTO[];

  /**
   * The students who **applied** to this event — a confirmed entry in it — with
   * the team each is already on when they are on one. This is the list beside the
   * teams: a teacher ticks these and groups them into the teams the event has.
   *
   * A teacher's copy carries only the applicants of their own classes (the ones
   * they may place) while the teams above are the event's whole set, because a
   * house team spans classes. A relay that is undivided still reports its
   * applicants — they are what a page counts before anything can be derived.
   */
  applicants?: RelayApplicantDTO[];
  /** How many applicants this board shows. */
  applicantCount?: number;
  /** How many of them already hold a leg in one of the teams. */
  placedCount?: number;
  /** How many are still unplaced — `applicantCount - placedCount`. */
  unplacedCount?: number;
}

/**
 * One student who applied to a relay event, as the board lists them beside the
 * teams. `teamId` is null, `teamLabel` absent and `placed` false for a student
 * who is still unplaced — that is the whole point of the list.
 */
export interface RelayApplicantDTO {
  /** The login account — what a team leg is named against. */
  userId: number;
  /** The student id string, e.g. `S0001`. */
  studentRef?: string;
  name?: string;
  /** The form the class belongs to — `5` for `5D`; absent when it names none. */
  form?: string;
  className?: string;
  classNumber?: number;
  /** e.g. `5D 8`. */
  classLabel?: string;
  /** The house, in full, as the register stores it — `Red`. */
  house?: string;
  /** `R`, `Y`, `B` or `G`; absent for a house the server does not recognise. */
  houseCode?: string;
  /** The team this applicant already runs for, or null when still unplaced. */
  teamId?: number | null;
  /** That team's name as the school writes it — `1A`, `C Grade Yellow`. */
  teamLabel?: string | null;
  /** True when `teamId` is set. */
  placed?: boolean;
}

/**
 * What deriving an event's relay teams did. Deriving is **additive**: it creates
 * the teams the roster calls for and refreshes their labels, and never removes a
 * team somebody has put runners into. An empty team the roster no longer calls
 * for is only dropped when the caller asks to prune.
 */
export interface RelayTeamDerivationDTO {
  eventId: number;
  eventName?: string;
  /** The kind the teams were derived for: `FORM` or `HOUSE`. */
  kind: RelayTeamKind;
  /** Teams created by this call. */
  created: number;
  /** Teams that were already there and kept, labels refreshed. */
  kept: number;
  /** Teams dropped because they are no longer on the roster and are empty. */
  pruned: number;
  /** Teams kept only because somebody runs in them. */
  keptWithRunners: number;
  /** How many students the teams were derived from — the event's grade + division. */
  eligibleStudents: number;
  board: RelayEventTeamsDTO;
}

/** `DELETE /admin/events/{eventId}/relay-teams` — every team of an event removed. */
export interface RelayTeamRemovalDTO {
  eventId: number;
  teamsRemoved: number;
}

/* ------------------------------------------------------------------ *
 * Backups
 * ------------------------------------------------------------------ */

/**
 * One backup file on disk, as `GET /admin/backups` describes it: what it is
 * called, how big it is, when it was written, and the row counts out of its
 * header. `problem` is set when the file could not be read, so a listing can
 * say which file is suspect rather than hiding it.
 */
export interface BackupSummaryDTO {
  name: string;
  bytes: number;
  /** e.g. `2026-10-04 18:04`, formatted by the server. */
  writtenAt?: string;
  /** `{ enrollments, finalEntries, groups, results, records }`. */
  counts?: Record<string, number>;
  /** Set when the file could not be read as a season backup. */
  problem?: string;
}

/**
 * `POST /admin/season/reset` — the reset's own summary.
 *
 * The reset writes a restorable backup **first** and refuses to run without one,
 * so the backup's name and size are always here: that is how the office can see
 * a reset was backed up. The counts are what the reset removed.
 */
export interface SeasonResetResultDTO {
  /** The file the backup was written to. */
  backupFile: string;
  backupBytes: number;
  backupWrittenAt?: string;
  enrollmentsRemoved: number;
  finalPlacesRemoved: number;
  groupsRemoved: number;
  resultsRemoved: number;
  /** The school records kept: a typed-in mark survives a reset. */
  recordsKept: number;
  /** Events put back to a straight final, because nobody is entered any more. */
  eventsReformatted: number;
  studentsKept?: string;
  eventsKept?: string;
}

/**
 * `POST /admin/backups/{name}/restore` — **destructive**. Every entry, heat,
 * final place, recorded mark and record baseline is replaced with the file's
 * contents. Students, events, school years and settings are not touched; a row
 * whose student or event no longer exists is skipped and counted.
 */
export interface SeasonRestoreResultDTO {
  restoredFrom: string;
  groupsRestored: number;
  enrollmentsRestored: number;
  finalEntriesRestored: number;
  resultsRestored: number;
  recordsRestored: number;
  recordsRecomputed: number;
  eventsReformatted: number;
  restoredAt: string;
  /** Rows the file held that could not be put back, by kind. */
  skipped?: Record<string, number>;
}

/* ------------------------------------------------------------------ *
 * Scoring settings
 * ------------------------------------------------------------------ */

/**
 * The tunable rules behind entries and championship points. `pointsTopPlace`
 * is the lowest place that still scores, and `pointsTop` is what every place
 * from 4th down to it is worth. Relays score on their own, larger scale.
 */
export interface SettingsDTO {
  /** The school's name. Heads every printed marking sheet. */
  schoolName: string;
  /** The school's name in Chinese. Heads every printed marking sheet. */
  schoolNameZh: string;
  address: string;
  principal: string;
  /** The heading printed across the top of every marking sheet. */
  sportDayTitle: string;
  trackMaxEntries: number;
  fieldMaxEntries: number;
  pointsFirst: number;
  pointsSecond: number;
  pointsThird: number;
  /** Lowest place that still scores, e.g. 8. */
  pointsTopPlace: number;
  /** Points for places 4..`pointsTopPlace`. */
  pointsTop: number;
  relayPointsFirst: number;
  relayPointsSecond: number;
  relayPointsThird: number;
  /** Points for relay places 4..`pointsTopPlace`. */
  relayPointsTop: number;
  updatedAt?: string;
}

/** Every field is optional: omitted fields keep their stored value. */
export type SettingsUpdate = Partial<Omit<SettingsDTO, 'updatedAt'>>;

export interface SettingsResetResultDTO extends SettingsDTO {
  message?: string;
}

/* ------------------------------------------------------------------ *
 * School records
 * ------------------------------------------------------------------ */

/**
 * Where the mark that stands came from: a mark an administrator typed in
 * (`BASELINE`), the best recorded result (`RESULT`), or nothing yet (`NONE`).
 */
export type RecordSource = 'BASELINE' | 'RESULT' | 'NONE';

/**
 * One school record, per event type + division + grade.
 *
 * A row exists for every combination as soon as the event does, so an event
 * nobody has competed in still appears with `source: 'NONE'` — and on those rows
 * the server omits `mark`, `unit`, `holderUserId`, `holderStudentRef`,
 * `holderName`, `eventId`, `eventName` and `achievedOn` altogether rather than
 * sending them as null.
 */
export interface RecordDTO {
  id: number;
  /** Enum name, e.g. `RUN_60M`. */
  eventType: string;
  /** e.g. `60M`. */
  eventTypeLabel: string;
  category: EventCategory;
  /** e.g. `徑項 Track`. */
  categoryLabel: string;
  sex: EventSex;
  /** e.g. `男 Boys`. */
  sexLabel: string;
  grade: string;
  /** e.g. `C組`. */
  gradeLabel: string;
  /** The mark that stands. Absent while `source` is `NONE`. */
  mark?: number;
  unit?: string;
  source: RecordSource;
  /** Present only when a recorded result holds the record. */
  holderUserId?: number;
  /** The student id string, e.g. `S0056`. */
  holderStudentRef?: string;
  holderName?: string;
  /** The event the record was set in, which may have been deleted. */
  eventId?: number | null;
  eventName?: string | null;
  achievedOn?: string;
  /** The mark an administrator typed in by hand, which survives a rebuild. */
  manualMark?: number;
  manualUnit?: string;
  manualHolderName?: string;
  manualAchievedOn?: string;
  /** The mark this record beat. Only meaningful when `hasPrevious`. */
  previousMark?: number | null;
  previousHolderName?: string | null;
  previousAchievedOn?: string | null;
  hasPrevious: boolean;
  updatedAt: string;
}

/** The body of `PUT /admin/records/{recordId}` — an administrator's typed-in mark. */
export interface RecordBaselineInput {
  mark: number;
  /** Defaults to `s` on the track and `M` in the field. */
  unit?: string | null;
  /** Free text: a record may be held by a student who has left. */
  holderName?: string | null;
  /** `yyyy-MM-dd`. */
  achievedOn?: string | null;
}

export interface RecomputeRecordsResultDTO {
  recordsRebuilt: number;
  records: number;
}

/** The reply of `POST /admin/records/seed`. */
export interface SeedRecordsResultDTO {
  recordsCreated: number;
  records: number;
}

/* ------------------------------------------------------------------ *
 * Championships / standings
 * ------------------------------------------------------------------ */

/** One scoring athlete in a championship table. */
export interface ChampionshipPersonRowDTO {
  rank: number;
  userId: number;
  /** The student id string, e.g. `S0056`. */
  studentRef: string;
  name: string;
  grade: string;
  className: string;
  /** The form the class belongs to — `5` for `5D`; absent when it names none. */
  form?: string;
  house: string;
  /** The house's short code — `R`, `Y`, `B`, `G`; absent for another house. */
  houseCode?: string;
  points: number;
  golds: number;
  silvers: number;
  bronzes: number;
  eventsScored: number;
}

/** One house in the house championship. */
export interface ChampionshipHouseRowDTO {
  rank: number;
  house: string;
  points: number;
  golds: number;
  silvers: number;
  bronzes: number;
  athletes: number;
}

/** One athlete's placing in an event, with the points it scored. */
export interface ChampionshipPlacingDTO {
  place: number;
  userId: number;
  /** The student id string, e.g. `S0056`. */
  studentRef: string;
  name: string;
  grade: string;
  className: string;
  /** The form the class belongs to — `5` for `5D`; absent when it names none. */
  form?: string;
  house: string;
  /** The house's short code — `R`, `Y`, `B`, `G`; absent for another house. */
  houseCode?: string;
  mark: number;
  unit: string;
  /** The mark with its unit, e.g. `18.12M` — see `resultMark()`. */
  displayMark?: string;
  points: number;
  /** True when the mark set a school record. */
  schoolRecord?: boolean;
}

/**
 * The placings of one event, which is also what
 * `GET /events/{id}/standings` answers with (a single entry of the same shape
 * as `ChampionshipsDTO.events[]`).
 */
export interface EventStandingsDTO {
  eventId: number;
  eventName: string;
  /** e.g. `60M`. */
  eventTypeLabel: string;
  category: EventCategory;
  categoryLabel: string;
  sex: EventSex;
  sexLabel: string;
  eventDate: string;
  /** `FINAL` where a final was run, otherwise `HEAT`. */
  scoringStage: MarkStage;
  /** True when the placings count for the house only. */
  relay: boolean;
  hasFinal: boolean;
  sheetSize: SheetSize;
  placings: ChampionshipPlacingDTO[];
}

export interface ChampionshipsDTO {
  /** The date the grade / age divisions were fixed on. */
  referenceDate: string;
  eventsScored: number;
  /** e.g. `FINAL where a final was run, otherwise HEAT`. */
  scoringStageNote: string;
  settings: SettingsDTO;
  personal: ChampionshipPersonRowDTO[];
  houses: ChampionshipHouseRowDTO[];
  events: EventStandingsDTO[];
}

/* ------------------------------------------------------------------ *
 * Query / download helpers
 * ------------------------------------------------------------------ */

type QueryValue = string | number | boolean | undefined | null;

export function buildQuery(params: Record<string, QueryValue>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    search.set(key, String(value));
  });
  const qs = search.toString();
  return qs ? `?${qs}` : '';
}

/** Reads the filename the server suggested via `Content-Disposition`. */
export function filenameFromDisposition(header: string | null): string | null {
  if (!header) return null;
  const extended = /filename\*=\s*(?:UTF-8'')?([^;]+)/i.exec(header);
  if (extended) {
    const raw = extended[1].trim().replace(/^"|"$/g, '');
    try {
      return decodeURIComponent(raw);
    } catch {
      return raw;
    }
  }
  const plain = /filename=\s*"?([^";]+)"?/i.exec(header);
  return plain ? plain[1].trim() : null;
}

function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/**
 * Splits a CSV document into rows of cells.
 *
 * The server quotes a cell only when it holds a comma, a quote or a newline
 * (see `TeacherService.escape`), so this handles those three cases and the
 * leading byte-order mark, and treats CRLF and LF alike. It is deliberately
 * small: the only sheet read back is the teachers' credentials sheet.
 */
function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;

  // The sheet starts with a UTF-8 BOM, which is not part of the first header.
  const body = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;

  for (let index = 0; index < body.length; index += 1) {
    const char = body[index];
    if (quoted) {
      if (char === '"') {
        if (body[index + 1] === '"') {
          cell += '"';
          index += 1;
        } else {
          quoted = false;
        }
      } else {
        cell += char;
      }
      continue;
    }
    if (char === '"') {
      quoted = true;
    } else if (char === ',') {
      row.push(cell);
      cell = '';
    } else if (char === '\n' || char === '\r') {
      // Swallow the LF of a CRLF pair rather than opening an empty row.
      if (char === '\r' && body[index + 1] === '\n') index += 1;
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else {
      cell += char;
    }
  }
  if (cell !== '' || row.length > 0) {
    row.push(cell);
    rows.push(row);
  }
  return rows;
}

/* ------------------------------------------------------------------ *
 * Client
 * ------------------------------------------------------------------ */

class ApiClient {
  private getToken(): string | null {
    if (typeof window !== 'undefined') {
      return localStorage.getItem('token');
    }
    return null;
  }

  private getHeaders(json = true): HeadersInit {
    const headers: Record<string, string> = {};
    if (json) headers['Content-Type'] = 'application/json';
    const token = this.getToken();
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    return headers;
  }

  private async request<T>(url: string, options: RequestInit = {}): Promise<T> {
    // Let the browser set the multipart boundary for FormData uploads.
    const isFormData =
      typeof FormData !== 'undefined' && options.body instanceof FormData;

    const response = await fetch(`${API_BASE}${url}`, {
      ...options,
      headers: {
        ...this.getHeaders(!isFormData),
        ...(options.headers as Record<string, string> | undefined),
      },
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed' }));
      if (error.errors) {
        const messages = Object.entries(error.errors).map(([field, msg]) => `${field}: ${msg}`);
        throw new Error(messages.join('\n'));
      }
      throw new Error(error.message || `HTTP ${response.status}`);
    }

    if (response.status === 204) return undefined as T;
    return response.json();
  }

  /**
   * Fetch a binary response (PDF / CSV) with the bearer token attached and save
   * it to disk. A plain `<a href>` cannot be used because these endpoints are
   * authenticated and would return 403.
   */
  async downloadFile(url: string, fallbackFilename: string): Promise<string> {
    const response = await fetch(`${API_BASE}${url}`, {
      headers: this.getHeaders(false),
    });

    if (!response.ok) {
      const error = await response
        .json()
        .catch(() => ({ message: `Download failed (HTTP ${response.status})` }));
      throw new Error(error.message || `Download failed (HTTP ${response.status})`);
    }

    const blob = await response.blob();
    const filename =
      filenameFromDisposition(response.headers.get('Content-Disposition')) || fallbackFilename;
    saveBlob(blob, filename);
    return filename;
  }

  /**
   * Fetches a text response (a CSV) with the bearer token attached, without
   * saving it. Used to read a sheet back rather than hand it to the user.
   */
  private async requestText(url: string): Promise<string> {
    const response = await fetch(`${API_BASE}${url}`, {
      headers: this.getHeaders(false),
    });

    if (!response.ok) {
      const error = await response
        .json()
        .catch(() => ({ message: `Request failed (HTTP ${response.status})` }));
      throw new Error(error.message || `HTTP ${response.status}`);
    }

    return response.text();
  }

  /* ---------------- Auth ---------------- */

  async login(username: string, password: string): Promise<AuthResponse> {
    return this.request('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });
  }

  // There is deliberately no register() here. Public self-registration was
  // removed, and POST /auth/register now returns 404 — accounts are created by an
  // administrator through createUser() below, and students arrive by register
  // import.

  /* ---------------- Events ---------------- */

  async getEvents(query: EventQuery | boolean = {}): Promise<EventDTO[]> {
    // Backwards compatible with the old `getEvents(onlyEnabled)` signature.
    const normalised: EventQuery = typeof query === 'boolean' ? { onlyEnabled: query } : query;
    return this.request(`/events${buildQuery({ ...normalised })}`);
  }

  async getEvent(id: number): Promise<EventDTO> {
    return this.request(`/events/${id}`);
  }

  /** The dates the programme runs on, newest first, each with its event count. */
  async getEventDates(): Promise<EventDateDTO[]> {
    return this.request('/events/dates');
  }

  async createEvent(event: Partial<EventDTO>): Promise<EventDTO> {
    return this.request('/events', {
      method: 'POST',
      body: JSON.stringify(event),
    });
  }

  async updateEvent(id: number, event: Partial<EventDTO>): Promise<EventDTO> {
    return this.request(`/events/${id}`, {
      method: 'PUT',
      body: JSON.stringify(event),
    });
  }

  async setEventEnabled(id: number, enabled: boolean): Promise<EventDTO> {
    return this.request(`/events/${id}/enable?enabled=${enabled}`, {
      method: 'PATCH',
    });
  }

  async deleteEvent(id: number): Promise<void> {
    return this.request(`/events/${id}`, { method: 'DELETE' });
  }

  /** Admin: create the standard event catalogue for a sport day. */
  async createDefaultEvents(
    eventDate: string,
    includeField = true
  ): Promise<EventDefaultsResultDTO> {
    return this.request(
      `/events/defaults${buildQuery({ eventDate, includeField })}`,
      { method: 'POST' }
    );
  }

  /** Events dated today or earlier, most recent first. */
  async getPastEvents(): Promise<EventDTO[]> {
    return this.request('/events/past');
  }

  /** The scored placings of one event. Used by the past-event results view. */
  async getEventStandings(eventId: number): Promise<EventStandingsDTO> {
    return this.request(`/events/${eventId}/standings`);
  }

  /* ---------------- Enrollments ---------------- */

  async enroll(eventId: number): Promise<EnrollmentDTO> {
    return this.request(`/enrollments/${eventId}`, { method: 'POST' });
  }

  async reEnroll(eventId: number): Promise<EnrollmentDTO> {
    return this.request(`/enrollments/${eventId}/re-enroll`, { method: 'POST' });
  }

  async cancelEnrollment(eventId: number): Promise<void> {
    return this.request(`/enrollments/${eventId}`, { method: 'DELETE' });
  }

  /** Confirmed entries for the signed-in user. */
  async getMyEnrollments(): Promise<EnrollmentDTO[]> {
    return this.request('/enrollments/my');
  }

  /** Every entry for the signed-in user, including withdrawn ones. */
  async getMyEnrollmentsAll(): Promise<EnrollmentDTO[]> {
    return this.request('/enrollments/my/all');
  }

  async checkEnrollment(eventId: number): Promise<boolean> {
    return this.request(`/enrollments/check/${eventId}`);
  }

  async getMyQuota(): Promise<QuotaDTO> {
    return this.request('/enrollments/my/quota');
  }

  /**
   * Roster of an event. The dedicated endpoint is staff-only and may come back
   * empty, so fall back to rebuilding the roster from the allocated heats.
   */
  async getEventEnrollments(eventId: number): Promise<EnrollmentDTO[]> {
    const direct = await this.request<EnrollmentDTO[]>(`/enrollments/event/${eventId}`).catch(
      () => []
    );
    if (direct.length > 0) return direct;

    try {
      const groups = await this.getEventGroups(eventId);
      const rosters = await Promise.all(groups.map(group => this.getGroup(group.id)));
      return rosters.flatMap(group => group.athletes || []);
    } catch {
      return direct;
    }
  }

  /* ---------------- Groups / heats ---------------- */

  /** Heat summaries for an event. The response carries no athlete rosters. */
  async getEventGroups(eventId: number): Promise<EventGroupDTO[]> {
    return this.request(`/events/${eventId}/groups`);
  }

  /** A single heat, including its `athletes` roster. */
  async getGroup(groupId: number): Promise<EventGroupDTO> {
    return this.request(`/groups/${groupId}`);
  }

  async allocateGroups(eventId: number, shuffle = true): Promise<AllocateGroupsResultDTO> {
    return this.request(
      `/events/${eventId}/groups/allocate?shuffle=${shuffle}`,
      { method: 'POST' }
    );
  }

  async clearEventGroups(eventId: number): Promise<void> {
    return this.request(`/events/${eventId}/groups`, { method: 'DELETE' });
  }

  // Authenticated binary downloads.

  async downloadGroupSheet(groupId: number, fallbackFilename: string): Promise<string> {
    return this.downloadFile(`/groups/${groupId}/sheet.pdf`, fallbackFilename);
  }

  async downloadEventSheets(eventId: number, fallbackFilename: string): Promise<string> {
    return this.downloadFile(`/events/${eventId}/sheets.pdf`, fallbackFilename);
  }

  /* ---------------- Seasons: one sport day per school year ---------------- */

  /**
   * Every school year, newest first, each with its date, event count and whether
   * entries are open. Readable by any signed-in user.
   */
  async getSeasons(): Promise<SeasonDTO[]> {
    return this.request('/seasons');
  }

  /**
   * The year students may enter, or `null` when no year has been made current.
   * Answers with an empty body as well as with `null`, so callers should treat
   * both the same way.
   */
  async getCurrentSeason(): Promise<SeasonDTO | null> {
    return (await this.request<SeasonDTO | null>('/seasons/current')) ?? null;
  }

  /**
   * Creates a school year. `copyEventsFromSeasonId` duplicates that year's whole
   * event catalogue into the new one. A year that already exists is refused with
   * a 400 worth showing as it stands.
   */
  async createSeason(season: SeasonInput): Promise<SeasonDTO> {
    return this.request('/admin/seasons', {
      method: 'POST',
      body: JSON.stringify(season),
    });
  }

  /** Admin: writes any subset; omitted fields keep their stored value. */
  async updateSeason(id: number, patch: SeasonUpdate): Promise<SeasonDTO> {
    return this.request(`/admin/seasons/${id}`, {
      method: 'PUT',
      body: JSON.stringify(patch),
    });
  }

  /**
   * Makes this the year students may enter, and closes the others. This is the
   * switch that decides whether a student can enter anything at all.
   */
  async activateSeason(id: number): Promise<SeasonDTO> {
    return this.request(`/admin/seasons/${id}/activate`, { method: 'POST' });
  }

  /**
   * Removes a school year. Refused with a 409 whose message names the count
   * while the year still has events, so show the server's own wording.
   */
  async deleteSeason(id: number): Promise<void> {
    return this.request(`/admin/seasons/${id}`, { method: 'DELETE' });
  }

  /* ---------------- Admin: students ---------------- */

  async getStudents(filters: StudentFilters = {}): Promise<StudentDTO[]> {
    return this.request(`/admin/students${buildQuery({ ...filters })}`);
  }

  async getStudentSummary(): Promise<StudentSummaryDTO> {
    return this.request('/admin/students/summary');
  }

  /** One student of the register, by their student id string. */
  async getStudent(studentId: string): Promise<StudentDTO> {
    return this.request(`/admin/students/${encodeURIComponent(studentId)}`);
  }

  /* ------------- Admin: a student's entries, made on their behalf ------------- */

  /**
   * The entries an administrator manages for a student, with the track / field
   * quota that still applies to them. The list is the student's whole history,
   * so withdrawn entries come back with `status: 'CANCELLED'`.
   */
  async getStudentEnrollments(studentId: string): Promise<StudentEnrollmentsDTO> {
    return this.request(`/admin/students/${encodeURIComponent(studentId)}/enrollments`);
  }

  /**
   * Enters the student in an event on their behalf. The quota is enforced
   * against the student, so a full category is refused with a 409 whose message
   * names the category and the maximum.
   */
  async enrollStudent(studentId: string, eventId: number): Promise<EnrollmentDTO> {
    return this.request(
      `/admin/students/${encodeURIComponent(studentId)}/enrollments/${eventId}`,
      { method: 'POST' }
    );
  }

  /** Cancels a student's entry, freeing the place in their quota. */
  async cancelStudentEnrollment(studentId: string, eventId: number): Promise<void> {
    return this.request(
      `/admin/students/${encodeURIComponent(studentId)}/enrollments/${eventId}`,
      { method: 'DELETE' }
    );
  }

  async getGradeRule(): Promise<GradeRuleDTO> {
    return this.request('/admin/students/grade-rule');
  }

  /**
   * Upload a `.csv` / `.xlsx` student register.
   *
   * `lockAbsent` declares the file the complete student list for the year: on a
   * real run everyone left out of it is locked. It defaults to false, which is
   * the plain upload the register has always done.
   */
  async uploadStudents(
    file: File,
    referenceDate?: string,
    lockAbsent = false
  ): Promise<StudentUploadResultDTO> {
    const form = new FormData();
    form.append('file', file);
    return this.request(
      `/admin/students/upload${buildQuery({ referenceDate, lockAbsent })}`,
      { method: 'POST', body: form }
    );
  }

  /**
   * Upload the same file declared to be the complete student list for the year.
   *
   * On this endpoint `lockAbsent` defaults to true and `dryRun` defaults to true,
   * so the first call only reports who *would* be locked — `lockedTotal` and the
   * `lockedStudents` sample — and changes nothing. Call it again with
   * `dryRun: false`, on an explicit confirm, to lock them for real.
   */
  async uploadStudentsRoster(
    file: File,
    dryRun = true
  ): Promise<StudentUploadResultDTO> {
    const form = new FormData();
    form.append('file', file);
    return this.request(`/admin/students/upload/roster${buildQuery({ dryRun })}`, {
      method: 'POST',
      body: form,
    });
  }

  /**
   * Locks a student, or restores them.
   *
   * A locked student keeps their entries, results and records — they are hidden,
   * not deleted — but cannot sign in (their login answers 403) and cannot be
   * entered in an event.
   */
  async setStudentLock(studentId: string, locked: boolean): Promise<StudentDTO> {
    return this.request(
      `/admin/students/${encodeURIComponent(studentId)}/lock?locked=${locked}`,
      { method: 'PATCH' }
    );
  }

  /**
   * Locks everyone the most recent register upload left out, for an admin who
   * did not declare that upload the complete list.
   */
  async lockMissingStudents(): Promise<LockMissingResultDTO> {
    return this.request('/admin/students/lock-missing', { method: 'POST' });
  }

  /** Seed sample students and return the same result shape as an upload. */
  async generateSampleStudents(count = 600): Promise<StudentUploadResultDTO> {
    return this.request(`/admin/students/sample?count=${count}`, { method: 'POST' });
  }

  async recomputeGrades(referenceDate?: string): Promise<RecomputeGradesResultDTO> {
    return this.request(`/admin/students/recompute-grades${buildQuery({ referenceDate })}`, {
      method: 'POST',
    });
  }

  async downloadCredentialsCsv(className?: string): Promise<string> {
    return this.downloadFile(
      `/admin/students/credentials.csv${buildQuery({ className })}`,
      'student-credentials.csv'
    );
  }

  async downloadStudentTemplate(): Promise<string> {
    return this.downloadFile('/admin/students/template.csv', 'student-upload-template.csv');
  }

  async downloadStudentSample(count = 600): Promise<string> {
    return this.downloadFile(
      `/admin/students/sample.csv?count=${count}`,
      'student-sample.csv'
    );
  }

  /**
   * Clears entries, heats, finals, results and the school records — leaves
   * students, events and the typed-in record baselines alone.
   *
   * It writes a restorable backup of everything it destroys **first** and refuses
   * to run at all if that file cannot be written, so the answer always names the
   * file and its size. That is what a screen shows to prove a reset was backed
   * up — see `SeasonResetResultDTO`.
   */
  async resetSeason(): Promise<SeasonResetResultDTO> {
    return this.request('/admin/season/reset', { method: 'POST' });
  }

  /* ---------------- Admin: teachers ---------------- */

  /**
   * Every TEACHER account. The response deliberately carries no classes — see
   * `getTeacherClassMap()` for those.
   */
  async getTeachers(): Promise<TeacherDTO[]> {
    return this.request('/admin/teachers');
  }

  /**
   * Upload the staff list, one row per teacher: `username`, `name` and
   * `classes` are required, `email` and `password` optional. `classes` is one
   * cell holding one or more class names separated by `;`, `,`, `|` or `、`,
   * e.g. `1A;3B`.
   *
   * `dryRun` rehearses the whole file and writes nothing, classifying every row
   * as created / updated / failed — so a rehearsal is always safe to run first
   * and is what the screen shows before it applies the same file for real. An
   * existing username is updated and its class list replaced, so re-uploading
   * is idempotent.
   */
  async uploadTeachers(file: File, dryRun: boolean): Promise<TeacherUploadResultDTO> {
    const form = new FormData();
    form.append('file', file);
    return this.request(`/admin/teachers/upload${buildQuery({ dryRun })}`, {
      method: 'POST',
      body: form,
    });
  }

  async downloadTeacherTemplate(): Promise<string> {
    return this.downloadFile('/admin/teachers/template.csv', 'teacher-upload-template.csv');
  }

  /**
   * Replaces one teacher's class list without re-uploading the whole staff file
   * — for a mistyped class, or a teacher who has changed year group. The list is
   * one cell of class names separated by `;`, `,`, `|` or `、`, e.g. `1A;3B`, and
   * replaces whatever was there. The server refuses an empty list with a message
   * worth showing, because a teacher with no classes can help nobody.
   */
  async updateTeacherClasses(username: string, classes: string): Promise<TeacherClassesDTO> {
    return this.request(
      `/admin/teachers/${encodeURIComponent(username)}/classes${buildQuery({ classes })}`,
      { method: 'PUT' }
    );
  }

  async downloadTeacherCredentials(): Promise<string> {
    return this.downloadFile('/admin/teachers/credentials.csv', 'teacher-credentials.csv');
  }

  /**
   * The classes each teacher may help in, keyed by username.
   *
   * `GET /admin/teachers` does not carry a teacher's classes, and the only
   * response that spells them out per teacher is the credentials sheet, so that
   * sheet is read back here and reduced to `username -> classes`. Nothing else
   * on it is kept or shown: the passwords in it are never touched by this
   * method.
   */
  async getTeacherClassMap(): Promise<Record<string, string[]>> {
    const csv = await this.requestText('/admin/teachers/credentials.csv');
    const map: Record<string, string[]> = {};
    parseCsv(csv).forEach(cells => {
      const username = (cells[0] ?? '').trim();
      // The header row, and any blank line the sheet ends on.
      if (!username || username.toLowerCase() === 'username') return;
      map[username] = (cells[3] ?? '')
        .split(/[;|、,]/)
        .map(className => className.trim())
        .filter(Boolean);
    });
    return map;
  }

  /* ---------------- Teacher: helping a student ---------------- */

  /**
   * The signed-in teacher's own account, the classes they may help in and the
   * server's wording of that rule. An administrator gets every class on the
   * register.
   */
  async getTeacherMe(): Promise<TeacherMeDTO> {
    return this.request('/teacher/me');
  }

  /**
   * The students of the caller's own classes, optionally narrowed to one of
   * them. A teacher with no classes gets an empty list — never the whole
   * school — so the caller must tell that case apart from "no students here".
   */
  async getTeacherStudents(className?: string): Promise<StudentDTO[]> {
    return this.request(`/teacher/students${buildQuery({ className })}`);
  }

  /**
   * A student's entries — withdrawn ones included — with the track / field
   * quota that still applies to them. The same shape as the administrator's
   * `getStudentEnrollments`, held to the teacher's class rule instead.
   */
  async getTeacherStudentEnrollments(studentId: string): Promise<StudentEnrollmentsDTO> {
    return this.request(`/teacher/students/${encodeURIComponent(studentId)}/enrollments`);
  }

  /**
   * Enters the student in an event on their behalf. An entry they had withdrawn
   * from is revived rather than refused, and the event's own grade and division
   * plus the student's quota all still apply.
   */
  async enrollTeacherStudent(studentId: string, eventId: number): Promise<EnrollmentDTO> {
    return this.request(
      `/teacher/students/${encodeURIComponent(studentId)}/enrollments/${eventId}`,
      { method: 'POST' }
    );
  }

  /** Withdraws the student's entry, freeing the place in their quota. */
  async cancelTeacherStudentEnrollment(studentId: string, eventId: number): Promise<void> {
    return this.request(
      `/teacher/students/${encodeURIComponent(studentId)}/enrollments/${eventId}`,
      { method: 'DELETE' }
    );
  }

  /* ---------------- Admin: relay teams ---------------- */

  /**
   * The relay endpoint family the caller is allowed to use: `/admin/**` is
   * ADMIN only, `/teacher/**` admits ADMIN and TEACHER alike. Both answer the
   * same board and obey the same service, so a single relay page serves both
   * roles by choosing the right family — an administrator gets the one that can
   * also remove every team.
   */
  private relayBase(role: Role): string {
    return role === 'ADMIN' ? '/admin' : '/teacher';
  }

  /**
   * An event's relay board: what kind of relay it is, how big a team is, every
   * team with its runners, and the applicants who have still to be placed.
   *
   * A relay with no kind is undivided and reports no teams (its applicants are
   * still listed). An event that is not a relay is refused with a 400 — and a
   * role with no business here with a 403 — so both are worth showing as they
   * stand.
   */
  async getRelayTeams(eventId: number, role: Role = 'ADMIN'): Promise<RelayEventTeamsDTO> {
    return this.request(`${this.relayBase(role)}/events/${eventId}/relay-teams`);
  }

  /**
   * Creates the teams the roster calls for — one per class of the event's scope,
   * or one per house of its grade, in its own division — and refreshes their
   * labels.
   *
   * The scope is the event's **kind and its form together**: a form-scoped relay
   * (`form` set) takes one team per class of that form *across grades* — `1A`,
   * `1B`, `1C`, `1D` — while a relay with no form keeps the older rule and takes
   * one team per class of its own grade. A house relay is one team per house of
   * that grade either way.
   *
   * Additive on purpose: a team somebody has already put runners into is never
   * removed, because those selections are not the roster's to throw away. An
   * *empty* team the roster no longer calls for is only dropped when `prune` is
   * set — which is what a re-scoped form relay needs, or the classes of the form
   * it just left would sit there empty and hold the relay back from being ready.
   * A relay that has not been divided yet is refused with a 409 telling the
   * administrator to set its kind first.
   */
  async deriveRelayTeams(
    eventId: number,
    prune = false,
    role: Role = 'ADMIN'
  ): Promise<RelayTeamDerivationDTO> {
    return this.request(
      `${this.relayBase(role)}/events/${eventId}/relay-teams/derive${buildQuery({ prune })}`,
      { method: 'POST' }
    );
  }

  /**
   * Removes every team of the event, with its runners. **ADMIN only** — this is
   * what frees a relay to change kind, and what starts a selection again; it
   * leaves the event otherwise untouched. A teacher is refused by the server.
   */
  async removeRelayTeams(eventId: number): Promise<RelayTeamRemovalDTO> {
    return this.request(`/admin/events/${eventId}/relay-teams`, { method: 'DELETE' });
  }

  /**
   * Creates one relay team by hand out of the students the caller chose, under
   * the name the school writes on the sheet.
   *
   * This is **the primary way a team is made** — a teacher ticks the applicants,
   * types a name and presses create — and the team it makes is deliberately not
   * required to be one class or one house: `1A`, `B Grade Yellow`, anything the
   * school writes. `userIds` is **in leg order**, so the first student listed
   * runs leg 1.
   *
   * Every eligibility rule is the server's and is refused with its own wording,
   * worth showing as it stands: a blank name, a name over 40 characters, a name
   * another team of this event already holds, a student who is not in this
   * year's list, is in the wrong grade or division, is already running in another
   * team of this event, and a squad larger than the event's own cap (four runners
   * and at most one reserve). For a teacher every chosen student must also be in
   * one of their own classes.
   *
   * A request that is going to be refused writes nothing at all, so a failure
   * leaves the board exactly as it was and the ticks can simply be retried.
   */
  async createRelayTeam(
    eventId: number,
    name: string,
    userIds: number[],
    role: Role = 'ADMIN'
  ): Promise<RelayTeamDTO> {
    return this.request(`${this.relayBase(role)}/relay-events/${eventId}/teams`, {
      method: 'POST',
      body: JSON.stringify({ name, userIds }),
    });
  }

  /**
   * Names a runner for a leg (1-based; omit `leg` for the next free one).
   *
   * Every eligibility rule is enforced by the server — the event's division and
   * grade, the team's own form or house, one leg per athlete per event, and the
   * team's size — and an ineligible pick is refused with a 409 whose message
   * names the athlete and the reason, which is worth showing as it stands. For a
   * teacher the class rule bites as well: a student outside their own classes is
   * refused, and the message says so.
   */
  async addRelayRunner(
    teamId: number,
    userId: number,
    leg?: number | null,
    role: Role = 'ADMIN'
  ): Promise<RelayTeamDTO> {
    return this.request(`${this.relayBase(role)}/relay-teams/${teamId}/runners`, {
      method: 'POST',
      body: JSON.stringify(leg ? { userId, leg } : { userId }),
    });
  }

  /** Takes the athlete out of the team; the legs close up behind them. */
  async removeRelayRunner(
    teamId: number,
    userId: number,
    role: Role = 'ADMIN'
  ): Promise<RelayTeamDTO> {
    return this.request(`${this.relayBase(role)}/relay-teams/${teamId}/runners/${userId}`, {
      method: 'DELETE',
    });
  }

  /**
   * Sets the running order, leg 1 first. The list must name exactly the runners
   * the team already has — the server refuses anything else rather than guessing.
   */
  async setRelayLegs(
    teamId: number,
    userIds: number[],
    role: Role = 'ADMIN'
  ): Promise<RelayTeamDTO> {
    return this.request(`${this.relayBase(role)}/relay-teams/${teamId}/legs`, {
      method: 'PUT',
      body: JSON.stringify({ userIds }),
    });
  }

  /**
   * Renames a relay team. This is **the name the school writes on the sheet**, so
   * it is what a marking sheet and a mark grid are keyed on.
   *
   * The server refuses a blank name, a name over 40 characters, and a name another
   * team of the same event already holds — each with its own wording, worth showing
   * as it stands. An administrator may rename any team; a teacher only one from
   * their own classes, and a house team only while one of their own athletes is
   * named on it.
   */
  async renameRelayTeam(
    teamId: number,
    name: string,
    role: Role = 'ADMIN'
  ): Promise<RelayTeamDTO> {
    return this.request(`${this.relayBase(role)}/relay-teams/${teamId}/name`, {
      method: 'PUT',
      body: JSON.stringify({ name }),
    });
  }

  /* ---------------- Admin: backups ---------------- */

  /**
   * Every season backup, newest first, with its size, the moment it was taken and
   * the row counts out of its header — so the right one can be picked without
   * downloading it. A file that cannot be read is still listed, with `problem`
   * saying so.
   */
  async getBackups(): Promise<BackupSummaryDTO[]> {
    return this.request('/admin/backups');
  }

  /** One backup file, saved to disk exactly as it was written. */
  async downloadBackup(name: string, fallbackFilename: string): Promise<string> {
    return this.downloadFile(
      `/admin/backups/${encodeURIComponent(name)}`,
      fallbackFilename
    );
  }

  /**
   * **DESTRUCTIVE.** Replaces the current entries, heats, final places, marks and
   * school-record baselines with the contents of the named file. Students,
   * events, school years and settings are not touched; a row whose student or
   * event no longer exists is skipped and counted rather than invented.
   */
  async restoreBackup(name: string): Promise<SeasonRestoreResultDTO> {
    return this.request(`/admin/backups/${encodeURIComponent(name)}/restore`, {
      method: 'POST',
    });
  }

  /* ---------------- Results ---------------- */

  async getEventResults(eventId: number): Promise<EventResultDTO[]> {
    return this.request(`/results/event/${eventId}`);
  }

  async getUserResults(userId: number): Promise<EventResultDTO[]> {
    return this.request(`/results/user/${userId}`);
  }

  async recordResult(
    userId: number,
    eventId: number,
    mark: number,
    unit?: string,
    notes?: string
  ): Promise<EventResultDTO> {
    const params = new URLSearchParams({
      userId: String(userId),
      eventId: String(eventId),
      mark: String(mark),
    });
    if (unit) params.set('unit', unit);
    if (notes) params.set('notes', notes);
    return this.request(`/results?${params.toString()}`, { method: 'POST' });
  }

  async deleteResult(id: number): Promise<void> {
    return this.request(`/results/${id}`, { method: 'DELETE' });
  }

  /**
   * One event's results as a PDF. An event with nothing recorded is refused with
   * a 409 whose message says so, and is worth showing as it stands.
   */
  async downloadEventResultsPdf(
    eventId: number,
    fallbackFilename: string
  ): Promise<string> {
    return this.downloadFile(`/events/${eventId}/results.pdf`, fallbackFilename);
  }

  /**
   * The whole programme's results as a PDF — every event that has results.
   * `sex` and `category` narrow it to one division or one half of the
   * programme. Like the single event, an empty programme is refused with a 409.
   */
  async downloadAllResultsPdf(
    fallbackFilename: string,
    filters: { sex?: EventSex; category?: EventCategory } = {}
  ): Promise<string> {
    return this.downloadFile(`/results.pdf${buildQuery({ ...filters })}`, fallbackFilename);
  }

  /* ---------------- Mark entry grid ---------------- */

  /**
   * The athletes entered in an event, with their heat and any mark recorded.
   * `stage` picks the heats or the drawn final, `groupId` and `grade` narrow
   * the rows; the filter options in the response always describe the sheet.
   */
  async getMarkSheet(
    eventId: number,
    filters: { stage?: MarkStage; groupId?: number | null; grade?: string | null } = {}
  ): Promise<MarkSheetDTO> {
    return this.request(
      `/events/${eventId}/marks${buildQuery({
        stage: filters.stage ?? 'HEAT',
        groupId: filters.groupId || null,
        grade: filters.grade,
      })}`
    );
  }

  /**
   * Saves the whole grid at once. Rows carrying a mark — or, in the field, a set
   * of attempts — are stored, rows flagged `clear` have the whole result removed
   * (attempts included), and rows with no mark are left untouched. `stage`
   * decides whether the rows land on the heats or on the final.
   */
  async saveMarks(
    eventId: number,
    rows: MarkEntryInput[],
    stage: MarkStage = 'HEAT'
  ): Promise<BulkMarkResultDTO> {
    return this.request(`/events/${eventId}/marks`, {
      method: 'POST',
      body: JSON.stringify({ stage, rows }),
    });
  }

  /* ---------------- Final (short sprints) ---------------- */

  /*
   * All three of these answer with a 409 whose message is worth showing as it
   * stands when the event is set to run direct to a final, and again when its
   * type cannot have a final at all (`mayHaveFinal` is false). A direct-to-final
   * event still has heats, but never a final.
   */

  /**
   * Who would qualify for the final, ranked by heat mark. Changes nothing, so
   * it is safe to call on every page load — and its `drawn` flag says whether
   * a final already exists.
   */
  async previewFinal(eventId: number, limit = 8): Promise<FinalSummaryDTO> {
    return this.request(`/events/${eventId}/final${buildQuery({ limit })}`);
  }

  /** Draws — or with a confirm, re-draws — the final. Re-drawing clears its marks. */
  async drawFinal(eventId: number, limit = 8): Promise<FinalSummaryDTO> {
    return this.request(`/events/${eventId}/final${buildQuery({ limit })}`, {
      method: 'POST',
    });
  }

  /** Removes the final. Marks recorded in the final are discarded. */
  async removeFinal(eventId: number): Promise<FinalRemovalDTO> {
    return this.request(`/events/${eventId}/final`, { method: 'DELETE' });
  }

  /* ---------------- Users ---------------- */

  async getCurrentUser(): Promise<UserDTO> {
    return this.request('/users/me');
  }

  async updateCurrentUser(data: Partial<UserDTO>): Promise<UserDTO> {
    return this.request('/users/me', {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  }

  async getAllUsers(): Promise<UserDTO[]> {
    return this.request('/users');
  }

  async setUserEnabled(id: number, enabled: boolean): Promise<void> {
    return this.request(`/users/${id}/enable?enabled=${enabled}`, { method: 'PATCH' });
  }

  async deleteUser(id: number): Promise<void> {
    return this.request(`/users/${id}`, { method: 'DELETE' });
  }

  /**
   * Creates a staff account. `role` is restricted to the assignable roles
   * (`ADMIN`, `MANAGER`, `USER`) and defaults to `MANAGER`; anything else is
   * refused by the server with a 400 and a message worth showing. Student
   * accounts come from the register import, never from here.
   */
  async createUser(
    data: { username: string; password: string; email: string; fullName?: string },
    role?: string
  ): Promise<UserDTO> {
    return this.request(`/admin/users${buildQuery({ role })}`, {
      method: 'POST',
      body: JSON.stringify({ fullName: '', ...data }),
    });
  }

  /** The roles an admin may hand out on this build. */
  async getAssignableRoles(): Promise<string[]> {
    return this.request('/admin/users/roles');
  }

  /* ---------------- Settings ---------------- */

  /** Scoring and entry-limit settings. Readable by any signed-in user. */
  async getSettings(): Promise<SettingsDTO> {
    return this.request('/settings');
  }

  /** Admin: writes any subset; omitted fields keep their stored value. */
  async updateSettings(patch: SettingsUpdate): Promise<SettingsDTO> {
    return this.request('/admin/settings', {
      method: 'PUT',
      body: JSON.stringify(patch),
    });
  }

  /** Admin: restores the documented defaults. */
  async resetSettings(): Promise<SettingsResetResultDTO> {
    return this.request('/admin/settings/reset', { method: 'POST' });
  }

  /* ---------------- School records ---------------- */

  /** One school record per event type + division + grade. */
  async getRecords(): Promise<RecordDTO[]> {
    return this.request('/records');
  }

  /**
   * Admin: sets the mark an administrator typed in for a record. It stays until
   * a recorded result beats it, and survives every rebuild. `mark` is required;
   * `holderName` is free text because the holder may have left the school.
   */
  async setRecordBaseline(recordId: number, payload: RecordBaselineInput): Promise<RecordDTO> {
    return this.request(`/admin/records/${recordId}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  }

  /** Admin: drops the typed-in mark, leaving the record to the results. */
  async clearRecordBaseline(recordId: number): Promise<RecordDTO> {
    return this.request(`/admin/records/${recordId}/baseline`, { method: 'DELETE' });
  }

  /** Admin: creates any missing record rows, leaving the existing ones alone. */
  async seedRecords(): Promise<SeedRecordsResultDTO> {
    return this.request('/admin/records/seed', { method: 'POST' });
  }

  /** Admin: rebuilds every record from the recorded marks, keeping the baselines. */
  async recomputeRecords(): Promise<RecomputeRecordsResultDTO> {
    return this.request('/admin/records/recompute', { method: 'POST' });
  }

  /* ---------------- Championships ---------------- */

  /** Personal, house and per-event championship standings. */
  async getChampionships(): Promise<ChampionshipsDTO> {
    return this.request('/championships');
  }
}

export const api = new ApiClient();
