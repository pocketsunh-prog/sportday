'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { api, EventDTO, EventResultDTO } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import Link from 'next/link';

export default function EventDetailPage() {
  const params = useParams();
  const router = useRouter();
  const { user } = useAuth();
  const [event, setEvent] = useState<EventDTO | null>(null);
  const [results, setResults] = useState<EventResultDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [enrolled, setEnrolled] = useState(false);

  const eventId = Number(params.id);

  useEffect(() => {
    api.getEvent(eventId).then(setEvent).catch(() => {});
    api.getEventResults(eventId).then(setResults).catch(() => {});
    if (user) {
      api.checkEnrollment(eventId).then(setEnrolled).catch(() => setEnrolled(false));
    }
    setLoading(false);
  }, [eventId, user]);

  const handleEnroll = async () => {
    try {
      await api.enroll(eventId);
      setEnrolled(true);
      const updated = await api.getEvent(eventId);
      setEvent(updated);
    } catch (err: any) {
      alert(err.message);
    }
  };

  const handleCancel = async () => {
    try {
      await api.cancelEnrollment(eventId);
      setEnrolled(false);
      const updated = await api.getEvent(eventId);
      setEvent(updated);
    } catch (err: any) {
      alert(err.message);
    }
  };

  if (loading) return <div>Loading...</div>;
  if (!event) return <div className="card">Event not found</div>;

  const isAdmin = user?.role === 'ADMIN' || user?.role === 'MANAGER';

  return (
    <div>
      <div className="card">
        <div className="flex justify-between items-center">
          <div>
            <h1 className="page-title" style={{ marginBottom: '0.5rem' }}>{event.name}</h1>
            <span className="badge badge-info">{event.type.replace(/_/g, ' ')}</span>
            {!event.enabled && <span className="badge badge-warning ml-2">Disabled</span>}
          </div>
          <div className="flex gap-2">
            {user && event.enabled && (
              enrolled ? (
                <button onClick={handleCancel} className="btn btn-sm btn-danger">
                  Cancel Enrollment
                </button>
              ) : (
                <button onClick={handleEnroll} className="btn btn-sm btn-success">
                  Enroll Now
                </button>
              )
            )}
            {isAdmin && (
              <>
                <Link href={`/admin/events/${event.id}/edit`} className="btn btn-sm btn-secondary">
                  Edit
                </Link>
                <Link href={`/admin/results/new?eventId=${event.id}`} className="btn btn-sm btn-primary">
                  Record Result
                </Link>
              </>
            )}
          </div>
        </div>

        <div className="mt-3" style={{ color: '#666' }}>
          <p>{event.description}</p>
          <div style={{ marginTop: '1rem' }}>
            <div><strong>Date:</strong> {new Date(event.eventDate).toLocaleDateString()}</div>
            {event.location && <div><strong>Location:</strong> {event.location}</div>}
            <div>
              <strong>Participants:</strong> {event.enrolledCount || 0} / {event.maxParticipants}
            </div>
          </div>
        </div>
      </div>

      <div className="card mt-2">
        <h2>Results</h2>
        {results.length === 0 ? (
          <p style={{ color: '#888' }}>No results recorded yet.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Rank</th>
                <th>Athlete</th>
                <th>Mark</th>
                <th>Unit</th>
                <th>Notes</th>
              </tr>
            </thead>
            <tbody>
              {results.map((result, idx) => (
                <tr key={result.id}>
                  <td>{idx + 1}</td>
                  <td>{result.fullName || result.username}</td>
                  <td>{result.mark}</td>
                  <td>{result.unit || '-'}</td>
                  <td>{result.notes || '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
