'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  api,
  EventCategory,
  EventDTO,
  EventGroupDTO,
  EventSex,
  Grade,
  GRADES,
  SexCode,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';

/** Same fallback as `lib/api.ts`; the constant itself is not exported. */
const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api';

/** Short division code the `?sex=` query parameter expects. */
const SEX_CODE: Record<EventSex, SexCode> = { MALE: 'M', FEMALE: 'F' };
const DIVISIONS: SexCode[] = ['M', 'F'];
const CATEGORIES: EventCategory[] = ['TRACK', 'FIELD'];

type DivisionFilter = SexCode | '';
type CategoryFilter = EventCategory | '';

/** Turns an event name into something safe for a download filename. */
function slug(value: string): string {
  const cleaned = value.replace(/[^\w.-]+/g, '-').replace(/^-+|-+$/g, '');
  return cleaned || 'event';
}

function errorText(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback;
}

/** Reads `message` out of a JSON error body without an `any` cast. */
function bodyMessage(body: unknown, fallback: string): string {
  if (body && typeof body === 'object' && 'message' in body) {
    const message = (body as Record<string, unknown>).message;
    if (typeof message === 'string' && message) return message;
  }
  return fallback;
}

/**
 * Downloads a marking-sheet PDF as a blob URL for the preview iframe.
 *
 * `lib/api.ts` keeps the token private and only exposes file *saving*, so the
 * page builds this one request itself. A plain `<a href>` cannot be used: these
 * endpoints are authenticated and answer 403 without the bearer token.
 */
async function fetchSheetBlobUrl(url: string): Promise<string> {
  const token = typeof window === 'undefined' ? null : localStorage.getItem('token');
  const response = await fetch(`${API_BASE}${url}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  });
  if (!response.ok) {
    const body: unknown = await response.json().catch(() => null);
    throw new Error(bodyMessage(body, `HTTP ${response.status}`));
  }
  return URL.createObjectURL(await response.blob());
}

interface Preview {
  name: string;
  url: string;
}

export default function PrintSheetsPage() {
  const { user, isLoading: authLoading } = useAuth();
  const { t, label } = useI18n();
  const router = useRouter();

  const [events, setEvents] = useState<EventDTO[]>([]);
  const [groups, setGroups] = useState<Record<number, EventGroupDTO[]>>({});
  const [loading, setLoading] = useState(true);
  const [groupsLoading, setGroupsLoading] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [sex, setSex] = useState<DivisionFilter>('');
  const [category, setCategory] = useState<CategoryFilter>('');
  /**
   * The grade the list is narrowed to. An event belongs to exactly one grade,
   * and the whole-run download endpoint takes no grade, so this narrows what is
   * shown and what is downloaded per event — see `print.gradeDownloadHint`.
   */
  const [grade, setGrade] = useState<Grade | ''>('');
  const [eventId, setEventId] = useState(0);

  const [preview, setPreview] = useState<Preview | null>(null);
  const iframeRef = useRef<HTMLIFrameElement | null>(null);
  /** The blob URL currently mounted, so it can be revoked exactly once. */
  const previewUrlRef = useRef<string | null>(null);
  /** Events whose heat list has already been cached. */
  const cachedGroupEvents = useRef<Set<number>>(new Set());

  const isStaff = user?.role === 'ADMIN' || user?.role === 'MANAGER';

  // Staff-only screen: send students home and anonymous visitors to the login.
  useEffect(() => {
    if (authLoading) return;
    if (!user) router.push('/login');
    else if (!isStaff) router.push('/');
  }, [authLoading, user, isStaff, router]);

  const loadEvents = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // Disabled events are included: an event closed to new entries may still
      // need its marking sheets printed.
      setEvents(await api.getEvents({ onlyEnabled: false }));
    } catch (err) {
      setError(errorText(err, t('events.loadFailed')));
      setEvents([]);
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    if (!isStaff) {
      setLoading(false);
      return;
    }
    loadEvents();
  }, [isStaff, loadEvents]);

  // Release the preview blob when the page unmounts.
  useEffect(
    () => () => {
      if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
      previewUrlRef.current = null;
    },
    []
  );

  const matches = useMemo(
    () =>
      events.filter(
        event =>
          (!sex || SEX_CODE[event.sex] === sex) &&
          (!category || event.category === category) &&
          (!grade || event.grade === grade) &&
          (!eventId || event.id === eventId)
      ),
    [events, sex, category, grade, eventId]
  );

  // Heat lists, fetched once per event and cached. `groupCount` on the event DTO
  // says up front which events actually have heats, so an ungrouped event costs
  // no request at all.
  useEffect(() => {
    const missing = matches
      .filter(event => event.groupCount > 0 && !cachedGroupEvents.current.has(event.id))
      .map(event => event.id);
    if (missing.length === 0) {
      setGroupsLoading(false);
      return;
    }

    let cancelled = false;
    setGroupsLoading(true);
    (async () => {
      const fetched = await Promise.all(
        missing.map(async id => {
          try {
            return [id, await api.getEventGroups(id)] as const;
          } catch {
            return [id, [] as EventGroupDTO[]] as const;
          }
        })
      );
      if (cancelled) return;
      setGroups(prev => {
        const next = { ...prev };
        fetched.forEach(([id, list]) => {
          next[id] = list;
          cachedGroupEvents.current.add(id);
        });
        return next;
      });
      setGroupsLoading(false);
    })();

    return () => {
      cancelled = true;
    };
  }, [matches]);

  /**
   * Heats only — a drawn final is a seventh group with `groupNumber: 0`, so it
   * must not be counted as a heat. `groupCount` on the event DTO counts every
   * group including the final, so it is only the fallback for the moment
   * before the list has been fetched.
   */
  const heatsOf = useCallback(
    (event: EventDTO) => {
      const list = groups[event.id];
      if (list) return list.filter(group => group.stage !== 'FINAL').length;
      return event.groupCount ?? 0;
    },
    [groups]
  );

  const totalHeats = matches.reduce((sum, event) => sum + heatsOf(event), 0);

  /** `?sex=M&category=TRACK&eventId=2` — a parameter is dropped when it is "All". */
  const runQuery = useMemo(() => {
    const params = new URLSearchParams();
    if (sex) params.set('sex', sex);
    if (category) params.set('category', category);
    if (eventId) params.set('eventId', String(eventId));
    const search = params.toString();
    return search ? `?${search}` : '';
  }, [sex, category, eventId]);

  const replacePreviewUrl = (url: string | null) => {
    if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
    previewUrlRef.current = url;
  };

  const closePreview = useCallback(() => {
    if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
    previewUrlRef.current = null;
    setPreview(null);
  }, []);

  const handleRefresh = async () => {
    closePreview();
    setGroups({});
    cachedGroupEvents.current = new Set();
    setNotice(null);
    await loadEvents();
  };

  const handleDownloadAll = async () => {
    setBusy('all');
    setError(null);
    setNotice(null);
    try {
      const filename = await api.downloadFile(
        `/sheets.pdf${runQuery}`,
        'sportday-marking-sheets.pdf'
      );
      setNotice(t('common.downloaded', { filename }));
    } catch (err) {
      setError(errorText(err, t('groups.sheetDownloadFailed')));
    } finally {
      setBusy(null);
    }
  };

  const handleEventDownload = async (event: EventDTO) => {
    setBusy(`event-${event.id}`);
    setError(null);
    setNotice(null);
    try {
      const filename = await api.downloadEventSheets(
        event.id,
        `${slug(event.name)}-marking-sheets.pdf`
      );
      setNotice(t('common.downloaded', { filename }));
    } catch (err) {
      setError(errorText(err, t('groups.sheetDownloadFailed')));
    } finally {
      setBusy(null);
    }
  };

  const handleHeatDownload = async (event: EventDTO, group: EventGroupDTO) => {
    setBusy(`heat-${group.id}`);
    setError(null);
    setNotice(null);
    try {
      const filename = await api.downloadGroupSheet(
        group.id,
        `${slug(event.name)}-${slug(group.label)}-${group.sheetSize}.pdf`
      );
      setNotice(t('common.downloaded', { filename }));
    } catch (err) {
      setError(errorText(err, t('groups.sheetDownloadFailed')));
    } finally {
      setBusy(null);
    }
  };

  const handlePreview = async (event: EventDTO) => {
    setBusy(`preview-${event.id}`);
    setError(null);
    setNotice(null);
    try {
      const url = await fetchSheetBlobUrl(`/events/${event.id}/sheets.pdf`);
      replacePreviewUrl(url);
      setPreview({ name: event.name, url });
    } catch (err) {
      setError(errorText(err, t('print.previewFailed')));
    } finally {
      setBusy(null);
    }
  };

  const handlePrintPreview = () => {
    const frame = iframeRef.current;
    if (!frame) return;
    try {
      frame.contentWindow?.focus();
      frame.contentWindow?.print();
    } catch {
      setError(t('print.previewFailed'));
    }
  };

  if (authLoading || !isStaff) {
    return <p className="muted">{t('common.loading')}</p>;
  }

  return (
    <div>
      <div className="flex justify-between items-center no-print">
        <h1 className="page-title">{t('print.title')}</h1>
        <div className="flex gap-2">
          <Link href="/admin" className="btn btn-secondary">
            {t('common.backToAdmin')}
          </Link>
          <Link href="/admin/marks" className="btn btn-secondary">
            {t('nav.marks')}
          </Link>
          <button
            type="button"
            className="btn btn-secondary"
            disabled={loading}
            onClick={handleRefresh}
          >
            {loading ? t('common.loading') : t('common.refresh')}
          </button>
        </div>
      </div>

      {error && <div className="alert alert-error no-print">{error}</div>}
      {notice && <div className="alert alert-success no-print">{notice}</div>}

      {/* Filters and the whole-run download — never printed */}
      <div className="card no-print">
        <p className="muted">{t('print.subtitle')}</p>

        <div className="grid-toolbar mt-2">
          <div className="field">
            <label htmlFor="print-division">{t('print.division')}</label>
            <select
              id="print-division"
              value={sex}
              onChange={e => setSex(e.target.value as DivisionFilter)}
            >
              <option value="">{t('print.allDivisions')}</option>
              {DIVISIONS.map(code => (
                <option key={code} value={code}>
                  {label('sex', code === 'M' ? 'MALE' : 'FEMALE')}
                </option>
              ))}
            </select>
          </div>

          <div className="field">
            <label htmlFor="print-category">{t('print.category')}</label>
            <select
              id="print-category"
              value={category}
              onChange={e => setCategory(e.target.value as CategoryFilter)}
            >
              <option value="">{t('print.allCategories')}</option>
              {CATEGORIES.map(value => (
                <option key={value} value={value}>
                  {label('category', value)}
                </option>
              ))}
            </select>
          </div>

          <div className="field">
            <label htmlFor="print-grade">{t('marks.grade')}</label>
            <select
              id="print-grade"
              value={grade}
              onChange={e => setGrade(e.target.value as Grade | '')}
            >
              <option value="">{t('marks.allGrades')}</option>
              {GRADES.map(value => (
                <option key={value} value={value}>
                  {label('grade', value)}
                </option>
              ))}
            </select>
          </div>

          <div className="field">
            <label htmlFor="print-event">{t('print.event')}</label>
            <select
              id="print-event"
              value={eventId}
              onChange={e => setEventId(Number(e.target.value))}
            >
              <option value={0}>{t('print.allEvents')}</option>
              {events.map(event => (
                <option key={event.id} value={event.id}>
                  {event.name}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="pill-actions mt-3">
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy === 'all' || totalHeats === 0 || grade !== ''}
            onClick={handleDownloadAll}
          >
            {busy === 'all' ? t('common.downloading') : t('print.downloadAll')}
          </button>
          <span className="muted">
            {t('print.matchingCount', { count: matches.length, heats: totalHeats })}
          </span>
          {groupsLoading && <span className="muted">{t('common.loading')}</span>}
        </div>
        {/* `GET /sheets.pdf` narrows by division, category and event only, so a
            grade filter cannot be honoured by the whole-run download. Say so
            rather than downloading more grades than the count above implies. */}
        {grade !== '' && <p className="muted mt-2">{t('print.gradeDownloadHint')}</p>}

        <div className="hint mt-3">{t('print.columns')}</div>
      </div>

      {/* PDF preview — opened per event, never printed */}
      {preview && (
        <div className="card no-print">
          <div className="flex justify-between items-center">
            <h2>{t('print.previewTitle', { name: preview.name })}</h2>
            <div className="flex gap-2">
              <button
                type="button"
                className="btn btn-sm btn-primary"
                onClick={handlePrintPreview}
              >
                {t('common.print')}
              </button>
              <button type="button" className="btn btn-sm btn-secondary" onClick={closePreview}>
                {t('common.close')}
              </button>
            </div>
          </div>
          <iframe
            ref={iframeRef}
            src={preview.url}
            title={t('print.previewTitle', { name: preview.name })}
            style={{ width: '100%', height: '70vh', border: '1px solid #dfe2ea', borderRadius: 8 }}
          />
          <p className="muted mt-2">{t('print.browserPrint')}</p>
        </div>
      )}

      {loading ? (
        <p className="muted">{t('common.loading')}</p>
      ) : matches.length === 0 ? (
        <div className="empty">
          <p>{t('print.noMatching')}</p>
          <p className="muted">{t('print.needHeats')}</p>
        </div>
      ) : (
        <div className="print-panel">
          {matches.map(event => {
            const allGroups = groups[event.id] ?? [];
            const heatList = allGroups.filter(group => group.stage !== 'FINAL');
            const finalGroup = allGroups.find(group => group.stage === 'FINAL') ?? null;
            const heatCount = heatsOf(event);
            const athleteCount = heatList.reduce(
              (sum, group) => sum + (group.athleteCount || 0),
              0
            );
            const hasSheets = heatCount > 0 || finalGroup !== null;

            return (
              <div key={event.id} className="print-card">
                <h3>
                  {event.name}
                  <span
                    className="badge badge-info"
                    style={{ marginLeft: '0.5rem' }}
                    title={label('grade', event.grade)}
                  >
                    {label('grade.short', event.grade)}
                  </span>
                </h3>
                <div className="print-meta">
                  {event.typeLabel} · {label('sex', event.sex)} · {label('category', event.category)}{' '}
                  · {t('events.sheet')} {label('sheet', event.sheetSize)}
                  {!event.enabled && (
                    <>
                      {' · '}
                      <span className="badge badge-danger">{t('adminEvents.disabled')}</span>
                    </>
                  )}
                </div>

                {!hasSheets ? (
                  <>
                    <p className="muted">{t('print.noHeats')}</p>
                    <p className="muted">{t('print.needHeats')}</p>
                    <Link
                      href={`/admin/events/${event.id}/groups`}
                      className="btn btn-sm btn-secondary no-print"
                    >
                      {t('groups.allocate')}
                    </Link>
                  </>
                ) : (
                  <>
                    <div className="print-meta">
                      {t('print.heats')}: {heatCount} · {t('print.athletes')}: {athleteCount}
                      {finalGroup && (
                        <> · {t('print.finalCount', { count: finalGroup.athleteCount })}</>
                      )}
                    </div>

                    <div className="pill-actions no-print">
                      <button
                        type="button"
                        className="btn btn-sm btn-primary"
                        disabled={busy === `event-${event.id}`}
                        onClick={() => handleEventDownload(event)}
                      >
                        {busy === `event-${event.id}`
                          ? t('common.downloading')
                          : t('print.downloadEvent')}
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm btn-secondary"
                        disabled={busy === `preview-${event.id}`}
                        onClick={() => handlePreview(event)}
                      >
                        {busy === `preview-${event.id}` ? t('common.loading') : t('print.preview')}
                      </button>
                    </div>

                    {(heatList.length > 0 || finalGroup) && (
                      <div className="pill-actions mt-2 no-print">
                        {heatList.map(group => (
                          <button
                            key={group.id}
                            type="button"
                            className="btn btn-sm btn-secondary"
                            disabled={busy === `heat-${group.id}`}
                            onClick={() => handleHeatDownload(event, group)}
                          >
                            {busy === `heat-${group.id}`
                              ? t('common.downloading')
                              : t('print.downloadHeat', { label: group.label })}
                            <span className="badge badge-info" style={{ marginLeft: '0.4rem' }}>
                              {group.athleteCount}
                            </span>
                          </button>
                        ))}
                        {finalGroup && (
                          <button
                            key={finalGroup.id}
                            type="button"
                            className="btn btn-sm btn-primary"
                            disabled={busy === `heat-${finalGroup.id}`}
                            onClick={() => handleHeatDownload(event, finalGroup)}
                          >
                            {busy === `heat-${finalGroup.id}`
                              ? t('common.downloading')
                              : t('print.downloadHeat', { label: finalGroup.label })}
                            <span className="badge badge-info" style={{ marginLeft: '0.4rem' }}>
                              {finalGroup.athleteCount}
                            </span>
                            <span className="badge badge-success" style={{ marginLeft: '0.4rem' }}>
                              {finalGroup.stageLabel}
                            </span>
                          </button>
                        )}
                      </div>
                    )}
                  </>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
