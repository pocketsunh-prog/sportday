'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '@/lib/api';
import Link from 'next/link';

const EVENT_TYPES = [
  'RUN_100M', 'RUN_200M', 'RUN_400M', 'RUN_800M', 'RUN_1500M', 'RUN_5000M',
  'SHOT_PUT', 'DISCUSSION_THROW', 'JAVELIN_THROW', 'HAMMER_THROW',
  'LONG_JUMP', 'HIGH_JUMP', 'TRIPLE_JUMP', 'POLE_VAULT',
  'RELAY_4X100M', 'RELAY_4X400M', 'HURDLES_110M', 'HURDLES_400M', 'OTHER'
];

export default function NewEventPage() {
  const router = useRouter();
  const [form, setForm] = useState({
    name: '',
    description: '',
    type: 'RUN_100M',
    eventDate: '',
    location: '',
    maxParticipants: 50,
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    const value = e.target.type === 'number' ? parseInt(e.target.value) : e.target.value;
    setForm({ ...form, [e.target.name]: value });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await api.createEvent(form);
      router.push('/events');
    } catch (err: any) {
      setError(err.message || 'Failed to create event');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <h1 className="page-title">Create New Event</h1>
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
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? 'Creating...' : 'Create Event'}
            </button>
            <Link href="/admin" className="btn btn-secondary">Cancel</Link>
          </div>
        </form>
      </div>
    </div>
  );
}
