'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  api,
  defaultUnitForCategory,
  EnrollmentDTO,
  EventCategory,
  EventDateDTO,
  EventDTO,
  gradeMatchesEvent,
  QuotaDTO,
  SeasonDTO,
  SexCode,
  UserDTO,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { formatDate } from '@/lib/format';
import { useI18n } from '@/lib/i18n';

type MessageKind = 'error' | 'success';
type Message = { kind: MessageKind; text: string };
const SECTION_ORDER: EventCategory[] = ['TRACK', 'FIELD', 'RELAY'];

function toSexCode(gender?: string): SexCode | '' {
  return gender === 'M' || gender === 'F' ? gender : '';
}

export default function EventsPage() {
  const { user, isLoading } = useAuth();
  const { t, label } = useI18n();
  const router = useRouter();

  const [me, setMe] = useState<UserDTO | null>(null);
  const [events, setEvents] = useState<EventDTO[]>([]);
  const [quota, setQuota] = useState<QuotaDTO | null>(null);
  const [enrolledIds, setEnrolledIds] = useState<number[]>([]);
  /**
   * The signed-in student's grade, read from their own profile — `GET /users/me`
   * carries it, so it is right even before the student has entered anything.
   *
   * An event belongs to exactly one grade and the server refuses a student's
   * entry to another grade with a 409, so the programme is narrowed to the
   * student's own grade below. A student whose profile carries no grade sees
   * everything, and the server is left as the guard.
   */
  const [myGrade, setMyGrade] = useState('');
  const [sexFilter, setSexFilter] = useState<SexCode | ''>('');
  const [dateFilter, setDateFilter] = useState('');
  const [dates, setDates] = useState<EventDateDTO[]>([]);
  /** The school years, and which one the programme is being read for. */
  const [seasons, setSeasons] = useState<SeasonDTO[]>([]);
  const [seasonId, setSeasonId] = useState<number | ''>('');
  /** False until the year to open on has been decided, so the first load is the
   *  current year rather than every year at once. */
  const [seasonReady, setSeasonReady] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [message, setMessage] = useState<Message | null>(null);

  const isStudent = me?.role === 'STUDENT';
  // A student only ever sees their own division; staff may pick one.
  const effectiveSex: SexCode | '' = isStudent ? toSexCode(me?.gender) : sexFilter;

  const selectedSeason = useMemo(
    () => seasons.find(season => season.id === seasonId) || null,
    [seasons, seasonId]
  );

  const genderLabel = (sex: SexCode | ''): string =>
    sex === '' ? t('common.all') : label('sex', sex === 'M' ? 'MALE' : 'FEMALE');

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    api.getCurrentUser().then(setMe).catch(() => setMe(null));
  }, [user, isLoading, router]);

  useEffect(() => {
    if (!user || !me) return;
    api
      .getEventDates()
      .then(setDates)
      .catch(() => setDates([]));
  }, [user, me]);

  // The school years. `?seasonId=` wins; otherwise the page opens on the current
  // year, which is the one a student can actually enter.
  useEffect(() => {
    if (!user || !me) return;
    let cancelled = false;
    (async () => {
      const list = await api.getSeasons().catch(() => [] as SeasonDTO[]);
      if (cancelled) return;
      setSeasons(list);
      const fromUrl = Number(new URLSearchParams(window.location.search).get('seasonId'));
      if (fromUrl && list.some(season => season.id === fromUrl)) {
        setSeasonId(fromUrl);
      } else {
        setSeasonId(list.find(season => season.current)?.id ?? '');
      }
      setSeasonReady(true);
    })();
    return () => {
      cancelled = true;
    };
  }, [user, me]);

  const loadEvents = useCallback(
    async (sex: SexCode | '', date: string, season: number | '') => {
      const list = await api.getEvents({
        onlyEnabled: true,
        sex,
        date: date || undefined,
        seasonId: season === '' ? undefined : season,
      });
      setEvents(list);
    },
    []
  );

  useEffect(() => {
    if (!user || !me || !seasonReady) return;
    let cancelled = false;

    (async () => {
      setLoading(true);
      try {
        // 徑項/田項 are split client-side from each event's own `category`
        // field, so one request covers both sections. The date and the school
        // year narrow the programme without replacing the division filter.
        // The student's grade comes from their own profile, which reads it off the
        // register — so it is right even before they have entered anything.
        const [list, quotaResult, mine] = await Promise.all([
          api.getEvents({
            onlyEnabled: true,
            sex: effectiveSex,
            date: dateFilter || undefined,
            seasonId: seasonId === '' ? undefined : seasonId,
          }),
          api.getMyQuota().catch(() => null),
          api.getMyEnrollments().catch(() => [] as EnrollmentDTO[]),
        ]);
        if (cancelled) return;
        setEvents(list);
        setQuota(quotaResult);
        setEnrolledIds(mine.map(entry => entry.eventId));
        setMyGrade(me?.role === 'STUDENT' ? me.grade ?? '' : '');
      } catch (err: any) {
        if (!cancelled) {
          setMessage({ kind: 'error', text: err?.message || t('events.loadFailed') });
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [user, me, effectiveSex, dateFilter, seasonId, seasonReady]);

  /** Re-reads the quota and entry counts after a successful change. */
  const refreshAfterChange = useCallback(async () => {
    const [quotaResult, mine] = await Promise.all([
      api.getMyQuota().catch(() => null),
      api.getMyEnrollments().catch(() => [] as EnrollmentDTO[]),
    ]);
    setQuota(quotaResult);
    setEnrolledIds(mine.map(entry => entry.eventId));
    await loadEvents(effectiveSex, dateFilter, seasonId).catch(() => {});
  }, [effectiveSex, dateFilter, seasonId, loadEvents]);

  const handleEnroll = async (event: EventDTO) => {
    setBusyId(event.id);
    setMessage(null);
    try {
      await api.enroll(event.id);
      setMessage({
        kind: 'success',
        text: t('events.enteredNotice', { type: event.typeLabel, name: event.name }),
      });
    } catch (err: any) {
      // Always surface the server's own wording (e.g. the 409 quota message).
      setMessage({
        kind: 'error',
        text: err?.message || t('events.enterFailed', { name: event.name }),
      });
    } finally {
      await refreshAfterChange();
      setBusyId(null);
    }
  };

  const handleWithdraw = async (event: EventDTO) => {
    if (!confirm(t('events.withdrawConfirm', { name: event.name }))) return;
    setBusyId(event.id);
    setMessage(null);
    try {
      await api.cancelEnrollment(event.id);
      setMessage({
        kind: 'success',
        text: t('events.withdrawnNotice', { type: event.typeLabel, name: event.name }),
      });
    } catch (err: any) {
      setMessage({
        kind: 'error',
        text: err?.message || t('events.withdrawFailed', { name: event.name }),
      });
    } finally {
      await refreshAfterChange();
      setBusyId(null);
    }
  };

  /** Why this event cannot be entered right now, or `null` when it can. */
  const blockedReason = (event: EventDTO): string | null => {
    if (enrolledIds.includes(event.id)) return null;
    if (!event.enabled) return t('adminEvents.disabled');
    // A year whose entries are closed refuses every new entry with a 409; saying
    // so up front is kinder than letting the click fail.
    if (selectedSeason && !selectedSeason.enrollmentOpen) {
      return t('events.entriesClosedYear', { year: selectedSeason.year });
    }
    if (event.maxParticipants > 0 && event.enrolledCount >= event.maxParticipants) {
      return t('events.fullCount', {
        count: event.enrolledCount,
        max: event.maxParticipants,
      });
    }
    if (quota) {
      if (event.category === 'TRACK' && quota.trackRemaining <= 0) {
        return t('events.trackQuotaFull', { used: quota.trackUsed, max: quota.trackMax });
      }
      if (event.category === 'FIELD' && quota.fieldRemaining <= 0) {
        return t('events.fieldQuotaFull', { used: quota.fieldUsed, max: quota.fieldMax });
      }
    }
    return null;
  };

  /**
   * The programme as this student sees it: their own grade's events, plus any
   * event they already hold an entry in — an entry of another grade stands, and
   * hiding it would take away the only place it can be withdrawn from. Staff
   * carry no grade and so see everything.
   */
  const visibleEvents = useMemo(
    () =>
      myGrade
        ? events.filter(
            event => gradeMatchesEvent(event, myGrade) || enrolledIds.includes(event.id)
          )
        : events,
    [events, myGrade, enrolledIds]
  );

  const byCategory = useMemo(() => {
    const grouped: Record<EventCategory, EventDTO[]> = { TRACK: [], FIELD: [], RELAY: [] };
    visibleEvents.forEach(event => {
      if (grouped[event.category]) grouped[event.category].push(event);
    });
    SECTION_ORDER.forEach(category => {
      grouped[category].sort(
        (a, b) => a.typeLabel.localeCompare(b.typeLabel) || a.name.localeCompare(b.name)
      );
    });
    return grouped;
  }, [visibleEvents]);

  if (isLoading || !user || !me) {
    return (
      <div className="text-center" style={{ padding: '4rem 0' }}>
        <p>{t('common.loading')}</p>
      </div>
    );
  }

  const renderedMessages = message ? (
    <div className={`alert ${message.kind === 'error' ? 'alert-error' : 'alert-success'}`}>
      {message.text}
    </div>
  ) : null;

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('events.title')}</h1>
        <div className="flex gap-2">
          <Link href="/my-enrollments" className="btn btn-secondary">
            {t('nav.myEntries')}
          </Link>
          {(user.role === 'ADMIN' || user.role === 'MANAGER') && (
            <Link href="/admin/events" className="btn btn-primary">
              {t('events.manage')}
            </Link>
          )}
        </div>
      </div>

      {/* Quota */}
      <div className="card quota-card">
        <div className="flex justify-between items-center">
          <div>
            <div className="stat-label">{t('events.quota')}</div>
            <div className="quota-line">
              <span className={`quota-pill ${quota && quota.trackRemaining <= 0 ? 'is-full' : ''}`}>
                {quota
                  ? t('events.trackUsed', { used: quota.trackUsed, max: quota.trackMax })
                  : `${label('category', 'TRACK')} -`}
              </span>
              <span className="quota-sep">·</span>
              <span className={`quota-pill ${quota && quota.fieldRemaining <= 0 ? 'is-full' : ''}`}>
                {quota
                  ? t('events.fieldUsed', { used: quota.fieldUsed, max: quota.fieldMax })
                  : `${label('category', 'FIELD')} -`}
              </span>
            </div>
            {quota && (
              <div className="muted mt-2">
                {t('events.remaining', {
                  track: quota.trackRemaining,
                  field: quota.fieldRemaining,
                })}
              </div>
            )}
          </div>
          <div className="text-center">
            <div className="stat-label">{t('print.division')}</div>
            {isStudent ? (
              <div className="quota-division">{genderLabel(effectiveSex)}</div>
            ) : (
              <select
                value={sexFilter}
                onChange={e => setSexFilter(e.target.value as SexCode | '')}
                aria-label={t('events.sexDivision')}
              >
                <option value="">{t('common.all')}</option>
                <option value="M">{label('sex', 'MALE')}</option>
                <option value="F">{label('sex', 'FEMALE')}</option>
              </select>
            )}
          </div>
        </div>
      </div>

      {/* Which year, and which day of the programme */}
      <div className="card">
        <div className="grid-toolbar">
          <div className="field" style={{ minWidth: '16rem' }}>
            <label>{t('events.schoolYear')}</label>
            <select
              value={seasonId}
              onChange={e => setSeasonId(e.target.value ? Number(e.target.value) : '')}
            >
              <option value="">{t('events.allYears')}</option>
              {seasons.map(season => (
                <option key={season.id} value={season.id}>
                  {season.current
                    ? t('events.yearOptionCurrent', {
                        year: season.year,
                        count: season.eventCount ?? 0,
                      })
                    : !season.enrollmentOpen
                      ? t('events.yearOptionClosed', {
                          year: season.year,
                          count: season.eventCount ?? 0,
                        })
                      : t('events.yearOption', {
                          year: season.year,
                          count: season.eventCount ?? 0,
                        })}
                </option>
              ))}
            </select>
          </div>
          <div className="field" style={{ minWidth: '16rem' }}>
            <label>{t('events.programmeDate')}</label>
            <select value={dateFilter} onChange={e => setDateFilter(e.target.value)}>
              <option value="">{t('events.allDates')}</option>
              {dates.map(entry => (
                <option key={entry.date} value={entry.date}>
                  {entry.isToday
                    ? t('events.dateOptionToday', {
                        date: formatDate(entry.date),
                        count: entry.eventCount,
                      })
                    : entry.isPast
                      ? t('events.dateOptionPast', {
                          date: formatDate(entry.date),
                          count: entry.eventCount,
                        })
                      : t('events.dateOption', {
                          date: formatDate(entry.date),
                          count: entry.eventCount,
                        })}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <span className="muted">
              {selectedSeason
                ? t('events.viewingYear', {
                    year: selectedSeason.year,
                    name: selectedSeason.displayName || selectedSeason.name,
                  })
                : t('events.viewingAllYears')}
            </span>
          </div>
          <div className="field">
            <span className="muted">
              {dateFilter
                ? t('events.dateSummary', {
                    count: events.length,
                    date: formatDate(dateFilter),
                  })
                : t('events.allDatesSummary', { count: events.length })}
            </span>
          </div>
        </div>

        {isStudent && myGrade && (
          <p className="muted">
            {t('events.gradeSummary', {
              grade: label('grade.short', myGrade),
              allowed: events.filter(event => gradeMatchesEvent(event, myGrade)).length,
              total: events.length,
            })}
          </p>
        )}
        {isStudent && !myGrade && <p className="muted">{t('events.gradeUnknown')}</p>}

        {selectedSeason && !selectedSeason.enrollmentOpen && (
          <div className="blocked-note">
            {t('events.entriesClosedYear', { year: selectedSeason.year })}
          </div>
        )}
      </div>

      {renderedMessages}

      {loading ? (
        <div className="text-center" style={{ padding: '2rem 0' }}>
          <p>{t('events.loadingEvents')}</p>
        </div>
      ) : (
        SECTION_ORDER.map(category => {
          const list = byCategory[category];
          const isTrack = category === 'TRACK';
          return (
            <section key={category} className="mt-3">
              <h2 className={`section-title ${isTrack ? 'section-track' : 'section-field'}`}>
                {label('category', category)}
                <span className="badge badge-info">{list.length}</span>
              </h2>

              {list.length === 0 ? (
                <div className="card text-center">
                  <p className="muted">{t('events.noEventsDivision')}</p>
                </div>
              ) : (
                <div className="card-grid">
                  {list.map(event => {
                    const enrolled = enrolledIds.includes(event.id);
                    const reason = blockedReason(event);
                    const busy = busyId === event.id;
                    return (
                      <div
                        key={event.id}
                        className={`card event-card${event.enabled ? '' : ' card-disabled'}`}
                      >
                        <div className="flex justify-between items-center mb-2">
                          <h3>{event.name}</h3>
                          <div className="flex gap-2">
                            <span className="badge badge-info">{event.typeLabel}</span>
                            {/* Only worth showing when every year is on screen. */}
                            {!selectedSeason && event.seasonYear && (
                              <span className="badge badge-warning">{event.seasonYear}</span>
                            )}
                            {!event.enabled && (
                              <span className="badge badge-danger">{t('adminEvents.disabled')}</span>
                            )}
                            {enrolled && (
                              <span className="badge badge-success">{t('events.entered')}</span>
                            )}
                          </div>
                        </div>

                        <p className="muted">{event.description}</p>

                        <div className="event-meta mt-2">
                          <div>
                            <strong>{t('common.sex')}:</strong> {label('sex', event.sex)}
                          </div>
                          <div>
                            <strong>{t('events.date')}:</strong> {formatDate(event.eventDate)}
                          </div>
                          <div>
                            <strong>{t('events.place')}:</strong> {event.location || '-'}
                          </div>
                          <div>
                            <strong>{t('events.participants')}:</strong> {event.enrolledCount} /{' '}
                            {event.maxParticipants}
                          </div>
                          <div>
                            <strong>{t('events.groupSize')}:</strong> {event.groupSize}
                          </div>
                          <div>
                            <strong>{t('events.sheet')}:</strong> {label('sheet', event.sheetSize)}
                          </div>
                          <div>
                            <strong>{t('marks.unit')}:</strong>{' '}
                            {label('unit', event.defaultUnit || defaultUnitForCategory(event.category))}
                          </div>
                          {event.groupCount > 0 && (
                            <div>
                              <strong>{t('print.heats')}:</strong> {event.groupCount}
                              {event.ungroupedCount > 0 &&
                                ` ${t('events.ungroupedCount', { count: event.ungroupedCount })}`}
                            </div>
                          )}
                        </div>

                        <div className="pill-actions mt-3">
                          {enrolled ? (
                            <button
                              type="button"
                              className="btn btn-sm btn-danger"
                              disabled={busy}
                              onClick={() => handleWithdraw(event)}
                            >
                              {busy ? t('common.processing') : t('events.withdraw')}
                            </button>
                          ) : (
                            <button
                              type="button"
                              className="btn btn-sm btn-success"
                              disabled={busy || reason !== null}
                              title={reason || undefined}
                              onClick={() => handleEnroll(event)}
                            >
                              {busy ? t('common.processing') : t('events.enter')}
                            </button>
                          )}
                          <Link href={`/events/${event.id}`} className="btn btn-sm btn-secondary">
                            {t('common.details')}
                          </Link>
                        </div>

                        {reason && <div className="blocked-note">{reason}</div>}
                      </div>
                    );
                  })}
                </div>
              )}
            </section>
          );
        })
      )}
    </div>
  );
}
