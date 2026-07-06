'use client';

import { useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { api, EventDTO, UserDTO, Enrollment } from '@/lib/api';

export default function NewResultPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [events, setEvents] = useState<EventDTO[]>([]);
  const [users, setUsers] = useState<UserDTO[]>([]);
  const [enrollments, setEnrollments] = useState<Enrollment[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const [form, setForm] = useState({
    eventId: 0,
    userId: 0,
    mark: '',
    unit: '',
    notes: '',
  });

  useEffect(() => {
    api.getEvents(true).then(setEvents).catch(() => {});
    api.getAllUsers().then(setUsers).catch(() => {});
    setLoading(false);

    const eventId = searchParams.get('eventId');
    if (eventId) {
      setForm(f => ({ ...f, eventId: Number(eventId) }));
      loadEnrollments(Number(eventId));
    }
  }, [searchParams]);

  const loadEnrollments = async (eventId: number) => {
    try {
      const enrolls = await api.getEventEnrollments(eventId);
      setEnrollments(enrolls);
    } catch {
      // Not critical
    }
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    const value = e.target.value;
    setForm({ ...form, [e.target.name]: value });
    if (e.target.name === 'eventId') {
      loadEnrollments(Number(value));
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setSuccess('');
    setSaving(true);
    try {
      await api.recordResult(
        form.userId,
        form.eventId,
        parseFloat(form.mark),
        form.unit || undefined,
        form.notes || undefined
      );
      setSuccess('Result recorded successfully!');
      setForm({ ...form, userId: 0, mark: '', notes: '' });
    } catch (err: any) {
      setError(err.message || 'Failed to record result');
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <div>Loading...</div>;

  // Filter users to show only enrolled ones for selected event
  const enrolledUserIds = new Set(enrollments.map(e => e.user.id));
  const filteredUsers = form.eventId
    ? users.filter(u => enrolledUserIds.has(u.id))
    : users;

  return (
    <div>
      <h1 className="page-title">Record Event Result</h1>
      <div className="card" style={{ maxWidth: '600px' }}>
        {error && <div className="alert alert-error">{error}</div>}
        {success && <div className="alert alert-success">{success}</div>}
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>Event *</label>
            <select name="eventId" value={form.eventId} onChange={handleChange} required>
              <option value={0}>Select Event...</option>
              {events.map(event => (
                <option key={event.id} value={event.id}>
                  {event.name} ({new Date(event.eventDate).toLocaleDateString()})
                </option>
              ))}
            </select>
          </div>
          <div className="form-group">
            <label>Athlete *</label>
            <select name="userId" value={form.userId} onChange={handleChange} required>
              <option value={0}>Select Athlete...</option>
              {filteredUsers.map(user => (
                <option key={user.id} value={user.id}>
                  {user.fullName || user.username} ({user.email})
                </option>
              ))}
            </select>
          </div>
          <div className="flex gap-2">
            <div className="form-group" style={{ flex: 2 }}>
              <label>Mark *</label>
              <input
                name="mark"
                type="number"
                step="0.001"
                placeholder="e.g. 10.123"
                value={form.mark}
                onChange={handleChange}
                required
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label>Unit</label>
              <input
                name="unit"
                placeholder="e.g. seconds, meters"
                value={form.unit}
                onChange={handleChange}
              />
            </div>
          </div>
          <div className="form-group">
            <label>Notes</label>
            <textarea
              name="notes"
              value={form.notes}
              onChange={handleChange}
              rows={2}
              placeholder="Optional notes..."
            />
          </div>
          <div className="flex gap-2">
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? 'Saving...' : 'Record Result'}
            </button>
            <button type="button" onClick={() => router.back()} className="btn btn-secondary">
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
