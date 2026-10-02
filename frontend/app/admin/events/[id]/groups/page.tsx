'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import {
  api,
  EnrollmentDTO,
  EventDTO,
  EventGroupDTO,
  FinalSummaryDTO,
} from '@/lib/api';
import { formatDate } from '@/lib/format';
import { useI18n } from '@/lib/i18n';

/** Turns an event/heat name into something safe for a download filename. */
function slug(value: string): string {
  const cleaned = value
    .replace(/[^\w.-]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return cleaned || 'event';
}

/** The lane roster shared by the heat cards and the final card. */
function RosterTable({ athletes }: { athletes: EnrollmentDTO[] }) {
  const { t, label } = useI18n();
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t('my.lane')}</th>
            <th>{t('students.colId')}</th>
            <th>{t('students.colName')}</th>
            <th>{t('marks.grade')}</th>
            <th>{t('marks.class')}</th>
            <th>{t('students.colHouse')}</th>
          </tr>
        </thead>
        <tbody>
          {[...athletes]
            .sort((a, b) => (a.lane ?? 0) - (b.lane ?? 0))
            .map(athlete => (
              <tr key={athlete.id}>
                <td>{athlete.lane ?? '-'}</td>
                <td>{athlete.studentRef}</td>
                <td>{athlete.name}</td>
                <td>{label('grade', athlete.grade)}</td>
                <td>
                  {athlete.className} {athlete.classNumber}
                </td>
                <td>{athlete.house || '-'}</td>
              </tr>
            ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Fetches heat rosters with bounded concurrency — `GET /events/{id}/groups`
 * deliberately returns no athletes, so each heat needs its own request.
 */
async function fetchRosters(
  groups: EventGroupDTO[]
): Promise<Record<number, EnrollmentDTO[]>> {
  const rosters: Record<number, EnrollmentDTO[]> = {};
  const concurrency = Math.min(6, groups.length) || 1;
  let cursor = 0;

  const worker = async () => {
    while (cursor < groups.length) {
      const group = groups[cursor];
      cursor += 1;
      try {
        const detail = await api.getGroup(group.id);
        rosters[group.id] = detail.athletes || [];
      } catch {
        rosters[group.id] = [];
      }
    }
  };

  await Promise.all(Array.from({ length: concurrency }, worker));
  return rosters;
}

export default function EventGroupsPage() {
  const params = useParams();
  const { t, label } = useI18n();
  const eventId = Number(params.id);

  const [event, setEvent] = useState<EventDTO | null>(null);
  const [groups, setGroups] = useState<EventGroupDTO[]>([]);
  const [finalSummary, setFinalSummary] = useState<FinalSummaryDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [rostersLoading, setRostersLoading] = useState(false);
  const [shuffle, setShuffle] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [finalBusy, setFinalBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const loadRosters = useCallback(async (list: EventGroupDTO[]) => {
    if (list.length === 0) {
      setGroups([]);
      return;
    }
    setRostersLoading(true);
    try {
      const rosters = await fetchRosters(list);
      setGroups(list.map(group => ({ ...group, athletes: rosters[group.id] || [] })));
    } finally {
      setRostersLoading(false);
    }
  }, []);

  /** Re-reads the group list without blanking the whole page. */
  const reloadGroups = useCallback(async () => {
    const groupList = await api.getEventGroups(eventId);
    setGroups(groupList);
    await loadRosters(groupList);
  }, [eventId, loadRosters]);

  /**
   * The final preview also reports whether a final already exists, so it is
   * read on load as well as by the "Preview the final" button.
   */
  const loadFinal = useCallback(async () => {
    try {
      setFinalSummary(await api.previewFinal(eventId));
    } catch {
      setFinalSummary(null);
    }
  }, [eventId]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [eventResult, groupList] = await Promise.all([
        api.getEvent(eventId),
        api.getEventGroups(eventId),
      ]);
      setEvent(eventResult);
      setGroups(groupList);
      if (eventResult.shortSprint) await loadFinal();
      await loadRosters(groupList);
    } catch (err: any) {
      setError(err?.message || t('groups.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, [eventId, loadFinal, loadRosters]);

  useEffect(() => {
    if (Number.isFinite(eventId) && eventId > 0) load();
  }, [eventId, load]);

  /** Heats are everything except the final, which is its own panel below. */
  const heatGroups = useMemo(
    () => groups.filter(group => group.stage !== 'FINAL'),
    [groups]
  );
  const finalGroup = useMemo(
    () => groups.find(group => group.stage === 'FINAL') ?? null,
    [groups]
  );
  /** The group list is authoritative; the summary is the fallback. */
  const finalDrawn = finalGroup !== null || finalSummary?.drawn === true;

  const handleAllocate = async () => {
    setBusy('allocate');
    setError(null);
    setNotice(null);
    try {
      const result = await api.allocateGroups(eventId, shuffle);
      setNotice(
        `${t('groups.allocated', { count: result.groupCount })}${
          result.shuffle ? ` ${t('groups.shuffledSuffix')}` : ''
        }`
      );
      // Re-read rather than trusting the allocate payload: the event may also
      // carry a final group, which the heat allocation does not describe.
      await reloadGroups();
      await loadFinal();
    } catch (err: any) {
      setError(err?.message || t('groups.allocateFailed'));
    } finally {
      setBusy(null);
    }
  };

  const handleClear = async () => {
    if (!confirm(t('groups.clearConfirm'))) return;
    setBusy('clear');
    setError(null);
    setNotice(null);
    try {
      await api.clearEventGroups(eventId);
      await reloadGroups();
      await loadFinal();
      setNotice(t('groups.cleared'));
    } catch (err: any) {
      setError(err?.message || t('groups.clearFailed'));
    } finally {
      setBusy(null);
    }
  };

  const handleDownloadHeat = async (group: EventGroupDTO) => {
    setBusy(`heat-${group.id}`);
    setError(null);
    setNotice(null);
    try {
      const filename = await api.downloadGroupSheet(
        group.id,
        `${slug(event?.name || 'event')}-${slug(group.label)}-${group.sheetSize}.pdf`
      );
      setNotice(t('common.downloaded', { filename }));
    } catch (err: any) {
      setError(err?.message || t('groups.sheetDownloadFailed'));
    } finally {
      setBusy(null);
    }
  };

  /** Ranked list of who would go through — changes nothing on the server. */
  const handleFinalPreview = async () => {
    setFinalBusy('final-preview');
    setError(null);
    setNotice(null);
    try {
      setFinalSummary(await api.previewFinal(eventId));
    } catch (err: any) {
      setError(err?.message || t('groups.finalPreviewFailed'));
    } finally {
      setFinalBusy(null);
    }
  };

  /**
   * Drawing is safe the first time; re-drawing throws away whatever has been
   * recorded in the final, so it is confirmed first.
   */
  const handleFinalDraw = async () => {
    if (finalDrawn && !confirm(t('groups.finalRedrawConfirm'))) return;
    setFinalBusy('final-draw');
    setError(null);
    setNotice(null);
    try {
      const summary = await api.drawFinal(eventId);
      setFinalSummary(summary);
      const cleared = summary.clearedFinalMarks || 0;
      setNotice(
        `${t('groups.finalDrawnNotice', { count: summary.qualified })}${
          cleared > 0 ? ` ${t('groups.finalMarksCleared', { count: cleared })}` : ''
        }`
      );
      await reloadGroups();
    } catch (err: any) {
      setError(err?.message || t('groups.finalDrawFailed'));
    } finally {
      setFinalBusy(null);
    }
  };

  const handleFinalRemove = async () => {
    if (!confirm(t('groups.finalRemoveConfirm'))) return;
    setFinalBusy('final-remove');
    setError(null);
    setNotice(null);
    try {
      const removal = await api.removeFinal(eventId);
      const cleared = removal.finalMarksCleared || 0;
      setNotice(
        `${t('groups.finalRemovedNotice')}${
          cleared > 0 ? ` ${t('groups.finalMarksCleared', { count: cleared })}` : ''
        }`
      );
      await reloadGroups();
      await loadFinal();
    } catch (err: any) {
      setError(err?.message || t('groups.finalRemoveFailed'));
    } finally {
      setFinalBusy(null);
    }
  };

  const handleDownloadAll = async () => {
    setBusy('all-sheets');
    setError(null);
    setNotice(null);
    try {
      const filename = await api.downloadEventSheets(
        eventId,
        `${slug(event?.name || 'event')}-all-heats-${event?.sheetSize || 'A4'}.pdf`
      );
      setNotice(t('common.downloaded', { filename }));
    } catch (err: any) {
      setError(err?.message || t('groups.sheetDownloadFailed'));
    } finally {
      setBusy(null);
    }
  };

  if (loading) {
    return (
      <div className="text-center" style={{ padding: '3rem 0' }}>
        <p>{t('groups.loadingEvent')}</p>
      </div>
    );
  }

  if (!event) {
    return (
      <div>
        {error && <div className="alert alert-error">{error}</div>}
        <div className="card text-center">
          <p>{t('events.notFound')}</p>
          <Link href="/admin/events" className="btn btn-secondary mt-2">
            {t('common.backToEvents')}
          </Link>
        </div>
      </div>
    );
  }

  const layout =
    event.groupSize === 8 && event.sheetSize === 'A5'
      ? t('groups.layoutPreset', { groupSize: 8, sheet: label('sheet', 'A5') })
      : event.groupSize === 24 && event.sheetSize === 'A4'
        ? t('groups.layoutPreset', { groupSize: 24, sheet: label('sheet', 'A4') })
        : t('groups.layoutPreset', {
            groupSize: event.groupSize,
            sheet: label('sheet', event.sheetSize),
          });

  const allocatedAthletes = heatGroups.reduce(
    (sum, group) => sum + (group.athletes?.length || 0),
    0
  );

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('groups.heading')}</h1>
        <div className="flex gap-2">
          <Link href="/admin/events" className="btn btn-secondary">
            {t('adminEvents.allEvents')}
          </Link>
          <Link href={`/events/${event.id}`} className="btn btn-secondary">
            {t('events.event')}
          </Link>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {notice && <div className="alert alert-success">{notice}</div>}

      <div className="card">
        <div className="flex justify-between items-start">
          <div>
            <h2>{event.name}</h2>
            <div className="flex gap-2 mt-2">
              <span className="badge badge-info">{event.typeLabel}</span>
              <span
                className={event.category === 'TRACK' ? 'badge badge-danger' : 'badge badge-info'}
              >
                {label('category', event.category)}
              </span>
              <span className="badge badge-info">{label('sex', event.sex)}</span>
              <span className="badge badge-info">{label('sheet', event.sheetSize)}</span>
              <span className={event.enabled ? 'badge badge-success' : 'badge badge-danger'}>
                {event.enabled ? t('adminEvents.enabled') : t('adminEvents.disabled')}
              </span>
            </div>
          </div>
        </div>

        <div className="event-meta mt-3">
          <div>
            <strong>{t('events.date')}:</strong> {formatDate(event.eventDate)}
          </div>
          <div>
            <strong>{t('events.place')}:</strong> {event.location || '-'}
          </div>
          <div>
            <strong>{t('groups.sheetFormat')}:</strong> {layout}
          </div>
          <div>
            <strong>{t('events.entries')}:</strong> {event.enrolledCount} / {event.maxParticipants}
          </div>
          <div>
            <strong>{t('print.heats')}:</strong> {heatGroups.length}
            {event.ungroupedCount > 0 && (
              <span className="badge badge-warning" style={{ marginLeft: '0.5rem' }}>
                {t('adminEvents.ungrouped')} {event.ungroupedCount}
              </span>
            )}
          </div>
        </div>

        <div className="pill-actions mt-3">
          <label className="checkbox-line">
            <input
              type="checkbox"
              checked={shuffle}
              onChange={e => setShuffle(e.target.checked)}
            />
            {t('groups.shuffleAthletes')}
          </label>
          <button
            type="button"
            className="btn btn-sm btn-primary"
            disabled={busy === 'allocate'}
            onClick={handleAllocate}
          >
            {busy === 'allocate' ? t('groups.allocating') : t('groups.allocate')}
          </button>
          <button
            type="button"
            className="btn btn-sm btn-danger"
            disabled={busy === 'clear' || heatGroups.length === 0}
            onClick={handleClear}
          >
            {busy === 'clear' ? t('groups.clearing') : t('groups.clear')}
          </button>
          <button
            type="button"
            className="btn btn-sm btn-success"
            disabled={busy === 'all-sheets' || heatGroups.length === 0}
            onClick={handleDownloadAll}
          >
            {busy === 'all-sheets'
              ? t('common.downloading')
              : t('groups.downloadAllSheets', { sheet: label('sheet', event.sheetSize) })}
          </button>
        </div>
      </div>

      {rostersLoading && <p className="muted">{t('groups.loadingRosters')}</p>}

      {heatGroups.length === 0 ? (
        <div className="card text-center">
          <p className="muted">{t('groups.noGroups')}</p>
          <p className="muted">{t('groups.noGroupsHint', { groupSize: event.groupSize })}</p>
        </div>
      ) : (
        <>
          <p className="muted mt-2">
            {t('groups.allocatedSummary', {
              athletes: allocatedAthletes,
              groups: heatGroups.length,
            })}
          </p>
          {heatGroups.map(group => (
            <div key={group.id} className="card">
              <div className="flex justify-between items-center">
                <div className="flex gap-2 items-center">
                  <h3>{group.label}</h3>
                  <span className="badge badge-info">
                    {group.athleteCount} / {group.capacity}
                  </span>
                  <span className="badge badge-info">{label('sheet', group.sheetSize)}</span>
                </div>
                <button
                  type="button"
                  className="btn btn-sm btn-primary"
                  disabled={busy === `heat-${group.id}`}
                  onClick={() => handleDownloadHeat(group)}
                >
                  {busy === `heat-${group.id}`
                    ? t('common.downloading')
                    : t('groups.downloadSheet', { sheet: label('sheet', group.sheetSize) })}
                </button>
              </div>

              {group.athletes && group.athletes.length > 0 ? (
                <RosterTable athletes={group.athletes} />
              ) : (
                <p className="muted mt-2">{t('groups.noAthletes')}</p>
              )}
            </div>
          ))}
        </>
      )}

      {/* Short sprints get a final, drawn from the top finishers in the heats. */}
      {event.shortSprint && (
        <div className="card">
          <div className="flex justify-between items-center">
            <div className="flex gap-2 items-center">
              <h2>{t('groups.finalTitle')}</h2>
              <span className={finalDrawn ? 'badge badge-success' : 'badge badge-warning'}>
                {finalDrawn ? t('groups.finalDrawn') : t('groups.finalNotDrawn')}
              </span>
              {finalGroup && <span className="badge badge-info">{finalGroup.stageLabel}</span>}
            </div>
            <div className="flex gap-2">
              {/* The preview lists who would qualify, which only matters until
                  the final has been drawn — after that the panel shows it. */}
              {!finalDrawn && (
                <button
                  type="button"
                  className="btn btn-sm btn-secondary"
                  disabled={finalBusy === 'final-preview'}
                  onClick={handleFinalPreview}
                >
                  {finalBusy === 'final-preview'
                    ? t('groups.finalPreviewing')
                    : t('groups.finalPreview')}
                </button>
              )}
              <button
                type="button"
                className="btn btn-sm btn-primary"
                disabled={finalBusy === 'final-draw'}
                onClick={handleFinalDraw}
              >
                {finalBusy === 'final-draw'
                  ? t('groups.finalDrawing')
                  : finalDrawn
                    ? t('groups.finalRedraw')
                    : t('groups.finalDraw')}
              </button>
              {finalDrawn && (
                <button
                  type="button"
                  className="btn btn-sm btn-danger"
                  disabled={finalBusy === 'final-remove'}
                  onClick={handleFinalRemove}
                >
                  {finalBusy === 'final-remove'
                    ? t('groups.clearing')
                    : t('groups.finalRemove')}
                </button>
              )}
            </div>
          </div>

          <p className="muted mt-2">
            {t('groups.finalHint', { count: finalSummary?.finalSize ?? event.groupSize })}
          </p>

          {finalDrawn && finalGroup ? (
            <>
              <div className="flex justify-between items-center mt-2">
                <div className="flex gap-2 items-center">
                  <h3>{finalGroup.label}</h3>
                  <span className="badge badge-info">
                    {finalGroup.athleteCount} / {finalGroup.capacity}
                  </span>
                  <span className="badge badge-info">{label('sheet', finalGroup.sheetSize)}</span>
                </div>
                <button
                  type="button"
                  className="btn btn-sm btn-primary"
                  disabled={busy === `heat-${finalGroup.id}`}
                  onClick={() => handleDownloadHeat(finalGroup)}
                >
                  {busy === `heat-${finalGroup.id}`
                    ? t('common.downloading')
                    : t('groups.downloadSheet', { sheet: label('sheet', finalGroup.sheetSize) })}
                </button>
              </div>

              {finalGroup.athletes && finalGroup.athletes.length > 0 ? (
                <RosterTable athletes={finalGroup.athletes} />
              ) : (
                <p className="muted mt-2">{t('groups.noAthletes')}</p>
              )}
            </>
          ) : (
            <>
              <h3 className="mt-2">{t('groups.qualifiers')}</h3>
              {finalSummary && finalSummary.qualifiers.length > 0 ? (
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>{t('marks.rank')}</th>
                        <th>{t('students.colId')}</th>
                        <th>{t('students.colName')}</th>
                        <th>{t('marks.grade')}</th>
                        <th>{t('marks.class')}</th>
                        <th>{t('groups.heatMark')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {finalSummary.qualifiers.map(qualifier => (
                        <tr key={qualifier.userId}>
                          <td>{qualifier.rank}</td>
                          <td>{qualifier.studentRef}</td>
                          <td>{qualifier.name}</td>
                          <td>{label('grade', qualifier.grade)}</td>
                          <td>
                            {[qualifier.className, qualifier.classNumber].filter(Boolean).join(' ')}
                          </td>
                          <td>
                            {qualifier.heatMark} {label('unit', qualifier.unit)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className="muted">{t('groups.finalNoQualifiers')}</p>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}
