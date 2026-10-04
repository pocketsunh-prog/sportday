'use client';

import Link from 'next/link';
import { useAuth } from '@/lib/auth';
import { useRouter } from 'next/navigation';
import { useI18n } from '@/lib/i18n';
import { LanguageSwitch } from '@/components/LanguageSwitch';

export function Navbar() {
  const { user, logout } = useAuth();
  const { t, label } = useI18n();
  const router = useRouter();

  const handleLogout = () => {
    logout();
    router.push('/login');
  };

  const isStaff = user?.role === 'ADMIN' || user?.role === 'MANAGER';
  const isAdmin = user?.role === 'ADMIN';
  const isStudent = user?.role === 'STUDENT';

  return (
    <nav className="navbar">
      <div className="container">
        <Link href="/" className="brand">
          SportDay
        </Link>
        <div>
          {user ? (
            <>
              {isStudent ? (
                <>
                  <Link href="/events">{t('nav.events')}</Link>
                  <Link href="/my-enrollments">{t('nav.myEntries')}</Link>
                  <Link href="/results">{t('nav.results')}</Link>
                  <Link href="/records">{t('nav.records')}</Link>
                  <Link href="/championships">{t('nav.championships')}</Link>
                </>
              ) : (
                <>
                  <Link href="/events">{t('nav.events')}</Link>
                  <Link href="/my-enrollments">{t('nav.myEntries')}</Link>
                  <Link href="/results">{t('nav.results')}</Link>
                  <Link href="/records">{t('nav.records')}</Link>
                  <Link href="/championships">{t('nav.championships')}</Link>
                  {isStaff && (
                    <>
                      <Link href="/admin">{t('nav.admin')}</Link>
                      <Link href="/admin/marks">{t('nav.marks')}</Link>
                      <Link href="/admin/print">{t('nav.print')}</Link>
                    </>
                  )}
                  {isAdmin && (
                    <>
                      <Link href="/admin/sport-day">{t('nav.sportDay')}</Link>
                      <Link href="/admin/settings">{t('nav.settings')}</Link>
                      <Link href="/admin/users">{t('nav.users')}</Link>
                    </>
                  )}
                </>
              )}
              <span style={{ marginLeft: '1rem', color: '#aaa' }}>
                {user.fullName || user.username}
                {user.role && (
                  <span className="badge badge-info" style={{ marginLeft: '0.5rem' }}>
                    {label('role', user.role)}
                  </span>
                )}
              </span>
              <LanguageSwitch />
              <button
                onClick={handleLogout}
                className="btn btn-sm btn-danger"
                style={{ marginLeft: '0.75rem' }}
              >
                {t('nav.logout')}
              </button>
            </>
          ) : (
            <>
              <Link href="/login">{t('nav.login')}</Link>
              <LanguageSwitch />
            </>
          )}
        </div>
      </div>
    </nav>
  );
}
