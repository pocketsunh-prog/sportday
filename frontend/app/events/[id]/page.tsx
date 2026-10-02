'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { api, EventDTO, EventResultDTO } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';
import Link from 'next/link';

export default function EventDetailPage() {
  const params = useParams();
  const router = useRouter();
  const { user } = useAuth();
  const { t, label } = useI18n();
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

  if (loading) return <div>{t('common.loading')}</div>;
  if (!event) return <div className="card">{t('events.notFound')}</div>;

  const isAdmin = user?.role === 'ADMIN' || user?.role === 'MANAGER';

  return (
    <div>
      <div className="card">
        <div className="flex justify-between items-center">
          <div>
            <h1 className="page-title" style={{ marginBottom: '0.5rem' }}>{event.name}</h1>
            <span className="badge badge-info">{event.typeLabel}</span>
            {!event.enabled && (
              <span className="badge badge-warning ml-2">{t('adminEvents.disabled')}</span>
            )}
          </div>
          <div className="flex gap-2">
            {user && event.enabled && (
              enrolled ? (
                <button onClick={handleCancel} className="btn btn-sm btn-danger">
                  {t('events.withdraw')}
                </button>
              ) : (
                <button onClick={handleEnroll} className="btn btn-sm btn-success">
                  {t('events.enter')}
                </button>
              )
            )}
            {isAdmin && (
              <>
                <Link href={`/admin/events/${event.id}/edit`} className="btn btn-sm btn-secondary">
                  {t('common.edit')}
                </Link>
                <Link href={`/admin/results/new?eventId=${event.id}`} className="btn btn-sm btn-primary">
                  {t('results.recordResult')}
                </Link>
              </>
            )}
          </div>
        </div>

        <div className="mt-3" style={{ color: '#666' }}>
          <p>{event.description}</p>
          <div style={{ marginTop: '1rem' }}>
            <div><strong>{t('events.date')}:</strong> {new Date(event.eventDate).toLocaleDateString()}</div>
            {event.location && <div><strong>{t('events.place')}:</strong> {event.location}</div>}
            <div>
              <strong>{t('events.participants')}:</strong> {event.enrolledCount || 0} / {event.maxParticipants}
            </div>
          </div>
        </div>
      </div>

      <div className="card mt-2">
        <h2>{t('results.title')}</h2>
        {results.length === 0 ? (
          <p style={{ color: '#888' }}>{t('results.noResults')}</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>{t('marks.rank')}</th>
                <th>{t('results.athlete')}</th>
                <th>{t('marks.record')}</th>
                <th>{t('marks.unit')}</th>
                <th>{t('results.notes')}</th>
              </tr>
            </thead>
            <tbody>
              {results.map((result, idx) => (
                <tr key={result.id}>
                  <td>{idx + 1}</td>
                  <td>{result.fullName || result.username}</td>
                  <td>{result.mark}</td>
                  <td>{result.unit ? label('unit', result.unit) : '-'}</td>
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
