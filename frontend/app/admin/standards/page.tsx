'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  api,
  EventCategory,
  EventDTO,
  EventSex,
  Grade,
  StandardDefaultDTO,
  StandardDefaultsApplyResultDTO,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';

/**
 * The **required standards**.
 *
 * A standard is the qualifying mark an athlete must reach, and only some events
 * carry one — the track races of 400M and over, and every field event. A 60M,
 * 100M or 200M is not qualifying in this school's programme, the short hurdles
 * are not, and neither is a relay: a relay is its own category, run and scored by
 * team. **Which** events qualify is deliberately not decided here: the server
 * answers `carriesStandard` for every event from the one rule that owns the
 * question (`EventType.carriesAStandard()`), so this page cannot come to disagree
 * with the marking sheet or the mark grid.
 *
 * ## Set it once per grade, not 72 times
 *
 * The primary view is the **default**: one box per event type, grade and sex
 * division — "400M, A grade, boys = 64.0 s" — which the events of that key
 * inherit. The school's requirement is exactly this: *base on each grade update
 * standard record for default*. Before it there was one box per event, and on the
 * live programme that is **72 boxes** for 13 types.
 *
 * **The key is type × grade × sex, and the sex is not decoration.** A default
 * keyed on grade alone would give the boys' 400M and the girls' 400M the same
 * qualifying time, and the live programme holds both at every grade. So the grid
 * below has two columns, and a boys' default can never reach a girls' event.
 *
 * ## Saving a default, then applying it — two deliberate steps
 *
 * **Save** records the number and touches no event: it is what a *new* event
 * inherits from then on. **Apply** is what updates the events that already exist,
 * and it is a separate button because it rewrites live programme data. The page
 * previews the apply first — `dryRun` — and says how many events would change and
 * how many hand-set numbers are being left alone, before anything is written.
 *
 * A standard somebody typed on one event is **never** overwritten by a plain
 * apply. That is the rule for a race that is an exception to its grade, and the
 * count of what was kept is shown rather than hidden. Overwriting those is
 * possible, but it takes the second button and it says what it does.
 *
 * ## The per-event box is still here
 *
 * Below, collapsed, is the original page: one box per qualifying event, for the
 * exception a grade's default cannot express. It saves in one go, exactly as it
 * did.
 *
 * The standard is in the event's own unit — seconds on the track, metres in the
 * field — and a race meets it at or **under** it while a field event meets it at
 * or **over** it. That direction is not decided here either: the server applies
 * its one lower-is-better rule, the same one that settles the leaderboards.
 */

/** The order the families are shown in, so the page reads like a programme. */
const CATEGORY_ORDER: EventCategory[] = ['TRACK', 'FIELD'];

/** The divisions, in the order the programme runs them. */
const DIVISIONS: ReadonlyArray<{ sex: EventSex; labelKey: 'sex.MALE' | 'sex.FEMALE' }> = [
  { sex: 'MALE', labelKey: 'sex.MALE' },
  { sex: 'FEMALE', labelKey: 'sex.FEMALE' },
];

/** What has been typed into a default box, before it is saved, keyed by grid key. */
type DefaultDrafts = Record<string, string>;

/** What has been typed into an event's own box, before it is saved. */
type EventDrafts = Record<number, string>;

/** The number the server holds on an event, as it would be typed back; `''` for none. */
function eventServerValue(event: EventDTO): string {
  return event.standard === null || event.standard === undefined
    ? ''
    : String(Number(event.standard));
}

/** The unit a standard is measured in: metres in the field, seconds on the track. */
function unitOf(category: EventCategory): string {
  return category === 'FIELD' ? 'M' : 's';
}

/** `RUN_400M|A|MALE` — the key the server's own answer uses. */
function keyOf(type: string, grade: Grade, sex: EventSex): string {
  return `${type}|${grade}|${sex}`;
}

/** One division's box in the grid: the default, and the events that inherit it. */
interface GridCell {
  key: string;
  /** The event type this box is for — the first half of the key, kept as a field. */
  type: string;
  sex: EventSex;
  /** The events of this key, whatever standard they hold today. */
  events: EventDTO[];
}

/** One grade of one type: the two boxes an administrator actually sets. */
interface GridRow {
  key: string;
  type: string;
  typeLabel: string;
  grade: Grade;
  gradeLabel: string;
  cells: GridCell[];
}

interface GridFamily {
  category: EventCategory;
  heading: string;
  rows: GridRow[];
}

/** A box that cannot be a standard, with the reason, before anything is sent. */
interface Problem {
  message: string;
}

export default function StandardsPage() {
  const { t, label } = useI18n();
  const { user, isLoading } = useAuth();
  const router = useRouter();

  const [events, setEvents] = useState<EventDTO[]>([]);
  const [defaults, setDefaults] = useState<StandardDefaultDTO[]>([]);
  const [defaultDrafts, setDefaultDrafts] = useState<DefaultDrafts>({});
  const [eventDrafts, setEventDrafts] = useState<EventDrafts>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** Why an event's own box was refused, keyed by event id. */
  const [eventFailures, setEventFailures] = useState<Record<number, string>>({});
  /** Why a default's box was refused, keyed by grid key. */
  const [defaultFailures, setDefaultFailures] = useState<Record<string, string>>({});
  /** The last apply preview, kept on screen so the commit button has a context. */
  const [preview, setPreview] = useState<StandardDefaultsApplyResultDTO | null>(null);
  const [applying, setApplying] = useState(false);
  /** Whether the per-event exceptions are open. Closed by default: the grid is the page. */
  const [showExceptions, setShowExceptions] = useState(false);

  // Setting a standard is an administrative act, exactly like the settings page:
  // the page is ADMIN-only and the server refuses the write to anyone else.
  const isAdmin = user?.role === 'ADMIN';

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    if (!isAdmin) {
      router.push('/');
    }
  }, [isLoading, user, isAdmin, router]);

  useEffect(() => {
    if (!isAdmin) return;
    let cancelled = false;
    /* Every event, whether or not it is enabled: a standard belongs to the race,
       and an event closed to new entries is still run and still has one. A draft
       is a relay, so it never qualifies anyway.

       The defaults are read alongside it — two requests, not one per event: the
       whole default set is small and the grid is built from the two lists. */
    Promise.all([api.getEvents({ onlyEnabled: false }), api.getStandardDefaults()])
      .then(([all, configured]) => {
        if (cancelled) return;
        setEvents(all);
        setDefaults(configured);
      })
      .catch(err => {
        if (!cancelled) {
          setError(err instanceof Error && err.message ? err.message : t('events.loadFailed'));
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isAdmin, t]);

  /** Every event the server says carries a standard. */
  const qualifying = useMemo(() => events.filter(event => event.carriesStandard), [events]);

  /** The saved default for one key, as it would be typed; `''` when there is none. */
  const savedDefaultOf = useMemo(() => {
    const byKey = new Map<string, string>();
    for (const configured of defaults) {
      byKey.set(
        configured.key ?? keyOf(configured.type, configured.grade, configured.sex),
        configured.standard === null || configured.standard === undefined
          ? ''
          : String(Number(configured.standard))
      );
    }
    return byKey;
  }, [defaults]);

  /** What a default's box shows: what has been typed, else what the server holds. */
  const valueOfDefault = (key: string): string =>
    defaultDrafts[key] ?? savedDefaultOf.get(key) ?? '';

  /** What an event's box shows: what has been typed, else what the server holds. */
  const valueOfEvent = (event: EventDTO): string =>
    eventDrafts[event.id] ?? eventServerValue(event);

  /**
   * The grid: one row per type and grade, one box per division.
   *
   * The types are taken from the **events themselves** rather than from a list
   * written here, so the page offers exactly the types the server says carry a
   * standard and cannot drift from it. A relay and a short sprint are therefore
   * absent, which is what the school asked for.
   */
  const families = useMemo<GridFamily[]>(() => {
    type RowBuilder = {
      type: string;
      typeLabel: string;
      category: EventCategory;
      grade: Grade;
      gradeLabel: string;
      cells: Map<EventSex, GridCell>;
    };
    const byCategory = new Map<EventCategory, Map<string, RowBuilder>>();
    for (const event of qualifying) {
      const category = event.category;
      const rows = byCategory.get(category) ?? new Map<string, RowBuilder>();
      const rowKey = `${event.type}-${event.grade}`;
      const row: RowBuilder =
        rows.get(rowKey) ??
        {
          type: event.type,
          typeLabel: event.typeLabel,
          category,
          grade: event.grade,
          gradeLabel: event.gradeLabel,
          cells: new Map<EventSex, GridCell>(),
        };
      const cell: GridCell =
        row.cells.get(event.sex) ??
        {
          key: keyOf(event.type, event.grade, event.sex),
          type: event.type,
          sex: event.sex,
          events: [],
        };
      cell.events.push(event);
      row.cells.set(event.sex, cell);
      rows.set(rowKey, row);
      byCategory.set(category, rows);
    }
    return CATEGORY_ORDER.filter(category => byCategory.has(category)).map(category => {
      const rows = byCategory.get(category) as Map<string, RowBuilder>;
      return {
        category,
        heading: label('category', category),
        /* Programme order: the type as the server listed it, then the grade. */
        rows: [...rows.values()]
          .sort((a, b) => {
            const first = qualifying.findIndex(event => event.type === a.type);
            const second = qualifying.findIndex(event => event.type === b.type);
            if (first !== second) return first - second;
            return a.grade.localeCompare(b.grade);
          })
          .map(row => ({
            key: `${row.type}-${row.grade}`,
            type: row.type,
            typeLabel: row.typeLabel,
            grade: row.grade,
            gradeLabel: row.gradeLabel,
            cells: DIVISIONS.map(division => {
              const found = row.cells.get(division.sex);
              return (
                found ?? {
                  key: keyOf(row.type, row.grade, division.sex),
                  type: row.type,
                  sex: division.sex,
                  events: [],
                }
              );
            }),
          })),
      };
    });
  }, [qualifying, label]);

  /** Every box in the grid, so a save can walk them without rebuilding the shape. */
  const allCells = useMemo(() => families.flatMap(family => family.rows.flatMap(row => row.cells)), [
    families,
  ]);

  /**
   * The defaults whose box has actually been changed and not yet saved. Only these
   * are sent: a save is a write, and writing a box nobody touched would be a
   * request that changes nothing.
   */
  const changedDefaults = useMemo(
    () =>
      allCells.filter(
        cell =>
          defaultDrafts[cell.key] !== undefined &&
          defaultDrafts[cell.key].trim() !== (savedDefaultOf.get(cell.key) ?? '')
      ),
    [allCells, defaultDrafts, savedDefaultOf]
  );

  /** The events whose own box has been changed and not yet saved. */
  const changedEvents = useMemo(
    () =>
      qualifying.filter(
        event => eventDrafts[event.id] !== undefined && eventDrafts[event.id].trim() !== eventServerValue(event)
      ),
    [qualifying, eventDrafts]
  );

  /**
   * Anything that cannot be a standard: an empty box clears one and is always
   * allowed, but a standard that is not a number greater than zero — a time of
   * zero, a negative distance, a stray word — is a typo rather than a mark. It is
   * caught here so the whole page is refused with the reason, before any request
   * is sent; the server refuses the same value in the same words.
   */
  const problems = useMemo<Problem[]>(() => {
    const found: Problem[] = [];
    for (const cell of changedDefaults) {
      const raw = valueOfDefault(cell.key).trim();
      if (raw === '') continue;
      const value = Number(raw.replace(',', '.'));
      if (!Number.isFinite(value) || value <= 0) {
        found.push({
          message: t('standards.invalidDefault', {
            event: cell.events[0]?.typeLabel ?? cell.key,
            grade: cell.events[0]?.gradeLabel ?? '',
            value: raw,
          }),
        });
      }
    }
    for (const event of changedEvents) {
      const raw = valueOfEvent(event).trim();
      if (raw === '') continue;
      const value = Number(raw.replace(',', '.'));
      if (!Number.isFinite(value) || value <= 0) {
        found.push({ message: t('standards.invalid', { event: event.name, value: raw }) });
      }
    }
    return found;
  }, [changedDefaults, changedEvents, defaultDrafts, eventDrafts, t]); // eslint-disable-line react-hooks/exhaustive-deps

  /**
   * Saves the defaults, and the event exceptions if any were touched.
   *
   * **It changes no event.** A default is what a *new* event inherits; the events
   * that already exist are re-pointed by the apply below, which the administrator
   * runs deliberately. Doing it here instead would rewrite the whole programme on
   * every keystroke and would quietly replace hand-set numbers, which the school
   * must be told about rather than discover.
   */
  const handleSaveDefaults = async () => {
    setNotice(null);
    setError(null);
    setDefaultFailures({});
    setEventFailures({});
    setPreview(null);
    if (problems.length > 0) {
      setError(`${t('standards.fixFirst')} ${problems.map(problem => problem.message).join('; ')}`);
      return;
    }
    if (changedDefaults.length === 0 && changedEvents.length === 0) {
      setNotice(t('standards.nothingToSave'));
      return;
    }
    setSaving(true);
    let saved = 0;
    const failed: Record<string, string> = {};
    for (const cell of changedDefaults) {
      const owner = cell.events[0];
      if (!owner) continue;
      const raw = valueOfDefault(cell.key).trim();
      const value = raw === '' ? null : Number(raw.replace(',', '.'));
      try {
        const written = await api.setStandardDefault(cell.type, owner.grade, cell.sex, value);
        saved += 1;
        setDefaults(previous => [
          ...previous.filter(
            candidate =>
              (candidate.key ?? keyOf(candidate.type, candidate.grade, candidate.sex)) !== cell.key
          ),
          written,
        ]);
        setDefaultDrafts(previous => {
          const next = { ...previous };
          delete next[cell.key];
          return next;
        });
      } catch (err) {
        failed[cell.key] =
          err instanceof Error && err.message ? err.message : t('standards.saveFailed');
      }
    }
    setDefaultFailures(failed);

    /* The per-event exceptions, exactly as the page always saved them. */
    const eventFailed: Record<number, string> = {};
    for (const event of changedEvents) {
      const raw = valueOfEvent(event).trim();
      const value = raw === '' ? null : Number(raw.replace(',', '.'));
      try {
        // An empty box is "no standard", and it is the school's own decision, so it
        // is sent as `clearStandard` — which also stops the event following its
        // grade's default again. An omitted number would mean "leave it alone".
        await api.updateEvent(
          event.id,
          value === null ? { clearStandard: true } : { standard: value }
        );
        saved += 1;
        setEvents(previous =>
          previous.map(candidate =>
            candidate.id === event.id
              ? { ...candidate, standard: value, standardFromDefault: false }
              : candidate
          )
        );
        setEventDrafts(previous => {
          const next = { ...previous };
          delete next[event.id];
          return next;
        });
      } catch (err) {
        eventFailed[event.id] =
          err instanceof Error && err.message ? err.message : t('standards.saveFailed');
      }
    }
    setEventFailures(eventFailed);
    setSaving(false);
    const failedCount = Object.keys(failed).length + Object.keys(eventFailed).length;
    if (failedCount === 0) setNotice(t('standards.savedCount', { count: saved }));
    else setError(t('standards.failedCount', { count: failedCount }));
  };

  /**
   * Works out what an apply would do, and writes nothing.
   *
   * Shown before the commit so the administrator sees "3 events will change, 1
   * hand-set standard will be kept" while it is still free to change their mind.
   */
  const handlePreview = async (mode: 'INHERITED' | 'ALL') => {
    setNotice(null);
    setError(null);
    setApplying(true);
    try {
      setPreview(await api.applyStandardDefaults(mode, true));
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : t('standards.applyFailed'));
    } finally {
      setApplying(false);
    }
  };

  /** Commits the apply, and re-reads the events so every box shows what is stored. */
  const handleApply = async (mode: 'INHERITED' | 'ALL') => {
    setNotice(null);
    setError(null);
    setApplying(true);
    try {
      const result = await api.applyStandardDefaults(mode, false);
      setPreview(result);
      const [all, configured] = await Promise.all([
        api.getEvents({ onlyEnabled: false }),
        api.getStandardDefaults(),
      ]);
      setEvents(all);
      setDefaults(configured);
      setEventDrafts({});
      setNotice(
        t('standards.appliedCount', { count: result.changed, kept: result.kept })
      );
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : t('standards.applyFailed'));
    } finally {
      setApplying(false);
    }
  };

  if (isLoading || !isAdmin) {
    return <p className="muted">{t('common.loading')}</p>;
  }

  const changedCount = changedDefaults.length + changedEvents.length;

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('standards.title')}</h1>
        <Link href="/admin" className="btn btn-secondary">
          {t('common.backToAdmin')}
        </Link>
      </div>
      <p className="muted">{t('standards.intro')}</p>
      <p className="muted">{t('standards.defaultsIntro')}</p>

      {notice && <div className="alert alert-success">{notice}</div>}
      {error && <div className="alert alert-error">{error}</div>}

      {loading && <p className="muted">{t('common.loading')}</p>}

      {!loading && qualifying.length === 0 && (
        <div className="empty">
          <p>{t('standards.empty')}</p>
        </div>
      )}

      {/* ------------------------------------------------ the primary view */}
      {!loading && families.length > 0 && (
        <div className="card mt-2">
          <h2 className="section-title">{t('standards.defaultsTitle')}</h2>
          <p className="muted">{t('standards.defaultsKey')}</p>

          {families.map(family => (
            <div key={family.category} className="mt-2">
              <h3>{family.heading}</h3>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>{t('standards.event')}</th>
                      <th>{t('standards.grade')}</th>
                      {DIVISIONS.map(division => (
                        <th key={division.sex} className="col-narrow">
                          {t(division.labelKey)}
                        </th>
                      ))}
                      <th className="col-narrow">{t('standards.unit')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {family.rows.map(row => (
                      <tr key={row.key}>
                        <td>{row.typeLabel}</td>
                        <td>{row.gradeLabel}</td>
                        {row.cells.map(cell => (
                          <td key={cell.key}>
                            <input
                              type="text"
                              inputMode="decimal"
                              /* An empty box is a real answer: no standard at all. */
                              placeholder={t('standards.none')}
                              value={valueOfDefault(cell.key)}
                              disabled={saving}
                              aria-label={`${row.typeLabel} ${row.gradeLabel} ${label(
                                'sex',
                                cell.sex
                              )}`}
                              onChange={e => {
                                const value = e.target.value;
                                setDefaultDrafts(previous => ({ ...previous, [cell.key]: value }));
                                setNotice(null);
                              }}
                            />
                            <span className="muted" style={{ display: 'block', fontSize: '0.8rem' }}>
                              {cell.events.length > 0
                                ? t('standards.inheritors', { count: cell.events.length })
                                : t('standards.noEvents')}
                            </span>
                            {defaultFailures[cell.key] && (
                              <span className="muted" style={{ display: 'block' }}>
                                {defaultFailures[cell.key]}
                              </span>
                            )}
                          </td>
                        ))}
                        <td>{label('unit', unitOf(family.category))}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          ))}

          <div className="mt-2">
            <button
              type="button"
              className="btn btn-primary"
              onClick={handleSaveDefaults}
              disabled={saving || changedCount === 0}
            >
              {saving ? t('common.saving') : t('standards.saveDefaults')}
            </button>
            <button
              type="button"
              className="btn btn-secondary"
              style={{ marginLeft: '0.5rem' }}
              onClick={() => {
                setDefaultDrafts({});
                setEventDrafts({});
                setNotice(null);
                setError(null);
                setDefaultFailures({});
                setEventFailures({});
              }}
              disabled={saving || changedCount === 0}
            >
              {t('common.reset')}
            </button>
            <span className="muted" style={{ marginLeft: '0.75rem' }}>
              {changedCount === 0
                ? t('standards.blankSkipped')
                : t('standards.changedCount', { count: changedCount })}
            </span>
          </div>
        </div>
      )}

      {/* ------------------------------------------------- the apply step */}
      {!loading && families.length > 0 && (
        <div className="card mt-2">
          <h2 className="section-title">{t('standards.applyTitle')}</h2>
          <p className="muted">{t('standards.applyIntro')}</p>
          <p className="muted">{t('standards.applyHandSet')}</p>

          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => handlePreview('INHERITED')}
            disabled={applying}
          >
            {t('standards.preview')}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            style={{ marginLeft: '0.5rem' }}
            onClick={() => handleApply('INHERITED')}
            disabled={applying}
          >
            {applying ? t('common.saving') : t('standards.applyInherited')}
          </button>
          <button
            type="button"
            className="btn btn-danger"
            style={{ marginLeft: '0.5rem' }}
            onClick={() => handleApply('ALL')}
            disabled={applying}
          >
            {t('standards.applyAll')}
          </button>

          {preview && (
            <div className="mt-2">
              <p className={preview.changed === 0 ? 'muted' : undefined}>
                {preview.dryRun
                  ? t('standards.previewCount', { count: preview.changed, kept: preview.kept })
                  : t('standards.appliedCount', { count: preview.changed, kept: preview.kept })}
              </p>
              {preview.kept > 0 && (
                <p className="muted">{t('standards.keptNote', { count: preview.kept })}</p>
              )}
              {preview.events.length > 0 && (
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>{t('standards.event')}</th>
                        <th className="col-narrow">
                          {preview.dryRun ? t('standards.wouldBecome') : t('standards.standard')}
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {preview.events.map(changed => (
                        <tr key={changed.eventId}>
                          <td>{changed.eventName}</td>
                          <td>{changed.standardLabel ?? t('standards.none')}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* --------------------------------------- the exceptions, per event */}
      {!loading && qualifying.length > 0 && (
        <div className="card mt-2">
          <h2 className="section-title">{t('standards.exceptionsTitle')}</h2>
          <p className="muted">{t('standards.exceptionsIntro')}</p>
          <button
            type="button"
            className="btn btn-secondary"
            aria-expanded={showExceptions}
            onClick={() => setShowExceptions(open => !open)}
          >
            {showExceptions
              ? t('standards.hideExceptions')
              : t('standards.showExceptions', { count: qualifying.length })}
          </button>

          {showExceptions && (
            <>
              {families.map(family => (
                <div key={family.category} className="mt-2">
                  <h3>{family.heading}</h3>
                  <div className="table-wrap">
                    <table>
                      <thead>
                        <tr>
                          <th>{t('standards.event')}</th>
                          <th className="col-narrow">{t('standards.standard')}</th>
                          <th className="col-narrow">{t('standards.source')}</th>
                          <th className="col-narrow">{t('standards.unit')}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {family.rows.flatMap(row =>
                          row.cells.flatMap(cell =>
                            [...cell.events]
                              .sort((a, b) => a.name.localeCompare(b.name))
                              .map(event => (
                                <tr key={event.id}>
                                  <td>{event.name}</td>
                                  <td>
                                    <input
                                      type="text"
                                      inputMode="decimal"
                                      placeholder={t('standards.none')}
                                      value={valueOfEvent(event)}
                                      disabled={saving}
                                      aria-label={`${t('standards.standard')} ${event.name}`}
                                      onChange={e => {
                                        const value = e.target.value;
                                        setEventDrafts(previous => ({
                                          ...previous,
                                          [event.id]: value,
                                        }));
                                        setNotice(null);
                                      }}
                                    />
                                  </td>
                                  <td>
                                    {/* Where the number came from, which is what
                                        decides whether an apply may replace it. */}
                                    {event.standardFromDefault
                                      ? t('standards.fromDefault')
                                      : event.standard === null || event.standard === undefined
                                        ? t('standards.none')
                                        : t('standards.handSet')}
                                  </td>
                                  <td>{label('unit', unitOf(family.category))}</td>
                                </tr>
                              ))
                          )
                        )}
                      </tbody>
                    </table>
                  </div>
                </div>
              ))}
              {Object.entries(eventFailures).map(([id, reason]) => (
                <p key={id} className="muted">
                  {events.find(event => event.id === Number(id))?.name}: {reason}
                </p>
              ))}
            </>
          )}
        </div>
      )}

      {!loading && qualifying.length > 0 && (
        <div className="card mt-2">
          <p className="muted" style={{ marginBottom: 0 }}>
            {t('standards.listed', { count: qualifying.length })}
          </p>
        </div>
      )}
    </div>
  );
}
