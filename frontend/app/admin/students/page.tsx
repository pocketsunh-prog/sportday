'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import {
  api,
  Grade,
  GradeRuleDTO,
  SexCode,
  StudentDTO,
  StudentFilters,
  StudentSummaryDTO,
  StudentUploadResultDTO,
} from '@/lib/api';
import { formatCounts, formatDate } from '@/lib/format';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';
import { classText, formText, houseText } from '@/lib/students';

const EMPTY_FILTERS: StudentFilters = {
  className: '',
  sex: '',
  grade: '',
  house: '',
  // `''` is both active and locked students.
  enabled: '',
};

export default function AdminStudentsPage() {
  const { user } = useAuth();
  const { t, label } = useI18n();
  const isAdmin = user?.role === 'ADMIN';
  const [students, setStudents] = useState<StudentDTO[]>([]);
  const [allStudents, setAllStudents] = useState<StudentDTO[]>([]);
  const [summary, setSummary] = useState<StudentSummaryDTO | null>(null);
  const [gradeRule, setGradeRule] = useState<GradeRuleDTO | null>(null);
  const [filters, setFilters] = useState<StudentFilters>(EMPTY_FILTERS);
  const [search, setSearch] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [file, setFile] = useState<File | null>(null);
  const [referenceDate, setReferenceDate] = useState('');
  const [uploadResult, setUploadResult] = useState<StudentUploadResultDTO | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  /**
   * "This is the complete student list for this year". When ticked, the upload
   * goes through the roster endpoint: a dry run first, then an explicit confirm,
   * because getting it wrong locks students out.
   */
  const [completeList, setCompleteList] = useState(false);
  /**
   * The dry-run preview of who would be locked, with the exact file it describes
   * — the confirm step must never lock a different file from the one previewed.
   */
  const [preview, setPreview] = useState<{
    result: StudentUploadResultDTO;
    file: File;
  } | null>(null);

  const refreshSummary = useCallback(async () => {
    const [summaryResult, rule] = await Promise.all([
      api.getStudentSummary().catch(() => null),
      api.getGradeRule().catch(() => null),
    ]);
    if (summaryResult) setSummary(summaryResult);
    if (rule) setGradeRule(rule);
  }, []);

  const refreshAllStudents = useCallback(async () => {
    const list = await api.getStudents().catch(() => [] as StudentDTO[]);
    setAllStudents(list);
  }, []);

  const loadStudents = useCallback(async (active: StudentFilters) => {
    setLoading(true);
    try {
      const list = await api.getStudents(active);
      setStudents(list);
    } catch (err: any) {
      setError(err?.message || t('students.loadFailed'));
      setStudents([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refreshSummary();
    refreshAllStudents();
  }, [refreshSummary, refreshAllStudents]);

  useEffect(() => {
    loadStudents(filters);
  }, [filters, loadStudents]);

  const houseOptions = useMemo(
    () => Array.from(new Set(allStudents.map(s => s.house).filter(Boolean))).sort(),
    [allStudents]
  );
  const classOptions = useMemo(
    () => Array.from(new Set(allStudents.map(s => s.className).filter(Boolean))).sort(),
    [allStudents]
  );

  const visibleStudents = useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) return students;
    return students.filter(
      s =>
        s.studentId.toLowerCase().includes(needle) ||
        s.name.toLowerCase().includes(needle) ||
        s.classLabel.toLowerCase().includes(needle)
    );
  }, [students, search]);

  const updateFilter = (key: keyof StudentFilters, value: string) => {
    setFilters(prev => ({ ...prev, [key]: value as SexCode | Grade | '' }));
  };

  /** Active / Locked / All, which the server applies as `?enabled=`. */
  const setStatusFilter = (value: string) => {
    setFilters(prev => ({
      ...prev,
      enabled: value === 'active' ? true : value === 'locked' ? false : '',
    }));
  };

  /** Re-reads everything a register change can affect. */
  const refreshAfterChange = useCallback(
    async (active: StudentFilters) => {
      await Promise.all([refreshSummary(), refreshAllStudents(), loadStudents(active)]);
    },
    [refreshSummary, refreshAllStudents, loadStudents]
  );

  const handleUpload = async () => {
    if (!file) {
      setError(t('students.chooseFileFirst'));
      return;
    }
    if (completeList) {
      await previewRoster(file);
      return;
    }
    setBusy('upload');
    setError(null);
    setNotice(null);
    try {
      const result = await api.uploadStudents(file, referenceDate || undefined);
      setUploadResult(result);
      setNotice(
        t('students.uploadFinished', {
          created: result.created,
          updated: result.updated,
          failed: result.failed,
        })
      );
      setFile(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
      await refreshAfterChange(filters);
    } catch (err: any) {
      setError(err?.message || t('students.uploadFailed'));
    } finally {
      setBusy(null);
    }
  };

  /**
   * The dry run of the complete-list upload. It changes nothing and answers with
   * the count and a sample of the students the file leaves out, which is what the
   * admin confirms against.
   */
  const previewRoster = async (chosen: File) => {
    setBusy('preview');
    setError(null);
    setNotice(null);
    try {
      const result = await api.uploadStudentsRoster(chosen, true);
      setPreview({ result, file: chosen });
      setUploadResult(null);
    } catch (err: any) {
      setError(err?.message || t('students.uploadFailed'));
    } finally {
      setBusy(null);
    }
  };

  /** The explicit confirm: the same file again, this time actually locking. */
  const handleConfirmLock = async () => {
    if (!preview) return;
    setBusy('lock');
    setError(null);
    setNotice(null);
    try {
      const result = await api.uploadStudentsRoster(preview.file, false);
      setUploadResult(result);
      const unlocked = result.unlocked ?? 0;
      // `locked` is what this run locked; the roster endpoint always populates
      // `lockedTotal` as well, so fall back to it rather than reporting a
      // misleading zero.
      const locked = (result.locked ?? 0) > 0 ? (result.locked as number) : result.lockedTotal ?? 0;
      if (locked > 0 && unlocked > 0) {
        setNotice(t('students.lockApplied', { locked, unlocked }));
      } else if (locked > 0) {
        setNotice(t('students.lockAppliedOnly', { locked }));
      } else if (unlocked > 0) {
        setNotice(t('students.unlockedOnly', { count: unlocked }));
      } else {
        // Every student was in the file, so there was nobody to lock.
        setNotice(
          t('students.completeListDone', {
            created: result.created,
            updated: result.updated,
            failed: result.failed,
          })
        );
      }
      setPreview(null);
      setCompleteList(false);
      setFile(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
      await refreshAfterChange(filters);
    } catch (err: any) {
      setError(err?.message || t('students.lockFailed'));
    } finally {
      setBusy(null);
    }
  };

  const handleDiscardPreview = () => {
    setPreview(null);
    setNotice(t('students.lockDiscarded'));
  };

  /**
   * The other way into the same state, for an admin who uploaded a partial file
   * without declaring it complete.
   */
  const handleLockMissing = async () => {
    if (!confirm(t('students.lockMissingConfirm'))) return;
    setBusy('lock-missing');
    setError(null);
    setNotice(null);
    try {
      const result = await api.lockMissingStudents();
      setNotice(
        t('students.lockMissingDone', {
          locked: result.locked ?? 0,
          now: result.lockedNow ?? 0,
          batch: result.latestBatch || '-',
        })
      );
      await refreshAfterChange(filters);
    } catch (err: any) {
      setError(err?.message || t('students.lockMissingFailed'));
    } finally {
      setBusy(null);
    }
  };

  /**
   * Locks one student out of the register, or restores them. Their entries,
   * results and records stay where they are either way.
   */
  const handleToggleLock = async (student: StudentDTO) => {
    setBusy(`lock-${student.id}`);
    setError(null);
    setNotice(null);
    try {
      const updated = await api.setStudentLock(student.studentId, !student.enabled);
      setNotice(
        t(updated.enabled ? 'students.unlockedNotice' : 'students.lockedNotice', {
          name: student.name,
        })
      );
      await refreshAfterChange(filters);
    } catch (err: any) {
      setError(err?.message || t('students.lockStatusFailed'));
    } finally {
      setBusy(null);
    }
  };

  const handleGenerateSample = async () => {
    setBusy('sample');
    setError(null);
    setNotice(null);
    try {
      const result = await api.generateSampleStudents(600);
      setUploadResult(result);
      setNotice(
        t('students.sampleGenerated', {
          created: result.created,
          updated: result.updated,
          failed: result.failed,
        })
      );
      await refreshAfterChange(filters);
    } catch (err: any) {
      setError(err?.message || t('students.sampleFailed'));
    } finally {
      setBusy(null);
    }
  };

  const handleRecompute = async () => {
    setBusy('recompute');
    setError(null);
    setNotice(null);
    try {
      const result = await api.recomputeGrades(referenceDate || undefined);
      setNotice(
        t('students.recomputeFinished', {
          changed: result.changed,
          date: formatDate(result.referenceDate),
          counts: formatCounts(result.byGrade),
        })
      );
      await refreshAfterChange(filters);
    } catch (err: any) {
      setError(err?.message || t('students.recomputeFailed'));
    } finally {
      setBusy(null);
    }
  };

  const handleDownload = async (itemLabel: string, run: () => Promise<string>) => {
    setBusy('download');
    setError(null);
    setNotice(null);
    try {
      const filename = await run();
      setNotice(t('students.downloaded', { label: itemLabel, filename }));
    } catch (err: any) {
      setError(err?.message || t('students.downloadFailed', { label: itemLabel }));
    } finally {
      setBusy(null);
    }
  };

  // The named sample the server sent (up to 100 of them) and the true total it
  // stands for, so the preview can say "showing 100 of 599".
  const lockedSample = preview?.result.lockedStudents ?? [];
  const lockedTotal = preview?.result.lockedTotal ?? 0;

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('students.title')}</h1>
        <Link href="/admin" className="btn btn-secondary">
          {t('common.backToAdmin')}
        </Link>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {notice && <div className="alert alert-success">{notice}</div>}

      {/* Grade rule */}
      {gradeRule && (
        <div className="hint">
          <strong>{t('students.gradeRule')}</strong>
          <div className="mt-2">
            {t('students.gradeRuleLine', {
              a: gradeRule.A,
              b: gradeRule.B,
              c: gradeRule.C,
            })}
          </div>
          <div className="muted mt-2">{gradeRule.note}</div>
          <div className="muted">
            {t('auth.password')}: <code>{gradeRule.passwordRule}</code>
          </div>
        </div>
      )}

      {/* Summary */}
      <div className="stat-grid mt-3">
        <div className="stat">
          <div className="stat-value">{summary ? summary.total : '-'}</div>
          <div className="stat-label">{t('students.total')}</div>
        </div>
        {(['A', 'B', 'C'] as const).map(grade => (
          <div key={grade} className="stat">
            <div className="stat-value">{summary ? summary.byGrade?.[grade] ?? 0 : '-'}</div>
            <div className="stat-label">{label('grade', grade)}</div>
          </div>
        ))}
        <div className="stat">
          <div className="stat-value">
            {summary ? summary.bySex?.MALE ?? summary.bySex?.M ?? 0 : '-'}
          </div>
          <div className="stat-label">{label('sex', 'MALE')}</div>
        </div>
        <div className="stat">
          <div className="stat-value">
            {summary ? summary.bySex?.FEMALE ?? summary.bySex?.F ?? 0 : '-'}
          </div>
          <div className="stat-label">{label('sex', 'FEMALE')}</div>
        </div>
      </div>

      {/* Import / tools */}
      <div className="card mt-3">
        <h2>{t('students.importTools')}</h2>
        <div className="toolbar mt-2">
          <div className="form-group">
            <label>{t('students.referenceDate')}</label>
            <input
              type="date"
              value={referenceDate}
              onChange={e => setReferenceDate(e.target.value)}
            />
          </div>
          <div className="form-group" style={{ minWidth: '260px' }}>
            <label>{t('students.registerFile')}</label>
            <input
              ref={fileInputRef}
              type="file"
              accept=".csv,.xlsx"
              onChange={e => {
                setFile(e.target.files?.[0] ?? null);
                // A preview describes one exact file, so a new file needs a new one.
                setPreview(null);
              }}
            />
          </div>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy === 'upload' || busy === 'preview'}
            onClick={handleUpload}
          >
            {busy === 'upload'
              ? t('common.uploading')
              : busy === 'preview'
                ? t('students.previewing')
                : completeList
                  ? t('students.previewLock')
                  : t('common.upload')}
          </button>
          <button
            type="button"
            className="btn btn-success"
            disabled={busy === 'sample'}
            onClick={handleGenerateSample}
          >
            {busy === 'sample' ? t('students.generating') : t('students.generateSample')}
          </button>
          <button
            type="button"
            className="btn btn-secondary"
            disabled={busy === 'recompute'}
            onClick={handleRecompute}
          >
            {busy === 'recompute' ? t('students.computing') : t('students.recomputeGrades')}
          </button>
        </div>

        {/* The yearly roster: declaring this file the complete list for the year. */}
        <label className="checkbox-line">
          <input
            type="checkbox"
            checked={completeList}
            onChange={e => {
              setCompleteList(e.target.checked);
              setPreview(null);
            }}
          />
          {t('students.completeList')}
        </label>
        <div className="muted">{t('students.completeListHint')}</div>
        {completeList && <div className="muted">{t('students.rosterUsesServerDate')}</div>}

        <div className="mt-3">
          <button
            type="button"
            className="btn btn-sm btn-secondary"
            disabled={busy === 'lock-missing'}
            onClick={handleLockMissing}
          >
            {busy === 'lock-missing' ? t('students.lockingMissing') : t('students.lockMissing')}
          </button>
          <div className="muted mt-2">{t('students.lockMissingHint')}</div>
        </div>

        <div className="pill-actions mt-3">
          <button
            type="button"
            className="btn btn-sm btn-secondary"
            disabled={busy === 'download'}
            onClick={() =>
              handleDownload(t('students.credentials'), () =>
                api.downloadCredentialsCsv(filters.className || undefined)
              )
            }
          >
            {t('students.credentials')}
            {filters.className ? ` (${filters.className})` : ''}
          </button>
          <button
            type="button"
            className="btn btn-sm btn-secondary"
            disabled={busy === 'download'}
            onClick={() =>
              handleDownload(t('students.template'), () => api.downloadStudentTemplate())
            }
          >
            {t('students.template')}
          </button>
          <button
            type="button"
            className="btn btn-sm btn-secondary"
            disabled={busy === 'download'}
            onClick={() => handleDownload(t('students.sampleCsv'), () => api.downloadStudentSample(600))}
          >
            {t('students.sampleCsv')}
          </button>
        </div>
        <div className="muted mt-2">
          {t('students.downloadTokenNote')}
        </div>
      </div>

      {/* Lock preview — the dry run of the complete-list upload. */}
      {preview && (
        <div className="card">
          <div className="flex justify-between items-center">
            <h2>{t('students.lockPreviewTitle')}</h2>
            <span className="badge badge-warning">
              {t('students.wouldLock')}: {lockedTotal}
            </span>
          </div>
          <p className="muted">{t('students.lockPreviewHint')}</p>

          <div className="hint mt-2">
            <strong>{t('students.lockTotal', { count: lockedTotal })}</strong>
            <div className="mt-2">{t('students.lockConsequence')}</div>
          </div>

          <div className="event-meta mt-3">
            <div>
              <strong>{t('students.file')}:</strong> {preview.result.fileName || '-'}
            </div>
            <div>
              <strong>{t('students.batch')}:</strong> {preview.result.batch || '-'}
            </div>
            <div>
              <strong>{t('students.created')}:</strong> {preview.result.created} ·{' '}
              <strong>{t('students.updated')}:</strong> {preview.result.updated} ·{' '}
              <strong>{t('students.failed')}:</strong> {preview.result.failed}
            </div>
          </div>

          {lockedSample.length > 0 && (
            <div className="mt-3">
              <h3>{t('students.wouldLock')}</h3>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>{t('students.colId')}</th>
                      <th>{t('students.colName')}</th>
                      <th>{t('marks.class')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {lockedSample.map(student => (
                      <tr key={student.studentId}>
                        <td>{student.studentId}</td>
                        <td>{student.name}</td>
                        <td>
                          {`${student.className} ${student.classNumber}`.trim() || '-'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {lockedSample.length < lockedTotal && (
                <p className="muted mt-2">
                  {t('students.lockSampleNote', {
                    shown: lockedSample.length,
                    total: lockedTotal,
                  })}
                </p>
              )}
            </div>
          )}

          <div className="pill-actions mt-3">
            <button
              type="button"
              className="btn btn-danger"
              disabled={busy === 'lock'}
              onClick={handleConfirmLock}
            >
              {busy === 'lock' ? t('students.locking') : t('students.lockConfirm')}
            </button>
            <button
              type="button"
              className="btn btn-secondary"
              disabled={busy === 'lock'}
              onClick={handleDiscardPreview}
            >
              {t('students.lockDiscard')}
            </button>
          </div>
        </div>
      )}

      {/* Upload result */}
      {uploadResult && (
        <div className="card">
          <h2>{t('students.importResult')}</h2>
          <div className="stat-grid mt-2">
            <div className="stat">
              <div className="stat-value">{uploadResult.totalRows}</div>
              <div className="stat-label">{t('students.row')}</div>
            </div>
            <div className="stat">
              <div className="stat-value">{uploadResult.created}</div>
              <div className="stat-label">{t('students.created')}</div>
            </div>
            <div className="stat">
              <div className="stat-value">{uploadResult.updated}</div>
              <div className="stat-label">{t('students.updated')}</div>
            </div>
            <div className="stat">
              <div
                className="stat-value"
                style={{ color: uploadResult.failed > 0 ? '#d63031' : undefined }}
              >
                {uploadResult.failed}
              </div>
              <div className="stat-label">{t('students.failed')}</div>
            </div>
          </div>
          <div className="event-meta mt-3">
            <div>
              <strong>{t('students.batch')}:</strong> {uploadResult.batch || '-'}
            </div>
            <div>
              <strong>{t('students.file')}:</strong> {uploadResult.fileName || '-'}
            </div>
            <div>
              <strong>{t('students.gradeReference')}:</strong>{' '}
              {formatDate(uploadResult.gradeReferenceDate)}
            </div>
            <div>
              <strong>{t('students.byGrade')}:</strong> {formatCounts(uploadResult.gradeCounts)}
            </div>
          </div>

          {uploadResult.errors && uploadResult.errors.length > 0 && (
            <div className="mt-3">
              <h3>
                {t('students.rowErrors')}{' '}
                <span className="badge badge-danger">{uploadResult.errors.length}</span>
              </h3>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>{t('students.row')}</th>
                      <th>{t('students.colId')}</th>
                      <th>{t('students.message')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {uploadResult.errors.map((rowError, index) => (
                      <tr key={`${rowError.rowNumber}-${index}`}>
                        <td>{rowError.rowNumber}</td>
                        <td>{rowError.studentId || '-'}</td>
                        <td>{rowError.message}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Students */}
      <div className="card">
        <div className="flex justify-between items-center">
          <h2>{t('students.title')}</h2>
          <span className="badge badge-info">{visibleStudents.length}</span>
        </div>

        <div className="toolbar mt-2">
          <div className="form-group">
            <label>{t('common.search')}</label>
            <input
              type="text"
              value={search}
              placeholder={t('students.searchPlaceholder')}
              onChange={e => setSearch(e.target.value)}
            />
          </div>
          <div className="form-group">
            <label>{t('marks.class')}</label>
            <input
              type="text"
              list="class-options"
              value={filters.className || ''}
              placeholder={t('students.classPlaceholder')}
              onChange={e => updateFilter('className', e.target.value)}
            />
            <datalist id="class-options">
              {classOptions.map(option => (
                <option key={option} value={option} />
              ))}
            </datalist>
          </div>
          <div className="form-group">
            <label>{t('common.sex')}</label>
            <select value={filters.sex || ''} onChange={e => updateFilter('sex', e.target.value)}>
              <option value="">{t('common.all')}</option>
              <option value="M">{label('sex', 'MALE')}</option>
              <option value="F">{label('sex', 'FEMALE')}</option>
            </select>
          </div>
          <div className="form-group">
            <label>{t('marks.grade')}</label>
            <select value={filters.grade || ''} onChange={e => updateFilter('grade', e.target.value)}>
              <option value="">{t('common.all')}</option>
              <option value="A">{label('grade', 'A')}</option>
              <option value="B">{label('grade', 'B')}</option>
              <option value="C">{label('grade', 'C')}</option>
            </select>
          </div>
          <div className="form-group">
            <label>{t('students.colHouse')}</label>
            <select value={filters.house || ''} onChange={e => updateFilter('house', e.target.value)}>
              <option value="">{t('common.all')}</option>
              {houseOptions.map(option => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </div>
          <div className="form-group">
            <label>{t('students.statusFilter')}</label>
            <select
              value={
                filters.enabled === true ? 'active' : filters.enabled === false ? 'locked' : ''
              }
              onChange={e => setStatusFilter(e.target.value)}
            >
              <option value="">{t('common.all')}</option>
              <option value="active">{t('students.statusActive')}</option>
              <option value="locked">{t('students.locked')}</option>
            </select>
          </div>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => {
              setFilters(EMPTY_FILTERS);
              setSearch('');
            }}
          >
            {t('marks.clearMark')}
          </button>
        </div>

        {(filters.enabled === false ||
          visibleStudents.some(student => !student.enabled)) && (
          <p className="muted mt-2">{t('students.lockedNote')}</p>
        )}

        {loading ? (
          <p className="muted mt-2">{t('students.loadingStudents')}</p>
        ) : visibleStudents.length === 0 ? (
          <p className="muted mt-2">{t('students.noMatch')}</p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t('students.colId')}</th>
                  <th>{t('students.colName')}</th>
                  <th>{t('students.colDob')}</th>
                  <th>{t('students.colAge')}</th>
                  <th>{t('common.sex')}</th>
                  <th>{t('students.form')}</th>
                  <th>{t('marks.class')}</th>
                  <th>{t('students.colHouse')}</th>
                  <th>{t('marks.grade')}</th>
                  {isAdmin && <th>{t('common.actions')}</th>}
                </tr>
              </thead>
              <tbody>
                {visibleStudents.map(student => (
                  <tr key={student.id} className={student.enabled ? undefined : 'row-disabled'}>
                    <td>{student.studentId}</td>
                    <td>
                      {student.name}
                      {!student.enabled && (
                        <span className="badge badge-danger" style={{ marginLeft: '0.5rem' }}>
                          {t('students.locked')}
                        </span>
                      )}
                    </td>
                    <td>{formatDate(student.dob)}</td>
                    <td>{student.age}</td>
                    <td>{label('sex', student.sex)}</td>
                    <td>{formText(student, t)}</td>
                    <td>{classText(student) || '-'}</td>
                    <td>{houseText(student)}</td>
                    <td>
                      <span className="badge badge-info">{label('grade', student.grade)}</span>
                      {student.gradeAgeRange && (
                        <div className="muted">{student.gradeAgeRange}</div>
                      )}
                    </td>
                    {isAdmin && (
                      <td>
                        <div className="pill-actions">
                          <Link
                            href={`/admin/students/${student.studentId}/entries`}
                            className="btn btn-sm btn-primary"
                          >
                            {t('students.entries')}
                          </Link>
                          <button
                            type="button"
                            className={`btn btn-sm ${student.enabled ? 'btn-danger' : 'btn-success'}`}
                            disabled={busy === `lock-${student.id}`}
                            onClick={() => handleToggleLock(student)}
                          >
                            {student.enabled ? t('students.lock') : t('students.unlock')}
                          </button>
                        </div>
                      </td>
                    )}
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
