'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  api,
  BulkMarkResultDTO,
  EventDTO,
  formatAttempts,
  MarkEntryInput,
  MarkRowDTO,
  MarkSheetDTO,
  MarkStage,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';

/** The two units the backend records: `s` on the track, `M` in the field. */
const UNIT_OPTIONS = ['s', 'M'];

/** The two sheets a short sprint has: the numbered heats and the final. */
const STAGE_OPTIONS: MarkStage[] = ['HEAT', 'FINAL'];

/** What the user has typed for one athlete.
 *
 *  Marks stay strings while editing so partial input such as `8.` or `12.` is
 *  never clobbered. A field row is typed into `attempts` (a miss is left blank);
 *  a track row is typed into the single `mark`. */
interface MarkDraft {
  mark: string;
  attempts: string[];
  notes: string;
  clear: boolean;
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

/** The single mark a track row arrived with, in the same shape as a draft. */
function serverMark(row: MarkRowDTO): string {
  return row.mark === null || row.mark === undefined ? '' : String(row.mark);
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
  return (row.attempts ?? []).some(value => value !== null && value !== undefined);
}

function draftFrom(row: MarkRowDTO, attemptCount: number): MarkDraft {
  const attempts = Array.from({ length: attemptCount }, (_, index) => serverAttempt(row, index));
  // A field mark recorded before the attempts existed carries no attempt list.
  // It belongs in the first box, which is where it was measured, so the row
  // shows the mark it already has rather than three blank boxes.
  if (attempts.length > 1 && !row.attempts?.length) attempts[0] = serverMark(row);
  return {
    mark: serverMark(row),
    attempts,
    notes: row.notes ?? '',
    clear: false,
  };
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

type GroupedEvents = { key: string; category: string; sex: string; events: EventDTO[] }[];

/** Groups the event list by category + division so the selector stays readable. */
function groupEvents(events: EventDTO[]): GroupedEvents {
  const sorted = [...events].sort(
    (a, b) =>
      a.category.localeCompare(b.category) || a.sex.localeCompare(b.sex) || a.id - b.id
  );
  const grouped: GroupedEvents = [];
  sorted.forEach(event => {
    const key = `${event.category}-${event.sex}`;
    const last = grouped[grouped.length - 1];
    if (last && last.key === key) last.events.push(event);
    else grouped.push({ key, category: event.category, sex: event.sex, events: [event] });
  });
  return grouped;
}

export default function MarkEntryPage() {
  const { user, isLoading: authLoading } = useAuth();
  const { t, label } = useI18n();
  const router = useRouter();

  const [events, setEvents] = useState<EventDTO[]>([]);
  const [eventId, setEventId] = useState(0);
  /** `HEAT` is the numbered heats, `FINAL` the drawn final. */
  const [stage, setStage] = useState<MarkStage>('HEAT');
  /** 0 is "All heats". Not used on the final sheet, which is a single group. */
  const [groupId, setGroupId] = useState(0);
  /** `''` is "All grades". */
  const [grade, setGrade] = useState('');
  /**
   * The unit the marks are saved with. It is left empty until the sheet arrives,
   * so that the event's own `defaultUnit` — `M` or `s` — always wins on a fresh
   * event, while a reload after a save keeps whatever the user chose.
   */
  const [unit, setUnit] = useState('');

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

  const isStaff = user?.role === 'ADMIN' || user?.role === 'MANAGER';

  useEffect(() => {
    if (authLoading) return;
    if (!user) router.push('/login');
    else if (!isStaff) router.push('/');
  }, [authLoading, user, isStaff, router]);

  useEffect(() => {
    if (!isStaff) return;
    let cancelled = false;
    api
      .getEvents({ onlyEnabled: true })
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
        // Drafts are rebuilt from the server's copy: this only runs on an
        // explicit filter change or after a save.
        setDrafts({});
        setUnit(prev => prev || data.defaultUnit || 's');
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
  const attemptCount = fieldEvent ? sheet?.attemptCount ?? 3 : 1;
  const attemptIndexes = useMemo(
    () => Array.from({ length: attemptCount }, (_, index) => index),
    [attemptCount]
  );

  const dirty = useMemo(() => {
    if (!sheet) return Object.keys(drafts).length > 0;
    return sheet.rows.some(row => {
      const draft = drafts[row.userId];
      if (!draft) return false;
      if (draft.clear) return true;
      if (draft.notes !== (row.notes ?? '')) return true;
      if (fieldEvent) {
        return attemptIndexes.some(
          index => (draft.attempts[index] ?? '').trim() !== serverAttempt(row, index)
        );
      }
      return draft.mark.trim() !== serverMark(row);
    });
  }, [sheet, drafts, fieldEvent, attemptIndexes]);

  const updateDraft = (row: MarkRowDTO, patch: Partial<MarkDraft>) => {
    setDrafts(prev => ({
      ...prev,
      [row.userId]: { ...(prev[row.userId] ?? draftFrom(row, attemptCount)), ...patch },
    }));
    setNotice(null);
  };

  /** Types into one of a field row's attempt boxes, leaving the others alone. */
  const updateAttempt = (row: MarkRowDTO, index: number, value: string) => {
    setDrafts(prev => {
      const draft = prev[row.userId] ?? draftFrom(row, attemptCount);
      const attempts = [...draft.attempts];
      attempts[index] = value;
      return { ...prev, [row.userId]: { ...draft, attempts } };
    });
    setNotice(null);
  };

  /** Guard a filter change so typed marks are never silently discarded. */
  const confirmDiscard = useCallback(
    () => !dirty || window.confirm(tRef.current('marks.unsaved')),
    [dirty]
  );

  /**
   * Changing a filter re-reads the grid from the server, so the controlled
   * selector is put back by hand when the user cancels the warning.
   */
  const handleEventChange = (event: ChangeEvent<HTMLSelectElement>) => {
    const next = Number(event.target.value);
    if (next === eventId) return;
    if (!confirmDiscard()) {
      event.target.value = String(eventId);
      return;
    }
    setEventId(next);
    setStage('HEAT');
    setGroupId(0);
    setGrade('');
    setUnit('');
    setDrafts({});
    setResult(null);
    setNotice(null);
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
   * lazily by `updateDraft` / `updateAttempt`, so an untouched athlete is never
   * part of the save.
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
      const who = row ? [row.studentRef, row.name].filter(Boolean).join(' ') : `#${userId}`;

      if (draft.clear) {
        rows.push({ userId, mark: null, clear: true });
        return;
      }

      const notes = draft.notes.trim();

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
            rows.push({ userId, mark: null, clear: true });
          } else if (notes) {
            problems.push(tRef.current('marks.remarkNeedsRecord', { who }));
          }
          return;
        }

        rows.push({ userId, attempts: typed, unit: unit || null, notes: notes || null });
        return;
      }

      // A lone comma is a decimal point in much of the world; accept it.
      const raw = draft.mark.trim();
      const typed = normaliseDecimal(raw);

      if (typed === '') {
        if (row && serverMark(row) !== '') {
          rows.push({ userId, mark: null, clear: true });
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

      rows.push({ userId, mark: parsed, unit: unit || null, notes: notes || null });
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

  const groupedEvents = useMemo(() => groupEvents(events), [events]);

  const unitOptions = useMemo(() => {
    const fallback = sheet?.defaultUnit;
    return fallback && !UNIT_OPTIONS.includes(fallback) ? [...UNIT_OPTIONS, fallback] : UNIT_OPTIONS;
  }, [sheet?.defaultUnit]);

  /** Student ref / name for an error or result line, keyed by user id. */
  const athletes = useMemo(() => {
    const map = new Map<number, MarkRowDTO>();
    sheet?.rows.forEach(row => map.set(row.userId, row));
    return map;
  }, [sheet]);

  const isFinal = stage === 'FINAL';

  /**
   * The final sheet exists but has not been drawn: there are no athletes to
   * enter, so the grid is replaced by a pointer to the groups page.
   */
  const finalNotDrawn = isFinal && !!sheet && !sheet.finalDrawn;

  const stageText = (value: MarkStage) =>
    value === 'HEAT' ? t('marks.stageHeat') : t('marks.stageFinal');

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
        <div className="grid-toolbar">
          <div className="field">
            <label htmlFor="marks-event">{t('marks.pickEvent')}</label>
            <select
              id="marks-event"
              value={eventId}
              onChange={handleEventChange}
            >
              <option value={0}>{t('results.chooseEvent')}</option>
              {groupedEvents.map(group => (
                <optgroup
                  key={group.key}
                  label={`${label('category', group.category)} · ${label('sex', group.sex)}`}
                >
                  {group.events.map(event => (
                    <option key={event.id} value={event.id}>
                      {event.name}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
          </div>

          <div className="field">
            <label htmlFor="marks-stage">{t('marks.pickStage')}</label>
            <select
              id="marks-stage"
              value={stage}
              disabled={!eventId}
              onChange={handleStageChange}
            >
              {STAGE_OPTIONS.map(value => (
                <option key={value} value={value}>
                  {stageText(value)}
                </option>
              ))}
            </select>
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

          <div className="field">
            <label htmlFor="marks-unit">{t('marks.unit')}</label>
            <select
              id="marks-unit"
              value={unit}
              disabled={!sheet}
              onChange={e => setUnit(e.target.value)}
            >
              {unitOptions.map(value => (
                <option key={value} value={value}>
                  {label('unit', value)}
                </option>
              ))}
            </select>
          </div>
        </div>

        <p className="muted" style={{ marginBottom: 0 }}>
          {isFinal ? t('marks.finalHint') : fieldEvent ? t('marks.fieldHint') : t('marks.filterHint')}
        </p>

        {sheet && (
          <div className="grid-meta mt-2">
            <span>
              <strong>{sheet.eventName}</strong>
            </span>
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
                    <th>{t('marks.studentId')}</th>
                    <th>{t('marks.name')}</th>
                    <th className="col-narrow">{t('marks.grade')}</th>
                    <th className="col-narrow">{t('marks.class')}</th>
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
                          {t('marks.best')} ({label('unit', unit)})
                        </th>
                      </>
                    ) : (
                      <th className="col-mark">
                        {t('marks.record')} ({label('unit', unit)})
                      </th>
                    )}
                    <th className="col-remark">{t('marks.remark')}</th>
                    <th className="col-narrow">{t('marks.clearMark')}</th>
                  </tr>
                </thead>
                <tbody>
                  {sheet.rows.map(row => {
                    const draft = drafts[row.userId] ?? draftFrom(row, attemptCount);
                    return (
                      <tr key={row.userId} className={draft.clear ? 'cell-cleared' : undefined}>
                        <td>{row.studentRef}</td>
                        <td>{row.name || '-'}</td>
                        <td title={label('grade', row.grade)}>
                          {label('grade.short', row.grade)}
                        </td>
                        <td>
                          {[row.className, row.classNumber].filter(Boolean).join(' ') || '-'}
                        </td>
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
                                  aria-label={`${t('marks.attempt', { n: index + 1 })} ${row.studentRef}`}
                                  onChange={e => updateAttempt(row, index, e.target.value)}
                                />
                              </td>
                            ))}
                            <td className="col-mark">
                              <strong>{bestOf(draft.attempts) || '–'}</strong>
                            </td>
                          </>
                        ) : (
                          <td>
                            <input
                              type="text"
                              inputMode="decimal"
                              value={draft.mark}
                              placeholder={t('results.markPlaceholder')}
                              aria-label={`${t('marks.record')} ${row.studentRef}`}
                              onChange={e => updateDraft(row, { mark: e.target.value })}
                            />
                          </td>
                        )}
                        <td>
                          <input
                            type="text"
                            value={draft.notes}
                            placeholder={t('results.notesPlaceholder')}
                            aria-label={`${t('marks.remark')} ${row.studentRef}`}
                            onChange={e => updateDraft(row, { notes: e.target.value })}
                          />
                        </td>
                        <td style={{ textAlign: 'center' }}>
                          <input
                            type="checkbox"
                            checked={draft.clear}
                            aria-label={`${t('marks.clearMark')} ${row.studentRef}`}
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
                    <th>{t('marks.name')}</th>
                    <th>
                      {t('marks.record')} ({label('unit', unit)})
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {result.results.map((entry, index) => (
                    <tr key={entry.id}>
                      <td>{index + 1}</td>
                      <td>{athletes.get(entry.userId)?.studentRef ?? entry.username}</td>
                      <td>{athletes.get(entry.userId)?.name ?? entry.fullName}</td>
                      <td>
                        {entry.mark}
                        {/* The attempts behind a field mark, so the marker can
                            see how the best was arrived at. */}
                        {formatAttempts(entry.attempts) && (
                          <div className="muted">{formatAttempts(entry.attempts)}</div>
                        )}
                      </td>
                    </tr>
                  ))}
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
            {result.errors.map((item, index) => (
              <li key={`${item.userId ?? 'row'}-${index}`} className="muted">
                {t('results.athlete')}{' '}
                {athletes.get(item.userId ?? -1)?.studentRef ?? item.userId ?? '-'}: {item.message}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
