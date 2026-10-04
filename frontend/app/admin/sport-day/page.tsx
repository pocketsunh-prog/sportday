'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api, BackupSummaryDTO, SeasonDTO, SeasonInput, SeasonUpdate, SettingsDTO, SettingsUpdate } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { formatDate, formatDateTime } from '@/lib/format';
import { useI18n } from '@/lib/i18n';

/** The five school fields that head every printed marking sheet. */
type SchoolForm = {
  schoolName: string;
  schoolNameZh: string;
  address: string;
  principal: string;
  sportDayTitle: string;
};

const EMPTY_SCHOOL_FORM: SchoolForm = {
  schoolName: '',
  schoolNameZh: '',
  address: '',
  principal: '',
  sportDayTitle: '',
};

/**
 * The stored school row is allowed to be missing a value (the API omits null
 * fields altogether), so coerce every field to a string the form can bind to.
 */
function toSchoolForm(settings: SettingsDTO): SchoolForm {
  return {
    schoolName: settings.schoolName ?? '',
    schoolNameZh: settings.schoolNameZh ?? '',
    address: settings.address ?? '',
    principal: settings.principal ?? '',
    sportDayTitle: settings.sportDayTitle ?? '',
  };
}

interface CreateForm {
  year: string;
  name: string;
  sportDayDate: string;
  enrollmentOpen: boolean;
  /** `''` starts the year with an empty programme. */
  copyFrom: string;
}

const EMPTY_CREATE_FORM: CreateForm = {
  year: '',
  name: '',
  sportDayDate: '',
  enrollmentOpen: false,
  copyFrom: '',
};

interface EditForm {
  year: string;
  name: string;
  sportDayDate: string;
  enrollmentOpen: boolean;
  notes: string;
}

function toEditForm(season: SeasonDTO): EditForm {
  return {
    year: String(season.year ?? ''),
    name: season.name ?? '',
    sportDayDate: season.sportDayDate ?? '',
    enrollmentOpen: Boolean(season.enrollmentOpen),
    notes: season.notes ?? '',
  };
}

export default function AdminSportDayPage() {
  const { user, isLoading } = useAuth();
  const { t } = useI18n();
  const router = useRouter();

  const [settings, setSettings] = useState<SettingsDTO | null>(null);
  const [schoolForm, setSchoolForm] = useState<SchoolForm>(EMPTY_SCHOOL_FORM);
  const [seasons, setSeasons] = useState<SeasonDTO[]>([]);
  /**
   * The season backups, so the office can see from here that a reset was backed
   * up. The full list — with download and restore — lives on its own page; this
   * card only names the newest one and points at it.
   */
  const [backups, setBackups] = useState<BackupSummaryDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [createForm, setCreateForm] = useState<CreateForm>(EMPTY_CREATE_FORM);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editForm, setEditForm] = useState<EditForm | null>(null);

  const currentSeason = useMemo(() => seasons.find(season => season.current) || null, [seasons]);
  const editingSeason = useMemo(
    () => seasons.find(season => season.id === editingId) || null,
    [seasons, editingId]
  );

  const loadSeasons = useCallback(async () => {
    const list = await api.getSeasons().catch(() => [] as SeasonDTO[]);
    setSeasons(list);
    return list;
  }, []);

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    if (user.role !== 'ADMIN') {
      router.push('/');
      return;
    }
    Promise.all([
      api.getSettings().catch(() => null),
      api.getSeasons().catch(() => [] as SeasonDTO[]),
      api.getBackups().catch(() => [] as BackupSummaryDTO[]),
    ])
      .then(([settingsResult, seasonList, backupList]) => {
        if (settingsResult) {
          setSettings(settingsResult);
          setSchoolForm(toSchoolForm(settingsResult));
        }
        setSeasons(seasonList);
        setBackups(backupList);
      })
      .catch((err: unknown) =>
        setError(err instanceof Error ? err.message : t('sportDay.loadFailed'))
      )
      .finally(() => setLoading(false));
  }, [user, isLoading, router, t]);

  /* ---------------- A. the school's details ---------------- */

  const setSchoolValue = (field: keyof SchoolForm, value: string) => {
    setSchoolForm(prev => ({ ...prev, [field]: value }));
  };

  const handleSaveSchool = async () => {
    setNotice('');
    setError('');
    setBusy('school');
    try {
      const patch: SettingsUpdate = {
        schoolName: schoolForm.schoolName.trim(),
        schoolNameZh: schoolForm.schoolNameZh.trim(),
        address: schoolForm.address.trim(),
        principal: schoolForm.principal.trim(),
        sportDayTitle: schoolForm.sportDayTitle.trim(),
      };
      const updated = await api.updateSettings(patch);
      setSettings(updated);
      setSchoolForm(toSchoolForm(updated));
      setNotice(t('sportDay.schoolSaved'));
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('settings.saveFailed'));
    } finally {
      setBusy(null);
    }
  };

  /* ---------------- B. the school years ---------------- */

  /** Fills the name in from the year until the admin types one of their own. */
  const handleCreateYear = (value: string) => {
    setCreateForm(prev => {
      const suggested = prev.name === '' || prev.name === t('sportDay.defaultName', { year: prev.year });
      return {
        ...prev,
        year: value,
        name: suggested && value ? t('sportDay.defaultName', { year: value }) : prev.name,
      };
    });
  };

  const handleCreate = async () => {
    setNotice('');
    setError('');
    const year = createForm.year.trim();
    if (!/^\d{4}$/.test(year)) {
      setError(t('sportDay.yearRequired'));
      return;
    }
    if (!createForm.name.trim()) {
      setError(t('sportDay.nameRequired'));
      return;
    }
    if (!createForm.sportDayDate) {
      setError(t('sportDay.dateRequired'));
      return;
    }
    const payload: SeasonInput = {
      year: Number(year),
      name: createForm.name.trim(),
      sportDayDate: createForm.sportDayDate,
      enrollmentOpen: createForm.enrollmentOpen,
    };
    if (createForm.copyFrom) payload.copyEventsFromSeasonId = Number(createForm.copyFrom);

    setBusy('create');
    try {
      const created = await api.createSeason(payload);
      setCreateForm(EMPTY_CREATE_FORM);
      await loadSeasons();
      setNotice(t('sportDay.created', { name: created.displayName || created.name }));
    } catch (err: unknown) {
      // The server's own wording (e.g. "There is already a sport day for 2027.")
      // is the most useful thing to show here.
      setError(err instanceof Error ? err.message : t('sportDay.createFailed'));
    } finally {
      setBusy(null);
    }
  };

  const startEdit = (season: SeasonDTO) => {
    setEditingId(season.id);
    setEditForm(toEditForm(season));
    setNotice('');
    setError('');
  };

  const setEditValue = <K extends keyof EditForm>(field: K, value: EditForm[K]) => {
    setEditForm(prev => (prev ? { ...prev, [field]: value } : prev));
  };

  const handleSaveEdit = async () => {
    if (!editingSeason || !editForm) return;
    setNotice('');
    setError('');
    const year = editForm.year.trim();
    if (!/^\d{4}$/.test(year)) {
      setError(t('sportDay.yearRequired'));
      return;
    }
    if (!editForm.name.trim()) {
      setError(t('sportDay.nameRequired'));
      return;
    }
    if (!editForm.sportDayDate) {
      setError(t('sportDay.dateRequired'));
      return;
    }
    const patch: SeasonUpdate = {
      year: Number(year),
      name: editForm.name.trim(),
      sportDayDate: editForm.sportDayDate,
      enrollmentOpen: editForm.enrollmentOpen,
      notes: editForm.notes.trim() || null,
    };
    setBusy('edit');
    try {
      const updated = await api.updateSeason(editingSeason.id, patch);
      setEditingId(null);
      setEditForm(null);
      await loadSeasons();
      setNotice(t('sportDay.saved', { name: updated.displayName || updated.name }));
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('sportDay.saveFailed'));
    } finally {
      setBusy(null);
    }
  };

  /**
   * The enrolment switch itself. This is the one that decides whether students
   * can enter, so it is a first-class action on every row rather than something
   * buried inside the edit form.
   */
  const handleToggleEnrollment = async (season: SeasonDTO) => {
    setNotice('');
    setError('');
    setBusy(`open-${season.id}`);
    try {
      await api.updateSeason(season.id, { enrollmentOpen: !season.enrollmentOpen });
      // Re-read rather than trusting the patch: the server is the truth.
      await loadSeasons();
      setNotice(
        t(season.enrollmentOpen ? 'sportDay.enrollmentClosed' : 'sportDay.enrollmentOpened', {
          year: season.year,
        })
      );
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('sportDay.enrollmentFailed'));
    } finally {
      setBusy(null);
    }
  };

  const handleActivate = async (season: SeasonDTO) => {
    if (!confirm(t('sportDay.activateConfirm', { year: season.year }))) return;
    setNotice('');
    setError('');
    setBusy(`activate-${season.id}`);
    try {
      await api.activateSeason(season.id);
      await loadSeasons();
      setNotice(t('sportDay.activated', { year: season.year }));
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('sportDay.activateFailed'));
    } finally {
      setBusy(null);
    }
  };

  const handleDelete = async (season: SeasonDTO) => {
    const count = season.eventCount || 0;
    const warning = count
      ? t('sportDay.deleteConfirmWithEvents', { name: season.displayName || season.name, count })
      : t('sportDay.deleteConfirm', { name: season.displayName || season.name });
    if (!confirm(warning)) return;
    setNotice('');
    setError('');
    setBusy(`delete-${season.id}`);
    try {
      await api.deleteSeason(season.id);
      if (editingId === season.id) {
        setEditingId(null);
        setEditForm(null);
      }
      await loadSeasons();
      setNotice(t('sportDay.deleted', { name: season.displayName || season.name }));
    } catch (err: unknown) {
      // A year that still has events is refused with a 409 whose message names
      // the count — show it as it stands.
      setError(err instanceof Error ? err.message : t('sportDay.deleteFailed'));
    } finally {
      setBusy(null);
    }
  };

  if (isLoading || !user || loading) {
    return <div>{t('sportDay.loading')}</div>;
  }

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('sportDay.title')}</h1>
        <div className="flex gap-2">
          <Link href="/admin/settings" className="btn btn-secondary">
            {t('settings.title')}
          </Link>
          <Link href="/admin" className="btn btn-secondary">
            {t('common.backToAdmin')}
          </Link>
        </div>
      </div>
      <p className="muted">{t('sportDay.subtitle')}</p>

      {error && <div className="alert alert-error">{error}</div>}
      {notice && <div className="alert alert-success">{notice}</div>}

      {/* Which year students may enter — the one fact worth reading first. */}
      <div className="hint mt-2">
        <strong>
          {currentSeason
            ? t(
                currentSeason.enrollmentOpen
                  ? 'sportDay.currentSummary'
                  : 'sportDay.currentSummaryClosed',
                { year: currentSeason.year }
              )
            : t('sportDay.noCurrent')}
        </strong>
      </div>

      {/* ------------------------------------------------------------------ *
       * A. The school's details — the heading of every marking sheet
       * ------------------------------------------------------------------ */}
      <div className="card">
        <h2>{t('sportDay.schoolDetails')}</h2>
        <p className="muted">{t('sportDay.schoolDetailsHint')}</p>

        <div className="hint mt-2">
          <strong>{t('sportDay.sheetHeading')}</strong>
          <div className="mt-2">
            {schoolForm.schoolNameZh || t('sportDay.schoolNameZh')}
            {' · '}
            {schoolForm.schoolName || t('sportDay.schoolName')}
          </div>
          <div>{schoolForm.sportDayTitle || t('sportDay.sportDayTitle')}</div>
          <div className="muted">
            {schoolForm.address || t('sportDay.address')}
            {' · '}
            {schoolForm.principal || t('sportDay.principal')}
          </div>
          <div className="muted mt-2">{t('sportDay.sheetHeadingHint')}</div>
        </div>

        <div className="toolbar mt-2">
          <div className="form-group" style={{ minWidth: '16rem' }}>
            <label>{t('sportDay.schoolName')}</label>
            <input
              type="text"
              value={schoolForm.schoolName}
              onChange={e => setSchoolValue('schoolName', e.target.value)}
            />
          </div>
          <div className="form-group" style={{ minWidth: '16rem' }}>
            <label>{t('sportDay.schoolNameZh')}</label>
            <input
              type="text"
              value={schoolForm.schoolNameZh}
              onChange={e => setSchoolValue('schoolNameZh', e.target.value)}
            />
          </div>
        </div>

        <div className="form-group">
          <label>{t('sportDay.sportDayTitle')}</label>
          <input
            type="text"
            value={schoolForm.sportDayTitle}
            onChange={e => setSchoolValue('sportDayTitle', e.target.value)}
          />
        </div>

        <div className="toolbar">
          <div className="form-group" style={{ minWidth: '18rem' }}>
            <label>{t('sportDay.address')}</label>
            <input
              type="text"
              value={schoolForm.address}
              onChange={e => setSchoolValue('address', e.target.value)}
            />
          </div>
          <div className="form-group" style={{ minWidth: '14rem' }}>
            <label>{t('sportDay.principal')}</label>
            <input
              type="text"
              value={schoolForm.principal}
              onChange={e => setSchoolValue('principal', e.target.value)}
            />
          </div>
        </div>

        <button
          type="button"
          className="btn btn-primary"
          disabled={busy === 'school'}
          onClick={handleSaveSchool}
        >
          {busy === 'school' ? t('common.saving') : t('settings.save')}
        </button>
        <div className="muted mt-2">
          {t('sportDay.schoolFieldsHint')}{' '}
          <Link href="/admin/settings">{t('settings.entryLimits')}</Link>
        </div>
        {settings?.updatedAt && (
          <p className="muted">
            {t('settings.updatedAt', { date: formatDateTime(settings.updatedAt) })}
          </p>
        )}
      </div>

      {/* ------------------------------------------------------------------ *
       * B. The school years
       * ------------------------------------------------------------------ */}
      <div className="card mt-2">
        <h2>{t('sportDay.create')}</h2>
        <p className="muted">{t('sportDay.createHint')}</p>

        <div className="toolbar mt-2">
          <div className="form-group">
            <label>{t('sportDay.createYear')}</label>
            <input
              type="number"
              min={1900}
              max={2999}
              step={1}
              value={createForm.year}
              onChange={e => handleCreateYear(e.target.value)}
            />
          </div>
          <div className="form-group" style={{ minWidth: '16rem' }}>
            <label>{t('sportDay.createName')}</label>
            <input
              type="text"
              value={createForm.name}
              placeholder={t('sportDay.createNamePlaceholder')}
              onChange={e => setCreateForm(prev => ({ ...prev, name: e.target.value }))}
            />
          </div>
          <div className="form-group">
            <label>{t('sportDay.createDate')}</label>
            <input
              type="date"
              value={createForm.sportDayDate}
              onChange={e => setCreateForm(prev => ({ ...prev, sportDayDate: e.target.value }))}
            />
          </div>
          <div className="form-group" style={{ minWidth: '16rem' }}>
            <label>{t('sportDay.copyFrom')}</label>
            <select
              value={createForm.copyFrom}
              onChange={e => setCreateForm(prev => ({ ...prev, copyFrom: e.target.value }))}
            >
              <option value="">{t('sportDay.copyFromNone')}</option>
              {seasons.map(season => (
                <option key={season.id} value={season.id}>
                  {t('sportDay.copyFromYear', { year: season.year })}
                </option>
              ))}
            </select>
          </div>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy === 'create'}
            onClick={handleCreate}
          >
            {busy === 'create' ? t('sportDay.creating') : t('sportDay.createSubmit')}
          </button>
        </div>

        <label className="checkbox-line">
          <input
            type="checkbox"
            checked={createForm.enrollmentOpen}
            onChange={e =>
              setCreateForm(prev => ({ ...prev, enrollmentOpen: e.target.checked }))
            }
          />
          {t('sportDay.createOpen')}
        </label>
        <div className="muted">{t('sportDay.createOpenHint')}</div>
      </div>

      {/* Editing one year — a past year included. */}
      {editingSeason && editForm && (
        <div className="card mt-2">
          <div className="flex justify-between items-center">
            <h2>{t('sportDay.editTitle', { year: editingSeason.year })}</h2>
            <button
              type="button"
              className="btn btn-sm btn-secondary"
              onClick={() => {
                setEditingId(null);
                setEditForm(null);
              }}
            >
              {t('common.cancel')}
            </button>
          </div>
          <p className="muted">{t('sportDay.editHint')}</p>

          <div className="toolbar mt-2">
            <div className="form-group">
              <label>{t('sportDay.year')}</label>
              <input
                type="number"
                min={1900}
                max={2999}
                step={1}
                value={editForm.year}
                onChange={e => setEditValue('year', e.target.value)}
              />
            </div>
            <div className="form-group" style={{ minWidth: '16rem' }}>
              <label>{t('sportDay.name')}</label>
              <input
                type="text"
                value={editForm.name}
                onChange={e => setEditValue('name', e.target.value)}
              />
            </div>
            <div className="form-group">
              <label>{t('sportDay.date')}</label>
              <input
                type="date"
                value={editForm.sportDayDate}
                onChange={e => setEditValue('sportDayDate', e.target.value)}
              />
            </div>
          </div>

          <div className="form-group">
            <label>{t('sportDay.notes')}</label>
            <input
              type="text"
              value={editForm.notes}
              placeholder={t('sportDay.notesPlaceholder')}
              onChange={e => setEditValue('notes', e.target.value)}
            />
          </div>

          <label className="checkbox-line">
            <input
              type="checkbox"
              checked={editForm.enrollmentOpen}
              onChange={e => setEditValue('enrollmentOpen', e.target.checked)}
            />
            {t('sportDay.enrollmentOpen')}
          </label>
          <div className="muted">{t('sportDay.enrollmentOpenHint')}</div>

          <button
            type="button"
            className="btn btn-primary mt-3"
            disabled={busy === 'edit'}
            onClick={handleSaveEdit}
          >
            {busy === 'edit' ? t('common.saving') : t('sportDay.saveYear')}
          </button>
        </div>
      )}

      <div className="card mt-2">
        <div className="flex justify-between items-center">
          <h2>{t('sportDay.years')}</h2>
          <span className="badge badge-info">
            {t('sportDay.yearsCount', { count: seasons.length })}
          </span>
        </div>
        <p className="muted">{t('sportDay.yearsHint')}</p>

        {seasons.length === 0 ? (
          <div className="empty mt-2">
            <p>{t('sportDay.noYears')}</p>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t('sportDay.year')}</th>
                  <th>{t('sportDay.date')}</th>
                  <th>{t('sportDay.eventCount')}</th>
                  <th>{t('sportDay.entries')}</th>
                  <th>{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {seasons.map(season => {
                  const name = season.displayName || season.name;
                  return (
                    <tr key={season.id} className={season.current ? undefined : 'row-disabled'}>
                      <td>
                        <strong>{season.year}</strong>
                        <div className="muted">{name}</div>
                        {season.notes && <div className="muted">{season.notes}</div>}
                      </td>
                      <td>{formatDate(season.sportDayDate)}</td>
                      <td>{season.eventCount ?? 0}</td>
                      <td>
                        <span
                          className={
                            season.enrollmentOpen ? 'badge badge-success' : 'badge badge-danger'
                          }
                        >
                          {season.enrollmentOpen
                            ? t('sportDay.entriesOpen')
                            : t('sportDay.entriesClosed')}
                        </span>
                        <div className="mt-2">
                          <span
                            className={season.current ? 'badge badge-info' : 'badge badge-warning'}
                          >
                            {season.current ? t('sportDay.current') : t('sportDay.notCurrent')}
                          </span>
                        </div>
                      </td>
                      <td>
                        <div className="pill-actions">
                          <button
                            type="button"
                            className={`btn btn-sm ${
                              season.enrollmentOpen ? 'btn-danger' : 'btn-success'
                            }`}
                            disabled={busy === `open-${season.id}`}
                            onClick={() => handleToggleEnrollment(season)}
                          >
                            {season.enrollmentOpen
                              ? t('sportDay.closeEntries')
                              : t('sportDay.openEntries')}
                          </button>
                          {!season.current && (
                            <button
                              type="button"
                              className="btn btn-sm btn-primary"
                              title={t('sportDay.activateHint')}
                              disabled={busy === `activate-${season.id}`}
                              onClick={() => handleActivate(season)}
                            >
                              {busy === `activate-${season.id}`
                                ? t('sportDay.activating')
                                : t('sportDay.activate')}
                            </button>
                          )}
                          <button
                            type="button"
                            className="btn btn-sm btn-secondary"
                            onClick={() => startEdit(season)}
                          >
                            {t('sportDay.edit')}
                          </button>
                          <Link
                            href={`/admin/events?seasonId=${season.id}`}
                            className="btn btn-sm btn-secondary"
                          >
                            {t('sportDay.viewProgramme')}
                          </Link>
                          <button
                            type="button"
                            className="btn btn-sm btn-danger"
                            disabled={busy === `delete-${season.id}`}
                            onClick={() => handleDelete(season)}
                          >
                            {busy === `delete-${season.id}`
                              ? t('sportDay.deleting')
                              : t('sportDay.delete')}
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card mt-2">
        <div className="flex justify-between items-center">
          <h2>{t('backups.title')}</h2>
          <Link href="/admin/backups" className="btn btn-sm btn-secondary">
            {t('backups.openBackups')}
          </Link>
        </div>
        <p className="muted">{t('backups.subtitle')}</p>
        {backups.length === 0 ? (
          <div className="empty mt-2">
            <p>{t('backups.none')}</p>
          </div>
        ) : (
          <div className="event-meta mt-2">
            <div>
              <strong>{t('backups.latest')}:</strong> {backups[0].name}
            </div>
            <div>
              <strong>{t('backups.taken')}:</strong> {formatDateTime(backups[0].writtenAt)}
            </div>
            <div>
              <strong>{t('backups.contents')}:</strong>{' '}
              {t('backups.contentsLine', {
                enrollments: backups[0].counts?.enrollments ?? 0,
                groups: backups[0].counts?.groups ?? 0,
                results: backups[0].counts?.results ?? 0,
                records: backups[0].counts?.records ?? 0,
              })}
            </div>
          </div>
        )}
      </div>

      <p className="muted">{t('sportDay.activateHint')}</p>
    </div>
  );
}
