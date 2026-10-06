'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api, defaultUnitForCategory, EnrollmentDTO, EventCategory, EventDTO, gradeMatchesEvent } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { formatDate } from '@/lib/format';
import { useI18n } from '@/lib/i18n';

const SECTION_ORDER: EventCategory[] = ['TRACK', 'FIELD', 'RELAY'];

export default function MyEnrollmentsPage() {
  const { user, isLoading } = useAuth();
  const { t, label } = useI18n();
  const router = useRouter();
  const [enrollments, setEnrollments] = useState<EnrollmentDTO[]>([]);
  /**
   * The events the entries point at, by id, so each entry can be measured
   * against the event's own grade. An event the list does not cover simply
   * carries no grade to compare.
   */
  const [eventById, setEventById] = useState<Record<number, EventDTO>>({});
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string } | null>(null);

  const load = useCallback(async () => {
    const [list, events] = await Promise.all([
      api.getMyEnrollments(),
      api.getEvents({ onlyEnabled: false }).catch(() => [] as EventDTO[]),
    ]);
    setEnrollments(list);
    const byId: Record<number, EventDTO> = {};
    events.forEach(event => {
      byId[event.id] = event;
    });
    setEventById(byId);
  }, []);

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    load()
      .catch((err: any) =>
        setMessage({ kind: 'error', text: err?.message || t('my.loadFailed') })
      )
      .finally(() => setLoading(false));
  }, [user, isLoading, router, load]);

  const handleWithdraw = async (entry: EnrollmentDTO) => {
    if (!confirm(t('events.withdrawConfirm', { name: entry.eventName }))) return;
    setBusyId(entry.eventId);
    setMessage(null);
    try {
      await api.cancelEnrollment(entry.eventId);
      setMessage({ kind: 'success', text: t('my.withdrawnNotice', { name: entry.eventName }) });
      await load();
    } catch (err: any) {
      setMessage({ kind: 'error', text: err?.message || t('my.withdrawFailed') });
      await load().catch(() => {});
    } finally {
      setBusyId(null);
    }
  };

  const handleReEnroll = async (entry: EnrollmentDTO) => {
    setBusyId(entry.eventId);
    setMessage(null);
    try {
      await api.reEnroll(entry.eventId);
      setMessage({ kind: 'success', text: t('my.reEnteredNotice', { name: entry.eventName }) });
      await load();
    } catch (err: any) {
      setMessage({ kind: 'error', text: err?.message || t('my.reEnterFailed') });
      await load().catch(() => {});
    } finally {
      setBusyId(null);
    }
  };

  const byCategory = useMemo(() => {
    const grouped: Record<EventCategory, EnrollmentDTO[]> = { TRACK: [], FIELD: [], RELAY: [] };
    enrollments.forEach(entry => {
      if (grouped[entry.category]) grouped[entry.category].push(entry);
    });
    SECTION_ORDER.forEach(category => {
      grouped[category].sort(
        (a, b) => a.eventName.localeCompare(b.eventName) || (a.groupNumber ?? 0) - (b.groupNumber ?? 0)
      );
    });
    return grouped;
  }, [enrollments]);

  if (isLoading || !user) {
    return (
      <div className="text-center" style={{ padding: '4rem 0' }}>
        <p>{t('common.loading')}</p>
      </div>
    );
  }

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('my.title')}</h1>
        <div className="flex gap-2">
          <Link href="/events" className="btn btn-secondary">
            {t('events.browse')}
          </Link>
          <Link href="/results" className="btn btn-primary">
            {t('results.title')}
          </Link>
        </div>
      </div>

      {message && (
        <div className={`alert ${message.kind === 'error' ? 'alert-error' : 'alert-success'}`}>
          {message.text}
        </div>
      )}

      {loading ? (
        <div className="text-center" style={{ padding: '2rem 0' }}>
          <p>{t('my.loadingEntries')}</p>
        </div>
      ) : enrollments.length === 0 ? (
        <div className="card text-center">
          <p>{t('my.noEntries')}</p>
          <Link href="/events" className="btn btn-primary mt-2">
            {t('events.browse')}
          </Link>
        </div>
      ) : (
        SECTION_ORDER.map(category => {
          const list = byCategory[category];
          const isTrack = category === 'TRACK';
          if (list.length === 0) return null;
          return (
            <section key={category} className="mt-3">
              <h2 className={`section-title ${isTrack ? 'section-track' : 'section-field'}`}>
                {label('category', category)}
                <span className="badge badge-info">{list.length}</span>
              </h2>

              <div className="card-grid">
                {list.map(entry => {
                  const confirmed = entry.status === 'CONFIRMED';
                  const busy = busyId === entry.eventId;
                  const allocated = typeof entry.groupLabel === 'string' && entry.groupLabel !== '';
                  // An event belongs to exactly one grade, so an entry made under
                  // any other grade no longer matches it. Withdrawing always
                  // stays possible; it is a new entry that would be refused.
                  const event = eventById[entry.eventId];
                  const gradeBlocked = !!event && !gradeMatchesEvent(event, entry.grade);
                  const gradeReason = event
                    ? t('events.gradeNotAllowed', {
                        grade: label('grade.short', event.grade),
                        mine: label('grade.short', entry.grade),
                      })
                    : '';
                  return (
                    <div key={entry.id} className="card event-card">
                      <div className="flex justify-between items-center mb-2">
                        <h3>{entry.eventName}</h3>
                        <div className="flex gap-2">
                          <span className="badge badge-info">{entry.eventTypeLabel}</span>
                          <span className={confirmed ? 'badge badge-success' : 'badge badge-warning'}>
                            {confirmed ? t('my.confirmed') : t('my.withdrawn')}
                          </span>
                        </div>
                      </div>

                      <div className="event-meta">
                        <div>
                          <strong>{t('common.sex')}:</strong> {label('sex', entry.sex)}
                        </div>
                        <div>
                          <strong>{t('events.date')}:</strong> {formatDate(entry.eventDate)}
                        </div>
                        <div>
                          <strong>{t('events.place')}:</strong> {entry.location || '-'}
                        </div>
                        <div>
                          <strong>{t('events.sheet')}:</strong>{' '}
                          <span className="badge badge-info">{label('sheet', entry.sheetSize)}</span>
                        </div>
                        <div>
                          <strong>{t('marks.unit')}:</strong>{' '}
                          {label('unit', entry.defaultUnit || defaultUnitForCategory(entry.category))}
                        </div>
                        <div>
                          <strong>{t('my.heat')}:</strong>{' '}
                          {allocated ? (
                            <>
                              {entry.groupLabel}
                              {typeof entry.lane === 'number' && entry.lane > 0 && (
                                <> · {t('my.lane')} {entry.lane}</>
                              )}
                            </>
                          ) : (
                            <span className="muted">{t('my.notAllocated')}</span>
                          )}
                        </div>
                        <div>
                          <strong>{t('auth.studentId')}:</strong> {entry.studentRef} · {entry.name}
                        </div>
                        <div>
                          <strong>{t('marks.class')}:</strong> {entry.className} {entry.classNumber} ·{' '}
                          {label('grade', entry.grade)}
                        </div>
                      </div>

                      <div className="pill-actions mt-3">
                        {confirmed ? (
                          <button
                            type="button"
                            className="btn btn-sm btn-danger"
                            disabled={busy}
                            onClick={() => handleWithdraw(entry)}
                          >
                            {busy ? t('common.processing') : t('events.withdraw')}
                          </button>
                        ) : (
                          <button
                            type="button"
                            className="btn btn-sm btn-success"
                            disabled={busy || gradeBlocked}
                            title={gradeBlocked ? gradeReason : undefined}
                            onClick={() => handleReEnroll(entry)}
                          >
                            {busy ? t('common.processing') : t('my.reEnter')}
                          </button>
                        )}
                        <Link href={`/events/${entry.eventId}`} className="btn btn-sm btn-secondary">
                          {t('events.event')}
                        </Link>
                        <Link
                          href={`/results?eventId=${entry.eventId}`}
                          className="btn btn-sm btn-primary"
                        >
                          {t('results.title')}
                        </Link>
                      </div>

                      {gradeBlocked && (
                        <div className="blocked-note">
                          {confirmed ? t('my.entryGradeNotAllowed') : gradeReason}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </section>
          );
        })
      )}
    </div>
  );
}
