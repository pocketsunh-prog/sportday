'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, EventResultDTO, EventDTO } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import Link from 'next/link';

export default function ResultsPage() {
  const [events, setEvents] = useState<EventDTO[]>([]);
  const [selectedEvent, setSelectedEvent] = useState<number | null>(null);
  const [results, setResults] = useState<EventResultDTO[]>([]);
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
      api.getEvents(true)
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

  useEffect(() => {
    // Check for eventId in URL
    const params = new URLSearchParams(window.location.search);
    const eventId = params.get('eventId');
    if (eventId) {
      setSelectedEvent(Number(eventId));
    }
  }, []);

  useEffect(() => {
    if (selectedEvent) {
      api.getEventResults(selectedEvent).then(setResults).catch(() => setResults([]));
    }
  }, [selectedEvent]);

  if (loading) return <div>Loading...</div>;

  return (
    <div>
      <h1 className="page-title">Event Results</h1>

      <div className="card">
        <div className="form-group">
          <label>Select Event</label>
          <select
            value={selectedEvent || ''}
            onChange={e => setSelectedEvent(e.target.value ? Number(e.target.value) : null)}
          >
            <option value="">Choose an event...</option>
            {events.map(event => (
              <option key={event.id} value={event.id}>
                {event.name} ({new Date(event.eventDate).toLocaleDateString()})
              </option>
            ))}
          </select>
        </div>
      </div>

      {selectedEvent && (
        <div className="card mt-2">
          <h2>Leaderboard</h2>
          {results.length === 0 ? (
            <p style={{ color: '#888' }}>No results recorded for this event yet.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Rank</th>
                  <th>Athlete</th>
                  <th>Mark</th>
                  <th>Unit</th>
                  <th>Notes</th>
                  <th>Date</th>
                </tr>
              </thead>
              <tbody>
                {results.map((result, idx) => (
                  <tr key={result.id}>
                    <td>
                      {idx === 0 ? '🥇' : idx === 1 ? '🥈' : idx === 2 ? '🥉' : idx + 1}
                    </td>
                    <td>{result.fullName || result.username}</td>
                    <td><strong>{result.mark}</strong></td>
                    <td>{result.unit || '-'}</td>
                    <td>{result.notes || '-'}</td>
                    <td>{new Date(result.recordedAt).toLocaleDateString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}
