'use client';

import { useEffect, useState } from 'react';
import { api, UserDTO } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useRouter } from 'next/navigation';
import Link from 'next/link';

export default function AdminPage() {
  const { user } = useAuth();
  const router = useRouter();
  const [users, setUsers] = useState<UserDTO[]>([]);
  const [loading, setLoading] = useState(true);

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

  if (loading) return <div>Loading...</div>;

  return (
    <div>
      <h1 className="page-title">Admin Dashboard</h1>

      <div className="card-grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))' }}>
        <Link href="/admin/events/new" className="card text-center" style={{ textDecoration: 'none', color: '#333' }}>
          <div style={{ fontSize: '2rem' }}>📅</div>
          <h3>Create Event</h3>
          <p style={{ fontSize: '0.9rem', color: '#666' }}>Add a new sport event</p>
        </Link>
        <Link href="/events" className="card text-center" style={{ textDecoration: 'none', color: '#333' }}>
          <div style={{ fontSize: '2rem' }}>📋</div>
          <h3>Manage Events</h3>
          <p style={{ fontSize: '0.9rem', color: '#666' }}>Edit or disable events</p>
        </Link>
        <Link href="/admin/results/new" className="card text-center" style={{ textDecoration: 'none', color: '#333' }}>
          <div style={{ fontSize: '2rem' }}>🏆</div>
          <h3>Record Results</h3>
          <p style={{ fontSize: '0.9rem', color: '#666' }}>Input event marks</p>
        </Link>
      </div>

      <div className="card mt-2">
        <h2>All Users</h2>
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>Username</th>
              <th>Full Name</th>
              <th>Email</th>
              <th>Role</th>
              <th>Status</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {users.map(u => (
              <tr key={u.id}>
                <td>{u.id}</td>
                <td>{u.username}</td>
                <td>{u.fullName || '-'}</td>
                <td>{u.email}</td>
                <td><span className="badge badge-info">{u.role}</span></td>
                <td>
                  <span className={u.enabled ? 'badge badge-success' : 'badge badge-danger'}>
                    {u.enabled ? 'Active' : 'Disabled'}
                  </span>
                </td>
                <td>
                  {u.role !== 'ADMIN' && (
                    <button
                      onClick={() => handleToggleEnabled(u.id, !u.enabled)}
                      className={`btn btn-sm ${u.enabled ? 'btn-danger' : 'btn-success'}`}
                    >
                      {u.enabled ? 'Disable' : 'Enable'}
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
