'use client';

import { useEffect, useState } from 'react';
import { api, Enrollment } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import Link from 'next/link';
import { useRouter } from 'next/navigation';

export default function MyEnrollmentsPage() {
  const { user } = useAuth();
  const router = useRouter();
  const [enrollments, setEnrollments] = useState<Enrollment[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!user) {
      router.push('/login');
      return;
    }
    api.getMyEnrollments()
      .then(setEnrollments)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [user, router]);

  const handleCancel = async (eventId: number) => {
    if (!confirm('Are you sure you want to cancel this enrollment?')) return;
    try {
      await api.cancelEnrollment(eventId);
      setEnrollments(prev => prev.filter(e => e.event.id !== eventId));
    } catch (err: any) {
      alert(err.message);
    }
  };

  if (loading) return <div>Loading...</div>;

  return (
    <div>
      <h1 className="page-title">My Enrollments</h1>

      {enrollments.length === 0 ? (
        <div className="card text-center">
          <p>You haven&apos;t enrolled in any events yet.</p>
          <Link href="/events" className="btn btn-primary mt-2">Browse Events</Link>
        </div>
      ) : (
        <div className="card-grid">
          {enrollments.map(enrollment => (
            <div key={enrollment.id} className="card">
              <h3>{enrollment.event.name}</h3>
              <span className="badge badge-info">{enrollment.event.type.replace(/_/g, ' ')}</span>
              <div className="mt-2" style={{ fontSize: '0.9rem', color: '#666' }}>
                <div>Date: {new Date(enrollment.event.eventDate).toLocaleDateString()}</div>
                {enrollment.event.location && <div>Location: {enrollment.event.location}</div>}
                <div>
                  Status:{' '}
                  <span className={
                    enrollment.status === 'CONFIRMED' ? 'badge badge-success' :
                    enrollment.status === 'CANCELLED' ? 'badge badge-danger' : 'badge badge-warning'
                  }>
                    {enrollment.status}
                  </span>
                </div>
              </div>
              <div className="flex gap-2 mt-2">
                <Link href={`/events/${enrollment.event.id}`} className="btn btn-sm btn-primary">
                  View Event
                </Link>
                {enrollment.status === 'CONFIRMED' && (
                  <button
                    onClick={() => handleCancel(enrollment.event.id)}
                    className="btn btn-sm btn-danger"
                  >
                    Cancel
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
