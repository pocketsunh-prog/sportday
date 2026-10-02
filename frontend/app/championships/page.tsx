'use client';

import { useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, ChampionshipsDTO, EventStandingsDTO, SettingsDTO } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n, MessageKey } from '@/lib/i18n';
import { formatDate } from '@/lib/format';

/** `1st 9, 2nd 6, 3rd 3, 4th–8th 1 · relay 30 / 20 / 10` */
function scaleLine(
  settings: SettingsDTO,
  t: (key: MessageKey, vars?: Record<string, string | number>) => string,
): string {
  const base = t('settings.scale', {
    first: settings.pointsFirst,
    second: settings.pointsSecond,
    third: settings.pointsThird,
    topPlace: settings.pointsTopPlace,
    top: settings.pointsTop,
    rfirst: settings.relayPointsFirst,
    rsecond: settings.relayPointsSecond,
    rthird: settings.relayPointsThird,
  });
  const tops = t('settings.scaleTopPlace', {
    topPlace: settings.pointsTopPlace,
    top: settings.pointsTop,
    rtop: settings.relayPointsTop,
  });
  return `${base} · ${tops}`;
}

function placingIcon(place: number): string {
  return place === 1 ? '🥇' : place === 2 ? '🥈' : place === 3 ? '🥉' : String(place);
}

export default function ChampionshipsPage() {
  const { user, isLoading } = useAuth();
  const { t, label } = useI18n();
  const router = useRouter();
  const [data, setData] = useState<ChampionshipsDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState<Record<number, boolean>>({});

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    api
      .getChampionships()
      .then(setData)
      .catch((err: unknown) =>
        setError(err instanceof Error ? err.message : t('championships.loadFailed'))
      )
      .finally(() => setLoading(false));
  }, [user, isLoading, router, t]);

  const scoredAthletes = useMemo(() => {
    if (!data) return 0;
    const seen = new Set<number>();
    data.events.forEach(event => event.placings.forEach(placing => seen.add(placing.userId)));
    return seen.size;
  }, [data]);

  if (isLoading || !user || loading) {
    return <div>{t('common.loading')}</div>;
  }

  if (!data) {
    return (
      <div>
        <h1 className="page-title">{t('championships.title')}</h1>
        <div className="alert alert-error">{error || t('championships.loadFailed')}</div>
      </div>
    );
  }

  const toggleEvent = (eventId: number) => {
    setExpanded(prev => ({ ...prev, [eventId]: !prev[eventId] }));
  };

  const eventPlacings = (event: EventStandingsDTO) => (
    <div className="table-wrap" style={{ marginTop: '0.75rem' }}>
      <table>
        <thead>
          <tr>
            <th>{t('championships.colRank')}</th>
            <th>{t('championships.colAthlete')}</th>
            <th>{t('championships.colGrade')}</th>
            <th>{t('championships.colClass')}</th>
            <th>{t('championships.colHouse')}</th>
            <th>{t('marks.record')}</th>
            <th>{t('championships.colPoints')}</th>
          </tr>
        </thead>
        <tbody>
          {event.placings.map(placing => (
            <tr key={`${event.eventId}-${placing.userId}-${placing.place}`}>
              <td>{placingIcon(placing.place)}</td>
              <td>
                {placing.name}
                <span className="muted" style={{ marginLeft: '0.4rem' }}>
                  {placing.studentRef}
                </span>
                {event.relay && (
                  <span className="badge badge-warn" style={{ marginLeft: '0.4rem' }}>
                    {t('championships.relay')}
                  </span>
                )}
              </td>
              <td>{label('grade.short', placing.grade) || placing.grade}</td>
              <td>{placing.className}</td>
              <td>{placing.house}</td>
              <td>
                <strong>{placing.mark}</strong>{' '}
                {placing.unit ? label('unit', placing.unit) : ''}
                {placing.schoolRecord && (
                  <span className="badge badge-success" style={{ marginLeft: '0.4rem' }}>
                    {t('championships.schoolRecord')}
                  </span>
                )}
              </td>
              <td>
                <strong>{placing.points}</strong>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );

  return (
    <div>
      <h1 className="page-title">{t('championships.title')}</h1>
      <p className="muted">{t('championships.subtitle')}</p>

      <div className="stat-grid">
        <div className="stat">
          <div className="stat-value">{formatDate(data.referenceDate)}</div>
          <div className="stat-label">{t('championships.referenceDate')}</div>
        </div>
        <div className="stat">
          <div className="stat-value">{data.eventsScored}</div>
          <div className="stat-label">{t('championships.eventsScored')}</div>
        </div>
        <div className="stat">
          <div className="stat-value">{scoredAthletes}</div>
          <div className="stat-label">{t('championships.athletesScored')}</div>
        </div>
      </div>

      <div className="card">
        <h2>{t('championships.pointsScale')}</h2>
        <p style={{ fontSize: '1.05rem' }}>{scaleLine(data.settings, t)}</p>
        <p className="muted">{t('championships.stageHint')}</p>
        <p className="muted">
          {t('championships.scoringStage')}: {data.scoringStageNote}
        </p>
        <p className="muted">{t('championships.relayHint')}</p>
      </div>

      <div className="card mt-2">
        <h2>{t('championships.housesTitle')}</h2>
        {data.houses.length === 0 ? (
          <div className="empty">
            <p>{t('championships.housesEmpty')}</p>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t('championships.colRank')}</th>
                  <th>{t('championships.colHouse')}</th>
                  <th>{t('championships.colPoints')}</th>
                  <th>{t('championships.colGold')}</th>
                  <th>{t('championships.colSilver')}</th>
                  <th>{t('championships.colBronze')}</th>
                  <th>{t('championships.colAthletes')}</th>
                </tr>
              </thead>
              <tbody>
                {data.houses.map(row => (
                  <tr key={row.house}>
                    <td>{placingIcon(row.rank)}</td>
                    <td>
                      <strong>{row.house}</strong>
                      {row.rank === 1 && (
                        <span className="badge badge-success" style={{ marginLeft: '0.5rem' }}>
                          {t('championships.leader')}
                        </span>
                      )}
                    </td>
                    <td>
                      <strong>{row.points}</strong>
                    </td>
                    <td>{row.golds}</td>
                    <td>{row.silvers}</td>
                    <td>{row.bronzes}</td>
                    <td>{row.athletes}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card mt-2">
        <h2>{t('championships.personalTitle')}</h2>
        {data.personal.length === 0 ? (
          <div className="empty">
            <p>{t('championships.personalEmpty')}</p>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t('championships.colRank')}</th>
                  <th>{t('championships.colAthlete')}</th>
                  <th>{t('championships.colGrade')}</th>
                  <th>{t('championships.colClass')}</th>
                  <th>{t('championships.colHouse')}</th>
                  <th>{t('championships.colPoints')}</th>
                  <th>{t('championships.colGold')}</th>
                  <th>{t('championships.colSilver')}</th>
                  <th>{t('championships.colBronze')}</th>
                  <th>{t('championships.colEventsScored')}</th>
                </tr>
              </thead>
              <tbody>
                {data.personal.map(row => (
                  <tr key={row.userId}>
                    <td>{placingIcon(row.rank)}</td>
                    <td>
                      <strong>{row.name}</strong>
                      <span className="muted" style={{ marginLeft: '0.4rem' }}>
                        {row.studentRef}
                      </span>
                      {row.rank === 1 && (
                        <span className="badge badge-success" style={{ marginLeft: '0.5rem' }}>
                          {t('championships.leader')}
                        </span>
                      )}
                    </td>
                    <td>{label('grade.short', row.grade) || row.grade}</td>
                    <td>{row.className}</td>
                    <td>{row.house}</td>
                    <td>
                      <strong>{row.points}</strong>
                    </td>
                    <td>{row.golds}</td>
                    <td>{row.silvers}</td>
                    <td>{row.bronzes}</td>
                    <td>{row.eventsScored}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card mt-2">
        <h2>{t('championships.eventsTitle')}</h2>
        {data.events.length === 0 ? (
          <div className="empty">
            <p>{t('championships.eventsEmpty')}</p>
          </div>
        ) : (
          data.events.map(event => (
            <div key={event.eventId} style={{ borderTop: '1px solid #e4e6ee', padding: '0.75rem 0' }}>
              <div className="flex justify-between items-center">
                <div>
                  <strong>{event.eventName}</strong>
                  <span className="badge badge-info" style={{ marginLeft: '0.5rem' }}>
                    {event.categoryLabel}
                  </span>
                  <span className="badge badge-info" style={{ marginLeft: '0.4rem' }}>
                    {event.sexLabel}
                  </span>
                  <span className="badge badge-info" style={{ marginLeft: '0.4rem' }}>
                    {t('events.sheet')} {event.sheetSize}
                  </span>
                  {event.relay && (
                    <span className="badge badge-warn" style={{ marginLeft: '0.4rem' }}>
                      {t('championships.relayTag')}
                    </span>
                  )}
                  {event.placings.some(placing => placing.schoolRecord) && (
                    <span className="badge badge-success" style={{ marginLeft: '0.4rem' }}>
                      {t('championships.schoolRecord')}
                    </span>
                  )}
                  <div className="muted" style={{ marginTop: '0.25rem' }}>
                    {formatDate(event.eventDate)} · {t('championships.scoringStage')}:{' '}
                    {event.scoringStage === 'FINAL'
                      ? t('championships.stageFinal')
                      : t('championships.stageHeat')}
                    {event.hasFinal && ` · ${t('print.finalCount', { count: event.placings.length })}`}
                  </div>
                </div>
                <button
                  type="button"
                  className="btn btn-sm btn-secondary"
                  onClick={() => toggleEvent(event.eventId)}
                  aria-expanded={Boolean(expanded[event.eventId])}
                  title={t('championships.toggleEvent')}
                >
                  {expanded[event.eventId] ? t('common.close') : t('common.details')}
                </button>
              </div>
              {expanded[event.eventId] &&
                (event.placings.length === 0 ? (
                  <p className="muted">{t('championships.noPlacings')}</p>
                ) : (
                  eventPlacings(event)
                ))}
            </div>
          ))
        )}
      </div>
    </div>
  );
}
