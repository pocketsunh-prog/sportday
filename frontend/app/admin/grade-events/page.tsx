'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  api,
  EventCategory,
  GradeEligibilityDTO,
  GradeEligibilityRowDTO,
  GradeEligibilityUpdate,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';

const SECTION_ORDER: EventCategory[] = ['TRACK', 'FIELD'];

/** The grades the grid falls back to when the response does not spell them out. */
const FALLBACK_GRADES = ['A', 'B', 'C'];

/**
 * One cell of the grid is tracked under `eventType|grade`, so only the cells an
 * administrator actually changed are ever sent back to the server.
 */
function cellKey(eventType: string, grade: string): string {
  return `${eventType}|${grade}`;
}

/**
 * Which grades may enter which events — one row per event with A, B and C
 * across it, ticked where that grade may enter.
 *
 * Only changed cells are sent on save and the page re-renders from the reply, so
 * the counts on screen are always the server's. Unticking a box affects who may
 * enter from then on; it never removes an entry already made.
 */
export default function AdminGradeEventsPage() {
  const { user, isLoading } = useAuth();
  const { t, label } = useI18n();
  const router = useRouter();

  const [matrix, setMatrix] = useState<GradeEligibilityDTO | null>(null);
  /** Only the cells the administrator changed: `eventType|grade` → the new tick. */
  const [pending, setPending] = useState<Record<string, boolean>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const dirty = Object.keys(pending).length > 0;

  /**
   * `t` is read through a ref by the load effect so that switching language does
   * not re-read the grid and throw away the administrator's unsaved ticks.
   */
  const tRef = useRef(t);
  useEffect(() => {
    tRef.current = t;
  }, [t]);

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    if (user.role !== 'ADMIN') {
      router.push('/');
      return;
    }
    api
      .getGradeEvents()
      .then(data => {
        setMatrix(data);
        setPending({});
      })
      .catch((err: unknown) =>
        setError(err instanceof Error ? err.message : tRef.current('gradeEvents.loadFailed'))
      )
      .finally(() => setLoading(false));
  }, [user, isLoading, router]);

  // Warn on a browser-level exit while ticks are unsaved, in the same spirit as
  // the mark-entry page's unsaved guard.
  useEffect(() => {
    if (!dirty) return;
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [dirty]);

  /** Guard an in-page move so unsaved ticks are never silently discarded. */
  const confirmDiscard = useCallback(
    () => !dirty || window.confirm(tRef.current('gradeEvents.unsavedConfirm')),
    [dirty]
  );

  const grades = useMemo(
    () => (matrix && matrix.grades.length > 0 ? matrix.grades : FALLBACK_GRADES),
    [matrix]
  );

  const byCategory = useMemo(() => {
    const grouped: Record<EventCategory, GradeEligibilityRowDTO[]> = { TRACK: [], FIELD: [] };
    (matrix?.events ?? []).forEach(row => {
      if (grouped[row.category]) grouped[row.category].push(row);
      else grouped.TRACK.push(row);
    });
    SECTION_ORDER.forEach(category => {
      grouped[category].sort(
        (a, b) =>
          a.eventTypeLabel.localeCompare(b.eventTypeLabel) || a.eventType.localeCompare(b.eventType)
      );
    });
    return grouped;
  }, [matrix]);

  /** The tick a cell shows: the administrator's change, or the server's own value. */
  const cellValue = (row: GradeEligibilityRowDTO, grade: string): boolean =>
    pending[cellKey(row.eventType, grade)] ?? row.allowed[grade] === true;

  const isChanged = (row: GradeEligibilityRowDTO, grade: string): boolean =>
    cellKey(row.eventType, grade) in pending;

  const toggleCell = (row: GradeEligibilityRowDTO, grade: string) => {
    setMessage('');
    setError('');
    setPending(prev => {
      const key = cellKey(row.eventType, grade);
      const server = row.allowed[grade] === true;
      const next = { ...prev };
      const value = !(prev[key] ?? server);
      // A box ticked back to where it started is no longer a change.
      if (value === server) delete next[key];
      else next[key] = value;
      return next;
    });
  };

  /**
   * How many events a ticked cell is worth. The programme holds one event per
   * division, so a single event type counts for `totalEvents / rows` events —
   * the C grade's two blocked distances are the four events behind its 34.
   * Only used for the preview line, and skipped when the programme is not
   * uniform, so a wrong figure is never shown.
   */
  const eventsPerType = useMemo(() => {
    if (!matrix || matrix.events.length === 0 || !matrix.totalEvents) return 0;
    const per = matrix.totalEvents / matrix.events.length;
    return Number.isInteger(per) ? per : 0;
  }, [matrix]);

  /** The counts the grid would produce if the unsaved ticks were saved. */
  const projectedCounts = useMemo(() => {
    if (!matrix) return null;
    const counts: Record<string, number> = { ...matrix.allowedEventCounts };
    if (!eventsPerType) return counts;
    Object.entries(pending).forEach(([key, allowed]) => {
      const [eventType, grade] = key.split('|');
      const row = matrix.events.find(item => item.eventType === eventType);
      if (!row) return;
      const was = row.allowed[grade] === true;
      if (was === allowed) return;
      counts[grade] = (counts[grade] ?? 0) + (allowed ? eventsPerType : -eventsPerType);
    });
    return counts;
  }, [matrix, pending, eventsPerType]);

  const handleSave = async () => {
    if (!matrix || !dirty) return;
    // Only the cells that changed, as `{ eventType, grade, allowed }`.
    const updates: GradeEligibilityUpdate[] = Object.entries(pending).map(([key, allowed]) => {
      const [eventType, grade] = key.split('|');
      return { eventType, grade, allowed };
    });
    setSaving(true);
    setMessage('');
    setError('');
    try {
      const updated = await api.updateGradeEvents(updates);
      setMatrix(updated);
      setPending({});
      setMessage(t('gradeEvents.saved'));
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('gradeEvents.saveFailed'));
    } finally {
      setSaving(false);
    }
  };

  const handleReset = async () => {
    if (dirty && !window.confirm(t('gradeEvents.resetUnsaved'))) return;
    if (!window.confirm(t('gradeEvents.resetConfirm'))) return;
    setResetting(true);
    setMessage('');
    setError('');
    try {
      const result = await api.resetGradeEvents();
      setMatrix(result.matrix);
      setPending({});
      setMessage(t('gradeEvents.resetDone', { count: result.rulesCreated }));
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('gradeEvents.resetFailed'));
    } finally {
      setResetting(false);
    }
  };

  if (isLoading || !user || loading) {
    return <div>{t('gradeEvents.loading')}</div>;
  }

  if (!matrix) {
    return (
      <div>
        <h1 className="page-title">{t('gradeEvents.title')}</h1>
        {error && <div className="alert alert-error">{error}</div>}
        <div className="card">
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => window.location.reload()}
          >
            {t('common.retry')}
          </button>
        </div>
      </div>
    );
  }

  const totalEvents = matrix.totalEvents ?? 0;
  /** The figures the school cares about, straight from the server's own counts. */
  const summary = grades
    .map((grade, index) =>
      index === 0
        ? t('gradeEvents.summaryLead', {
            grade: label('grade.short', grade),
            count: matrix.allowedEventCounts[grade] ?? 0,
            total: totalEvents,
          })
        : t('gradeEvents.summaryRest', {
            grade: label('grade.short', grade),
            count: matrix.allowedEventCounts[grade] ?? 0,
          })
    )
    .join(' · ');

  const previewCounts =
    dirty && projectedCounts
      ? grades
          .map(grade =>
            t('gradeEvents.summaryRest', {
              grade: label('grade.short', grade),
              count: projectedCounts[grade] ?? 0,
            })
          )
          .join(' · ')
      : '';

  const changedCount = Object.keys(pending).length;

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('gradeEvents.title')}</h1>
        <div className="flex gap-2">
          <Link
            href="/admin/events"
            className="btn btn-secondary"
            onClick={event => {
              if (!confirmDiscard()) event.preventDefault();
            }}
          >
            {t('admin.events')}
          </Link>
          <Link
            href="/admin"
            className="btn btn-secondary"
            onClick={event => {
              if (!confirmDiscard()) event.preventDefault();
            }}
          >
            {t('common.backToAdmin')}
          </Link>
        </div>
      </div>

      <p className="muted">{t('gradeEvents.subtitle')}</p>

      {message && <div className="alert alert-success">{message}</div>}
      {error && <div className="alert alert-error">{error}</div>}

      {/* The number the school cares about: how many events each grade gets. */}
      <div className="card mt-2">
        <div className="flex justify-between items-center">
          <h2>{t('gradeEvents.summaryTitle')}</h2>
          {dirty && <span className="badge badge-warn">{t('gradeEvents.unsaved')}</span>}
        </div>
        <p style={{ fontSize: '1.05rem', fontWeight: 600 }}>{summary}</p>
        <p className="muted">{t('gradeEvents.countsNote', { total: totalEvents })}</p>
        {previewCounts && (
          <p className="muted">{t('gradeEvents.preview', { counts: previewCounts })}</p>
        )}
        <div className="stat-grid mt-2" style={{ marginBottom: 0 }}>
          {grades.map(grade => (
            <div className="stat" key={grade}>
              <div className="stat-value">{matrix.allowedEventCounts[grade] ?? 0}</div>
              <div className="stat-label">{label('grade', grade)}</div>
            </div>
          ))}
        </div>
      </div>

      {/* What the change does, and — just as important — what it does not do. */}
      <div className="card mt-2">
        <span className="badge badge-warning">{t('gradeEvents.warningTitle')}</span>
        <p className="muted mt-2">{t('gradeEvents.warning')}</p>
      </div>

      <div className="card mt-2">
        <div className="flex justify-between items-center">
          <h2>{t('gradeEvents.assignment')}</h2>
          {dirty && (
            <span className="badge badge-warn">
              {t('gradeEvents.changedCount', { count: changedCount })}
            </span>
          )}
        </div>
        <p className="muted">{t('gradeEvents.assignmentHint')}</p>
        <p className="muted">{t('gradeEvents.tickHint')}</p>
      </div>

      {matrix.events.length === 0 ? (
        <div className="empty mt-2">
          <p>{t('gradeEvents.empty')}</p>
        </div>
      ) : (
        SECTION_ORDER.map(category => {
          const rows = byCategory[category];
          if (rows.length === 0) return null;
          const isTrack = category === 'TRACK';
          return (
            <section key={category} className="mt-3">
              <h2 className={`section-title ${isTrack ? 'section-track' : 'section-field'}`}>
                {label('category', category)}
                <span className="badge badge-info">{rows.length}</span>
              </h2>
              <div className="card">
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>{t('gradeEvents.colEvent')}</th>
                        {grades.map(grade => (
                          <th key={grade}>{label('grade.short', grade)}</th>
                        ))}
                        <th>{t('gradeEvents.colGrades')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map(row => {
                        const allowedNow = grades.filter(grade => cellValue(row, grade));
                        return (
                          <tr key={row.eventType}>
                            <td>
                              <strong>{row.eventTypeLabel}</strong>
                              <div className="muted">{row.eventType}</div>
                            </td>
                            {grades.map(grade => {
                              const changed = isChanged(row, grade);
                              const ticked = cellValue(row, grade);
                              return (
                                <td
                                  key={grade}
                                  style={{
                                    textAlign: 'center',
                                    background: changed ? '#fff4e5' : undefined,
                                  }}
                                >
                                  <input
                                    type="checkbox"
                                    checked={ticked}
                                    disabled={saving || resetting}
                                    onChange={() => toggleCell(row, grade)}
                                    aria-label={t('gradeEvents.gradeCellAria', {
                                      grade: label('grade.short', grade),
                                      event: row.eventTypeLabel,
                                    })}
                                    style={{ width: 'auto', margin: 0 }}
                                  />
                                </td>
                              );
                            })}
                            <td>
                              {allowedNow.length === 0 ? (
                                <span className="muted">{t('common.none')}</span>
                              ) : allowedNow.length === grades.length ? (
                                <span className="badge badge-info">
                                  {t('adminEvents.allGrades')}
                                </span>
                              ) : (
                                <div className="flex gap-2">
                                  {allowedNow.map(grade => (
                                    <span className="badge badge-success" key={grade}>
                                      {label('grade.short', grade)}
                                    </span>
                                  ))}
                                </div>
                              )}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            </section>
          );
        })
      )}

      <div className="card mt-2">
        <p className="muted">{t('gradeEvents.saveHint')}</p>
        <div className="pill-actions mt-2">
          <button
            type="button"
            className="btn btn-primary"
            onClick={handleSave}
            disabled={saving || resetting || !dirty}
          >
            {saving ? t('common.saving') : t('gradeEvents.save')}
          </button>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={handleReset}
            disabled={saving || resetting}
          >
            {resetting ? t('gradeEvents.resetting') : t('gradeEvents.reset')}
          </button>
        </div>
        {!dirty && <p className="muted mt-2">{t('gradeEvents.noChanges')}</p>}
      </div>
    </div>
  );
}
