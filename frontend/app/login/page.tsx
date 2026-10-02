'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';

export default function LoginPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [showHelp, setShowHelp] = useState(true);
  const { login } = useAuth();
  const { t } = useI18n();
  const router = useRouter();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await login(username.trim(), password);
      router.push('/');
    } catch (err: any) {
      setError(err.message || t('auth.loginFailed'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-card">
        <h2>{t('auth.loginTitle')}</h2>
        {error && <div className="alert alert-error">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>
              {t('auth.username')} / {t('auth.studentId')}
            </label>
            <input
              type="text"
              value={username}
              onChange={e => setUsername(e.target.value)}
              placeholder={t('auth.usernamePlaceholder')}
              autoComplete="username"
              required
            />
          </div>
          <div className="form-group">
            <label>{t('auth.password')}</label>
            <input
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </div>
          <button
            type="submit"
            className="btn btn-primary"
            style={{ width: '100%' }}
            disabled={loading}
          >
            {loading ? t('auth.signingIn') : t('auth.signIn')}
          </button>
        </form>

        <div className="hint mt-3">
          <div className="flex justify-between items-center">
            <strong>{t('auth.studentLogin')}</strong>
            <button
              type="button"
              className="btn btn-sm btn-secondary"
              onClick={() => setShowHelp(v => !v)}
            >
              {showHelp ? t('auth.hide') : t('auth.show')}
            </button>
          </div>
          {showHelp && (
            <div className="mt-2">
              <div>{t('auth.studentHint')}</div>
              <div className="muted mt-2">{t('auth.staffHint')}</div>
            </div>
          )}
        </div>

        <div style={{ marginTop: '1rem', fontSize: '0.85rem', color: '#888', textAlign: 'center' }}>
          {t('auth.adminCredentials', { username: 'admin', password: 'admin123' })}
        </div>
      </div>
    </div>
  );
}
