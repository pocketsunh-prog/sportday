'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api, BackupSummaryDTO, SeasonRestoreResultDTO } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { formatDateTime } from '@/lib/format';
import { useI18n } from '@/lib/i18n';

/** The message the server sent, or our own wording when there is none. */
function errorText(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback;
}

/** A byte count as `12.4 KB`, which is a figure rather than a label. */
function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '-';
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  return `${(kb / 1024).toFixed(1)} MB`;
}

/**
 * The season backups: what each one holds, and the two things an office does
 * with one — download it, or put it back.
 *
 * Restoring is **destructive**: it replaces the current entries, heats, final
 * places, marks and school-record baselines with the file's contents, so it goes
 * behind a confirmation in the strongest wording the app uses (the season
 * reset's own), and the outcome — restored, or refused — is stated plainly
 * afterwards rather than left to be inferred from a changed table.
 */
export default function AdminBackupsPage() {
  const { user, isLoading } = useAuth();
  const { t } = useI18n();
  const router = useRouter();

  const [backups, setBackups] = useState<BackupSummaryDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /** The outcome of the last restore, kept on screen until the next action. */
  const [restored, setRestored] = useState<SeasonRestoreResultDTO | null>(null);

  const isAdmin = user?.role === 'ADMIN';

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const list = await api.getBackups();
      setBackups(list);
    } catch (err) {
      setError(errorText(err, t('backups.loadFailed')));
      setBackups([]);
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    if (!isAdmin) {
      router.push('/');
      return;
    }
    load();
  }, [user, isLoading, isAdmin, router, load]);

  const handleDownload = async (backup: BackupSummaryDTO) => {
    setBusy(`download-${backup.name}`);
    setError(null);
    setNotice(null);
    setRestored(null);
    try {
      const filename = await api.downloadBackup(backup.name, backup.name);
      setNotice(t('common.downloaded', { filename }));
    } catch (err) {
      setError(errorText(err, t('backups.downloadFailed')));
    } finally {
      setBusy(null);
    }
  };

  /**
   * Puts a backup back. The confirmation says exactly what is destroyed, in the
   * same words the season reset uses, because this is the same kind of action:
   * the current season is replaced, not merged.
   */
  const handleRestore = async (backup: BackupSummaryDTO) => {
    if (!confirm(t('backups.restoreConfirm', { name: backup.name }))) return;
    setBusy(`restore-${backup.name}`);
    setError(null);
    setNotice(null);
    setRestored(null);
    try {
      const result = await api.restoreBackup(backup.name);
      setRestored(result);
      setNotice(
        t('backups.restoreResult', {
          name: result.restoredFrom,
          groups: result.groupsRestored,
          enrollments: result.enrollmentsRestored,
          finalEntries: result.finalEntriesRestored,
          results: result.resultsRestored,
          records: result.recordsRestored,
        })
      );
      // The set of backups does not change, but re-reading keeps the page honest
      // if a restore failed halfway on another screen.
      await load();
    } catch (err) {
      // A refused restore must be unmistakable: say so first, then the server's
      // own reason.
      const reason = errorText(err, '');
      setError(
        reason ? `${t('backups.restoreFailed')} — ${reason}` : t('backups.restoreFailed')
      );
    } finally {
      setBusy(null);
    }
  };

  /** The rows a file's header counted, as one line. */
  const contentsLine = (backup: BackupSummaryDTO): string => {
    const counts = backup.counts;
    if (!counts) return '-';
    return t('backups.contentsLine', {
      enrollments: counts.enrollments ?? 0,
      groups: counts.groups ?? 0,
      results: counts.results ?? 0,
      records: counts.records ?? 0,
    });
  };

  const skippedTotal = restored?.skipped
    ? Object.values(restored.skipped).reduce((sum, value) => sum + (value || 0), 0)
    : 0;

  if (isLoading || !user || !isAdmin) {
    return <div>{t('common.loading')}</div>;
  }

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('backups.title')}</h1>
        <div className="flex gap-2">
          <button type="button" className="btn btn-secondary" onClick={load}>
            {t('common.refresh')}
          </button>
          <Link href="/admin" className="btn btn-secondary">
            {t('common.backToAdmin')}
          </Link>
        </div>
      </div>
      <p className="muted">{t('backups.subtitle')}</p>

      {error && <div className="alert alert-error">{error}</div>}
      {notice && <div className="alert alert-success">{notice}</div>}
      {restored && (
        <div className="card">
          <h3>{t('backups.restoreOutcome')}</h3>
          {skippedTotal > 0 && (
            <p className="muted mt-2">{t('backups.restoreSkipped', { count: skippedTotal })}</p>
          )}
          <p className="muted mt-2">{t('backups.afterRestore')}</p>
        </div>
      )}

      <div className="card">
        <div className="flex justify-between items-center">
          <h2>{t('backups.title')}</h2>
          <span className="badge badge-info">{t('backups.count', { count: backups.length })}</span>
        </div>

        {loading ? (
          <p className="muted mt-2">{t('backups.loading')}</p>
        ) : backups.length === 0 ? (
          <div className="empty mt-2">
            <p>{t('backups.none')}</p>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t('backups.name')}</th>
                  <th>{t('backups.size')}</th>
                  <th>{t('backups.taken')}</th>
                  <th>{t('backups.contents')}</th>
                  <th>{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {backups.map(backup => (
                  <tr key={backup.name}>
                    <td>
                      {backup.name}
                      {backup.problem && (
                        <div>
                          <span className="badge badge-danger">{t('backups.unreadable')}</span>
                          <div className="muted">{backup.problem}</div>
                        </div>
                      )}
                    </td>
                    <td>{formatBytes(backup.bytes)}</td>
                    <td>{formatDateTime(backup.writtenAt)}</td>
                    <td>{contentsLine(backup)}</td>
                    <td>
                      <div className="pill-actions">
                        <button
                          type="button"
                          className="btn btn-sm btn-secondary"
                          disabled={busy === `download-${backup.name}`}
                          onClick={() => handleDownload(backup)}
                        >
                          {busy === `download-${backup.name}`
                            ? t('backups.downloading')
                            : t('backups.download')}
                        </button>
                        {/* Restoring a file that cannot even be read is refused by
                            the server anyway; disabling it here says why sooner. */}
                        <button
                          type="button"
                          className="btn btn-sm btn-danger"
                          disabled={busy === `restore-${backup.name}` || !!backup.problem}
                          onClick={() => handleRestore(backup)}
                        >
                          {busy === `restore-${backup.name}`
                            ? t('backups.restoring')
                            : t('backups.restore')}
                        </button>
                      </div>
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
