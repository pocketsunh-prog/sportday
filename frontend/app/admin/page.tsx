'use client';

import { useEffect, useState } from 'react';
import { api, UserDTO } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';
import { useRouter } from 'next/navigation';
import Link from 'next/link';

export default function AdminPage() {
  const { user } = useAuth();
  const { t, label } = useI18n();
  const router = useRouter();
  /** The dashboard serves ADMIN and MANAGER, but not every tile serves both. */
  const isAdmin = user?.role === 'ADMIN';
  const [users, setUsers] = useState<UserDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [resetting, setResetting] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    if (!user || (user.role !== 'ADMIN' && user.role !== 'MANAGER')) {
      router.push('/');
      return;
    }
    api.getAllUsers()
      .then(setUsers)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [user, router]);

  const handleToggleEnabled = async (id: number, enabled: boolean) => {
    try {
      await api.setUserEnabled(id, enabled);
      setUsers(prev => prev.map(u => u.id === id ? { ...u, enabled } : u));
    } catch (err: any) {
      alert(err.message);
    }
  };

  /**
   * The reset writes a restorable backup of everything it destroys before it
   * deletes anything, and refuses to run at all if that file cannot be written.
   * Its answer names the file and its size, and that is shown here — the office
   * should be able to see that a reset was backed up, and where the way back is.
   */
  const handleResetSeason = async () => {
    if (!confirm(t('admin.seasonResetConfirm'))) {
      return;
    }
    setResetting(true);
    setMessage('');
    setError('');
    try {
      const result = await api.resetSeason();
      setMessage(
        `${t('admin.seasonResetDone')} ${t('backups.resetBackedUp', {
          file: result.backupFile,
          bytes: result.backupBytes,
          enrollments: result.enrollmentsRemoved,
          groups: result.groupsRemoved,
          results: result.resultsRemoved,
        })}`
      );
    } catch (err: any) {
      // A reset whose backup could not be written refuses to run and says so;
      // nothing was deleted. Show the server's own reason.
      setError(err?.message || t('admin.seasonResetFailed'));
    } finally {
      setResetting(false);
    }
  };

  if (loading) return <div>{t('common.loading')}</div>;

  return (
    <div>
      <h1 className="page-title">{t('admin.title')}</h1>

      {message && <div className="alert alert-success">{message}</div>}
      {error && <div className="alert alert-error">{error}</div>}

      <div className="card-grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))' }}>
        <Link href="/admin/students" className="card text-center" style={{ textDecoration: 'none', color: '#333' }}>
          <div style={{ fontSize: '2rem' }}>🎓</div>
          <h3>{t('admin.students')}</h3>
          <p style={{ fontSize: '0.9rem', color: '#666' }}>
            {t('admin.studentsHint')}
          </p>
        </Link>
        {/*
          Admin only: the staff list creates TEACHER accounts, and the endpoint
          behind it is `hasRole('ADMIN')`. A manager is not shown a tile that
          would refuse them.
        */}
        {isAdmin && (
          <Link href="/admin/teachers" className="card text-center" style={{ textDecoration: 'none', color: '#333' }}>
            <div style={{ fontSize: '2rem' }}>🧑‍🏫</div>
            <h3>{t('admin.teachers')}</h3>
            <p style={{ fontSize: '0.9rem', color: '#666' }}>
              {t('admin.teachersHint')}
            </p>
          </Link>
        )}
        {/*
          Admin only: the backup list, its download and its restore are all
          `hasRole('ADMIN')` endpoints, so a manager is not offered a tile that
          would refuse them.
        */}
        {isAdmin && (
          <Link href="/admin/backups" className="card text-center" style={{ textDecoration: 'none', color: '#333' }}>
            <div style={{ fontSize: '2rem' }}>💾</div>
            <h3>{t('admin.backups')}</h3>
            <p style={{ fontSize: '0.9rem', color: '#666' }}>
              {t('admin.backupsHint')}
            </p>
          </Link>
        )}
        <Link href="/admin/events" className="card text-center" style={{ textDecoration: 'none', color: '#333' }}>
          <div style={{ fontSize: '2rem' }}>📋</div>
          <h3>{t('admin.events')}</h3>
          <p style={{ fontSize: '0.9rem', color: '#666' }}>
            {t('admin.eventsHint')}
          </p>
        </Link>
        <Link href="/admin/events/new" className="card text-center" style={{ textDecoration: 'none', color: '#333' }}>
          <div style={{ fontSize: '2rem' }}>📅</div>
          <h3>{t('adminEvents.new')}</h3>
          <p style={{ fontSize: '0.9rem', color: '#666' }}>{t('admin.createEventHint')}</p>
        </Link>
        <Link href="/admin/results/new" className="card text-center" style={{ textDecoration: 'none', color: '#333' }}>
          <div style={{ fontSize: '2rem' }}>🏆</div>
          <h3>{t('admin.results')}</h3>
          <p style={{ fontSize: '0.9rem', color: '#666' }}>{t('admin.recordHint')}</p>
        </Link>
        <Link href="/results" className="card text-center" style={{ textDecoration: 'none', color: '#333' }}>
          <div style={{ fontSize: '2rem' }}>📊</div>
          <h3>{t('marks.leaderboard')}</h3>
          <p style={{ fontSize: '0.9rem', color: '#666' }}>{t('admin.leaderboardHint')}</p>
        </Link>
      </div>

      <div className="card">
        <h2>{t('admin.seasonReset')}</h2>
        <p className="muted">
          {t('admin.seasonResetHint')}
        </p>
        <button
          type="button"
          className="btn btn-danger mt-2"
          disabled={resetting}
          onClick={handleResetSeason}
        >
          {resetting ? t('admin.resetting') : t('admin.seasonReset')}
        </button>
      </div>

      <div className="card mt-2">
        <h2>{t('admin.allUsers')}</h2>
        <table>
          <thead>
            <tr>
              <th>{t('admin.colId')}</th>
              <th>{t('auth.username')}</th>
              <th>{t('auth.fullName')}</th>
              <th>{t('auth.email')}</th>
              <th>{t('admin.colRole')}</th>
              <th>{t('admin.colStatus')}</th>
              <th>{t('common.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {users.map(u => (
              <tr key={u.id}>
                <td>{u.id}</td>
                <td>{u.username}</td>
                <td>{u.fullName || '-'}</td>
                <td>{u.email}</td>
                <td><span className="badge badge-info">{label('role', u.role)}</span></td>
                <td>
                  <span className={u.enabled ? 'badge badge-success' : 'badge badge-danger'}>
                    {u.enabled ? t('admin.active') : t('adminEvents.disabled')}
                  </span>
                </td>
                <td>
                  {u.role !== 'ADMIN' && (
                    <button
                      onClick={() => handleToggleEnabled(u.id, !u.enabled)}
                      className={`btn btn-sm ${u.enabled ? 'btn-danger' : 'btn-success'}`}
                    >
                      {u.enabled ? t('adminEvents.disable') : t('adminEvents.enable')}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
