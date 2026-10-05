'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api, EventDTO, Grade, GRADES, isRelayEventType, SexCode, StudentDTO, TeacherMeDTO } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';
import { classText, formText, houseText } from '@/lib/students';

/**
 * A teacher's own classes and the students in them.
 *
 * The whole of a teacher's authority is the class list `GET /api/teacher/me`
 * returns: the server refuses every entry, withdrawal and read for a student
 * outside it, and refuses *everybody* to a teacher who holds no class at all.
 * That empty case therefore must not be rendered as an empty table — it is a
 * refusal, and it is said plainly, with what to do about it. Only an
 * administrator is shown the register-wide list, and for them an empty class
 * list means the register simply has no students yet.
 *
 * An administrator may use this page too: the endpoints are shared, and `me`
 * reports that their classes are every class on the register.
 */
export default function TeacherPage() {
  const { user, isLoading } = useAuth();
  const { t, label } = useI18n();
  const router = useRouter();

  const canHelp = user?.role === 'ADMIN' || user?.role === 'TEACHER';
  const isAdmin = user?.role === 'ADMIN';

  const [me, setMe] = useState<TeacherMeDTO | null>(null);
  const [students, setStudents] = useState<StudentDTO[]>([]);
  /**
   * The relay events of the programme. A teacher groups the students who applied
   * to a relay into its teams on that event's own board — this is the way in.
   */
  const [relayEvents, setRelayEvents] = useState<EventDTO[]>([]);
  /** `''` is every class the caller may help in. */
  const [className, setClassName] = useState('');
  /**
   * The division and grade the student list is narrowed to. `className` is the
   * only filter the server takes, so these two are applied here, to the list the
   * server already returned for the class — no extra request is made for them.
   */
  const [sex, setSex] = useState<SexCode | ''>('');
  const [grade, setGrade] = useState<Grade | ''>('');
  const [loading, setLoading] = useState(true);
  const [loadingStudents, setLoadingStudents] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    if (user.role !== 'ADMIN' && user.role !== 'TEACHER') router.push('/');
  }, [user, isLoading, router]);

  useEffect(() => {
    if (!canHelp) return;
    let cancelled = false;
    setLoading(true);
    api
      .getTeacherMe()
      .then(result => {
        if (!cancelled) setMe(result);
      })
      .catch((err: any) => {
        if (!cancelled) setError(err?.message || t('teacher.loadFailed'));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [canHelp]);

  /** A teacher with no classes is refused everybody, so there is nothing to fetch. */
  const hasClasses = (me?.classes?.length ?? 0) > 0;

  const loadStudents = useCallback(async (filter: string) => {
    setLoadingStudents(true);
    try {
      const list = await api.getTeacherStudents(filter || undefined);
      setStudents(list);
    } catch (err: any) {
      setError(err?.message || t('teacher.studentsLoadFailed'));
      setStudents([]);
    } finally {
      setLoadingStudents(false);
    }
  }, []);

  useEffect(() => {
    if (!hasClasses) return;
    loadStudents(className);
  }, [hasClasses, className, loadStudents]);

  /**
   * The relay events, fetched whether or not this teacher holds a class: the
   * relay board itself is what refuses an applicant outside their classes, and
   * showing the events is how a teacher gets to that board at all.
   */
  useEffect(() => {
    if (!canHelp) return;
    let cancelled = false;
    api
      .getEvents({ onlyEnabled: true })
      .then(list => {
        if (!cancelled) setRelayEvents(list.filter(event => isRelayEventType(event.type)));
      })
      .catch(() => {
        if (!cancelled) setRelayEvents([]);
      });
    return () => {
      cancelled = true;
    };
  }, [canHelp]);

  const classOptions = useMemo(() => me?.classes ?? [], [me]);

  /**
   * The class filter is the server's, the division and the grade are this
   * list's. An empty result may therefore mean either "this class has nobody
   * matching" or "none of your classes has anybody at all", and the two are
   * said differently below.
   */
  const visibleStudents = useMemo(
    () =>
      students.filter(
        student =>
          (sex === '' || student.sex === (sex === 'M' ? 'MALE' : 'FEMALE')) &&
          (grade === '' || student.grade === grade)
      ),
    [students, sex, grade]
  );

  const narrowed = sex !== '' || grade !== '';

  if (isLoading || !user || !canHelp) {
    return <div>{t('common.loading')}</div>;
  }

  if (loading) {
    return <div>{t('teacher.loading')}</div>;
  }

  /*
   * An account that could not be read is not the same thing as one with no
   * classes. "No classes" is a refusal the server means, and saying it because a
   * request failed would be a lie about the account, so the two are kept apart.
   */
  if (!me) {
    return (
      <div>
        <h1 className="page-title">{t('teacher.title')}</h1>
        <div className="alert alert-error">{error || t('teacher.loadFailed')}</div>
      </div>
    );
  }

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('teacher.title')}</h1>
        {isAdmin && (
          <Link href="/admin" className="btn btn-secondary">
            {t('common.backToAdmin')}
          </Link>
        )}
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="hint">{t('teacher.subtitle')}</div>

      {/* Relay events — where the students who applied are grouped into teams */}
      <div className="card mt-3">
        <div className="flex justify-between items-center">
          <h2>{t('teacher.relayTitle')}</h2>
          <div className="pill-actions">
            <span className="badge badge-info">
              {t('relay.teamCount', { count: relayEvents.length })}
            </span>
            {/*
              The programme's relays in one list, with the action that makes a
              divided event's teams and a link to each event's board. It sits here
              as well as on `/admin/relay-events` because this page is a teacher's
              way in: the card below lists one event per button, which stops being
              readable at twelve.
            */}
            <Link href="/admin/relay-events" className="btn btn-sm btn-secondary">
              {t('teacher.relayAll')}
            </Link>
          </div>
        </div>
        <p className="muted mt-2">{t('teacher.relayHint')}</p>
        <p className="muted mt-2">{t('relayEvents.teacherLimits')}</p>
        {relayEvents.length === 0 ? (
          <p className="muted mt-2">{t('teacher.relayEmpty')}</p>
        ) : (
          <div className="pill-actions mt-2">
            {relayEvents.map(event => (
              <Link
                key={event.id}
                href={`/admin/events/${event.id}/relay`}
                className="btn btn-sm btn-secondary"
              >
                {event.typeLabel} · {label('grade.short', event.grade)} · {label('sex', event.sex)}
              </Link>
            ))}
          </div>
        )}
      </div>

      {/* My classes */}
      <div className="card mt-3">
        <div className="flex justify-between items-center">
          <h2>{t('teacher.myClasses')}</h2>
          <span className="badge badge-info">{classOptions.length}</span>
        </div>
        {hasClasses ? (
          <div className="pill-actions mt-2">
            {classOptions.map(option => (
              <span key={option} className="badge badge-info">
                {option}
              </span>
            ))}
          </div>
        ) : (
          <p className="muted mt-2">{t('teachers.noClass')}</p>
        )}
      </div>

      {/*
        No classes at all. This is the refusal the server applies to everything
        this account could attempt, so it is stated as such — never as an empty
        list of students, which would read as a bug.
      */}
      {!hasClasses ? (
        <div className="card">
          <h2>{isAdmin ? t('teacher.adminNoClasses') : t('teacher.noClasses')}</h2>
          {!isAdmin && <p className="mt-2">{t('teacher.noClassesHint')}</p>}
          <p className="muted mt-2">{t('teacher.ownClassesOnly')}</p>
        </div>
      ) : (
        <div className="card">
          <div className="flex justify-between items-center">
            <h2>{t('teacher.studentsTitle')}</h2>
            <span className="badge badge-info">
              {t('teacher.studentCount', { count: visibleStudents.length })}
            </span>
          </div>

          {/*
            Class is the server's own filter; division and grade narrow the list
            it returned. All three narrow the students, never the events — an
            event is chosen on the student's own page.
          */}
          <div className="toolbar mt-2">
            <div className="form-group">
              <label>{t('teacher.classFilter')}</label>
              <select value={className} onChange={e => setClassName(e.target.value)}>
                <option value="">{t('teacher.allClasses')}</option>
                {classOptions.map(option => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </div>

            <div className="form-group">
              <label htmlFor="teacher-sex">{t('teacher.sexFilter')}</label>
              <select
                id="teacher-sex"
                value={sex}
                onChange={e => setSex(e.target.value as SexCode | '')}
              >
                <option value="">{t('teacher.allSexes')}</option>
                <option value="M">{label('sex', 'MALE')}</option>
                <option value="F">{label('sex', 'FEMALE')}</option>
              </select>
            </div>

            <div className="form-group">
              <label htmlFor="teacher-grade">{t('teacher.gradeFilter')}</label>
              <select
                id="teacher-grade"
                value={grade}
                onChange={e => setGrade(e.target.value as Grade | '')}
              >
                <option value="">{t('eventFilters.allGrades')}</option>
                {GRADES.map(value => (
                  <option key={value} value={value}>
                    {label('grade', value)}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <p className="muted mt-2">{t('teacher.ownClassesOnly')}</p>

          {loadingStudents ? (
            <p className="muted mt-2">{t('teacher.loadingStudents')}</p>
          ) : visibleStudents.length === 0 ? (
            /* "None of your classes has anyone" and "your filters exclude
               everybody" are different answers, so they are not conflated. */
            <p className="muted mt-2">
              {narrowed && students.length > 0 ? t('teacher.noMatchingStudents') : t('teacher.noStudents')}
            </p>
          ) : (
            <div className="table-wrap mt-2">
              <table>
                <thead>
                  <tr>
                    <th>{t('students.colId')}</th>
                    <th>{t('students.colName')}</th>
                    <th>{t('students.form')}</th>
                    <th>{t('marks.class')}</th>
                    <th>{t('marks.grade')}</th>
                    <th>{t('students.colHouse')}</th>
                    <th>{t('common.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleStudents.map(student => (
                    <tr key={student.id} className={student.enabled ? undefined : 'row-disabled'}>
                      <td>{student.studentId}</td>
                      <td>{student.name}</td>
                      {/* Form, class and house — the house with its short code,
                          and no letter invented for a house the register has
                          none for. */}
                      <td>{formText(student, t)}</td>
                      <td>{classText(student) || '-'}</td>
                      <td>
                        <span className="badge badge-info">{label('grade', student.grade)}</span>
                      </td>
                      <td>{houseText(student)}</td>
                      <td>
                        <Link
                          href={`/teacher/students/${student.studentId}`}
                          className="btn btn-sm btn-primary"
                        >
                          {t('teacher.entries')}
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
