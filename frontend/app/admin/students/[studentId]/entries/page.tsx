'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import {
  api,
  EnrollmentDTO,
  EventCategory,
  EventDTO,
  SexCode,
  StudentDTO,
  StudentEnrollmentsDTO,
  gradeMayEnterEvent,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { formatDate } from '@/lib/format';
import { useI18n } from '@/lib/i18n';

const SECTION_ORDER: EventCategory[] = ['TRACK', 'FIELD'];

/**
 * An administrator entering events on a student's behalf — for a student who
 * cannot do it themselves. The entries belong to the student, and the track /
 * field quota is the student's, not the administrator's, so the server refuses
 * a third track entry (or a second field entry) with a 409 whichever way it is
 * attempted from here.
 */
export default function AdminStudentEntriesPage() {
  const params = useParams();
  const router = useRouter();
  const { user, isLoading } = useAuth();
  const { t, label } = useI18n();

  const studentId = typeof params.studentId === 'string' ? params.studentId : '';

  const [student, setStudent] = useState<StudentDTO | null>(null);
  const [enrollments, setEnrollments] = useState<StudentEnrollmentsDTO | null>(null);
  const [eligible, setEligible] = useState<EventDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const isAdmin = user?.role === 'ADMIN';

  // ADMIN only: this page hands out places in a student's name.
  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    if (user.role !== 'ADMIN') router.push('/');
  }, [user, isLoading, router]);

  useEffect(() => {
    if (!user || user.role !== 'ADMIN' || !studentId) return;
    let cancelled = false;
    setLoading(true);
    (async () => {
      try {
        const [studentResult, entriesResult] = await Promise.all([
          api.getStudent(studentId),
          api.getStudentEnrollments(studentId),
        ]);
        if (cancelled) return;
        setStudent(studentResult);
        setEnrollments(entriesResult);
      } catch (err: any) {
        if (!cancelled) setError(err?.message || t('entries.loadFailed'));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [user, studentId]);

  // The student's own division is the only one they may be entered in.
  const studentSex: SexCode | '' =
    student?.sex === 'MALE' ? 'M' : student?.sex === 'FEMALE' ? 'F' : '';

  useEffect(() => {
    if (!studentSex) return;
    let cancelled = false;
    api
      .getEvents({ onlyEnabled: true, sex: studentSex })
      .then(list => {
        if (!cancelled) setEligible(list);
      })
      .catch((err: any) => {
        if (!cancelled) setError(err?.message || t('events.loadFailed'));
      });
    return () => {
      cancelled = true;
    };
  }, [studentSex]);

  /** Re-reads the entries — and with them the quota — plus the event list. */
  const refresh = useCallback(async () => {
    const jobs: Array<Promise<unknown>> = [
      api
        .getStudentEnrollments(studentId)
        .then(setEnrollments)
        .catch(() => {}),
    ];
    if (studentSex) {
      jobs.push(
        api
          .getEvents({ onlyEnabled: true, sex: studentSex })
          .then(setEligible)
          .catch(() => {})
      );
    }
    await Promise.all(jobs);
  }, [studentId, studentSex]);

  const handleAdd = async (event: EventDTO) => {
    if (!student) return;
    setBusyId(event.id);
    setError(null);
    setNotice(null);
    try {
      await api.enrollStudent(studentId, event.id);
      setNotice(t('entries.addedNotice', { name: student.name, event: event.name }));
    } catch (err: any) {
      // The 409 wording names the category and the maximum: show it as it is.
      setError(err?.message || t('entries.addFailed'));
    } finally {
      await refresh();
      setBusyId(null);
    }
  };

  const handleRemove = async (entry: EnrollmentDTO) => {
    if (!student || entry.status !== 'CONFIRMED') return;
    if (!confirm(t('entries.removeConfirm', { name: student.name, event: entry.eventName }))) {
      return;
    }
    setBusyId(entry.eventId);
    setError(null);
    setNotice(null);
    try {
      await api.cancelStudentEnrollment(studentId, entry.eventId);
      setNotice(t('entries.removedNotice', { name: student.name, event: entry.eventName }));
    } catch (err: any) {
      setError(err?.message || t('entries.removeFailed'));
    } finally {
      await refresh();
      setBusyId(null);
    }
  };

  const quota = enrollments?.quota;

  /** Every event the student already has a row for, confirmed or withdrawn. */
  const entryByEventId = useMemo(() => {
    const map = new Map<number, EnrollmentDTO>();
    (enrollments?.enrollments ?? []).forEach(entry => map.set(entry.eventId, entry));
    return map;
  }, [enrollments]);

  const rows = useMemo(() => {
    const list = [...(enrollments?.enrollments ?? [])];
    return list.sort((a, b) => {
      if (a.status !== b.status) return a.status === 'CONFIRMED' ? -1 : 1;
      return (
        (a.category ?? '').localeCompare(b.category ?? '') ||
        a.eventTypeLabel.localeCompare(b.eventTypeLabel) ||
        a.eventName.localeCompare(b.eventName)
      );
    });
  }, [enrollments]);

  const byCategory = useMemo(() => {
    const grouped: Record<EventCategory, EventDTO[]> = { TRACK: [], FIELD: [] };
    eligible.forEach(event => {
      if (grouped[event.category]) grouped[event.category].push(event);
    });
    SECTION_ORDER.forEach(category => {
      grouped[category].sort(
        (a, b) => a.typeLabel.localeCompare(b.typeLabel) || a.name.localeCompare(b.name)
      );
    });
    return grouped;
  }, [eligible]);

  /** Why this event cannot be added for the student, or `null` when it can. */
  const blockedReason = (event: EventDTO): string | null => {
    const existing = entryByEventId.get(event.id);
    if (existing && existing.status === 'CONFIRMED') {
      return t('entries.alreadyEntered');
    }
    // A withdrawn entry is NOT a block: the admin endpoint revives it, so those
    // events stay offered (the button says "Re-enter").
    // The student's grade decides which events they may enter at all — the server
    // refuses otherwise, including for an admin, so say so here.
    if (student?.grade && !gradeMayEnterEvent(event, student.grade)) {
      return t('events.gradeNotAllowed', { grade: label('grade.short', student.grade) });
    }
    if (event.maxParticipants > 0 && event.enrolledCount >= event.maxParticipants) {
      return t('events.fullCount', { count: event.enrolledCount, max: event.maxParticipants });
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

  /** True when the student withdrew from this event and can be put back in. */
  const wasWithdrawn = (event: EventDTO): boolean => {
    const existing = entryByEventId.get(event.id);
    return !!existing && existing.status !== 'CONFIRMED';
  };

  if (isLoading || !user || !isAdmin) {
    return <div>{t('common.loading')}</div>;
  }

  if (loading) {
    return <div>{t('entries.loading')}</div>;
  }

  if (!student) {
    return (
      <div>
        <div className="alert alert-error">{error || t('entries.loadFailed')}</div>
        <Link href="/admin/students" className="btn btn-secondary">
          {t('entries.backToRegister')}
        </Link>
      </div>
    );
  }

  const totalCount = eligible.length;
  // Events that can still be added: not already entered, not full, quota left.
  const addableCount = eligible.filter(event => blockedReason(event) === null).length;

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('entries.title')}</h1>
        <div className="flex gap-2">
          <Link href="/admin/students" className="btn btn-secondary">
            {t('entries.backToRegister')}
          </Link>
          <Link href="/admin" className="btn btn-secondary">
            {t('common.backToAdmin')}
          </Link>
        </div>
      </div>

      {/* Whose entries these are, and that an administrator is acting. */}
      <div className="hint">
        <strong>
          {t('entries.adminActing', { name: student.name, studentId: student.studentId })}
        </strong>
        <div className="mt-2">{t('entries.notSelf')}</div>
        <div className="muted mt-2">{t('entries.subtitle')}</div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {notice && <div className="alert alert-success">{notice}</div>}

      {/* The student */}
      <div className="stat-grid">
        <div className="stat">
          <div className="stat-value">{student.name}</div>
          <div className="stat-label">{t('students.colName')}</div>
        </div>
        <div className="stat">
          <div className="stat-value">{student.studentId}</div>
          <div className="stat-label">{t('students.colId')}</div>
        </div>
        <div className="stat">
          <div className="stat-value">
            {student.classLabel || `${student.className} ${student.classNumber}`}
          </div>
          <div className="stat-label">{t('marks.class')}</div>
        </div>
        <div className="stat">
          <div className="stat-value">{label('grade', student.grade)}</div>
          <div className="stat-label">{t('marks.grade')}</div>
        </div>
        <div className="stat">
          <div className="stat-value">{student.house || '-'}</div>
          <div className="stat-label">{t('students.colHouse')}</div>
        </div>
        <div className="stat">
          <div className="stat-value">{label('sex', student.sex)}</div>
          <div className="stat-label">{t('print.division')}</div>
        </div>
      </div>

      {/* Quota — the student's own allowance */}
      <div className="card quota-card mt-3">
        <div className="stat-label">{t('entries.quotaPanel')}</div>
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
          <>
            <div className="muted mt-2">
              {t('events.remaining', { track: quota.trackRemaining, field: quota.fieldRemaining })}
            </div>
            <div className="pill-actions mt-2">
              <span
                className={
                  quota.trackRemaining <= 0 ? 'badge badge-danger' : 'badge badge-success'
                }
              >
                {label('category', 'TRACK')} ·{' '}
                {t('entries.placesLeft', { count: quota.trackRemaining })}
              </span>
              <span
                className={
                  quota.fieldRemaining <= 0 ? 'badge badge-danger' : 'badge badge-success'
                }
              >
                {label('category', 'FIELD')} ·{' '}
                {t('entries.placesLeft', { count: quota.fieldRemaining })}
              </span>
            </div>
          </>
        )}
      </div>

      {/* What the student is entered in now */}
      <div className="card">
        <div className="flex justify-between items-center">
          <h2>{t('entries.currentEntries')}</h2>
          <span className="badge badge-info">{rows.length}</span>
        </div>

        {rows.length === 0 ? (
          <p className="muted mt-2">{t('entries.noEntries')}</p>
        ) : (
          <div className="table-wrap mt-2">
            <table>
              <thead>
                <tr>
                  <th>{t('events.event')}</th>
                  <th>{t('print.category')}</th>
                  <th>{t('print.division')}</th>
                  <th>{t('events.date')}</th>
                  <th>{t('admin.colStatus')}</th>
                  <th>{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map(entry => {
                  const confirmed = entry.status === 'CONFIRMED';
                  const busy = busyId === entry.eventId;
                  return (
                    <tr key={entry.id} className={confirmed ? undefined : 'row-disabled'}>
                      <td>
                        <strong>{entry.eventTypeLabel}</strong>
                        <div className="muted">{entry.eventName}</div>
                      </td>
                      <td>
                        <span
                          className={
                            entry.category === 'TRACK'
                              ? 'badge badge-danger'
                              : 'badge badge-info'
                          }
                        >
                          {label('category', entry.category)}
                        </span>
                      </td>
                      <td>{label('sex', entry.sex)}</td>
                      <td>{formatDate(entry.eventDate)}</td>
                      <td>
                        <span
                          className={confirmed ? 'badge badge-success' : 'badge badge-danger'}
                        >
                          {confirmed ? t('my.confirmed') : t('my.withdrawn')}
                        </span>
                      </td>
                      <td>
                        {confirmed ? (
                          <button
                            type="button"
                            className="btn btn-sm btn-danger"
                            disabled={busy}
                            onClick={() => handleRemove(entry)}
                          >
                            {busy ? t('common.processing') : t('events.withdraw')}
                          </button>
                        ) : (
                          <span className="muted">{t('entries.confirmedOnly')}</span>
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

      {/* Add an event, in the student's own division */}
      <div className="card">
        <div className="flex justify-between items-center">
          <h2>{t('entries.addEvent')}</h2>
          <span className="badge badge-info">{addableCount} / {totalCount}</span>
        </div>
        <p className="muted mt-2">{t('entries.addHint')}</p>

        {totalCount === 0 ? (
          <p className="muted mt-2">{t('events.noEventsDivision')}</p>
        ) : (
          <>
            {addableCount === 0 && <p className="muted mt-2">{t('entries.noEligible')}</p>}
            {SECTION_ORDER.map(category => {
            const list = byCategory[category];
            if (list.length === 0) return null;
            const remaining = quota
              ? category === 'TRACK'
                ? quota.trackRemaining
                : quota.fieldRemaining
              : 0;
            return (
              <section key={category} className="mt-3">
                <h2
                  className={`section-title ${
                    category === 'TRACK' ? 'section-track' : 'section-field'
                  }`}
                >
                  {label('category', category)}
                  <span className="badge badge-info">{list.length}</span>
                  <span
                    className={remaining <= 0 ? 'badge badge-danger' : 'badge badge-success'}
                  >
                    {t('entries.placesLeft', { count: remaining })}
                  </span>
                </h2>

                <div className="card-grid">
                  {list.map(event => {
                    const reason = blockedReason(event);
                    const busy = busyId === event.id;
                    return (
                      <div key={event.id} className="card event-card">
                        <div className="flex justify-between items-center mb-2">
                          <h3>{event.name}</h3>
                          <div className="flex gap-2">
                            <span className="badge badge-info">{event.typeLabel}</span>
                            {entryByEventId.has(event.id) && (
                              <span
                                className={
                                  wasWithdrawn(event)
                                    ? 'badge badge-warning'
                                    : 'badge badge-success'
                                }
                              >
                                {wasWithdrawn(event)
                                  ? t('my.withdrawn')
                                  : t('entries.alreadyEntered')}
                              </span>
                            )}
                          </div>
                        </div>

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
                        </div>

                        <div className="pill-actions mt-3">
                          <button
                            type="button"
                            className="btn btn-sm btn-success"
                            disabled={busy || reason !== null}
                            title={reason || undefined}
                            onClick={() => handleAdd(event)}
                          >
                            {busy
                              ? t('common.processing')
                              : wasWithdrawn(event)
                                ? t('entries.reEnter')
                                : t('events.enter')}
                          </button>
                          <Link
                            href={`/events/${event.id}`}
                            className="btn btn-sm btn-secondary"
                          >
                            {t('common.details')}
                          </Link>
                        </div>

                        {reason && <div className="blocked-note">{reason}</div>}
                      </div>
                    );
                  })}
                </div>
              </section>
            );
          })}
          </>
        )}
      </div>
    </div>
  );
}
