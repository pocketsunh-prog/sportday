'use client';

import { Suspense, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { api, EventDTO, UserDTO, EnrollmentDTO } from '@/lib/api';
import { useI18n } from '@/lib/i18n';

// `useSearchParams()` must sit inside a Suspense boundary or the whole route
// opts out of static prerendering and `next build` fails.
export default function NewResultPage() {
  const { t } = useI18n();
  return (
    <Suspense fallback={<div>{t('common.loading')}</div>}>
      <NewResultForm />
    </Suspense>
  );
}

function NewResultForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { t } = useI18n();
  const [events, setEvents] = useState<EventDTO[]>([]);
  const [users, setUsers] = useState<UserDTO[]>([]);
  const [enrollments, setEnrollments] = useState<EnrollmentDTO[]>([]);
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
      setSuccess(t('results.recorded'));
      setForm({ ...form, userId: 0, mark: '', notes: '' });
    } catch (err: any) {
      setError(err.message || t('results.recordFailed'));
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <div>{t('common.loading')}</div>;

  // Filter users to show only enrolled ones for selected event
  const enrolledUserIds = new Set(
    enrollments
      .map(e => e.userId)
      .filter((id): id is number => typeof id === 'number')
  );
  const filteredUsers = form.eventId
    ? users.filter(u => enrolledUserIds.has(u.id))
    : users;

  return (
    <div>
      <h1 className="page-title">{t('results.recordTitle')}</h1>
      <div className="card" style={{ maxWidth: '600px' }}>
        {error && <div className="alert alert-error">{error}</div>}
        {success && <div className="alert alert-success">{success}</div>}
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>{t('events.event')} *</label>
            <select name="eventId" value={form.eventId} onChange={handleChange} required>
              <option value={0}>{t('results.selectEvent')}</option>
              {events.map(event => (
                <option key={event.id} value={event.id}>
                  {event.name} ({new Date(event.eventDate).toLocaleDateString()})
                </option>
              ))}
            </select>
          </div>
          <div className="form-group">
            <label>{t('results.athlete')} *</label>
            <select name="userId" value={form.userId} onChange={handleChange} required>
              <option value={0}>{t('results.selectAthlete')}</option>
              {filteredUsers.map(user => (
                <option key={user.id} value={user.id}>
                  {user.fullName || user.username} ({user.email})
                </option>
              ))}
            </select>
          </div>
          <div className="flex gap-2">
            <div className="form-group" style={{ flex: 2 }}>
              <label>{t('marks.record')} *</label>
              <input
                name="mark"
                type="number"
                step="0.001"
                placeholder={t('results.markPlaceholder')}
                value={form.mark}
                onChange={handleChange}
                required
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label>{t('marks.unit')}</label>
              <input
                name="unit"
                placeholder={t('results.unitPlaceholder')}
                value={form.unit}
                onChange={handleChange}
              />
            </div>
          </div>
          <div className="form-group">
            <label>{t('results.notes')}</label>
            <textarea
              name="notes"
              value={form.notes}
              onChange={handleChange}
              rows={2}
              placeholder={t('results.notesPlaceholder')}
            />
          </div>
          <div className="flex gap-2">
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? t('common.saving') : t('results.recordResult')}
            </button>
            <button type="button" onClick={() => router.back()} className="btn btn-secondary">
              {t('common.cancel')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
