'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { api, EventCategory, EventDateDTO, EventDTO, SeasonDTO, SexCode } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { formatDate } from '@/lib/format';
import { useI18n } from '@/lib/i18n';

export default function AdminEventsPage() {
  const { user } = useAuth();
  const { t, label } = useI18n();
  const isAdmin = user?.role === 'ADMIN';

  const [events, setEvents] = useState<EventDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [defaultsDate, setDefaultsDate] = useState('');
  const [filterCategory, setFilterCategory] = useState<EventCategory | ''>('');
  const [filterSex, setFilterSex] = useState<SexCode | ''>('');
  const [filterDate, setFilterDate] = useState('');
  const [dates, setDates] = useState<EventDateDTO[]>([]);
  const [search, setSearch] = useState('');

  /** The school years, and which one the programme is being read for. */
  const [seasons, setSeasons] = useState<SeasonDTO[]>([]);
  const [seasonId, setSeasonId] = useState<number | ''>('');
  /** False until the year the page opens on has been decided, so the first load
   *  is the current year rather than every year at once. */
  const [seasonReady, setSeasonReady] = useState(false);

  const currentSeason = useMemo(() => seasons.find(season => season.current) || null, [seasons]);
  const selectedSeason = useMemo(
    () => seasons.find(season => season.id === seasonId) || null,
    [seasons, seasonId]
  );

  const load = useCallback(async (date: string, season: number | '') => {
    setLoading(true);
    try {
      // The date and the school year are the two filters the server applies; the
      // rest narrow the list below, so they compose with them rather than
      // replacing them.
      const list = await api.getEvents({
        date: date || undefined,
        seasonId: season === '' ? undefined : season,
      });
      setEvents(list);
    } catch (err: any) {
      setError(err?.message || t('events.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadDates = useCallback(async () => {
    const list = await api.getEventDates().catch(() => [] as EventDateDTO[]);
    setDates(list);
  }, []);

  // `?seasonId=` — the link from the sport day page — wins; otherwise the page
  // opens on the current year.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const list = await api.getSeasons().catch(() => [] as SeasonDTO[]);
      if (cancelled) return;
      setSeasons(list);
      const fromUrl = Number(new URLSearchParams(window.location.search).get('seasonId'));
      if (fromUrl && list.some(season => season.id === fromUrl)) {
        setSeasonId(fromUrl);
      } else {
        setSeasonId(list.find(season => season.current)?.id ?? '');
      }
      setSeasonReady(true);
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!seasonReady) return;
    load(filterDate, seasonId);
  }, [load, filterDate, seasonId, seasonReady]);

  useEffect(() => {
    loadDates();
  }, [loadDates]);

  /** The label of one option of the school year picker. */
  const yearOptionLabel = (season: SeasonDTO): string => {
    if (season.current) {
      return t('events.yearOptionCurrent', {
        year: season.year,
        count: season.eventCount ?? 0,
      });
    }
    if (!season.enrollmentOpen) {
      return t('events.yearOptionClosed', {
        year: season.year,
        count: season.eventCount ?? 0,
      });
    }
    return t('events.yearOption', { year: season.year, count: season.eventCount ?? 0 });
  };

  /** The label of one option of the date picker, with today marked. */
  const dateOptionLabel = (entry: EventDateDTO): string => {
    const date = formatDate(entry.date);
    if (entry.isToday) return t('events.dateOptionToday', { date, count: entry.eventCount });
    if (entry.isPast) return t('events.dateOptionPast', { date, count: entry.eventCount });
    return t('events.dateOption', { date, count: entry.eventCount });
  };

  const handleToggle = async (event: EventDTO) => {
    setBusyId(event.id);
    setError(null);
    setNotice(null);
    try {
      const updated = await api.setEventEnabled(event.id, !event.enabled);
      setEvents(prev => prev.map(item => (item.id === event.id ? { ...item, ...updated } : item)));
      setNotice(
        t(updated.enabled ? 'adminEvents.enabledNotice' : 'adminEvents.disabledNotice', {
          name: event.name,
        })
      );
    } catch (err: any) {
      setError(err?.message || t('adminEvents.statusFailed'));
    } finally {
      setBusyId(null);
    }
  };

  const handleDelete = async (event: EventDTO) => {
    if (!confirm(t('adminEvents.deleteConfirmNamed', { name: event.name }))) return;
    setBusyId(event.id);
    setError(null);
    setNotice(null);
    try {
      await api.deleteEvent(event.id);
      setEvents(prev => prev.filter(item => item.id !== event.id));
      setNotice(t('adminEvents.deletedNotice', { name: event.name }));
    } catch (err: any) {
      setError(err?.message || t('adminEvents.deleteFailed'));
    } finally {
      setBusyId(null);
    }
  };

  const handleCreateDefaults = async () => {
    if (!defaultsDate) {
      setError(t('adminEvents.pickDate'));
      return;
    }
    setBusyAction('defaults');
    setError(null);
    setNotice(null);
    try {
      const result = await api.createDefaultEvents(defaultsDate, true);
      setNotice(
        `${t('adminEvents.defaultsCreated', {
          created: result.created,
          total: result.totalEvents,
          date: formatDate(result.eventDate),
        })} ${
          result.includeField ? t('adminEvents.inclField') : t('adminEvents.trackOnly')
        }`
      );
      await Promise.all([load(filterDate, seasonId), loadDates()]);
    } catch (err: any) {
      setError(err?.message || t('adminEvents.defaultsFailed'));
    } finally {
      setBusyAction(null);
    }
  };

  const filtered = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return events.filter(event => {
      if (filterCategory && event.category !== filterCategory) return false;
      if (filterSex && event.sex !== (filterSex === 'M' ? 'MALE' : 'FEMALE')) return false;
      if (needle) {
        const haystack = `${event.name} ${event.typeLabel} ${event.type}`.toLowerCase();
        if (!haystack.includes(needle)) return false;
      }
      return true;
    });
  }, [events, filterCategory, filterSex, search]);

  const totals = useMemo(() => {
    const enabled = events.filter(event => event.enabled).length;
    const entries = events.reduce((sum, event) => sum + (event.enrolledCount || 0), 0);
    const ungrouped = events.reduce((sum, event) => sum + (event.ungroupedCount || 0), 0);
    return { enabled, disabled: events.length - enabled, entries, ungrouped };
  }, [events]);

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('adminEvents.title')}</h1>
        <div className="flex gap-2">
          <Link href="/admin/events/new" className="btn btn-secondary">
            {t('adminEvents.new')}
          </Link>
          <Link href="/admin" className="btn btn-secondary">
            {t('common.backToAdmin')}
          </Link>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {notice && <div className="alert alert-success">{notice}</div>}

      <div className="stat-grid">
        <div className="stat">
          <div className="stat-value">{events.length}</div>
          <div className="stat-label">{t('events.title')}</div>
        </div>
        <div className="stat">
          <div className="stat-value">{totals.enabled}</div>
          <div className="stat-label">{t('adminEvents.enabled')}</div>
        </div>
        <div className="stat">
          <div className="stat-value" style={{ color: totals.disabled > 0 ? '#d63031' : undefined }}>
            {totals.disabled}
          </div>
          <div className="stat-label">{t('adminEvents.disabled')}</div>
        </div>
        <div className="stat">
          <div className="stat-value">{totals.entries}</div>
          <div className="stat-label">{t('events.entries')}</div>
        </div>
        <div className="stat">
          <div className="stat-value">{totals.ungrouped}</div>
          <div className="stat-label">{t('adminEvents.ungrouped')}</div>
        </div>
      </div>

      <div className="card mt-3">
        <h2>{t('adminEvents.defaultCatalogue')}</h2>
        <p className="muted">
          {t('adminEvents.defaultCatalogueHint')}
        </p>
        <div className="toolbar mt-2">
          <div className="form-group">
            <label>{t('adminEvents.sportDay')}</label>
            <input
              type="date"
              value={defaultsDate}
              onChange={e => setDefaultsDate(e.target.value)}
            />
          </div>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busyAction === 'defaults'}
            onClick={handleCreateDefaults}
          >
            {busyAction === 'defaults' ? t('common.creating') : t('adminEvents.createDefaults')}
          </button>
        </div>
        <div className="muted mt-2">{t('adminEvents.defaultsYearNote')}</div>
      </div>

      <div className="card">
        <div className="flex justify-between items-center">
          <h2>{t('adminEvents.allEvents')}</h2>
          <span className="badge badge-info">{filtered.length}</span>
        </div>

        <div className="toolbar mt-2">
          <div className="form-group">
            <label>{t('common.search')}</label>
            <input
              type="text"
              value={search}
              placeholder={t('adminEvents.searchPlaceholder')}
              onChange={e => setSearch(e.target.value)}
            />
          </div>
          <div className="form-group">
            <label>{t('print.category')}</label>
            <select
              value={filterCategory}
              onChange={e => setFilterCategory(e.target.value as EventCategory | '')}
            >
              <option value="">{t('common.all')}</option>
              <option value="TRACK">{label('category', 'TRACK')}</option>
              <option value="FIELD">{label('category', 'FIELD')}</option>
            </select>
          </div>
          <div className="form-group">
            <label>{t('common.sex')}</label>
            <select value={filterSex} onChange={e => setFilterSex(e.target.value as SexCode | '')}>
              <option value="">{t('common.all')}</option>
              <option value="M">{label('sex', 'MALE')}</option>
              <option value="F">{label('sex', 'FEMALE')}</option>
            </select>
          </div>
          <div className="form-group">
            <label>{t('events.schoolYear')}</label>
            <select
              value={seasonId}
              onChange={e => setSeasonId(e.target.value ? Number(e.target.value) : '')}
            >
              <option value="">{t('events.allYears')}</option>
              {seasons.map(season => (
                <option key={season.id} value={season.id}>
                  {yearOptionLabel(season)}
                </option>
              ))}
            </select>
          </div>
          <div className="form-group">
            <label>{t('events.programmeDate')}</label>
            <select value={filterDate} onChange={e => setFilterDate(e.target.value)}>
              <option value="">{t('events.allDates')}</option>
              {dates.map(entry => (
                <option key={entry.date} value={entry.date}>
                  {dateOptionLabel(entry)}
                </option>
              ))}
            </select>
          </div>
        </div>

        <p className="muted mt-2">
          {selectedSeason
            ? t('adminEvents.viewingYear', {
                year: selectedSeason.year,
                name: selectedSeason.displayName || selectedSeason.name,
              })
            : t('adminEvents.viewingAllYears')}
        </p>

        <p className="muted mt-2">
          {filterDate
            ? t('events.dateSummary', {
                count: filtered.length,
                date: formatDate(filterDate),
              })
            : t('events.allDatesSummary', { count: filtered.length })}
        </p>

        {loading ? (
          <p className="muted mt-2">{t('events.loadingEvents')}</p>
        ) : filtered.length === 0 ? (
          <p className="muted mt-2">{t('adminEvents.noMatch')}</p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t('events.type')}</th>
                  <th>{t('adminEvents.year')}</th>
                  <th>{t('print.category')}</th>
                  <th>{t('common.sex')}</th>
                  <th>{t('events.date')}</th>
                  <th>{t('events.groupSize')}</th>
                  <th>{t('events.sheet')}</th>
                  <th>{t('events.entries')}</th>
                  <th>{t('admin.colStatus')}</th>
                  <th>{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map(event => (
                  <tr key={event.id} className={event.enabled ? undefined : 'row-disabled'}>
                    <td>
                      <strong>{event.typeLabel}</strong>
                      <div className="muted">{event.name}</div>
                    </td>
                    <td>
                      {/* The current year is green; anything else says so in
                          words as well as colour, so a past year can never be
                          mistaken for this one. */}
                      <span
                        className={
                          event.seasonId && currentSeason && event.seasonId === currentSeason.id
                            ? 'badge badge-success'
                            : 'badge badge-warning'
                        }
                      >
                        {event.seasonYear ?? selectedSeason?.year ?? '-'}
                      </span>
                      {event.seasonId && currentSeason && event.seasonId !== currentSeason.id && (
                        <div className="muted">{t('adminEvents.pastYear')}</div>
                      )}
                      {event.seasonName && <div className="muted">{event.seasonName}</div>}
                    </td>
                    <td>
                      <span
                        className={
                          event.category === 'TRACK' ? 'badge badge-danger' : 'badge badge-info'
                        }
                      >
                        {label('category', event.category)}
                      </span>
                    </td>
                    <td>{label('sex', event.sex)}</td>
                    <td>{formatDate(event.eventDate)}</td>
                    <td>{event.groupSize}</td>
                    <td>{label('sheet', event.sheetSize)}</td>
                    <td>
                      {event.enrolledCount} / {event.maxParticipants}
                      {event.groupCount > 0 && (
                        <div className="muted">
                          {t('adminEvents.heatsCount', { count: event.groupCount })}
                          {event.ungroupedCount > 0 &&
                            ` · ${t('adminEvents.ungrouped')} ${event.ungroupedCount}`}
                        </div>
                      )}
                    </td>
                    <td>
                      <span
                        className={event.enabled ? 'badge badge-success' : 'badge badge-danger'}
                      >
                        {event.enabled ? t('adminEvents.enabled') : t('adminEvents.disabled')}
                      </span>
                    </td>
                    <td>
                      <div className="pill-actions">
                        <button
                          type="button"
                          className={`btn btn-sm ${event.enabled ? 'btn-danger' : 'btn-success'}`}
                          disabled={busyId === event.id}
                          onClick={() => handleToggle(event)}
                        >
                          {event.enabled ? t('adminEvents.disable') : t('adminEvents.enable')}
                        </button>
                        <Link
                          href={`/admin/events/${event.id}/groups`}
                          className="btn btn-sm btn-secondary"
                        >
                          {t('groups.title')}
                        </Link>
                        <Link
                          href={`/admin/events/${event.id}/edit`}
                          className="btn btn-sm btn-secondary"
                        >
                          {t('common.edit')}
                        </Link>
                        {isAdmin && (
                          <button
                            type="button"
                            className="btn btn-sm btn-danger"
                            disabled={busyId === event.id}
                            onClick={() => handleDelete(event)}
                          >
                            {t('adminEvents.delete')}
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
