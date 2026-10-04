'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api, TeacherDTO, TeacherUploadResultDTO } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';

/**
 * The staff list, uploaded the way the student register is.
 *
 * The upload runs in two steps and never in one. A rehearsal is always first:
 * `POST /admin/teachers/upload?dryRun=true` classifies every row — created,
 * updated or failed, with the per-row errors — and writes nothing at all. Only
 * once the administrator has seen that does the same file go back with
 * `dryRun=false`. The file is held with its rehearsal, so the apply step can
 * never send a different file from the one that was previewed.
 *
 * A teacher is an account plus a set of classes, and the classes are what they
 * may help a student with. `GET /api/admin/teachers` carries them, so the list is
 * self-contained; a backend that omits them is covered by reading the classes
 * back from the credentials sheet, and the table says which of the two it used.
 * One teacher's classes can also be corrected on their own, through
 * `PUT /api/admin/teachers/{username}/classes`, without re-uploading the file.
 */
export default function AdminTeachersPage() {
  const { user, isLoading } = useAuth();
  const { t } = useI18n();
  const router = useRouter();

  const isAdmin = user?.role === 'ADMIN';

  const [teachers, setTeachers] = useState<TeacherDTO[]>([]);
  /** `username -> classes`, read from the credentials sheet — see `usedSheet`. */
  const [classMap, setClassMap] = useState<Record<string, string[]> | null>(null);
  /** True when the list did not carry the classes and the sheet had to answer. */
  const [usedSheet, setUsedSheet] = useState(false);
  const [listError, setListError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const [search, setSearch] = useState('');
  /** The teacher whose classes are being corrected in place, if any. */
  const [editingUsername, setEditingUsername] = useState<string | null>(null);
  const [draftClasses, setDraftClasses] = useState('');
  const [file, setFile] = useState<File | null>(null);
  /** The rehearsal, with the exact file it describes — the apply step uses both. */
  const [rehearsal, setRehearsal] = useState<{
    result: TeacherUploadResultDTO;
    file: File;
  } | null>(null);
  const [applied, setApplied] = useState<TeacherUploadResultDTO | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  /** ADMIN only: this page creates and rewrites staff accounts. */
  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    if (user.role !== 'ADMIN') router.push('/');
  }, [user, isLoading, router]);

  const loadTeachers = useCallback(async () => {
    // The classes ride on the list itself. A backend that leaves them off is
    // covered by the credentials sheet, which is the only other response that
    // spells them out — and is read only when a row actually lacks them, so the
    // ordinary case costs no second call.
    const list = await api.getTeachers().catch((err: any) => {
      setListError(err?.message || t('teachers.loadFailed'));
      return [] as TeacherDTO[];
    });
    const needsSheet = list.some(teacher => teacher.classes === undefined);
    const sheet = needsSheet ? await api.getTeacherClassMap().catch(() => null) : null;
    setTeachers(list);
    setClassMap(sheet);
    setUsedSheet(needsSheet);
  }, []);

  useEffect(() => {
    if (!isAdmin) return;
    let cancelled = false;
    setLoading(true);
    loadTeachers().finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [isAdmin, loadTeachers]);

  const visibleTeachers = useMemo(() => {
    const needle = search.trim().toLowerCase();
    const withClasses = teachers.map(teacher => ({
      ...teacher,
      // The list's own answer wins; the sheet is only the fallback.
      classes: teacher.classes ?? classMap?.[teacher.username] ?? [],
    }));
    if (!needle) return withClasses;
    return withClasses.filter(teacher => {
      if (teacher.username.toLowerCase().includes(needle)) return true;
      if ((teacher.name ?? '').toLowerCase().includes(needle)) return true;
      return teacher.classes.some(className => className.toLowerCase().includes(needle));
    });
  }, [teachers, classMap, search]);

  /**
   * The rehearsal. It changes nothing, so it is safe on any file, and its counts
   * are what the administrator decides on before saving.
   */
  const handleRehearse = async () => {
    if (!file) {
      setError(t('students.chooseFileFirst'));
      return;
    }
    setBusy('rehearse');
    setError(null);
    setNotice(null);
    try {
      const result = await api.uploadTeachers(file, true);
      setRehearsal({ result, file });
      setApplied(null);
    } catch (err: any) {
      setError(err?.message || t('students.uploadFailed'));
    } finally {
      setBusy(null);
    }
  };

  /** The explicit confirm: the rehearsed file again, this time written. */
  const handleApply = async () => {
    if (!rehearsal) return;
    if (!confirm(t('teachers.applyConfirm'))) return;
    setBusy('apply');
    setError(null);
    setNotice(null);
    try {
      const result = await api.uploadTeachers(rehearsal.file, false);
      setApplied(result);
      setRehearsal(null);
      setFile(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
      setNotice(
        t('teachers.applied', {
          created: result.created,
          updated: result.updated,
          failed: result.failed,
          classes: result.classesAssigned,
        })
      );
      await loadTeachers();
    } catch (err: any) {
      setError(err?.message || t('teachers.applyFailed'));
    } finally {
      setBusy(null);
    }
  };

  const handleDiscard = () => {
    setRehearsal(null);
    setNotice(t('teachers.discarded'));
  };

  const startEditing = (username: string, classes: string[]) => {
    setEditingUsername(username);
    setDraftClasses(classes.join(';'));
    setError(null);
    setNotice(null);
  };

  /**
   * Corrects one teacher's classes without touching anybody else's, or the whole
   * staff file. The server refuses an empty list, so an empty box is caught here
   * rather than being sent.
   */
  const handleSaveClasses = async (username: string, name: string) => {
    if (!draftClasses.trim()) {
      setError(t('teachers.classesRequired'));
      return;
    }
    setBusy(`classes-${username}`);
    setError(null);
    setNotice(null);
    try {
      const result = await api.updateTeacherClasses(username, draftClasses.trim());
      setNotice(
        t('teachers.classesSaved', {
          name: name || username,
          classes: result.classes.join('; ') || '-',
        })
      );
      setEditingUsername(null);
      await loadTeachers();
    } catch (err: any) {
      setError(err?.message || t('teachers.classesSaveFailed'));
    } finally {
      setBusy(null);
    }
  };

  const handleDownload = async (label: string, run: () => Promise<string>) => {
    setBusy('download');
    setError(null);
    setNotice(null);
    try {
      const filename = await run();
      setNotice(t('common.downloaded', { filename }));
    } catch (err: any) {
      setError(err?.message || t('teachers.downloadFailed', { label }));
    } finally {
      setBusy(null);
    }
  };

  /** The counts an upload or a rehearsal reported, shown the same way in both cards. */
  const ResultStats = ({ result }: { result: TeacherUploadResultDTO }) => (
    <div className="stat-grid mt-2">
      <div className="stat">
        <div className="stat-value">{result.totalRows}</div>
        <div className="stat-label">{t('students.row')}</div>
      </div>
      <div className="stat">
        <div className="stat-value">{result.created}</div>
        <div className="stat-label">{t('students.created')}</div>
      </div>
      <div className="stat">
        <div className="stat-value">{result.updated}</div>
        <div className="stat-label">{t('students.updated')}</div>
      </div>
      <div className="stat">
        <div className="stat-value" style={{ color: result.failed > 0 ? '#d63031' : undefined }}>
          {result.failed}
        </div>
        <div className="stat-label">{t('students.failed')}</div>
      </div>
      <div className="stat">
        <div className="stat-value">{result.classesAssigned}</div>
        <div className="stat-label">{t('teachers.classesAssigned')}</div>
      </div>
    </div>
  );

  /** The row-level problems, which an upload reports instead of failing wholesale. */
  const RowErrors = ({ result }: { result: TeacherUploadResultDTO }) => {
    if (!result.errors || result.errors.length === 0) return null;
    return (
      <div className="mt-3">
        <h3>
          {t('students.rowErrors')} <span className="badge badge-danger">{result.errors.length}</span>
        </h3>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{t('students.row')}</th>
                <th>{t('auth.username')}</th>
                <th>{t('students.message')}</th>
              </tr>
            </thead>
            <tbody>
              {result.errors.map((rowError, index) => (
                <tr key={`${rowError.rowNumber}-${index}`}>
                  <td>{rowError.rowNumber}</td>
                  <td>{rowError.username || '-'}</td>
                  <td>{rowError.message}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    );
  };

  if (isLoading || !user || !isAdmin) {
    return <div>{t('common.loading')}</div>;
  }

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('teachers.title')}</h1>
        <Link href="/admin" className="btn btn-secondary">
          {t('common.backToAdmin')}
        </Link>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {notice && <div className="alert alert-success">{notice}</div>}

      <div className="hint">{t('teachers.subtitle')}</div>

      {/* Upload — rehearse first, then apply the same file. */}
      <div className="card mt-3">
        <h2>{t('teachers.importTools')}</h2>
        <p className="muted mt-2">{t('teachers.subtitleHint')}</p>

        <div className="toolbar mt-2">
          <div className="form-group" style={{ minWidth: '260px' }}>
            <label>{t('teachers.uploadFile')}</label>
            <input
              ref={fileInputRef}
              type="file"
              accept=".csv,.xlsx"
              onChange={e => {
                setFile(e.target.files?.[0] ?? null);
                // A rehearsal describes one exact file, so a new file needs a new one.
                setRehearsal(null);
              }}
            />
          </div>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy === 'rehearse' || busy === 'apply'}
            onClick={handleRehearse}
          >
            {busy === 'rehearse' ? t('teachers.rehearsing') : t('teachers.rehearse')}
          </button>
        </div>

        {rehearsal && (
          <div className="pill-actions mt-3">
            <button
              type="button"
              className="btn btn-success"
              disabled={busy === 'apply'}
              onClick={handleApply}
            >
              {busy === 'apply' ? t('teachers.applying') : t('teachers.apply')}
            </button>
            <button
              type="button"
              className="btn btn-secondary"
              disabled={busy === 'apply'}
              onClick={handleDiscard}
            >
              {t('teachers.discard')}
            </button>
          </div>
        )}

        <div className="pill-actions mt-3">
          <button
            type="button"
            className="btn btn-sm btn-secondary"
            disabled={busy === 'download'}
            onClick={() =>
              handleDownload(t('teachers.template'), () => api.downloadTeacherTemplate())
            }
          >
            {t('teachers.template')}
          </button>
          <button
            type="button"
            className="btn btn-sm btn-secondary"
            disabled={busy === 'download'}
            onClick={() =>
              handleDownload(t('teachers.credentials'), () => api.downloadTeacherCredentials())
            }
          >
            {t('teachers.credentials')}
          </button>
        </div>
        <div className="muted mt-2">{t('students.downloadTokenNote')}</div>
      </div>

      {/* The rehearsal — nothing has been written. */}
      {rehearsal && (
        <div className="card">
          <div className="flex justify-between items-center">
            <h2>{t('teachers.dryRunTitle')}</h2>
            <span className="badge badge-warning">{t('teachers.rehearse')}</span>
          </div>
          <p className="muted mt-2">{t('teachers.dryRunHint')}</p>

          <div className="hint mt-2">
            <strong>
              {t('teachers.dryRunBanner', {
                created: rehearsal.result.created,
                updated: rehearsal.result.updated,
                failed: rehearsal.result.failed,
              })}
            </strong>
          </div>

          <ResultStats result={rehearsal.result} />

          <div className="event-meta mt-3">
            <div>
              <strong>{t('students.file')}:</strong> {rehearsal.result.fileName || '-'}
            </div>
            <div>
              <strong>{t('students.batch')}:</strong> {rehearsal.result.batch || '-'}
            </div>
            <div>
              <strong>{t('teachers.passwordRule')}:</strong>{' '}
              <code>{rehearsal.result.passwordRule}</code>
            </div>
          </div>

          <RowErrors result={rehearsal.result} />
        </div>
      )}

      {/* The applied upload. */}
      {applied && (
        <div className="card">
          <h2>{t('students.importResult')}</h2>
          <ResultStats result={applied} />

          <div className="event-meta mt-3">
            <div>
              <strong>{t('students.batch')}:</strong> {applied.batch || '-'}
            </div>
            <div>
              <strong>{t('students.file')}:</strong> {applied.fileName || '-'}
            </div>
            <div>
              <strong>{t('teachers.passwordRule')}:</strong>{' '}
              <code>{applied.passwordRule}</code>
            </div>
          </div>

          {/* A password is only known here, once — for a new account, or one the file re-keyed. */}
          {applied.credentials.length > 0 && (
            <div className="mt-3">
              <h3>
                {t('teachers.credentialsIssued', { count: applied.credentials.length })}
              </h3>
              <p className="muted">{t('teachers.credentialsHint')}</p>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>{t('auth.username')}</th>
                      <th>{t('students.colName')}</th>
                      <th>{t('teachers.classes')}</th>
                      <th>{t('auth.password')}</th>
                      <th>{t('admin.colStatus')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {applied.credentials.map(credential => (
                      <tr key={credential.username}>
                        <td>{credential.username}</td>
                        <td>{credential.name}</td>
                        <td>{credential.classes.join('; ') || '-'}</td>
                        <td>
                          <code>{credential.password}</code>
                        </td>
                        <td>
                          <span
                            className={
                              credential.supplied ? 'badge badge-info' : 'badge badge-success'
                            }
                          >
                            {credential.supplied ? t('teachers.supplied') : t('teachers.derived')}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          <RowErrors result={applied} />
        </div>
      )}

      {/* The teachers */}
      <div className="card">
        <div className="flex justify-between items-center">
          <h2>{t('teachers.title')}</h2>
          <span className="badge badge-info">{visibleTeachers.length}</span>
        </div>

        <div className="toolbar mt-2">
          <div className="form-group">
            <label>{t('common.search')}</label>
            <input
              type="text"
              value={search}
              placeholder={t('teachers.searchPlaceholder')}
              onChange={e => setSearch(e.target.value)}
            />
          </div>
        </div>

        {/* The classes come from the list itself where it carries them, and from
            the credentials sheet otherwise — say which, rather than letting the
            column look arbitrarily blank. */}
        {usedSheet && (
          <p className="muted mt-2">
            {classMap === null && !loading
              ? t('teachers.classesUnknown')
              : t('teachers.classesFromSheet')}
          </p>
        )}

        {listError && <div className="alert alert-error">{listError}</div>}

        {loading ? (
          <p className="muted mt-2">{t('teachers.loadingTeachers')}</p>
        ) : visibleTeachers.length === 0 ? (
          <p className="muted mt-2">
            {teachers.length === 0 ? t('teachers.none') : t('teachers.noMatch')}
          </p>
        ) : (
          <div className="table-wrap mt-2">
            <table>
              <thead>
                <tr>
                  <th>{t('auth.username')}</th>
                  <th>{t('students.colName')}</th>
                  <th>{t('auth.email')}</th>
                  <th>{t('teachers.classes')}</th>
                  <th>{t('admin.colStatus')}</th>
                  <th>{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {visibleTeachers.map(teacher => (
                  <tr key={teacher.username}>
                    <td>{teacher.username}</td>
                    <td>{teacher.name || '-'}</td>
                    <td>{teacher.email || '-'}</td>
                    <td>
                      {teacher.classes.length > 0 ? (
                        <div className="pill-actions">
                          {teacher.classes.map(className => (
                            <span key={className} className="badge badge-info">
                              {className}
                            </span>
                          ))}
                        </div>
                      ) : (
                        <span className="muted">{t('teachers.noClass')}</span>
                      )}
                    </td>
                    <td>
                      <span
                        className={teacher.enabled ? 'badge badge-success' : 'badge badge-danger'}
                      >
                        {teacher.enabled ? t('teachers.enabled') : t('teachers.disabled')}
                      </span>
                    </td>
                    <td>
                      {editingUsername === teacher.username ? (
                        <div className="pill-actions">
                          <input
                            type="text"
                            value={draftClasses}
                            placeholder={t('teachers.classesPlaceholder')}
                            onChange={e => setDraftClasses(e.target.value)}
                          />
                          <button
                            type="button"
                            className="btn btn-sm btn-success"
                            disabled={busy === `classes-${teacher.username}`}
                            onClick={() => handleSaveClasses(teacher.username, teacher.name)}
                          >
                            {busy === `classes-${teacher.username}`
                              ? t('common.saving')
                              : t('common.saveChanges')}
                          </button>
                          <button
                            type="button"
                            className="btn btn-sm btn-secondary"
                            disabled={busy === `classes-${teacher.username}`}
                            onClick={() => setEditingUsername(null)}
                          >
                            {t('common.cancel')}
                          </button>
                        </div>
                      ) : (
                        <button
                          type="button"
                          className="btn btn-sm btn-secondary"
                          onClick={() => startEditing(teacher.username, teacher.classes)}
                        >
                          {t('teachers.editClasses')}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
