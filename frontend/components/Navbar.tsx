'use client';

import Link from 'next/link';
import { useAuth } from '@/lib/auth';
import { useRouter } from 'next/navigation';

export function Navbar() {
  const { user, logout } = useAuth();
  const router = useRouter();

  const handleLogout = () => {
    logout();
    router.push('/login');
  };

  return (
    <nav className="navbar">
      <div className="container">
        <Link href="/" className="brand">
          SportDay
        </Link>
        <div>
          {user ? (
            <>
              <Link href="/events">Events</Link>
              <Link href="/results">Results</Link>
              <Link href="/my-enrollments">My Enrollments</Link>
              {(user.role === 'ADMIN' || user.role === 'MANAGER') && (
                <Link href="/admin">Admin</Link>
              )}
              <span style={{ marginLeft: '1.5rem', color: '#aaa' }}>
                {user.fullName || user.username}
              </span>
              <button
                onClick={handleLogout}
                className="btn btn-sm btn-danger"
                style={{ marginLeft: '1rem' }}
              >
                Logout
              </button>
            </>
          ) : (
            <>
              <Link href="/login">Login</Link>
              <Link href="/register">Register</Link>
            </>
          )}
        </div>
      </div>
    </nav>
  );
}
