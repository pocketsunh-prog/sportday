'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth';
import { api, EventDTO } from '@/lib/api';
import { useI18n } from '@/lib/i18n';

export default function HomePage() {
  const { user, isLoading } = useAuth();
  const { t } = useI18n();
  const router = useRouter();
  const [events, setEvents] = useState<EventDTO[]>([]);
  const [loading, setLoading] = useState(true);

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
        <p>{t('common.loading')}</p>
      </div>
    );
  }

  const menuItems = [
    { href: '/events', title: t('nav.events'), desc: t('home.eventsDesc'), color: '#e94560' },
    { href: '/results', title: t('nav.results'), desc: t('home.resultsDesc'), color: '#16213e' },
    { href: '/my-enrollments', title: t('nav.myEntries'), desc: t('home.myEntriesDesc'), color: '#00b894' },
  ];

  if (user.role === 'ADMIN' || user.role === 'MANAGER') {
    menuItems.push({ href: '/admin', title: t('home.adminTitle'), desc: t('home.adminDesc'), color: '#6c5ce7' });
  }

  return (
    <div>
      <div className="text-center" style={{ padding: '2rem 0' }}>
        <h1 className="page-title">
          {t('home.welcome', { name: user.fullName || user.username })}
        </h1>
        <p style={{ fontSize: '1.1rem', color: '#666' }}>
          {t('home.subtitle')}
        </p>
      </div>

      <div className="card-grid">
        {menuItems.map(item => (
          <Link key={item.href} href={item.href} className="card" style={{ textDecoration: 'none', color: 'inherit', borderTop: `4px solid ${item.color}` }}>
            <h3 style={{ marginBottom: '0.5rem' }}>{item.title}</h3>
            <p style={{ color: '#666', fontSize: '0.9rem' }}>{item.desc}</p>
          </Link>
        ))}
      </div>

      {!loading && events.length > 0 && (
        <div className="mt-3">
          <h2 className="page-title">{t('home.upcoming')}</h2>
          <div className="card-grid">
            {events.slice(0, 6).map(event => (
              <div key={event.id} className="card">
                <div className="flex justify-between items-center mb-2">
                  <h3>{event.name}</h3>
                  <span className="badge badge-info">{event.typeLabel}</span>
                </div>
                <p style={{ color: '#666', fontSize: '0.9rem' }}>
                  {event.description?.substring(0, 100)}
                </p>
                <div className="mt-2" style={{ fontSize: '0.85rem', color: '#888' }}>
                  <div>{t('events.date')}: {new Date(event.eventDate).toLocaleDateString()}</div>
                  {event.location && <div>{t('events.place')}: {event.location}</div>}
                  <div>
                    {t('events.participants')}: {event.enrolledCount || 0} / {event.maxParticipants}
                  </div>
                </div>
                <Link href={`/events/${event.id}`} className="btn btn-sm btn-primary mt-2">
                  {t('common.details')}
                </Link>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
