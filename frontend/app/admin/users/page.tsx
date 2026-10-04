'use client';

import { useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { api, UserDTO } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';

type EnabledFilter = '' | 'true' | 'false';

/**
 * Used until `GET /admin/users/roles` answers, so the form is never empty.
 * `HELPER` is the input helper: they record marks and print marking sheets and
 * nothing else. It is an accepted value of the endpoint, which is the authority
 * on this list — this is only the placeholder shown before it replies.
 */
const FALLBACK_ROLES: string[] = ['ADMIN', 'MANAGER', 'TEACHER', 'HELPER', 'USER'];

export default function AdminUsersPage() {
  const { user, isLoading } = useAuth();
  const { t, label } = useI18n();
  const router = useRouter();

  const [users, setUsers] = useState<UserDTO[]>([]);
  const [roles, setRoles] = useState<string[]>(FALLBACK_ROLES);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const [roleFilter, setRoleFilter] = useState('');
  const [enabledFilter, setEnabledFilter] = useState<EnabledFilter>('');
  const [search, setSearch] = useState('');

  const [form, setForm] = useState({
    username: '',
    password: '',
    email: '',
    fullName: '',
    role: 'MANAGER',
  });
  const [creating, setCreating] = useState(false);

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
      api.getAllUsers().catch((err: unknown) => {
        setError(err instanceof Error ? err.message : t('users.loadFailed'));
        return [] as UserDTO[];
      }),
      api.getAssignableRoles().catch(() => FALLBACK_ROLES),
    ])
      .then(([accounts, assignable]) => {
        setUsers(accounts);
        if (assignable.length > 0) {
          setRoles(assignable);
          setForm(prev => ({
            ...prev,
            role: assignable.includes(prev.role) ? prev.role : assignable[0],
          }));
        }
      })
      .finally(() => setLoading(false));
  }, [user, isLoading, router, t]);

  const reload = async () => {
    try {
      const accounts = await api.getAllUsers();
      setUsers(accounts);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('users.loadFailed'));
    }
  };

  const visible = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return users.filter(account => {
      if (roleFilter && account.role !== roleFilter) return false;
      if (enabledFilter === 'true' && !account.enabled) return false;
      if (enabledFilter === 'false' && account.enabled) return false;
      if (!needle) return true;
      return (
        account.username.toLowerCase().includes(needle) ||
        (account.fullName || '').toLowerCase().includes(needle) ||
        (account.email || '').toLowerCase().includes(needle)
      );
    });
  }, [users, roleFilter, enabledFilter, search]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setMessage('');
    setError('');
    /*
     * Only a username and a password are required: the server treats a blank
     * email as "none" rather than refusing the account, which is what lets a
     * HELPER be created with nothing but a username, a password and a name.
     */
    if (form.username.trim().length < 3 || form.password.length < 6) {
      setError(t('users.validationFull'));
      return;
    }
    setCreating(true);
    try {
      const created = await api.createUser(
        {
          username: form.username.trim(),
          password: form.password,
          email: form.email.trim(),
          fullName: form.fullName.trim(),
        },
        form.role
      );
      setMessage(t('users.created', { username: created.username || form.username.trim() }));
      // Never keep the password around once the account exists.
      setForm(prev => ({ ...prev, username: '', password: '', email: '', fullName: '' }));
      await reload();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('users.createFailed'));
    } finally {
      setCreating(false);
    }
  };

  const handleToggle = async (account: UserDTO) => {
    setMessage('');
    setError('');
    setBusyId(account.id);
    try {
      await api.setUserEnabled(account.id, !account.enabled);
      setUsers(prev =>
        prev.map(item => (item.id === account.id ? { ...item, enabled: !account.enabled } : item))
      );
      setMessage(
        account.enabled
          ? t('users.disabledNotice', { username: account.username })
          : t('users.enabledNotice', { username: account.username })
      );
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('users.statusFailed'));
    } finally {
      setBusyId(null);
    }
  };

  const handleDelete = async (account: UserDTO) => {
    if (!confirm(t('users.deleteConfirm', { username: account.username }))) return;
    setMessage('');
    setError('');
    setBusyId(account.id);
    try {
      await api.deleteUser(account.id);
      setUsers(prev => prev.filter(item => item.id !== account.id));
      setMessage(t('users.deletedNotice', { username: account.username }));
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('users.deleteFailed'));
    } finally {
      setBusyId(null);
    }
  };

  if (isLoading || !user || loading) {
    return <div>{t('common.loading')}</div>;
  }

  return (
    <div>
      <h1 className="page-title">{t('users.title')}</h1>
      <p className="muted">{t('users.subtitle')}</p>

      {message && <div className="alert alert-success">{message}</div>}
      {error && <div className="alert alert-error">{error}</div>}

      <div className="card">
        <h2>{t('users.studentsNotice')}</h2>
        <p>
          <Link href="/admin/students" className="btn btn-secondary btn-sm">
            {t('users.studentsLink')}
          </Link>
        </p>
      </div>

      <div className="card mt-2">
        <h2>{t('users.createTitle')}</h2>
        <p className="muted">{t('users.createHint')}</p>
        <form onSubmit={handleCreate}>
          <div className="grid-toolbar">
            <div className="field">
              <label>{t('auth.username')}</label>
              <input
                type="text"
                value={form.username}
                onChange={e => setForm(prev => ({ ...prev, username: e.target.value }))}
                required
              />
            </div>
            <div className="field">
              <label>{t('users.password')}</label>
              <input
                type="password"
                value={form.password}
                onChange={e => setForm(prev => ({ ...prev, password: e.target.value }))}
                placeholder={t('users.passwordPlaceholder')}
                autoComplete="new-password"
                required
              />
            </div>
            <div className="field">
              <label>{t('users.email')}</label>
              <input
                type="email"
                value={form.email}
                onChange={e => setForm(prev => ({ ...prev, email: e.target.value }))}
                placeholder={t('users.emailOptional')}
              />
            </div>
            <div className="field">
              <label>{t('users.fullName')}</label>
              <input
                type="text"
                value={form.fullName}
                onChange={e => setForm(prev => ({ ...prev, fullName: e.target.value }))}
              />
            </div>
            <div className="field">
              <label>{t('users.roleLabel')}</label>
              <select
                value={form.role}
                onChange={e => setForm(prev => ({ ...prev, role: e.target.value }))}
              >
                {roles.map(role => (
                  <option key={role} value={role}>
                    {label('role', role)}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <button type="submit" className="btn btn-primary" disabled={creating}>
                {creating ? t('users.creating') : t('users.create')}
              </button>
            </div>
          </div>
        </form>
        <p className="muted">{t('users.passwordHint')}</p>
      </div>

      <div className="card mt-2">
        <div className="grid-toolbar">
          <div className="field">
            <label>{t('users.roleFilter')}</label>
            <select value={roleFilter} onChange={e => setRoleFilter(e.target.value)}>
              <option value="">{t('users.allRoles')}</option>
              {roles.map(role => (
                <option key={role} value={role}>
                  {label('role', role)}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>{t('users.statusFilter')}</label>
            <select
              value={enabledFilter}
              onChange={e => setEnabledFilter(e.target.value as EnabledFilter)}
            >
              <option value="">{t('users.allStatuses')}</option>
              <option value="true">{t('admin.active')}</option>
              <option value="false">{t('adminEvents.disabled')}</option>
            </select>
          </div>
          <div className="field">
            <label>{t('common.search')}</label>
            <input
              type="search"
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder={t('users.searchPlaceholder')}
            />
          </div>
          <div className="field">
            <button type="button" className="btn btn-secondary" onClick={reload}>
              {t('common.refresh')}
            </button>
          </div>
        </div>
        <p className="muted">{t('users.count', { count: visible.length })}</p>

        {visible.length === 0 ? (
          <div className="empty">
            <p>{t('users.noMatch')}</p>
          </div>
        ) : (
          <div className="table-wrap">
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
                {visible.map(account => (
                  <tr key={account.id}>
                    <td>{account.id}</td>
                    <td>{account.username}</td>
                    <td>{account.fullName || '-'}</td>
                    <td>{account.email || '-'}</td>
                    <td>
                      <span className="badge badge-info">{label('role', account.role)}</span>
                    </td>
                    <td>
                      <span
                        className={account.enabled ? 'badge badge-success' : 'badge badge-danger'}
                      >
                        {account.enabled ? t('admin.active') : t('adminEvents.disabled')}
                      </span>
                    </td>
                    <td>
                      <button
                        type="button"
                        className={`btn btn-sm ${account.enabled ? 'btn-danger' : 'btn-success'}`}
                        onClick={() => handleToggle(account)}
                        disabled={busyId === account.id || account.id === user.userId}
                      >
                        {account.enabled ? t('users.disable') : t('users.enable')}
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm btn-secondary"
                        style={{ marginLeft: '0.35rem' }}
                        onClick={() => handleDelete(account)}
                        disabled={busyId === account.id || account.id === user.userId}
                      >
                        {t('users.delete')}
                      </button>
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
