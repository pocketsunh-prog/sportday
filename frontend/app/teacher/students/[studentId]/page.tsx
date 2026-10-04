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
  gradeMatchesEvent,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { formatDate } from '@/lib/format';
import { useI18n } from '@/lib/i18n';

const SECTION_ORDER: EventCategory[] = ['TRACK', 'FIELD'];

/**
 * A teacher entering or withdrawing events for a student in one of their own
 * classes.
 *
 * The shape is the administrator's `admin/students/[studentId]/entries` page,
 * held to the teacher's class rule instead of the register-wide one: the student
 * is looked up in the roster the teacher was given, and everything the page does
 * goes through `/api/teacher/**`, which refuses a student outside their classes
 * with a 403. Nothing here widens what the server allows.
 *
 * The entry rules stay visible because they are what an entry is judged against:
 * the student's own division and grade (an event belongs to exactly one grade
 * and never ranks against another), the track / field quota, and the fact that a
 * withdrawn entry can be entered again — that revives the old row rather than
 * adding a second one.
 */
export default function TeacherStudentEntriesPage() {
  const params = useParams();
  const router = useRouter();
  const { user, isLoading } = useAuth();
  const { t, label } = useI18n();

  const studentId = typeof params.studentId === 'string' ? params.studentId : '';

  const canHelp = user?.role === 'ADMIN' || user?.role === 'TEACHER';

  const [student, setStudent] = useState<StudentDTO | null>(null);
  const [enrollments, setEnrollments] = useState<StudentEnrollmentsDTO | null>(null);
  const [eligible, setEligible] = useState<EventDTO[]>([]);
  /**
   * The half of the programme and the event type offered for entry. Both narrow
   * the events this page offers; the server list is already the student's own
   * division, so the division itself is not offered again here.
   */
  const [category, setCategory] = useState<EventCategory | ''>('');
  const [eventType, setEventType] = useState('');
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /** The student is not in one of the caller's classes — a refusal, not an error. */
  const [notYours, setNotYours] = useState(false);
  /** True once the roster the caller may help actually answered. */
  const [rosterLoaded, setRosterLoaded] = useState(false);

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    if (user.role !== 'ADMIN' && user.role !== 'TEACHER') router.push('/');
  }, [user, isLoading, router]);

  useEffect(() => {
    if (!canHelp || !studentId) return;
    let cancelled = false;
    setLoading(true);
    (async () => {
      try {
        // The student is not readable one-by-one on this path — a teacher is
        // granted the entry paths and nothing else — so they are found in the
        // roster the caller was given, which is exactly the set they may help.
        // That is the only thing allowed to fail quietly: the entries call below
        // is the authority on whether this student may be touched at all, so its
        // refusal must reach the catch.
        const [roster, entriesResult] = await Promise.all([
          api
            .getTeacherStudents()
            .then(list => {
              if (!cancelled) setRosterLoaded(true);
              return list;
            })
            .catch(() => {
              if (!cancelled) setRosterLoaded(false);
              return [] as StudentDTO[];
            }),
          api.getTeacherStudentEnrollments(studentId),
        ]);
        if (cancelled) return;
        setStudent(roster.find(candidate => candidate.studentId === studentId) ?? null);
        setEnrollments(entriesResult);
        setNotYours(false);
      } catch (err: any) {
        if (cancelled) return;
        // The server refuses a student outside the teacher's classes with a 403
        // naming the class, so that refusal is shown as itself.
        setNotYours(true);
        setError(err?.message || t('teacher.entriesLoadFailed'));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [canHelp, studentId]);

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
        .getTeacherStudentEnrollments(studentId)
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
      await api.enrollTeacherStudent(studentId, event.id);
      setNotice(t('teacher.addedNotice', { name: student.name, event: event.name }));
    } catch (err: any) {
      // The 409 wording names the category and the maximum: show it as it is.
      setError(err?.message || t('teacher.addFailed'));
    } finally {
      await refresh();
      setBusyId(null);
    }
  };

  const handleRemove = async (entry: EnrollmentDTO) => {
    if (!student || entry.status !== 'CONFIRMED') return;
    if (!confirm(t('teacher.removeConfirm', { name: student.name, event: entry.eventName }))) {
      return;
    }
    setBusyId(entry.eventId);
    setError(null);
    setNotice(null);
    try {
      await api.cancelTeacherStudentEnrollment(studentId, entry.eventId);
      setNotice(t('teacher.removedNotice', { name: student.name, event: entry.eventName }));
    } catch (err: any) {
      setError(err?.message || t('teacher.removeFailed'));
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
    eligible
      .filter(
        event =>
          (category === '' || event.category === category) &&
          (eventType === '' || event.type === eventType)
      )
      .forEach(event => {
        if (grouped[event.category]) grouped[event.category].push(event);
      });
    SECTION_ORDER.forEach(category => {
      grouped[category].sort(
        (a, b) => a.typeLabel.localeCompare(b.typeLabel) || a.name.localeCompare(b.name)
      );
    });
    return grouped;
  }, [eligible, category, eventType]);

  /**
   * The event types this student may actually be entered in, so the picker
   * never lists a type the student's division does not run. Ordered by label,
   * which is how the cards below are ordered.
   */
  const typeOptions = useMemo(() => {
    const seen = new Map<string, string>();
    eligible.forEach(event => {
      if (!seen.has(event.type)) seen.set(event.type, event.typeLabel);
    });
    return Array.from(seen, ([type, label]) => ({ type, label })).sort((a, b) =>
      a.label.localeCompare(b.label)
    );
  }, [eligible]);

  /** The events the two filters let through, i.e. what the cards draw. */
  const shownEvents = useMemo(
    () => SECTION_ORDER.flatMap(category => byCategory[category]),
    [byCategory]
  );

  /** Why this event cannot be added for the student, or `null` when it can. */
  const blockedReason = (event: EventDTO): string | null => {
    const existing = entryByEventId.get(event.id);
    if (existing && existing.status === 'CONFIRMED') {
      return t('entries.alreadyEntered');
    }
    // A withdrawn entry is NOT a block: the teacher endpoint revives it, so
    // those events stay offered (the button says "Re-enter").
    // An event belongs to exactly one grade, and only the student's own grade
    // may enter it — the server refuses any other with a 409, teacher or not.
    if (student?.grade && !gradeMatchesEvent(event, student.grade)) {
      return t('teacher.gradeNotAllowed', {
        grade: label('grade.short', event.grade),
        mine: label('grade.short', student.grade),
      });
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

  if (isLoading || !user || !canHelp) {
    return <div>{t('common.loading')}</div>;
  }

  if (loading) {
    return <div>{t('teacher.entriesLoading')}</div>;
  }

  // Not one of the caller's students. The server refuses this outright, so it is
  // reported as a refusal rather than as a page that failed to load. A roster
  // that could not be read at all is the other case, and says so instead —
  // claiming "not one of your classes" off a failed request would be wrong.
  const notAmongMine = notYours || (!student && rosterLoaded);

  if (notAmongMine) {
    return (
      <div>
        <div className="alert alert-error">{error || t('teacher.entriesLoadFailed')}</div>
        <div className="card">
          <h2>{t('teacher.notYours')}</h2>
          <p className="muted mt-2">{t('teacher.ownClassesOnly')}</p>
        </div>
        <Link href="/teacher" className="btn btn-secondary">
          {t('teacher.backToStudents')}
        </Link>
      </div>
    );
  }

  if (!student) {
    return (
      <div>
        <h1 className="page-title">{t('teacher.title')}</h1>
        <div className="alert alert-error">{error || t('teacher.entriesLoadFailed')}</div>
        <Link href="/teacher" className="btn btn-secondary">
          {t('teacher.backToStudents')}
        </Link>
      </div>
    );
  }

  const totalCount = shownEvents.length;
  // Events that can still be added: not already entered, not full, quota left.
  const addableCount = shownEvents.filter(event => blockedReason(event) === null).length;

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('teacher.title')}</h1>
        <Link href="/teacher" className="btn btn-secondary">
          {t('teacher.backToStudents')}
        </Link>
      </div>

      {/* Whose entries these are, and that a teacher is acting for them. */}
      <div className="hint">
        <strong>{t('teacher.acting', { name: student.name, studentId: student.studentId })}</strong>
        <div className="mt-2">{t('entries.notSelf')}</div>
        <div className="muted mt-2">{t('teacher.ownClassesOnly')}</div>
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

      {/* The rules the entries are judged against — kept visible on purpose. */}
      <div className="card mt-3">
        <h2>{t('teacher.entryRules')}</h2>
        <ul className="mt-2">
          <li>
            {quota
              ? t('teacher.quotaRule', {
                  track: quota.trackMax,
                  field: quota.fieldMax,
                  trackLeft: quota.trackRemaining,
                  fieldLeft: quota.fieldRemaining,
                })
              : `${label('category', 'TRACK')} / ${label('category', 'FIELD')}`}
          </li>
          <li>{t('teacher.rulesHint')}</li>
          <li>{t('teacher.withdrawnReEnter')}</li>
        </ul>
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
                  <th>{t('teacher.eventGrade')}</th>
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
                      <td>
                        <span className="badge badge-info">{label('grade.short', entry.grade)}</span>
                      </td>
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
          <span className="badge badge-info">
            {addableCount} / {totalCount}
          </span>
        </div>
        <p className="muted mt-2">{t('entries.addHint')}</p>

        {/*
          The events this page offers are the student's own division (the server
          list) narrowed by the half of the programme and the event type. The
          filters are on the events alone, so the entry rules below stay visible
          whatever they are set to.
        */}
        {eligible.length > 0 && (
          <div className="toolbar mt-2">
            <div className="form-group">
              <label htmlFor="entries-category">{t('eventFilters.category')}</label>
              <select
                id="entries-category"
                value={category}
                onChange={e => setCategory(e.target.value as EventCategory | '')}
              >
                <option value="">{t('eventFilters.allCategories')}</option>
                {SECTION_ORDER.map(value => (
                  <option key={value} value={value}>
                    {label('category', value)}
                  </option>
                ))}
              </select>
            </div>

            <div className="form-group">
              <label htmlFor="entries-type">{t('eventFilters.eventType')}</label>
              <select
                id="entries-type"
                value={eventType}
                onChange={e => setEventType(e.target.value)}
              >
                <option value="">{t('eventFilters.allEventTypes')}</option>
                {typeOptions.map(option => (
                  <option key={option.type} value={option.type}>
                    {option.label}
                  </option>
                ))}
              </select>
            </div>
          </div>
        )}

        {totalCount === 0 ? (
          <p className="muted mt-2">
            {eligible.length > 0 ? t('entries.noMatchingEvents') : t('events.noEventsDivision')}
          </p>
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
                    <span className={remaining <= 0 ? 'badge badge-danger' : 'badge badge-success'}>
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

                          {/* The event's own division and grade — what an entry is judged on. */}
                          <div className="event-meta mt-2">
                            <div>
                              <strong>{t('teacher.division')}:</strong> {label('sex', event.sex)}
                            </div>
                            <div>
                              <strong>{t('teacher.eventGrade')}:</strong>{' '}
                              {label('grade.short', event.grade)}
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
                            <Link href={`/events/${event.id}`} className="btn btn-sm btn-secondary">
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
