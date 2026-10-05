'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  api,
  asFinalState,
  BulkMarkResultDTO,
  EventCategory,
  EventDTO,
  EventSex,
  FinalState,
  finalStateForEvent,
  formatAttempts,
  Grade,
  MarkEntryInput,
  MarkOutcome,
  MarkRowDTO,
  MarkSheetDTO,
  MarkStage,
  SexCode,
  shouldOfferFinal,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { EventFilters } from '@/components/EventFilters';
import type { EventFilterControl } from '@/components/EventFilters';
import { useI18n } from '@/lib/i18n';
import { classText, formText, houseText } from '@/lib/students';

/** The two sheets a short sprint has: the numbered heats and the final. */
const STAGE_OPTIONS: MarkStage[] = ['HEAT', 'FINAL'];

/** Short division code the filter control works in, from the event's own sex. */
const SEX_CODE: Record<EventSex, SexCode> = { MALE: 'M', FEMALE: 'F' };

/**
 * What the number box can be replaced by: `''` leaves the row a number — which
 * is what an omitted outcome means to the server — and the other two record an
 * athlete who did not compete or was disqualified.
 */
type DraftOutcome = '' | MarkOutcome;

/**
 * The seconds part of a stopped time has to sit under a whole minute: 2 minutes
 * 15 seconds is 2 and 15, never 1 and 75. The server refuses the row outright,
 * so it is caught here first and only ever confirmed by the server's own words.
 */
const TIME_SECONDS_MAX = 60;

/** What the user has typed for one athlete.
 *
 *  Marks stay strings while editing so partial input such as `8.` or `12.` is
 *  never clobbered. A field row is typed into `attempts` (a miss is left blank);
 *  a track row is typed into the single `mark`, and a race over 400M into the
 *  `minutes` and `seconds` boxes that stand in for it. */
interface MarkDraft {
  mark: string;
  /** Whole minutes of a race over 400M, as typed. */
  minutes: string;
  /** The seconds left over after those minutes, as typed. */
  seconds: string;
  attempts: string[];
  notes: string;
  clear: boolean;
  /**
   * What the row is recorded as: `''` for a number, or `ABS` / `DQ`, which
   * stands in place of a mark and leaves every number box empty.
   */
  outcome: DraftOutcome;
}

function errorText(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback;
}

/**
 * A lone comma is a decimal point in much of the world; accept it, but leave
 * anything that already carries a point (or several commas) alone.
 */
function normaliseDecimal(raw: string): string {
  const trimmed = raw.trim();
  return trimmed.includes('.') || (trimmed.match(/,/g) ?? []).length !== 1
    ? trimmed
    : trimmed.replace(',', '.');
}

/**
 * One number the way the user would retype it: `7.500` reads back as `7.5`, an
 * absent field as empty. Comparison only — `formatServer` holds what is shown.
 */
function normaliseNumber(value: number | null | undefined): string {
  return value === null || value === undefined ? '' : String(Number(value));
}

/** The single mark a track row arrived with, in the same shape as a draft. */
function serverMark(row: MarkRowDTO): string {
  return row.mark === null || row.mark === undefined ? '' : String(row.mark);
}

/**
 * One of the two boxes a race over 400M is timed in — the whole minutes and the
 * seconds left over — seeded from the row. Both are blank when no mark is held.
 */
function serverMinutes(row: MarkRowDTO): string {
  return normaliseNumber(row.minutes);
}

function serverSeconds(row: MarkRowDTO): string {
  return normaliseNumber(row.seconds);
}

/**
 * One attempt of a field row, in the same shape as a draft cell. A missed
 * attempt reads as an empty box, whatever shape it arrived in.
 */
function serverAttempt(row: MarkRowDTO, index: number): string {
  const value = row.attempts?.[index];
  return value === null || value === undefined ? '' : String(value);
}

/** True when the server holds anything at all for this row. */
function hasServerValue(row: MarkRowDTO): boolean {
  if (serverMark(row) !== '') return true;
  // ABS / DQ is a record of its own: there is no number behind it, but the
  // athlete has been marked and the row must not read as untouched.
  if (row.outcome === 'ABS' || row.outcome === 'DQ') return true;
  return (row.attempts ?? []).some(value => value !== null && value !== undefined);
}

/** The outcome a row arrived with, in the same shape as a draft. */
function serverOutcome(row: MarkRowDTO): DraftOutcome {
  return row.outcome === 'ABS' || row.outcome === 'DQ' ? row.outcome : '';
}

/**
 * True when a grid line stands for a relay **team** rather than an athlete.
 *
 * The row's own two fields are the whole test, and they are the server's own
 * statement about the line: a team line carries the team's `teamId` and
 * `teamLabel`, an athlete's line carries neither. The backend draws its printed
 * relay sheet on exactly that rule — the team labels are either on the lines or
 * they are not — so the grid and the sheet agree line for line.
 *
 * The event's *type* is deliberately not the test. A relay that nobody has
 * divided is still a relay, but its grid is the athlete-per-line grid it has
 * always been and no team is invented for it: going by the type alone would
 * blank the identity of every such row and leave the grid naming nobody. An
 * athlete line therefore cannot be mistaken for a team line (it never carries
 * either field), and a team line cannot fall through to the athlete rendering,
 * because the identity cells ask this first.
 */
function isTeamRow(row: MarkRowDTO): boolean {
  return (
    (row.teamId !== undefined && row.teamId !== null) ||
    (row.teamLabel !== undefined && row.teamLabel !== '')
  );
}

/**
 * What a line is called in the messages and accessible names that have to name
 * it: the team's name on a relay line, the athlete's student id on every other.
 *
 * A team line is never identified by the runner its one mark happens to hang
 * off — that runner is an implementation detail of `event_results.user_id`, not
 * the line's identity, and the grid must not name them.
 */
function rowWho(row: MarkRowDTO): string {
  if (!isTeamRow(row)) return row.studentRef;
  return row.teamLabel && row.teamLabel.trim() !== '' ? row.teamLabel : `#${row.userId}`;
}

function draftFrom(row: MarkRowDTO, attemptCount: number): MarkDraft {
  const attempts = Array.from({ length: attemptCount }, (_, index) => serverAttempt(row, index));
  // A field mark recorded before the attempts existed carries no attempt list.
  // It belongs in the first box, which is where it was measured, so the row
  // shows the mark it already has rather than three blank boxes.
  if (attempts.length > 1 && !row.attempts?.length) attempts[0] = serverMark(row);
  return {
    mark: serverMark(row),
    minutes: serverMinutes(row),
    seconds: serverSeconds(row),
    attempts,
    notes: row.notes ?? '',
    clear: false,
    outcome: serverOutcome(row),
  };
}

/**
 * A lone comma is a decimal point in much of the world; accept it, and read an
 * empty box as `null` — the whole minutes of a stopped time are a whole number.
 */
function draftMinutes(draft: MarkDraft): number | null {
  const raw = draft.minutes.trim();
  if (raw === '') return null;
  const value = Number(normaliseDecimal(raw));
  return Number.isFinite(value) ? value : null;
}

/** The seconds left over after the minutes, or `null` when the box is empty. */
function draftSeconds(draft: MarkDraft): number | null {
  const raw = draft.seconds.trim();
  if (raw === '') return null;
  const value = Number(normaliseDecimal(raw));
  return Number.isFinite(value) ? value : null;
}

/** True when the seconds box holds 60 or more, which is not a real part of a time. */
function secondsOutOfRange(draft: MarkDraft): boolean {
  const seconds = draftSeconds(draft);
  return seconds !== null && seconds >= TIME_SECONDS_MAX;
}

/**
 * A total in seconds the way a stopwatch reads it: `2:15` for 135. The `mark`
 * a result arrives with is always the total, so it is split here for display
 * only — `0:08` for a sprint is written `8.000` instead, which is what the
 * track has always printed.
 */
function formatStoppedTime(total: number): string {
  const minutes = Math.floor(total / 60);
  const seconds = total - minutes * 60;
  const parts = seconds.toFixed(3).split('.');
  return `${minutes}:${parts[0].padStart(2, '0')}.${parts[1]}`;
}

/** One result mark: a stopped time for a race over 400M, the raw mark otherwise. */
function resultMarkText(mark: number, minutesAndSeconds: boolean): string {
  return minutesAndSeconds ? formatStoppedTime(mark) : String(mark);
}

/** The best of the attempts currently on screen, as typed, or `''` for none. */
function bestOf(attempts: string[]): string {
  let best: number | null = null;
  attempts.forEach(raw => {
    const text = normaliseDecimal(raw);
    if (text === '') return;
    const value = Number(text);
    if (Number.isFinite(value) && value >= 0 && (best === null || value > best)) best = value;
  });
  return best === null ? '' : String(best);
}

type GroupedEvents = {
  key: string;
  /** The heading drawn over the group, e.g. `徑項 Track · 男 Boys`. */
  label: string;
  category: string;
  sex: string;
  events: EventDTO[];
}[];

/** Groups the event list by category + division so the selector stays readable. */
function groupEvents(events: EventDTO[], heading: (event: EventDTO) => string): GroupedEvents {
  const sorted = [...events].sort(
    (a, b) =>
      a.category.localeCompare(b.category) || a.sex.localeCompare(b.sex) || a.id - b.id
  );
  const grouped: GroupedEvents = [];
  sorted.forEach(event => {
    const key = `${event.category}-${event.sex}`;
    const last = grouped[grouped.length - 1];
    if (last && last.key === key) last.events.push(event);
    else
      grouped.push({
        key,
        label: heading(event),
        category: event.category,
        sex: event.sex,
        events: [event],
      });
  });
  return grouped;
}

export default function MarkEntryPage() {
  const { user, isLoading: authLoading } = useAuth();
  const { t, label } = useI18n();
  const router = useRouter();

  const [events, setEvents] = useState<EventDTO[]>([]);
  const [eventId, setEventId] = useState(0);
  /**
   * The grade the event list is narrowed to. An event belongs to exactly one
   * grade, so this is what tells `Boys 100M · A Grade` from the B grade in the
   * picker. It filters the list only — the sheet on screen is untouched.
   */
  const [eventGrade, setEventGrade] = useState<Grade | ''>('');
  /** The division the event list is narrowed to (`M` / `F`), list only. */
  const [eventSex, setEventSex] = useState<SexCode | ''>('');
  /** The half of the programme the event list is narrowed to, list only. */
  const [eventCategory, setEventCategory] = useState<EventCategory | ''>('');
  /** `HEAT` is the numbered heats, `FINAL` the drawn final. */
  const [stage, setStage] = useState<MarkStage>('HEAT');
  /** 0 is "All heats". Not used on the final sheet, which is a single group. */
  const [groupId, setGroupId] = useState(0);
  /** `''` is "All grades". */
  const [grade, setGrade] = useState('');
  /**
   * The state of each event's final, remembered from the sheet that was loaded
   * for it. The sheet is the precise source (`finalState`), and it is kept so
   * the picker still knows the answer after the user switches to another event.
   */
  const [finalStates, setFinalStates] = useState<Record<number, FinalState>>({});

  const [sheet, setSheet] = useState<MarkSheetDTO | null>(null);
  const [drafts, setDrafts] = useState<Record<number, MarkDraft>>({});
  const [result, setResult] = useState<BulkMarkResultDTO | null>(null);

  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  /** Ignores a stale response when filters change mid-flight. */
  const requestRef = useRef(0);
  /** Keeps `t` out of the fetch dependencies so switching language between
   *  loads never risks a re-fetch that could disturb half-typed marks. */
  const tRef = useRef(t);
  useEffect(() => {
    tRef.current = t;
  }, [t]);

  // Mark entry is the input helper's whole job, so they are staff here even though
  // they are nothing else anywhere else.
  const isStaff = user?.role === 'ADMIN' || user?.role === 'MANAGER'
    || user?.role === 'HELPER';

  useEffect(() => {
    if (authLoading) return;
    if (!user) router.push('/login');
    else if (!isStaff) router.push('/');
  }, [authLoading, user, isStaff, router]);

  useEffect(() => {
    if (!isStaff) return;
    let cancelled = false;
    // Every event, enabled or not: an event closed to new entries may still
    // have marks to take, and the four filters are the only narrowing here.
    api
      .getEvents()
      .then(list => {
        if (!cancelled) setEvents(list);
      })
      .catch(err => {
        if (!cancelled) setError(errorText(err, tRef.current('events.loadFailed')));
      });
    return () => {
      cancelled = true;
    };
  }, [isStaff]);

  // The grid is re-read from the server whenever the event, stage, heat or
  // grade changes — that is the filtering by event group, stage and grade.
  useEffect(() => {
    if (!eventId) {
      setSheet(null);
      setDrafts({});
      return;
    }
    const requestId = requestRef.current + 1;
    requestRef.current = requestId;
    setLoading(true);
    setError(null);
    api
      .getMarkSheet(eventId, { stage, groupId: groupId || null, grade: grade || null })
      .then(data => {
        if (requestRef.current !== requestId) return;
        setSheet(data);
        // The sheet's own verdict on this event's final, kept so the stage
        // picker stays right after the user moves to another event. The heats
        // and the final answer alike: the state is a property of the event.
        const state = asFinalState(data.finalState);
        if (state) setFinalStates(prev => ({ ...prev, [eventId]: state }));
        // Drafts are rebuilt from the server's copy: this only runs on an
        // explicit filter change or after a save.
        setDrafts({});
      })
      .catch(err => {
        if (requestRef.current !== requestId) return;
        setSheet(null);
        setError(errorText(err, tRef.current('marks.loadFailed')));
      })
      .finally(() => {
        if (requestRef.current === requestId) setLoading(false);
      });
  }, [eventId, stage, groupId, grade, reloadToken]);

  /**
   * A field event is measured over `attemptCount` attempts (three), a track
   * event in a single mark. `attemptIndexes` is only read on a field sheet: a
   * track sheet is drawn with the one record box instead.
   */
  const fieldEvent = !!sheet?.fieldEvent;
  /**
   * A race longer than 400M is timed on a stopwatch, so its one record box
   * becomes the two boxes `minutes` and `seconds`. False on every other sheet,
   * which keeps the single box exactly as it was.
   */
  const timeInMinutes = !fieldEvent && !!sheet?.timeInMinutes;
  /*
   * A race timed to a thousandth rather than a bare count of seconds: 60M, 100M and
   * 200M. Their mark box is masked `00.000`, so a helper types all three decimals
   * and a time cannot be entered to the wrong precision by accident.
   */
  const sprintEvent = ['RUN_60M', 'RUN_100M', 'RUN_200M'].includes(sheet?.eventType ?? '');
  /**
   * True when the sheet on screen marks TEAMS: a relay whose teams have been
   * derived. Every line is then a team's, so the column that carries a line's
   * name is headed `Team` rather than `Name`. It is read off the rows themselves
   * — the same test each row is rendered with, see `isTeamRow` — and not off the
   * event's type, so a relay nobody has divided keeps the athlete-per-line
   * headings and columns it has always had.
   */
  const teamSheet = useMemo(() => (sheet?.rows ?? []).some(isTeamRow), [sheet]);
  const attemptCount = fieldEvent ? sheet?.attemptCount ?? 3 : 1;
  const attemptIndexes = useMemo(
    () => Array.from({ length: attemptCount }, (_, index) => index),
    [attemptCount]
  );

  /**
   * The heading over the record column. A stopped time reads `M:S` — the two
   * boxes are one value — and everything else carries the unit of the event
   * itself, which is the unit its marks are saved in.
   */
  const unitHeading = timeInMinutes ? t('unit.M:S') : label('unit', sheet?.defaultUnit);

  const dirty = useMemo(() => {
    if (!sheet) return Object.keys(drafts).length > 0;
    return sheet.rows.some(row => {
      const draft = drafts[row.userId];
      if (!draft) return false;
      if (draft.clear) return true;
      if (draft.outcome !== serverOutcome(row)) return true;
      if (draft.notes !== (row.notes ?? '')) return true;
      if (fieldEvent) {
        return attemptIndexes.some(
          index => (draft.attempts[index] ?? '').trim() !== serverAttempt(row, index)
        );
      }
      if (timeInMinutes) {
        return (
          normaliseDecimal(draft.minutes.trim()) !== serverMinutes(row) ||
          normaliseDecimal(draft.seconds.trim()) !== serverSeconds(row)
        );
      }
      return draft.mark.trim() !== serverMark(row);
    });
  }, [sheet, drafts, fieldEvent, attemptIndexes, timeInMinutes]);

  const updateDraft = (row: MarkRowDTO, patch: Partial<MarkDraft>) => {
    setDrafts(prev => ({
      ...prev,
      [row.userId]: { ...(prev[row.userId] ?? draftFrom(row, attemptCount)), ...patch },
    }));
    setNotice(null);
  };

  /**
   * Types into one of the two boxes of a stopped time. The two are one value, so
   * a lone comma is accepted as a decimal point in either, and emptying both
   * leaves the row with nothing typed — which is what clears the mark. Typing a
   * number takes the row back off ABS / DQ: the time is the record again.
   */
  const updateTime = (row: MarkRowDTO, part: 'minutes' | 'seconds', value: string) => {
    setDrafts(prev => ({
      ...prev,
      [row.userId]: {
        ...(prev[row.userId] ?? draftFrom(row, attemptCount)),
        [part]: value,
        outcome: '',
      },
    }));
    setNotice(null);
  };

  /** Types into one of a field row's attempt boxes, leaving the others alone. */
  const updateAttempt = (row: MarkRowDTO, index: number, value: string) => {
    setDrafts(prev => {
      const draft = prev[row.userId] ?? draftFrom(row, attemptCount);
      const attempts = [...draft.attempts];
      attempts[index] = value;
      // A throw or a jump takes the row back off ABS / DQ.
      return { ...prev, [row.userId]: { ...draft, attempts, outcome: '' } };
    });
    setNotice(null);
  };

  /**
   * Records ABS or DQ in place of a mark, or puts the row back to a number.
   *
   * ABS and DQ are the whole record for that athlete: there is no number to
   * keep and none to validate, so the boxes are emptied here — including a field
   * athlete's three attempts, which is exactly what fouling all three is. The
   * Clear tick is dropped too, because "remove the record" and "record an
   * absence" are two different answers for the same row.
   */
  const updateOutcome = (row: MarkRowDTO, next: DraftOutcome) => {
    setDrafts(prev => {
      const draft = prev[row.userId] ?? draftFrom(row, attemptCount);
      if (next === '') {
        return { ...prev, [row.userId]: { ...draft, outcome: '' } };
      }
      return {
        ...prev,
        [row.userId]: {
          ...draft,
          outcome: next,
          mark: '',
          minutes: '',
          seconds: '',
          attempts: draft.attempts.map(() => ''),
          clear: false,
        },
      };
    });
    setNotice(null);
  };

  /** Guard a filter change so typed marks are never silently discarded. */
  const confirmDiscard = useCallback(
    () => !dirty || window.confirm(tRef.current('marks.unsaved')),
    [dirty]
  );

  /**
   * A change to one of the four event controls.
   *
   * The event picker reloads the grid, so it also drops the group, the row
   * grade and the stage back to their defaults — the new event's heats have
   * their own. The division, grade and category filters narrow the *list*
   * only and leave the sheet on screen alone.
   *
   * `control` is the `<select>` the change came from, and is put back by hand
   * when the user cancels the unsaved-changes warning, because the displayed
   * value is driven by the state this handler declined to change.
   */
  const handleEventFilterChange = (
    control: EventFilterControl,
    value: string,
    select: HTMLSelectElement
  ) => {
    if (!confirmDiscard()) {
      select.value =
        control === 'sex'
          ? eventSex
          : control === 'grade'
            ? eventGrade
            : control === 'category'
              ? eventCategory
              : String(eventId);
      return;
    }
    if (control === 'event') {
      const next = Number(value);
      if (next === eventId) return;
      setEventId(next);
      setStage('HEAT');
      setGroupId(0);
      setGrade('');
      setDrafts({});
      setResult(null);
      setNotice(null);
      return;
    }
    if (control === 'sex') setEventSex(value as SexCode | '');
    else if (control === 'grade') setEventGrade(value as Grade | '');
    else setEventCategory(value as EventCategory | '');
  };

  /**
   * Heats and the final are different sheets with different groups, so the
   * heat filter cannot carry over. Like every other filter change this goes
   * through the unsaved-changes guard.
   */
  const handleStageChange = (event: ChangeEvent<HTMLSelectElement>) => {
    const next = event.target.value as MarkStage;
    if (next === stage) return;
    if (!confirmDiscard()) {
      event.target.value = stage;
      return;
    }
    setStage(next);
    setGroupId(0);
    setResult(null);
    setNotice(null);
  };

  const handleGroupChange = (event: ChangeEvent<HTMLSelectElement>) => {
    const next = Number(event.target.value);
    if (next === groupId) return;
    if (!confirmDiscard()) {
      event.target.value = String(groupId);
      return;
    }
    setGroupId(next);
    setNotice(null);
  };

  const handleGradeChange = (event: ChangeEvent<HTMLSelectElement>) => {
    const next = event.target.value;
    if (next === grade) return;
    if (!confirmDiscard()) {
      event.target.value = grade;
      return;
    }
    setGrade(next);
    setNotice(null);
  };

  /**
   * Only rows the user actually touched are sent back: `drafts` is populated
   * lazily by `updateDraft` / `updateAttempt` / `updateTime`, so an untouched
   * athlete is never part of the save.
   *
   * Three cases would otherwise lose data silently and are handled explicitly:
   *   - a value that is not a number (a helper writing "12.3s") is reported, not
   *     quietly dropped;
   *   - a remark with no record is reported, because a result row always needs a
   *     mark and the server would skip the row and discard the remark;
   *   - emptying a cell that held a mark means "remove it", which is what anyone
   *     erasing a wrong time expects. The Clear column does the same thing.
   *
   * A field event is typed into three attempt boxes instead of one, and the
   * same distinction holds across all three: three empty boxes for an athlete
   * with no mark are nothing to save, while clearing the attempts of an athlete
   * who had a mark clears the whole result, attempts included. The best attempt
   * is what the server stores in `mark`, so it is not sent separately.
   *
   * A race over 400M is typed into minutes and seconds instead of one box, and
   * is checked here before it is sent: the seconds part has to be under 60, or
   * the server would refuse the row. Both empty boxes clear the mark, exactly as
   * one empty box does on every other sheet.
   *
   * An athlete recorded as ABS or DQ is the one row with nothing to check: the
   * outcome is sent on its own, with no mark and no attempts, so it can never
   * reach the problems list. The unit is never sent either — the event decides
   * whether its marks are seconds or metres, and the server falls back to it.
   */
  const buildRows = (): { rows: MarkEntryInput[]; problems: string[] } => {
    const rows: MarkEntryInput[] = [];
    const problems: string[] = [];
    const byId = new Map<number, MarkRowDTO>();
    sheet?.rows.forEach(row => byId.set(row.userId, row));

    Object.entries(drafts).forEach(([key, draft]) => {
      const userId = Number(key);
      if (!Number.isFinite(userId)) return;

      const row = byId.get(userId);
      /*
       * A line is named by what it is: a team line by the team's name, an
       * athlete's by their student id and name. Naming a relay line by the
       * runner it hangs off would put that runner's id in front of a helper who
       * is marking teams, which is the one thing a relay grid must not do.
       */
      const who = row
        ? isTeamRow(row)
          ? rowWho(row)
          : [row.studentRef, row.name].filter(Boolean).join(' ')
        : `#${userId}`;

      /*
       * A relay line's mark is the TEAM's, so the row carries the team's id back
       * to the server and the one time is hung off the team rather than off the
       * runner the line is anchored to — otherwise the mark would be stored
       * against that runner and no longer found when the team's line is read
       * back. An athlete's line leaves the field out altogether, so its payload
       * is exactly what it has always been.
       */
      const entry: MarkEntryInput =
        row && isTeamRow(row) && row.teamId !== undefined
          ? { userId, teamId: row.teamId }
          : { userId };

      if (draft.clear) {
        rows.push({ ...entry, mark: null, clear: true });
        return;
      }

      const notes = draft.notes.trim();

      // ABS / DQ: the outcome is the whole record, so there is no number to
      // validate and none to send.
      if (draft.outcome !== '') {
        rows.push({ ...entry, outcome: draft.outcome, mark: null, notes: notes || null });
        return;
      }

      if (fieldEvent) {
        const typed: Array<number | null> = [];
        let invalid: string | null = null;

        attemptIndexes.forEach(index => {
          const raw = (draft.attempts[index] ?? '').trim();
          if (invalid !== null) return;
          if (raw === '') {
            typed.push(null);
            return;
          }
          const parsed = Number(normaliseDecimal(raw));
          if (!Number.isFinite(parsed) || parsed < 0) {
            invalid = raw;
            return;
          }
          typed.push(parsed);
        });

        if (invalid !== null) {
          problems.push(tRef.current('marks.invalidMark', { who, value: invalid }));
          return;
        }

        // The last attempt may simply be left off rather than padded with
        // misses, so a trailing blank is trimmed; a blank in the middle is a
        // miss and is sent as one.
        while (typed.length > 0 && typed[typed.length - 1] === null) typed.pop();

        if (typed.length === 0) {
          if (row && hasServerValue(row)) {
            rows.push({ ...entry, mark: null, clear: true });
          } else if (notes) {
            problems.push(tRef.current('marks.remarkNeedsRecord', { who }));
          }
          return;
        }

        rows.push({ ...entry, outcome: 'RESULT', attempts: typed, notes: notes || null });
        return;
      }

      // A race over 400M is timed on a stopwatch, so the row carries the whole
      // minutes and the seconds left over rather than one count of seconds. The
      // seconds part is checked here so the helper is told before anything is
      // sent; the server refuses the same row with the same words.
      if (timeInMinutes) {
        const minutes = draftMinutes(draft);
        const seconds = draftSeconds(draft);

        if (minutes === null && seconds === null) {
          if (row && hasServerValue(row)) {
            rows.push({ ...entry, mark: null, clear: true });
          } else if (notes) {
            problems.push(tRef.current('marks.remarkNeedsRecord', { who }));
          }
          return;
        }

        if (minutes !== null && minutes < 0) {
          problems.push(tRef.current('marks.invalidMark', { who, value: draft.minutes.trim() }));
          return;
        }

        if (seconds !== null && (seconds < 0 || seconds >= TIME_SECONDS_MAX)) {
          problems.push(tRef.current('marks.secondsLimit', { who }));
          return;
        }

        rows.push({
          ...entry,
          outcome: 'RESULT',
          minutes: minutes ?? 0,
          seconds: seconds ?? 0,
          notes: notes || null,
        });
        return;
      }

      // A lone comma is a decimal point in much of the world; accept it.
      const raw = draft.mark.trim();
      const typed = normaliseDecimal(raw);

      if (typed === '') {
        if (row && hasServerValue(row)) {
          rows.push({ ...entry, mark: null, clear: true });
        } else if (notes) {
          problems.push(tRef.current('marks.remarkNeedsRecord', { who }));
        }
        return;
      }

      const parsed = Number(typed);
      if (!Number.isFinite(parsed) || parsed < 0) {
        problems.push(tRef.current('marks.invalidMark', { who, value: raw }));
        return;
      }

      rows.push({ ...entry, outcome: 'RESULT', mark: parsed, notes: notes || null });
    });

    return { rows, problems };
  };

  const handleSave = async () => {
    if (!eventId) return;
    const { rows, problems } = buildRows();
    if (problems.length > 0) {
      setNotice(null);
      setError(`${t('marks.fixBeforeSaving')} ${problems.join('; ')}`);
      return;
    }
    if (rows.length === 0) {
      setNotice(t('marks.nothingToSave'));
      return;
    }
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const saved = await api.saveMarks(eventId, rows, stage);
      setResult(saved);
      setDrafts({});
      setNotice(
        t('marks.saveSummary', {
          saved: saved.saved,
          cleared: saved.cleared,
          skipped: saved.skipped,
          failed: saved.failed,
        })
      );
      setReloadToken(token => token + 1);
    } catch (err) {
      setError(errorText(err, t('marks.saveFailed')));
    } finally {
      setSaving(false);
    }
  };

  /**
   * The events the four filters let through: sex, grade and category each
   * narrow the list, and the event control then picks one of them. The event
   * already open stays on the list whatever the filters say, so the picker
   * never points away from the grid on screen.
   *
   * There is deliberately no date, school-year or "only enabled" filter here:
   * a marking grid is worked from the programme in front of the helper, and a
   * disabled event may still have marks to take.
   */
  const filteredEvents = useMemo(
    () =>
      events.filter(
        event =>
          (eventGrade === '' || event.grade === eventGrade || event.id === eventId) &&
          (eventSex === '' || SEX_CODE[event.sex] === eventSex || event.id === eventId) &&
          (eventCategory === '' || event.category === eventCategory || event.id === eventId)
      ),
    [events, eventGrade, eventSex, eventCategory, eventId]
  );

  /**
   * The events worth marking: at least two athletes entered. One athlete has
   * nobody to be placed against and nobody at all has no marks to take, so
   * neither belongs in the picker — it would only make the list longer.
   *
   * The chosen event is kept on the list even when its field has since fallen
   * below two, because the grid is already open on it and it still has whatever
   * was written there; it is simply not selectable any more.
   */
  const markableEvents = useMemo(
    () =>
      filteredEvents.filter(
        event => (event.enrolledCount ?? 0) > 1 || (eventId > 0 && event.id === eventId)
      ),
    [filteredEvents, eventId]
  );

  /** The markable events, grouped so the selector stays readable. */
  const groupedEvents = useMemo(
    () =>
      groupEvents(
        markableEvents,
        event => `${label('category', event.category)} · ${label('sex', event.sex)}`
      ),
    [markableEvents, label]
  );

  /** Events that have too few entered to be marked, i.e. the ones left out. */
  const thinEvents = useMemo(
    () => filteredEvents.filter(event => (event.enrolledCount ?? 0) <= 1),
    [filteredEvents]
  );

  /** The event whose sheet is open, so its grade can be shown beside the name. */
  const selectedEvent = useMemo(
    () => events.find(event => event.id === eventId) ?? null,
    [events, eventId]
  );

  /** Student ref / name for an error or result line, keyed by user id. */
  const athletes = useMemo(() => {
    const map = new Map<number, MarkRowDTO>();
    sheet?.rows.forEach(row => map.set(row.userId, row));
    return map;
  }, [sheet]);

  const isFinal = stage === 'FINAL';

  /**
   * This event's final, and whether it can be worked on at all.
   *
   * `finalState` is the server's own verdict and the precise one, so it is used
   * whenever a sheet has already been read. Failing that the event is asked
   * directly: its type says whether a final stage exists, and the sheet's own
   * `finalDrawn` (or the remembered state of a previous load) says whether the
   * draw has run. That answer is deliberately conservative — a sprint not known
   * to have run its final counts as `NOT_DRAWN`, so the final is withheld
   * rather than offered and then refused with a 409.
   */
  const finalState = useMemo(() => {
    // A sheet already loaded for this event is the precise answer.
    if (sheet && sheet.eventId === eventId) {
      const live = asFinalState(sheet.finalState);
      if (live) return live;
      return finalStateForEvent(selectedEvent, { finalDrawn: sheet.finalDrawn });
    }
    // Otherwise the state remembered from the last time a sheet was read for
    // it, which is what keeps the picker right after switching events.
    const remembered = finalStates[eventId];
    if (remembered) return remembered;
    return finalStateForEvent(selectedEvent, { finalDrawn: undefined });
  }, [selectedEvent, sheet, eventId, finalStates]);

  /**
   * Only a drawn final is workable, so `Final` is only *enabled* then. The
   * option itself is left out for an event that never has one, and shown greyed
   * while the draw is still to come. With no event chosen the state is unknown,
   * so both labels stay listed and the disabled control says nothing has been
   * picked yet; the option is what carries the gating.
   */
  const offerFinal = finalState === null || shouldOfferFinal(finalState);
  const finalReady = finalState === 'DRAWN';
  const stageOptions = offerFinal ? STAGE_OPTIONS : STAGE_OPTIONS.slice(0, 1);

  /**
   * The final is offered but not yet workable: heats are in play and the draw
   * has not run. The grid is replaced by a pointer to the draw, saying that the
   * heat results come first rather than leaving a disabled control unexplained.
   */
  const finalNotDrawn = stage === 'FINAL' && finalState === 'NOT_DRAWN';

  /** The state's own label, in the user's language, for the note beside the stage. */
  const finalStateText = useMemo(() => {
    if (!finalState) return '';
    const fromServer =
      sheet && sheet.eventId === eventId && asFinalState(sheet.finalState) === finalState
        ? sheet.finalStateLabel
        : null;
    return fromServer || t(`final.state.${finalState}` as 'final.state.DRAWN');
  }, [finalState, sheet, eventId, t]);

  const stageText = (value: MarkStage) =>
    value === 'HEAT' ? t('marks.stageHeat') : t('marks.stageFinal');

  /** What a recorded outcome reads as: `ABS` / `DQ`, and nothing for a mark. */
  const outcomeText = (value: MarkOutcome | null | undefined): string =>
    value === 'ABS' ? t('marks.outcomeAbs') : value === 'DQ' ? t('marks.outcomeDq') : '';

  /**
   * The ABS / DQ choice that sits beside an athlete's number box, on both the
   * track and the field sheet: the first option leaves the row a number, the
   * other two record the outcome with no mark at all.
   */
  const outcomeSelect = (row: MarkRowDTO, draft: MarkDraft) => (
    <select
      value={draft.outcome}
      aria-label={`${t('marks.outcome')} ${rowWho(row)}`}
      onChange={e => updateOutcome(row, e.target.value as DraftOutcome)}
    >
      <option value="">{t('marks.outcomeResult')}</option>
      <option value="ABS">{t('marks.outcomeAbs')}</option>
      <option value="DQ">{t('marks.outcomeDq')}</option>
    </select>
  );

  if (authLoading || !isStaff) {
    return <p className="muted">{t('common.loading')}</p>;
  }

  return (
    <div>
      <div className="flex justify-between items-center no-print">
        <h1 className="page-title">{t('marks.title')}</h1>
        <div className="flex gap-2">
          <Link href="/admin" className="btn btn-secondary">
            {t('common.backToAdmin')}
          </Link>
          <Link href="/admin/print" className="btn btn-secondary">
            {t('nav.print')}
          </Link>
          <button
            type="button"
            className="btn btn-secondary"
            disabled={!eventId || loading}
            onClick={() => setReloadToken(token => token + 1)}
          >
            {loading ? t('common.loading') : t('common.refresh')}
          </button>
        </div>
      </div>

      <p className="muted">{t('marks.subtitle')}</p>

      {error && <div className="alert alert-error">{error}</div>}
      {notice && <div className="alert alert-success">{notice}</div>}

      <div className="card">
        {/*
          Sex, grade, category and event, and nothing else: the event control
          picks the sheet, the other three narrow the list it offers. The
          division, grade and category changes go through the unsaved-changes
          guard because they can change which event is on screen.
        */}
        <div className="grid-toolbar">
          <EventFilters
            idPrefix="marks"
            value={{ sex: eventSex, grade: eventGrade, category: eventCategory, event: '' }}
            onChange={handleEventFilterChange}
            events={filteredEvents}
            selectable={{
              eventId,
              noneLabel: t('results.chooseEvent'),
              isDisabled: event => (event.enrolledCount ?? 0) <= 1 && event.id !== eventId,
              groups: groupedEvents,
              note:
                markableEvents.length === 0 ? (
                  <p className="muted">{t('marks.noMarkableEvents')}</p>
                ) : (
                  thinEvents.length > 0 && (
                    <p className="muted">
                      {t('marks.thinEventsHidden', { count: thinEvents.length })}
                    </p>
                  )
                ),
            }}
          />

          <div className="field">
            <label htmlFor="marks-stage">{t('marks.pickStage')}</label>
            <select
              id="marks-stage"
              value={stage}
              disabled={!eventId}
              onChange={handleStageChange}
            >
              {/* The final is offered only while it can actually be worked on.
                  An event that never has one is not offered it at all; one whose
                  final is still to be drawn shows it greyed with the reason
                  beside the control, so nothing is disabled without a why. */}
              {stageOptions.map(value => (
                <option key={value} value={value} disabled={value === 'FINAL' && !finalReady}>
                  {stageText(value)}
                  {value === 'FINAL' && !finalReady ? ` — ${t('marks.stageUnavailable')}` : ''}
                </option>
              ))}
            </select>
            {eventId > 0 && finalState && finalState !== 'DRAWN' && (
              <p className="muted">
                {finalStateText}
                {finalState === 'NOT_DRAWN'
                  ? ` — ${t('final.notDrawnHint')}`
                  : ` — ${t('final.noStageHint')}`}
              </p>
            )}
          </div>

          {/* The final is a single group, so the heat filter does not apply. */}
          {!isFinal && (
            <div className="field">
              <label htmlFor="marks-heat">{t('marks.pickGroup')}</label>
              <select
                id="marks-heat"
                value={groupId}
                disabled={!sheet}
                onChange={handleGroupChange}
              >
                <option value={0}>{t('marks.allGroups')}</option>
                {sheet?.groups.map(group => (
                  <option key={group.id} value={group.id}>
                    {t('marks.groupOption', { label: group.label, count: group.athleteCount })}
                  </option>
                ))}
              </select>
            </div>
          )}

          <div className="field">
            <label htmlFor="marks-grade">{t('marks.pickGrade')}</label>
            <select
              id="marks-grade"
              value={grade}
              disabled={!sheet}
              onChange={handleGradeChange}
            >
              <option value="">{t('marks.allGrades')}</option>
              {sheet?.grades.map(value => (
                <option key={value} value={value}>
                  {label('grade', value)}
                </option>
              ))}
            </select>
          </div>
        </div>

        <p className="muted" style={{ marginBottom: 0 }}>
          {isFinal ? t('marks.finalHint') : fieldEvent ? t('marks.fieldHint') : t('marks.filterHint')}
        </p>
        <p className="muted" style={{ marginBottom: 0 }}>
          {t('marks.outcomeHint')}
        </p>

        {sheet && (
          <div className="grid-meta mt-2">
            <span>
              <strong>{sheet.eventName}</strong>
            </span>
            {selectedEvent && (
              <span className="badge badge-info" title={label('grade', selectedEvent.grade)}>
                {label('grade.short', selectedEvent.grade)}
              </span>
            )}
            {isFinal && <span className="badge badge-info">{t('marks.stageFinal')}</span>}
            {sheet.category && <span>{label('category', sheet.category)}</span>}
            {sheet.sex && <span>{label('sex', sheet.sex)}</span>}
            <span>{t('marks.marked', { marked: sheet.markedCount, total: sheet.totalAthletes })}</span>
            {sheet.sheetSize && (
              <span>
                {t('events.sheetSize')}: {label('sheet', sheet.sheetSize)}
              </span>
            )}
            {typeof sheet.groupSize === 'number' && (
              <span>
                {t('events.groupSize')}: {sheet.groupSize}
              </span>
            )}
            {isFinal ? (
              <>
                {sheet.finalDrawn && (
                  <span>{t('marks.finalQualifiers', { count: sheet.rows.length })}</span>
                )}
                {sheet.sheetSize && (
                  <span>{t('marks.finalSheetHint', { sheet: label('sheet', sheet.sheetSize) })}</span>
                )}
              </>
            ) : (
              <span>
                {t('print.heats')}: {sheet.groups.length}
              </span>
            )}
            {dirty && <span className="badge badge-warning">{t('marks.unsaved')}</span>}
          </div>
        )}
      </div>

      {!eventId && (
        <div className="empty">
          <p>{t('marks.pickEventFirst')}</p>
        </div>
      )}

      {eventId > 0 && loading && <p className="muted">{t('marks.loadingSheet')}</p>}

      {finalNotDrawn && (
        <div className="empty">
          <p>{t('marks.finalNotDrawn')}</p>
          <p className="muted">{t('marks.finalNotDrawnHint')}</p>
          <Link href={`/admin/events/${eventId}/groups`} className="btn btn-primary mt-2">
            {t('marks.openGroups')}
          </Link>
        </div>
      )}

      {sheet && !loading && !finalNotDrawn && (
        <>
          {sheet.rows.length === 0 ? (
            <div className="empty">
              <p>{t('marks.noRows')}</p>
            </div>
          ) : (
            <div className="table-wrap">
              <table className="marks-table">
                <thead>
                  <tr>
                    {/* A relay line IS a team, so the column a line is named in
                        is headed `Team` there; an individual grid is untouched
                        and still heads it `Name`. The student id column stays
                        where it is with nothing in it on a team line, exactly
                        as the printed relay sheet leaves its student id blank. */}
                    <th>{t('marks.studentId')}</th>
                    <th>{teamSheet ? t('relay.team') : t('marks.name')}</th>
                    <th className="col-narrow">{t('marks.grade')}</th>
                    <th className="col-narrow">{t('marks.form')}</th>
                    <th className="col-narrow">{t('marks.class')}</th>
                    <th className="col-narrow">{t('marks.house')}</th>
                    <th className="col-narrow">{t('marks.heat')}</th>
                    <th className="col-narrow">{t('marks.lane')}</th>
                    {/* A field event is measured over three attempts; the best
                        of them is the mark, and is shown beside them. */}
                    {fieldEvent ? (
                      <>
                        {attemptIndexes.map(index => (
                          <th key={index} className="col-mark">
                            {t('marks.attempt', { n: index + 1 })}
                          </th>
                        ))}
                        <th className="col-mark">
                          {t('marks.best')} ({label('unit', sheet.defaultUnit)})
                        </th>
                      </>
                    ) : (
                      /* A stopped time reads `M:S` — its two boxes are one value. */
                      <th className="col-mark">
                        {t('marks.record')} ({unitHeading})
                      </th>
                    )}
                    <th className="col-remark">{t('marks.remark')}</th>
                    <th className="col-narrow">{t('marks.clearMark')}</th>
                  </tr>
                </thead>
                <tbody>
                  {sheet.rows.map(row => {
                    const draft = drafts[row.userId] ?? draftFrom(row, attemptCount);
                    /*
                     * A relay line IS the team: one time is written for the four
                     * runners together, so the line is named by the team and by
                     * nothing else. The runner the mark hangs off — the server's
                     * anchor for the result row — is not the line's identity, so
                     * the student id, grade, form, class and house cells are left
                     * empty. That is the same shape the printed relay sheet has:
                     * the team's name in the Name column, every other identity
                     * cell blank. An athlete's line is rendered exactly as before.
                     */
                    const teamRow = isTeamRow(row);
                    return (
                      <tr key={row.userId} className={draft.clear ? 'cell-cleared' : undefined}>
                        <td>{teamRow ? '' : row.studentRef}</td>
                        <td>
                          {teamRow ? row.teamLabel || '-' : row.name || '-'}
                          {/* On a final grid the official needs to see what this
                              athlete ran in the heats, right where they are writing
                              the final down. Absent altogether on a heat grid. */}
                          {row.heatDisplayMark && (
                            <span className="muted" style={{ display: 'block', fontSize: '0.8em' }}>
                              {t('marks.heatRecord')} {row.heatDisplayMark}
                            </span>
                          )}
                        </td>
                        <td title={teamRow ? undefined : label('grade', row.grade)}>
                          {teamRow ? '' : label('grade.short', row.grade)}
                        </td>
                        {/* Form, class and house are the three facts that identify
                            an athlete on the sheet; the house carries its short code
                            (R, Y, B, G) and no code at all when the register's house
                            has none. A team line is not a person, so all three are
                            blank there. */}
                        <td>{teamRow ? '' : formText(row, t)}</td>
                        <td>{teamRow ? '' : classText(row) || '-'}</td>
                        <td>{teamRow ? '' : houseText(row)}</td>
                        <td>{row.groupLabel ?? '-'}</td>
                        <td>{row.lane ?? '-'}</td>
                        {fieldEvent ? (
                          <>
                            {attemptIndexes.map(index => (
                              <td key={index}>
                                <input
                                  type="text"
                                  inputMode="decimal"
                                  value={draft.attempts[index] ?? ''}
                                  placeholder={t('results.markPlaceholder')}
                                  aria-label={`${t('marks.attempt', { n: index + 1 })} ${rowWho(row)}`}
                                  onChange={e => updateAttempt(row, index, e.target.value)}
                                />
                              </td>
                            ))}
                            <td className="col-mark">
                              <div className="marks-outcome">
                                <strong>
                                  {draft.outcome !== '' ? outcomeText(draft.outcome) : bestOf(draft.attempts) || '–'}
                                </strong>
                                {outcomeSelect(row, draft)}
                              </div>
                            </td>
                          </>
                        ) : timeInMinutes ? (
                          /* One stopped time in two boxes: minutes, then the
                             seconds left over. They sit inside the one cell and
                             Tab runs M → S → the next athlete's M. */
                          <td className="col-mark">
                            <div className="marks-outcome">
                              <div className="marks-time">
                                <input
                                  type="number"
                                  min={0}
                                  step={1}
                                  inputMode="numeric"
                                  className="marks-time-part"
                                  value={draft.minutes}
                                  placeholder={t('marks.minutesShort')}
                                  aria-label={`${t('marks.minutes')} — ${rowWho(row)}`}
                                  onChange={e => updateTime(row, 'minutes', e.target.value)}
                                />
                                <span className="marks-time-colon" aria-hidden="true">
                                  :
                                </span>
                                <input
                                  type="number"
                                  min={0}
                                  max={TIME_SECONDS_MAX - 1}
                                  step="any"
                                  inputMode="decimal"
                                  className="marks-time-part"
                                  value={draft.seconds}
                                  placeholder={t('marks.secondsShort')}
                                  aria-label={`${t('marks.seconds')} — ${rowWho(row)}`}
                                  onChange={e => updateTime(row, 'seconds', e.target.value)}
                                />
                              </div>
                              {outcomeSelect(row, draft)}
                            </div>
                            {secondsOutOfRange(draft) && (
                              <span className="marks-time-warning">
                                {t('marks.secondsLimit', { who: rowWho(row) })}
                              </span>
                            )}
                          </td>
                        ) : (
                          <td>
                            <div className="marks-outcome">
                              <input
                                type="text"
                                inputMode="decimal"
                                value={draft.mark}
                                /* A sprint is timed to a thousandth, so the box is
                                   masked 00.000 — both as a guide to the helper and
                                   as a nudge to type all three decimals. */
                                placeholder={sprintEvent ? '00.000' : t('results.markPlaceholder')}
                                aria-label={`${t('marks.record')} ${rowWho(row)}`}
                                /* A number typed in is a result again, which is
                                   what takes the row back off ABS / DQ. */
                                onChange={e => updateDraft(row, { mark: e.target.value, outcome: '' })}
                                onBlur={() => {
                                  if (!sprintEvent) return;
                                  const typed = draft.mark.trim();
                                  if (!/^\d+(\.\d{0,3})?$/.test(typed)) return;
                                  const [whole, fraction = ''] = typed.split('.');
                                  updateDraft(row, {
                                    mark: `${whole}.${fraction.padEnd(3, '0')}`,
                                  });
                                }}
                              />
                              {outcomeSelect(row, draft)}
                            </div>
                          </td>
                        )}
                        <td>
                          <input
                            type="text"
                            value={draft.notes}
                            placeholder={t('results.notesPlaceholder')}
                            aria-label={`${t('marks.remark')} ${rowWho(row)}`}
                            onChange={e => updateDraft(row, { notes: e.target.value })}
                          />
                        </td>
                        <td style={{ textAlign: 'center' }}>
                          <input
                            type="checkbox"
                            checked={draft.clear}
                            aria-label={`${t('marks.clearMark')} ${rowWho(row)}`}
                            onChange={e => updateDraft(row, { clear: e.target.checked })}
                          />
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}

          <div className="save-bar">
            <button
              type="button"
              className="btn btn-primary"
              disabled={saving || !dirty}
              onClick={handleSave}
            >
              {saving ? t('common.saving') : t('marks.saveAll')}
            </button>
            <button
              type="button"
              className="btn btn-secondary"
              disabled={saving || !dirty}
              onClick={() => {
                setDrafts({});
                setNotice(null);
              }}
            >
              {t('common.reset')}
            </button>
            {dirty && <span className="badge badge-warning">{t('marks.unsaved')}</span>}
            <span className="muted">{t('marks.blankSkipped')}</span>
          </div>
        </>
      )}

      {sheet && !loading && !finalNotDrawn && (
        <div className="leaderboard">
          <h2 className="section-title">{t('marks.leaderboard')}</h2>
          {!result || result.results.length === 0 ? (
            <p className="muted">{t('results.noResultsEvent')}</p>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{t('marks.rank')}</th>
                    <th>{t('marks.studentId')}</th>
                    {/* The same wording the grid above uses: on a relay sheet a
                        line is a team's, so the column it is named in is `Team`. */}
                    <th>{teamSheet ? t('relay.team') : t('marks.name')}</th>
                    <th>
                      {t('marks.record')} ({unitHeading})
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {result.results.map((entry, index) => {
                    // An athlete recorded as ABS or DQ has no place and no mark:
                    // the outcome is what the leaderboard shows in its place.
                    const outcome = outcomeText(entry.outcome);
                    /*
                     * The saved line is named the same way the grid names it: a
                     * relay team's time by the team, an athlete's by the athlete.
                     * A team's mark is stored against the runner the line is
                     * anchored to, so naming that runner here would put a
                     * student's id and name against a time the team ran.
                     */
                    const line = athletes.get(entry.userId);
                    const teamLine = !!line && isTeamRow(line);
                    return (
                      <tr key={entry.id}>
                        <td>{outcome ? '–' : index + 1}</td>
                        <td>{teamLine ? '' : line?.studentRef ?? entry.username}</td>
                        <td>{teamLine ? line?.teamLabel ?? entry.fullName : line?.name ?? entry.fullName}</td>
                        <td>
                          {outcome || resultMarkText(entry.mark, timeInMinutes)}
                          {/* The attempts behind a field mark, so the marker can
                              see how the best was arrived at. */}
                          {!outcome && formatAttempts(entry.attempts) && (
                            <div className="muted">{formatAttempts(entry.attempts)}</div>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {result && result.errors.length > 0 && (
        <div className="card mt-3">
          <h2 className="section-title">
            {t('marks.saveErrors')} <span className="badge badge-danger">{result.errors.length}</span>
          </h2>
          <ul>
            {result.errors.map((item, index) => {
              // Named as the grid names it, so a relay team's refused row is
              // reported by the team and not by the runner it is anchored to.
              const failed = athletes.get(item.userId ?? -1);
              return (
                <li key={`${item.userId ?? 'row'}-${index}`} className="muted">
                  {t('results.athlete')}{' '}
                  {failed ? rowWho(failed) : item.userId ?? '-'}: {item.message}
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}
