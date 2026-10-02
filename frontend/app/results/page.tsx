'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, EventResultDTO, EventDTO, EventStandingsDTO, SeasonDTO, formatAttempts } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';
import { formatDate } from '@/lib/format';
import Link from 'next/link';

type ResultsTab = 'event' | 'past';

export default function ResultsPage() {
  const [events, setEvents] = useState<EventDTO[]>([]);
  const [selectedEvent, setSelectedEvent] = useState<number | null>(null);
  const [results, setResults] = useState<EventResultDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<ResultsTab>('event');
  const [pastEvents, setPastEvents] = useState<EventDTO[]>([]);
  const [pastLoading, setPastLoading] = useState(true);
  const [selectedPastEvent, setSelectedPastEvent] = useState<number | null>(null);
  const [standings, setStandings] = useState<EventStandingsDTO | null>(null);
  const [standingsLoading, setStandingsLoading] = useState(false);
  /** The school years, and the one the past events are narrowed to. */
  const [seasons, setSeasons] = useState<SeasonDTO[]>([]);
  const [pastYearFilter, setPastYearFilter] = useState<number | ''>('');
  const { user, isLoading } = useAuth();
  const { t, label } = useI18n();
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

  // Events dated today or earlier, most recent first.
  useEffect(() => {
    if (user) {
      api.getPastEvents()
        .then(setPastEvents)
        .catch(() => setPastEvents([]))
        .finally(() => setPastLoading(false));
    }
  }, [user]);

  // The school years, for narrowing the past events to one sport day.
  useEffect(() => {
    if (user) {
      api
        .getSeasons()
        .then(setSeasons)
        .catch(() => setSeasons([]));
    }
  }, [user]);

  // Read ?eventId= once on mount.
  //
  // This effect and the one below it must stay ABOVE the early return: an early
  // return that skips a hook changes the number of hooks between renders, and
  // React throws "Rendered more hooks than during the previous render". Because
  // AuthProvider.isLoading starts true and then flips, the very first navigation
  // to this page used to hit exactly that.
  useEffect(() => {
    const eventId = new URLSearchParams(window.location.search).get('eventId');
    if (eventId) {
      setSelectedEvent(Number(eventId));
    }
  }, []);

  useEffect(() => {
    if (selectedEvent) {
      api.getEventResults(selectedEvent).then(setResults).catch(() => setResults([]));
    }
  }, [selectedEvent]);

  // The placings of the selected past event, with its points.
  useEffect(() => {
    if (!selectedPastEvent) {
      setStandings(null);
      return;
    }
    setStandingsLoading(true);
    api
      .getEventStandings(selectedPastEvent)
      .then(setStandings)
      .catch(() => setStandings(null))
      .finally(() => setStandingsLoading(false));
  }, [selectedPastEvent]);

  if (isLoading || !user) {
    return (
      <div className="text-center" style={{ padding: '4rem 0' }}>
        <p>{t('common.loading')}</p>
      </div>
    );
  }

  const newRecordBadge = (
    <span className="badge badge-warn" style={{ marginLeft: '0.4rem' }}>
      {t('results.newRecord')}
    </span>
  );

  const pastEvent = pastEvents.find(event => event.id === selectedPastEvent) || null;

  /** The past events of the chosen school year (all of them when none is chosen). */
  const pastEventsForYear =
    pastYearFilter === ''
      ? pastEvents
      : pastEvents.filter(event => event.seasonId === pastYearFilter);

  const eventTab = (
    <>
      <div className="card">
        <div className="form-group">
          <label>{t('results.selectEvent')}</label>
          <select
            value={selectedEvent || ''}
            onChange={e => setSelectedEvent(e.target.value ? Number(e.target.value) : null)}
          >
            <option value="">{t('results.chooseEvent')}</option>
            {events.map(event => (
              <option key={event.id} value={event.id}>
                {event.name} ({formatDate(event.eventDate)})
              </option>
            ))}
          </select>
        </div>
      </div>

      {selectedEvent && (
        <div className="card mt-2">
          <h2>{t('marks.leaderboard')}</h2>
          {results.length === 0 ? (
            <p style={{ color: '#888' }}>{t('results.noResultsEvent')}</p>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{t('marks.rank')}</th>
                    <th>{t('results.athlete')}</th>
                    <th>{t('marks.record')}</th>
                    <th>{t('marks.unit')}</th>
                    <th>{t('results.notes')}</th>
                    <th>{t('events.date')}</th>
                  </tr>
                </thead>
                <tbody>
                  {results.map((result, idx) => (
                    <tr key={result.id}>
                      <td>
                        {idx === 0 ? '🥇' : idx === 1 ? '🥈' : idx === 2 ? '🥉' : idx + 1}
                      </td>
                      <td>{result.fullName || result.username}</td>
                      <td>
                        <strong>{result.mark}</strong>
                        {result.newRecord && newRecordBadge}
                        {/* A field result carries its three attempts; the mark
                            above is the best of them. A track result has none. */}
                        {formatAttempts(result.attempts) && (
                          <div className="muted">{formatAttempts(result.attempts)}</div>
                        )}
                      </td>
                      <td>{result.unit ? label('unit', result.unit) : '-'}</td>
                      <td>{result.notes || '-'}</td>
                      <td>{formatDate(result.recordedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </>
  );

  const pastTab = (
    <>
      <div className="card">
        <div className="grid-toolbar">
          <div className="field" style={{ minWidth: '16rem' }}>
            <label>{t('results.yearFilter')}</label>
            <select
              value={pastYearFilter}
              onChange={e => {
                setPastYearFilter(e.target.value ? Number(e.target.value) : '');
                // The chosen event may not be in the year now on screen.
                setSelectedPastEvent(null);
              }}
            >
              <option value="">{t('events.allYears')}</option>
              {seasons.map(season => (
                <option key={season.id} value={season.id}>
                  {season.current
                    ? t('events.yearOptionCurrent', {
                        year: season.year,
                        count: season.eventCount ?? 0,
                      })
                    : t('events.yearOption', {
                        year: season.year,
                        count: season.eventCount ?? 0,
                      })}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="form-group mt-2">
          <label>{t('results.selectPastEvent')}</label>
          <select
            value={selectedPastEvent || ''}
            onChange={e => setSelectedPastEvent(e.target.value ? Number(e.target.value) : null)}
          >
            <option value="">{t('results.choosePastEvent')}</option>
            {pastEventsForYear.map(event => (
              <option key={event.id} value={event.id}>
                {event.typeLabel || event.type} · {event.name} ({formatDate(event.eventDate)})
              </option>
            ))}
          </select>
        </div>
        <p className="muted">
          {pastYearFilter === '' ? t('results.pastHint') : t('results.pastHintYear')}
        </p>
        {pastEventsForYear.length === 0 && (
          <p className="muted">
            {pastLoading
              ? t('common.loading')
              : pastYearFilter === ''
                ? t('results.noPastEvents')
                : t('results.noPastEventsYear')}
          </p>
        )}
      </div>

      {selectedPastEvent && (
        <div className="card mt-2">
          <h2>
            {pastEvent ? pastEvent.name : t('results.pastStandings')}
            {pastEvent?.sexLabel && (
              <span className="badge badge-info" style={{ marginLeft: '0.5rem' }}>
                {label('sex', pastEvent.sex)}
              </span>
            )}
            {pastEvent?.category && (
              <span className="badge badge-info" style={{ marginLeft: '0.4rem' }}>
                {label('category', pastEvent.category)}
              </span>
            )}
          </h2>

          {standingsLoading ? (
            <p className="muted">{t('common.loading')}</p>
          ) : !standings || standings.placings.length === 0 ? (
            <div className="empty">
              <p>{t('results.pastNoResults')}</p>
              <p>
                <Link href={`/events/${selectedPastEvent}`}>{t('events.backToEvent')}</Link>
              </p>
            </div>
          ) : (
            <>
              <div className="grid-meta">
                <span>{formatDate(standings.eventDate)}</span>
                <span>
                  {t('championships.scoringStage')}:{' '}
                  <span className="badge badge-info">
                    {standings.scoringStage === 'FINAL'
                      ? t('championships.stageFinal')
                      : t('championships.stageHeat')}
                  </span>
                </span>
                {standings.relay && (
                  <span>
                    <span className="badge badge-warn">{t('championships.relay')}</span>{' '}
                    {t('championships.relayHint')}
                  </span>
                )}
              </div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>{t('results.place')}</th>
                      <th>{t('results.athlete')}</th>
                      <th>{t('championships.colGrade')}</th>
                      <th>{t('championships.colClass')}</th>
                      <th>{t('championships.colHouse')}</th>
                      <th>{t('marks.record')}</th>
                      <th>{t('championships.colPoints')}</th>
                      <th>{t('common.details')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {standings.placings.map(placing => (
                      <tr key={`${placing.userId}-${placing.place}`}>
                        <td>
                          {placing.place === 1
                            ? '🥇'
                            : placing.place === 2
                              ? '🥈'
                              : placing.place === 3
                                ? '🥉'
                                : placing.place}
                        </td>
                        <td>
                          {placing.name}
                          <span className="muted" style={{ marginLeft: '0.4rem' }}>
                            {placing.studentRef}
                          </span>
                        </td>
                        <td>{label('grade.short', placing.grade)}</td>
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
                        <td>
                          <Link href={`/events/${standings.eventId || selectedPastEvent}`}>
                            {t('common.details')}
                          </Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      )}
    </>
  );

  return (
    <div>
      <h1 className="page-title">{t('results.eventResults')}</h1>

      <div className="grid-toolbar">
        <button
          type="button"
          className={`btn btn-sm ${tab === 'event' ? 'btn-primary' : 'btn-secondary'}`}
          onClick={() => setTab('event')}
        >
          {t('results.tabEvent')}
        </button>
        <button
          type="button"
          className={`btn btn-sm ${tab === 'past' ? 'btn-primary' : 'btn-secondary'}`}
          onClick={() => setTab('past')}
        >
          {t('results.tabPast')}
        </button>
      </div>

      {loading ? (
        <p className="muted">{t('common.loading')}</p>
      ) : tab === 'event' ? (
        eventTab
      ) : (
        pastTab
      )}
    </div>
  );
}
