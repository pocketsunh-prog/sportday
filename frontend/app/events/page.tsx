'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { api, EventDTO } from '@/lib/api';
import { useAuth } from '@/lib/auth';

export default function EventsPage() {
  const [events, setEvents] = useState<EventDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const { user, isLoading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!isLoading && !user) {
      router.push('/login');
    }
  }, [user, isLoading, router]);

  useEffect(() => {
    if (user) {
      const urlParams = new URLSearchParams(window.location.search);
      const onlyEnabled = urlParams.get('all') !== 'true';
      api.getEvents(onlyEnabled)
        .then(setEvents)
        .catch(() => {})
        .finally(() => setLoading(false));
    }
  }, [user]);

  if (isLoading || !user) {
    return (
      <div className="text-center" style={{ padding: '4rem 0' }}>
        <p>Loading...</p>
      </div>
    );
  }

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">Events</h1>
        {(user?.role === 'ADMIN' || user?.role === 'MANAGER') && (
          <Link href="/admin/events/new" className="btn btn-primary">
            Create Event
          </Link>
        )}
      </div>

      {events.length === 0 ? (
        <div className="card text-center">
          <p>No events found.</p>
        </div>
      ) : (
        <div className="card-grid">
          {events.map(event => (
            <div key={event.id} className="card">
              <div className="flex justify-between items-center mb-2">
                <h3>{event.name}</h3>
                <div className="flex gap-2">
                  <span className="badge badge-info">{event.type.replace(/_/g, ' ')}</span>
                  {!event.enabled && <span className="badge badge-warning">Disabled</span>}
                </div>
              </div>
              <p style={{ color: '#666', fontSize: '0.9rem' }}>{event.description}</p>
              <div className="mt-2" style={{ fontSize: '0.85rem', color: '#888' }}>
                <div>Date: {new Date(event.eventDate).toLocaleDateString()}</div>
                {event.location && <div>Location: {event.location}</div>}
                <div>Participants: {event.enrolledCount || 0} / {event.maxParticipants}</div>
              </div>
              <div className="flex gap-2 mt-2">
                <Link href={`/events/${event.id}`} className="btn btn-sm btn-primary">
                  Details
                </Link>
                <Link href={`/results?eventId=${event.id}`} className="btn btn-sm btn-secondary">
                  Results
                </Link>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
