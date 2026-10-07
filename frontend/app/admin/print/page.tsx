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
  FinalState,
  finalStateForEvent,
  Grade,
  isRelayEvent,
  SexCode,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { EventFilters } from '@/components/EventFilters';
import { useI18n } from '@/lib/i18n';

/** Same fallback as `lib/api.ts`; the constant itself is not exported. */
const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api';

/** Short division code the `?sex=` query parameter expects. */
const SEX_CODE: Record<EventSex, SexCode> = { MALE: 'M', FEMALE: 'F' };

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
  /**
   * The event type the list is narrowed to. The whole-run download takes no
   * type either, so this narrows the list and the per-event downloads, exactly
   * as the grade does.
   */
  const [eventType, setEventType] = useState('');
  /**
   * Whether each event's final has been drawn, worked out from its group list.
   * An event that runs a final but has not drawn it cannot have its sheets
   * printed yet — the server refuses the whole run with a 409, and printing
   * only the heats would look like the final's sheet had gone missing.
   */
  const [finalStates, setFinalStates] = useState<Record<number, FinalState>>({});

  const [preview, setPreview] = useState<Preview | null>(null);
  const iframeRef = useRef<HTMLIFrameElement | null>(null);
  /** The blob URL currently mounted, so it can be revoked exactly once. */
  const previewUrlRef = useRef<string | null>(null);
  /** Events whose heat list has already been cached. */
  const cachedGroupEvents = useRef<Set<number>>(new Set());

  // Printing marking sheets is the input helper's other job, so they are staff here
  // even though they are nothing else anywhere else.
  const isStaff = user?.role === 'ADMIN' || user?.role === 'MANAGER'
    || user?.role === 'HELPER';

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
      /*
       * Disabled events are included: an event closed to new entries may still
       * need its marking sheets printed. **Relays are not** — and they are left
       * out here, at the one place the page reads the programme, so they cannot
       * reappear in the list, in its counts, in its filters or in the whole-run
       * download below.
       *
       * This page prints the individual athlete's paper: one line per athlete,
       * one sheet per heat. A relay is run and scored by *team*, its sheet is one
       * line per team, and it is printed from the relay programme page — from each
       * relay's own card, or all of them in one press — which is
       * also where its marks are keyed in. The whole-run download asks the server
       * for the same thing (`includeRelays=false`) and the page says so out loud,
       * so nothing is omitted behind the reader's back.
       */
      const all = await api.getEvents({ onlyEnabled: false });
      setEvents(all.filter(event => !isRelayEvent(event)));
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

  /**
   * The events the four filters let through — the whole of what this page lists,
   * counts, offers and downloads. No relay is ever among them: `loadEvents` left
   * every relay out, so nothing here has to ask again, and the type filter built
   * from this list can never offer a relay distance.
   */
  const matches = useMemo(
    () =>
      events.filter(
        event =>
          (!sex || SEX_CODE[event.sex] === sex) &&
          (!category || event.category === category) &&
          (!grade || event.grade === grade) &&
          (!eventType || event.type === eventType)
      ),
    [events, sex, category, grade, eventType]
  );

  /**
   * Heat lists, fetched once per event and cached. `groupCount` on the event DTO
   * says up front which events actually have heats, so an ungrouped event costs
   * no request at all.
   */
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
      /*
       * The group list answers the one question this page has to ask before it
       * offers a sheet: has this event's final been drawn? A final is group
       * number 0 with the `FINAL` stage, so its presence is the draw. An event
       * that cannot be split, or that runs straight to a final, is settled by
       * its own type without looking at any group.
       */
      setFinalStates(prev => {
        const next = { ...prev };
        fetched.forEach(([id, list]) => {
          const event = matches.find(candidate => candidate.id === id);
          if (!event) return;
          const drawn = list.some(group => group.stage === 'FINAL');
          const state = finalStateForEvent(event, { finalDrawn: drawn });
          if (state) next[id] = state;
        });
        return next;
      });
      setGroupsLoading(false);
    })();

    return () => {
      cancelled = true;
    };
  }, [matches]);

  const finalStateOf = useCallback(
    (event: EventDTO): FinalState | null =>
      finalStates[event.id] ?? finalStateForEvent(event, { finalDrawn: undefined }),
    [finalStates]
  );

  /** The events whose final is still to come: their sheets cannot print yet. */
  const awaitingFinal = useMemo(
    () => matches.filter(event => finalStateOf(event) === 'NOT_DRAWN'),
    [matches, finalStateOf]
  );

  /**
   * Heats only — a drawn final is a seventh group with `groupNumber: 0`, so it
   * must not be counted as a heat. `groupCount` on the event DTO counts every
   * real group including the final, so it is only the fallback for the moment
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

  /**
   * The whole-programme download: one page per heat, narrowed by the division and
   * the category the filters are on, and **without the relays**.
   *
   * `includeRelays=false` is asked for on purpose rather than left to the server.
   * A relay's paper is one line per team and is printed from the relay programme
   * page — a card at a time, or the whole list in one press — so this page prints
   * the individual events only, and the file it hands
   * over is exactly the list above it rather than a programme that quietly
   * differs from what the count says. The control and the page both say so
   * (`print.relayNote`), because a print run that omits a relay must be a
   * decision the reader can see, never a side effect they discover on the day.
   */
  const handleDownloadAll = async () => {
    setBusy('all');
    setError(null);
    setNotice(null);
    try {
      const filename = await api.downloadAllSheets('sportday-marking-sheets.pdf', {
        sex: sex || undefined,
        category: category || undefined,
        includeRelays: false,
      });
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
      // The preview is named the way the paper under it is headed: the event's own
      // name, which is the line the PDF prints, so the two cannot disagree on one
      // screen.
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

        {/*
          Sex, grade, category and event, and nothing else. The event control
          filters the run rather than picking one: `?sheets.pdf` narrows by
          division, category and event, so a grade or type is honoured by the
          per-event downloads below — see `print.gradeDownloadHint`.
        */}
        <div className="grid-toolbar mt-2">
          <EventFilters
            idPrefix="print"
            value={{ sex, grade, category, event: eventType }}
            onChange={(control, value) => {
              if (control === 'sex') setSex(value as DivisionFilter);
              else if (control === 'grade') setGrade(value as Grade | '');
              else if (control === 'category') setCategory(value as CategoryFilter);
              else setEventType(value);
            }}
            events={events}
          />
        </div>

        <div className="pill-actions mt-3">
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy === 'all' || totalHeats === 0 || grade !== '' || awaitingFinal.length > 0}
            title={awaitingFinal.length > 0 ? t('print.allHeldBack') : undefined}
            onClick={handleDownloadAll}
          >
            {busy === 'all' ? t('common.downloading') : t('print.downloadAll')}
          </button>
          <span className="muted">
            {t('print.matchingCount', { count: matches.length, heats: totalHeats })}
          </span>
          {groupsLoading && <span className="muted">{t('common.loading')}</span>}
        </div>
        {/* `GET /sheets.pdf` is refused whole when any event it covers is still
            waiting for its final, so the batch is held back with the reason
            rather than left to fail with a 409. */}
        {awaitingFinal.length > 0 && <p className="muted mt-2">{t('print.allHeldBack')}</p>}
        {/* `GET /sheets.pdf` narrows by division, category and event only, so a
            grade filter cannot be honoured by the whole-run download. Say so
            rather than downloading more grades than the count above implies. */}
        {grade !== '' && <p className="muted mt-2">{t('print.gradeDownloadHint')}</p>}
        {/*
          The relays are not on this page and not in this file, and that is said
          here rather than left to be noticed: a reader who prints the whole
          programme must know that the relays are printed from their own page,
          and where that page is. Without this the relays would simply be missing
          from the run, which reads as a broken page or — worse — as a programme
          with no relays in it.
        */}
        <p className="muted mt-2">
          {t('print.relayNote')}{' '}
          <Link href="/admin/relay-events/form" className="btn btn-sm btn-secondary no-print">
            {t('print.openRelays')}
          </Link>
        </p>

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

      {/*
        The final waits for the heat results. An event whose draw has not run is
        held back whole, because the server refuses its print run with a 409 —
        printing only the heats would look like the final's sheet had simply gone
        missing. Its individual heat sheets are still offered.
      */}
      {awaitingFinal.length > 0 && (
        <div className="alert alert-error no-print">
          <strong>{t('print.finalNotDrawn')}</strong>
          <div className="mt-2">{t('print.finalNotDrawnHint')}</div>
          <ul className="mt-2">
            {awaitingFinal.map(event => (
              <li key={event.id}>
                {event.name} — {t('final.state.NOT_DRAWN')}
              </li>
            ))}
          </ul>
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
            /*
             * An event that runs a final and has not drawn it yet: its whole-run
             * sheet — and so the print run — is refused by the server with a 409,
             * and the same rule covers its preview. Heat sheets stay available.
             */
            const awaitingFinalDraw = finalStateOf(event) === 'NOT_DRAWN';
            const heldBack = awaitingFinalDraw && !finalGroup && heatCount > 0;

            return (
              <div key={event.id} className="print-card">
                <h3>
                  {/*
                    The event's own name: this card stands for the sheets below it,
                    and those sheets are headed by the same name. The grade badge
                    beside it is the event's own grade.
                  */}
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
                        disabled={busy === `event-${event.id}` || heldBack}
                        title={heldBack ? t('print.finalNotDrawnHint') : undefined}
                        onClick={() => handleEventDownload(event)}
                      >
                        {busy === `event-${event.id}`
                          ? t('common.downloading')
                          : t('print.downloadEvent')}
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm btn-secondary"
                        disabled={busy === `preview-${event.id}` || heldBack}
                        title={heldBack ? t('print.finalNotDrawnHint') : undefined}
                        onClick={() => handlePreview(event)}
                      >
                        {busy === `preview-${event.id}` ? t('common.loading') : t('print.preview')}
                      </button>
                    </div>

                    {/*
                      The reason, next to the controls it applies to, and not only
                      a greyed button: the heat results come first. A run that is
                      not held back is still covered by the server's own 409,
                      which the error banner shows as it stands.
                    */}
                    {heldBack && (
                      <p className="muted mt-2">
                        {t('print.finalNotDrawn')} {t('print.finalNotDrawnHint')}
                      </p>
                    )}

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
