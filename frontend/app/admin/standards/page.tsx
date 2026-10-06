'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api, EventCategory, EventDTO } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';

/**
 * The **required standards**: one box per qualifying event, on one page.
 *
 * A standard is the qualifying mark an athlete must reach, and only some events
 * carry one — the track races of 400M and over, and every field event. A 60M,
 * 100M or 200M is not qualifying in this school's programme, the short hurdles
 * are not, and neither is a relay: a relay is its own category, run and scored by
 * team. The requirement is explicit that this is set **here and not on the event
 * edit page**, so this page is the one place a standard is typed.
 *
 * **Which** events qualify is deliberately not decided here. The server answers
 * `carriesStandard` for every event from the single rule that owns the question
 * (`EventType.carriesAStandard()`), so this page cannot come to disagree with the
 * marking sheet or the mark grid about what is qualifying. This page only
 * collects numbers.
 *
 * The standard is in the event's own unit — seconds on the track, metres in the
 * field — and a race meets it at or **under** it while a field event meets it at
 * or **over** it. That direction is not decided here either: the server applies
 * its one lower-is-better rule, the same one that settles the leaderboards.
 *
 * **One save for the whole page**, the shape `/admin/settings` uses: boxes are
 * edited freely and a single button sends every event whose box actually changed
 * through `PUT /api/events/{id}`. An empty box is a real answer — no standard —
 * and is sent as `clearStandard`, because an omitted number means "leave it
 * alone" to a server that also serves callers sending partial bodies.
 */

/** The order the families are shown in, so the page reads like a programme. */
const CATEGORY_ORDER: EventCategory[] = ['TRACK', 'FIELD', 'RELAY'];

/** What has been typed into a box, before it is saved, keyed by event id. */
type Drafts = Record<number, string>;

/** The number the server holds, as it would be typed back; `''` for none. */
function serverValue(event: EventDTO): string {
  return event.standard === null || event.standard === undefined
    ? ''
    : String(Number(event.standard));
}

/** The unit a standard is measured in: metres in the field, seconds on the track. */
function unitOf(event: EventDTO): string {
  return event.category === 'FIELD' ? 'M' : 's';
}

/** A page of boxes: one family, then the grades and divisions inside it. */
interface Block {
  key: string;
  heading: string;
  events: EventDTO[];
}

interface Family {
  category: EventCategory;
  heading: string;
  blocks: Block[];
}

export default function StandardsPage() {
  const { t, label } = useI18n();
  const { user, isLoading } = useAuth();
  const router = useRouter();

  const [events, setEvents] = useState<EventDTO[]>([]);
  const [drafts, setDrafts] = useState<Drafts>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** Why an event's box was refused, keyed by event id. */
  const [failures, setFailures] = useState<Record<number, string>>({});

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
       is a relay, so it never qualifies anyway. */
    api
      .getEvents({ onlyEnabled: false })
      .then(all => {
        if (!cancelled) setEvents(all);
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

  /** What the box shows: what has been typed, else what the server holds. */
  const valueOf = (event: EventDTO): string => drafts[event.id] ?? serverValue(event);

  /** Every event the server says carries a standard. */
  const qualifying = useMemo(() => events.filter(event => event.carriesStandard), [events]);

  /**
   * The events whose box has actually been changed and not yet saved. Only these
   * are sent: a save is a write, and writing an event nobody touched would be a
   * request that changes nothing.
   */
  const changed = useMemo(
    () =>
      qualifying.filter(
        event => drafts[event.id] !== undefined && drafts[event.id].trim() !== serverValue(event)
      ),
    [qualifying, drafts]
  );

  /**
   * Anything that cannot be a standard: an empty box clears one and is always
   * allowed, but a standard that is not a number greater than zero — a time of
   * zero, a negative distance, a stray word — is a typo rather than a mark. It is
   * caught here so the whole page is refused with the reason, before any request
   * is sent; the server refuses the same value in the same words.
   */
  const problems = useMemo(() => {
    const found: string[] = [];
    for (const event of changed) {
      const raw = valueOf(event).trim();
      if (raw === '') continue;
      const value = Number(raw.replace(',', '.'));
      if (!Number.isFinite(value) || value <= 0) {
        found.push(t('standards.invalid', { event: event.name, value: raw }));
      }
    }
    return found;
  }, [changed, drafts, t]); // eslint-disable-line react-hooks/exhaustive-deps

  /** The boxes grouped by family, then by grade and division, as a programme reads. */
  const families = useMemo<Family[]>(() => {
    const byCategory = new Map<EventCategory, Map<string, EventDTO[]>>();
    for (const event of qualifying) {
      const blocks = byCategory.get(event.category) ?? new Map<string, EventDTO[]>();
      const key = `${event.grade}-${event.sex}`;
      blocks.set(key, [...(blocks.get(key) ?? []), event]);
      byCategory.set(event.category, blocks);
    }
    return CATEGORY_ORDER.filter(category => byCategory.has(category)).map(category => {
      const blocks = byCategory.get(category) as Map<string, EventDTO[]>;
      return {
        category,
        heading: label('category', category),
        blocks: [...blocks.entries()]
          .map(([key, listed]) => ({
            key,
            heading: `${listed[0].gradeLabel} · ${listed[0].sexLabel}`,
            events: [...listed].sort((a, b) => a.name.localeCompare(b.name)),
          }))
          .sort((a, b) => a.heading.localeCompare(b.heading)),
      };
    });
  }, [qualifying, label]);

  const handleSave = async () => {
    setNotice(null);
    setError(null);
    setFailures({});
    if (problems.length > 0) {
      setError(`${t('standards.fixFirst')} ${problems.join('; ')}`);
      return;
    }
    if (changed.length === 0) {
      setNotice(t('standards.nothingToSave'));
      return;
    }
    setSaving(true);
    let saved = 0;
    const failed: Record<number, string> = {};
    for (const event of changed) {
      const raw = valueOf(event).trim();
      const value = raw === '' ? null : Number(raw.replace(',', '.'));
      try {
        // An empty box is "no standard". It cannot be sent as an omitted number,
        // which the server reads as "leave it alone", so it is asked for.
        await api.updateEvent(
          event.id,
          value === null ? { clearStandard: true } : { standard: value }
        );
        saved += 1;
        setEvents(previous =>
          previous.map(candidate =>
            candidate.id === event.id ? { ...candidate, standard: value } : candidate
          )
        );
        setDrafts(previous => {
          const next = { ...previous };
          delete next[event.id];
          return next;
        });
      } catch (err) {
        failed[event.id] =
          err instanceof Error && err.message ? err.message : t('standards.saveFailed');
      }
    }
    setFailures(failed);
    setSaving(false);
    const failedCount = Object.keys(failed).length;
    if (failedCount === 0) setNotice(t('standards.savedCount', { count: saved }));
    else setError(t('standards.failedCount', { count: failedCount }));
  };

  if (isLoading || !isAdmin) {
    return <p className="muted">{t('common.loading')}</p>;
  }

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('standards.title')}</h1>
        <Link href="/admin" className="btn btn-secondary">
          {t('common.backToAdmin')}
        </Link>
      </div>
      <p className="muted">{t('standards.intro')}</p>

      {notice && <div className="alert alert-success">{notice}</div>}
      {error && <div className="alert alert-error">{error}</div>}

      {loading && <p className="muted">{t('common.loading')}</p>}

      {!loading && qualifying.length === 0 && (
        <div className="empty">
          <p>{t('standards.empty')}</p>
        </div>
      )}

      {!loading && qualifying.length > 0 && (
        <div className="card">
          <p className="muted" style={{ marginBottom: 0 }}>
            {t('standards.listed', { count: qualifying.length })}
          </p>
        </div>
      )}

      {families.map(family => (
        <div key={family.category} className="card mt-2">
          <h2 className="section-title">{family.heading}</h2>
          {family.blocks.map(block => (
            <div key={block.key} className="mt-2">
              <h3>{block.heading}</h3>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>{t('standards.event')}</th>
                      <th className="col-narrow">{t('standards.standard')}</th>
                      <th className="col-narrow">{t('standards.unit')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {block.events.map(event => (
                      <tr key={event.id}>
                        <td>{event.name}</td>
                        <td>
                          <input
                            type="text"
                            inputMode="decimal"
                            /* An empty box is a real answer: no standard at all. */
                            placeholder={t('standards.none')}
                            value={valueOf(event)}
                            disabled={saving}
                            aria-label={`${t('standards.standard')} ${event.name}`}
                            onChange={e => {
                              setDrafts(previous => ({ ...previous, [event.id]: e.target.value }));
                              setNotice(null);
                            }}
                          />
                        </td>
                        <td>{label('unit', unitOf(event))}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {Object.entries(failures)
                .filter(([id]) => block.events.some(event => event.id === Number(id)))
                .map(([id, reason]) => (
                  <p key={id} className="muted">
                    {block.events.find(event => event.id === Number(id))?.name}: {reason}
                  </p>
                ))}
            </div>
          ))}
        </div>
      ))}

      {!loading && qualifying.length > 0 && (
        <div className="card mt-2">
          {/* One button for the page: every changed box is sent in one go, and a
              box that was never touched is never written. */}
          <button
            type="button"
            className="btn btn-primary"
            onClick={handleSave}
            disabled={saving || changed.length === 0}
          >
            {saving ? t('common.saving') : t('standards.saveAll')}
          </button>
          <button
            type="button"
            className="btn btn-secondary"
            style={{ marginLeft: '0.5rem' }}
            onClick={() => {
              setDrafts({});
              setNotice(null);
              setError(null);
              setFailures({});
            }}
            disabled={saving || changed.length === 0}
          >
            {t('common.reset')}
          </button>
          <span className="muted" style={{ marginLeft: '0.75rem' }}>
            {changed.length === 0
              ? t('standards.blankSkipped')
              : t('standards.changedCount', { count: changed.length })}
          </span>
        </div>
      )}
    </div>
  );
}
