'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { api, EventDTO } from '@/lib/api';
import Link from 'next/link';

const EVENT_TYPES = [
  'RUN_100M', 'RUN_200M', 'RUN_400M', 'RUN_800M', 'RUN_1500M', 'RUN_5000M',
  'SHOT_PUT', 'DISCUSSION_THROW', 'JAVELIN_THROW', 'HAMMER_THROW',
  'LONG_JUMP', 'HIGH_JUMP', 'TRIPLE_JUMP', 'POLE_VAULT',
  'RELAY_4X100M', 'RELAY_4X400M', 'HURDLES_110M', 'HURDLES_400M', 'OTHER'
];

export default function EditEventPage() {
  const params = useParams();
  const router = useRouter();
  const [form, setForm] = useState({
    name: '',
    description: '',
    type: '',
    eventDate: '',
    location: '',
    maxParticipants: 50,
  });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const eventId = Number(params.id);

  useEffect(() => {
    api.getEvent(eventId)
      .then((event: EventDTO) => {
        setForm({
          name: event.name,
          description: event.description || '',
          type: event.type,
          eventDate: event.eventDate,
          location: event.location || '',
          maxParticipants: event.maxParticipants,
        });
      })
      .catch(err => setError(err.message))
      .finally(() => setLoading(false));
  }, [eventId]);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    const value = e.target.type === 'number' ? parseInt(e.target.value) : e.target.value;
    setForm({ ...form, [e.target.name]: value });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setSaving(true);
    try {
      await api.updateEvent(eventId, form);
      router.push(`/events/${eventId}`);
    } catch (err: any) {
      setError(err.message || 'Failed to update event');
    } finally {
      setSaving(false);
    }
  };

  const handleToggleEnable = async () => {
    try {
      const current = await api.getEvent(eventId);
      await api.setEventEnabled(eventId, !current.enabled);
      router.refresh();
    } catch (err: any) {
      alert(err.message);
    }
  };

  const handleDelete = async () => {
    if (!confirm('Are you sure you want to delete this event? This cannot be undone.')) return;
    try {
      await api.deleteEvent(eventId);
      router.push('/events');
    } catch (err: any) {
      alert(err.message);
    }
  };

  if (loading) return <div>Loading...</div>;

  return (
    <div>
      <h1 className="page-title">Edit Event</h1>
      <div className="card" style={{ maxWidth: '600px' }}>
        {error && <div className="alert alert-error">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>Event Name *</label>
            <input name="name" value={form.name} onChange={handleChange} required />
          </div>
          <div className="form-group">
            <label>Description</label>
            <textarea name="description" value={form.description} onChange={handleChange} rows={3} />
          </div>
          <div className="form-group">
            <label>Event Type *</label>
            <select name="type" value={form.type} onChange={handleChange}>
              {EVENT_TYPES.map(t => (
                <option key={t} value={t}>{t.replace(/_/g, ' ')}</option>
              ))}
            </select>
          </div>
          <div className="form-group">
            <label>Event Date *</label>
            <input name="eventDate" type="date" value={form.eventDate} onChange={handleChange} required />
          </div>
          <div className="form-group">
            <label>Location</label>
            <input name="location" value={form.location} onChange={handleChange} />
          </div>
          <div className="form-group">
            <label>Max Participants *</label>
            <input name="maxParticipants" type="number" min="1" value={form.maxParticipants} onChange={handleChange} required />
          </div>
          <div className="flex gap-2">
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? 'Saving...' : 'Save Changes'}
            </button>
            <button type="button" onClick={handleToggleEnable} className="btn btn-secondary">
              Toggle Enable
            </button>
            <button type="button" onClick={handleDelete} className="btn btn-danger">
              Delete
            </button>
          </div>
        </form>
      </div>
      <div className="mt-2">
        <Link href={`/events/${eventId}`} className="btn btn-secondary">Back to Event</Link>
      </div>
    </div>
  );
}
