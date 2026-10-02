'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  api,
  EventCategory,
  EventSex,
  EVENT_TYPE_OPTIONS,
  eventTypeCategory,
  SheetSize,
  sheetDefaultsForType,
} from '@/lib/api';
import { useI18n } from '@/lib/i18n';
import Link from 'next/link';

export default function NewEventPage() {
  const { t, label } = useI18n();
  const router = useRouter();
  const [form, setForm] = useState({
    name: '',
    description: '',
    type: 'RUN_100M',
    category: 'TRACK' as EventCategory,
    sex: 'MALE' as EventSex,
    eventDate: '',
    location: '',
    maxParticipants: 50,
    groupSize: 8,
    shortSprint: true,
    sheetSize: 'A5' as SheetSize,
    maxEntriesPerStudent: 1,
    enabled: true,
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleChange = (
    e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>
  ) => {
    const { name, value, type } = e.target;
    const checked = (e.target as HTMLInputElement).checked;
    setForm(prev => {
      const next = {
        ...prev,
        [name]: type === 'number' ? parseInt(value, 10) : type === 'checkbox' ? checked : value,
      };
      // Changing the type re-derives the category and the sheet defaults.
      if (name === 'type') {
        next.category = eventTypeCategory(value);
        Object.assign(next, sheetDefaultsForType(value));
      }
      return next;
    });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await api.createEvent(form);
      router.push('/admin/events');
    } catch (err: any) {
      setError(err.message || t('adminEvents.createFailed'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('adminEvents.createTitle')}</h1>
        <Link href="/admin/events" className="btn btn-secondary">
          {t('common.cancel')}
        </Link>
      </div>
      <div className="card" style={{ maxWidth: '700px' }}>
        {error && <div className="alert alert-error">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>{t('events.name')} *</label>
            <input name="name" value={form.name} onChange={handleChange} required />
          </div>
          <div className="form-group">
            <label>{t('events.description')}</label>
            <textarea name="description" value={form.description} onChange={handleChange} rows={3} />
          </div>
          <div className="flex gap-2">
            <div className="form-group" style={{ flex: 2 }}>
              <label>{t('events.type')} *</label>
              <select name="type" value={form.type} onChange={handleChange}>
                {EVENT_TYPE_OPTIONS.map(option => (
                  <option key={option.value} value={option.value}>
                    {option.label} ({label('category', option.category)})
                  </option>
                ))}
              </select>
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label>{t('print.category')} *</label>
              <select name="category" value={form.category} onChange={handleChange}>
                <option value="TRACK">{label('category', 'TRACK')}</option>
                <option value="FIELD">{label('category', 'FIELD')}</option>
              </select>
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label>{t('print.division')} *</label>
              <select name="sex" value={form.sex} onChange={handleChange}>
                <option value="MALE">{label('sex', 'MALE')}</option>
                <option value="FEMALE">{label('sex', 'FEMALE')}</option>
              </select>
            </div>
          </div>
          <div className="flex gap-2">
            <div className="form-group" style={{ flex: 1 }}>
              <label>{t('events.date')} *</label>
              <input
                name="eventDate"
                type="date"
                value={form.eventDate}
                onChange={handleChange}
                required
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label>{t('events.place')}</label>
              <input name="location" value={form.location} onChange={handleChange} />
            </div>
          </div>
          <div className="flex gap-2">
            <div className="form-group" style={{ flex: 1 }}>
              <label>{t('events.maxParticipants')} *</label>
              <input
                name="maxParticipants"
                type="number"
                min="1"
                value={form.maxParticipants}
                onChange={handleChange}
                required
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label>{t('events.groupSize')}</label>
              <input
                name="groupSize"
                type="number"
                min="1"
                value={form.groupSize}
                onChange={handleChange}
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label>{t('events.sheetSize')}</label>
              <select name="sheetSize" value={form.sheetSize} onChange={handleChange}>
                <option value="A5">{label('sheet', 'A5')}</option>
                <option value="A4">{label('sheet', 'A4')}</option>
              </select>
            </div>
          </div>
          <div className="flex gap-2">
            <div className="form-group" style={{ flex: 1 }}>
              <label>{t('events.maxEntries')}</label>
              <input
                name="maxEntriesPerStudent"
                type="number"
                min="1"
                value={form.maxEntriesPerStudent}
                onChange={handleChange}
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label>{t('events.shortSprint')}</label>
              <label className="checkbox-line">
                <input
                  name="shortSprint"
                  type="checkbox"
                  checked={form.shortSprint}
                  onChange={handleChange}
                />
                {t('events.lanesPhotoFinish')}
              </label>
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label>{t('adminEvents.enabled')}</label>
              <label className="checkbox-line">
                <input
                  name="enabled"
                  type="checkbox"
                  checked={form.enabled}
                  onChange={handleChange}
                />
                {t('events.visibleToStudents')}
              </label>
            </div>
          </div>
          <div className="flex gap-2 mt-2">
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? t('common.creating') : t('adminEvents.create')}
            </button>
            <Link href="/admin/events" className="btn btn-secondary">
              {t('common.cancel')}
            </Link>
          </div>
        </form>
      </div>
    </div>
  );
}
