'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import {
  api,
  EventCategory,
  EventDTO,
  EventSex,
  EVENT_TYPE_OPTIONS,
  eventTypeCategory,
  eventTypeLabel,
  Grade,
  GRADES,
  gradesForEventType,
  isRelayEventType,
  mayHaveFinalForType,
  RELAY_TEAM_KIND_OPTIONS,
  RelayTeamKind,
  SheetSize,
  sheetDefaultsForType,
} from '@/lib/api';
import { useI18n } from '@/lib/i18n';
import Link from 'next/link';

export default function EditEventPage() {
  const params = useParams();
  const { t, label } = useI18n();
  const router = useRouter();
  const [form, setForm] = useState({
    name: '',
    description: '',
    type: '',
    category: 'TRACK' as EventCategory,
    sex: 'MALE' as EventSex,
    /** Seeded from the event; an event belongs to exactly one grade. */
    grade: 'A' as Grade,
    eventDate: '',
    location: '',
    maxParticipants: 50,
    groupSize: 24,
    shortSprint: false,
    sheetSize: 'A4' as SheetSize,
    maxEntriesPerStudent: 1,
    enabled: true,
    directToFinal: true,
    /**
     * The relay team kind, and whether reserves are allowed past the legs. `''`
     * is Undivided and is what clears a kind; both fields are only ever sent for
     * a relay, because the server refuses a relay team kind on anything else.
     */
    relayTeamKind: '' as RelayTeamKind | '',
    relayReservesAllowed: false,
  });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  /**
   * Whether the event already has heats, or a final, drawn. The format may
   * still be changed, but a programme that has already been run is at stake.
   */
  const [hasDrawings, setHasDrawings] = useState(false);
  /**
   * True when the *system* set `directToFinal` rather than the school — a short
   * sprint the field is too small for a final to improve on. The box stays
   * unticked-able: unticking it forces heats and a final and clears the flag.
   */
  const [directToFinalAutomatic, setDirectToFinalAutomatic] = useState(false);

  const eventId = Number(params.id);

  /**
   * The same rule the server applies: only the four short sprints may be split
   * into heats and a final. It follows the type picked on this form, so the box
   * locks itself the moment the type is changed to one that cannot have a final.
   */
  const mayHaveFinal = mayHaveFinalForType(form.type);

  /**
   * Whether the event on the form is a relay. The relay section is shown for the
   * two relays alone, and its fields are only sent for them.
   */
  const relayEvent = isRelayEventType(form.type);
  /**
   * The kind the event was loaded with. Changing it is refused by the server
   * while the event still has teams, so the note that says so is only shown once
   * the school has actually moved the choice.
   */
  const [loadedRelayKind, setLoadedRelayKind] = useState<RelayTeamKind | ''>('');
  const relayKindChanged = relayEvent && form.relayTeamKind !== loadedRelayKind;

  /**
   * The 5000M is A grade only, and the 1500M and 110M hurdles have no C grade,
   * so changing the type can leave the grade sitting on one the type does not
   * run. The server refuses that with a 400; the form refuses it too and names
   * the type.
   */
  const gradeMismatch = !gradesForEventType(form.type).includes(form.grade);
  const gradeMismatchText = t('adminEvents.gradeNotRun', {
    type: eventTypeLabel(form.type),
    grade: label('grade.short', form.grade),
  });

  useEffect(() => {
    api
      .getEvent(eventId)
      .then((event: EventDTO) => {
        setForm({
          name: event.name,
          description: event.description || '',
          type: event.type,
          category: event.category,
          sex: event.sex,
          grade: event.grade,
          eventDate: event.eventDate ? event.eventDate.slice(0, 10) : '',
          location: event.location || '',
          maxParticipants: event.maxParticipants,
          groupSize: event.groupSize,
          shortSprint: event.shortSprint,
          sheetSize: event.sheetSize,
          maxEntriesPerStudent: event.maxEntriesPerStudent,
          enabled: event.enabled,
          directToFinal: event.directToFinal !== false,
          relayTeamKind: event.relayTeamKind ?? '',
          relayReservesAllowed: event.relayReservesAllowed === true,
        });
        setLoadedRelayKind(event.relayTeamKind ?? '');
        // `groupCount` counts the heats and, where one was drawn, the final.
        setHasDrawings((event.groupCount || 0) > 0);
        // Absent, like every optional field this API omits when it has nothing
        // to say, means the school chose the format — or the event was always
        // direct — so nothing is said about an automatic choice.
        setDirectToFinalAutomatic(event.directToFinalAutomatic === true);
      })
      .catch(err => setError(err.message))
      .finally(() => setLoading(false));
  }, [eventId]);

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
      if (name === 'type') {
        next.category = eventTypeCategory(value);
        Object.assign(next, sheetDefaultsForType(value));
        // A type that cannot have a final is locked back to direct to a final.
        if (!mayHaveFinalForType(value)) next.directToFinal = true;
      }
      return next;
    });
    // Once the school moves the format box itself, the choice is the school's
    // and the note about the system's own choice no longer applies. Changing
    // the type is not that: the box follows the type on its own.
    if (name === 'directToFinal') setDirectToFinalAutomatic(false);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setNotice('');
    // The button is disabled on a mismatch, but Enter in any field still
    // submits the form, so the guard is repeated here.
    if (gradeMismatch) {
      setError(gradeMismatchText);
      return;
    }
    setSaving(true);
    try {
      // The relay team kind and the reserve switch belong to a relay alone; for
      // anything else they are left off the request, so the update cannot clear a
      // setting that does not apply to it.
      const { relayTeamKind, relayReservesAllowed, ...rest } = form;
      const payload: Partial<EventDTO> = relayEvent
        ? { ...rest, relayTeamKind, relayReservesAllowed }
        : { ...rest };
      await api.updateEvent(eventId, payload);
      router.push('/admin/events');
    } catch (err: any) {
      // Changing the kind under existing teams is refused with a 409 whose message
      // names the count and the way out — show it as it stands.
      setError(err.message || t('adminEvents.updateFailed'));
    } finally {
      setSaving(false);
    }
  };

  const handleToggleEnable = async () => {
    setError('');
    setNotice('');
    try {
      const current = await api.getEvent(eventId);
      const updated = await api.setEventEnabled(eventId, !current.enabled);
      setForm(prev => ({ ...prev, enabled: updated.enabled }));
      setNotice(
        t(updated.enabled ? 'adminEvents.enabledNotice' : 'adminEvents.disabledNotice', {
          name: form.name,
        })
      );
    } catch (err: any) {
      setError(err?.message || t('adminEvents.statusFailed'));
    }
  };

  const handleDelete = async () => {
    if (!confirm(t('adminEvents.deleteConfirm'))) return;
    setError('');
    try {
      await api.deleteEvent(eventId);
      router.push('/admin/events');
    } catch (err: any) {
      setError(err?.message || t('adminEvents.deleteFailed'));
    }
  };

  if (loading) return <div>{t('common.loading')}</div>;

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('adminEvents.editTitle')}</h1>
        <Link href={`/admin/events/${eventId}/groups`} className="btn btn-secondary">
          {t('groups.title')}
        </Link>
      </div>
      <div className="card" style={{ maxWidth: '700px' }}>
        {error && <div className="alert alert-error">{error}</div>}
        {notice && <div className="alert alert-success">{notice}</div>}
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
                <option value="RELAY">{label('category', 'RELAY')}</option>
              </select>
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label>{t('print.division')} *</label>
              <select name="sex" value={form.sex} onChange={handleChange}>
                <option value="MALE">{label('sex', 'MALE')}</option>
                <option value="FEMALE">{label('sex', 'FEMALE')}</option>
              </select>
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label>{t('marks.grade')} *</label>
              <select name="grade" value={form.grade} onChange={handleChange}>
                {GRADES.map(grade => (
                  <option key={grade} value={grade}>
                    {label('grade', grade)}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <p className="muted">{t('adminEvents.gradeHint')}</p>
          {gradeMismatch && <p className="muted">{gradeMismatchText}</p>}
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
          <div className="form-group">
            <label className="checkbox-line">
              <input
                name="directToFinal"
                type="checkbox"
                checked={form.directToFinal}
                disabled={!mayHaveFinal}
                onChange={handleChange}
              />
              {t('events.directToFinal')}
            </label>
            <p className="muted">{t('events.directToFinalHint')}</p>
            {!mayHaveFinal && <p className="muted">{t('events.directToFinalForced')}</p>}
            {/* The system decided this, not the school: the field is small
                enough that a final would be the same runners as the heat. The
                box above stays unticked-able, which forces heats and a final. */}
            {form.directToFinal && directToFinalAutomatic && (
              <p className="muted">
                <span className="badge badge-warning">
                  {t('events.directToFinalAutomatic')}
                </span>{' '}
                {t('events.directToFinalAutomaticHint')}
              </p>
            )}
          </div>
          {/* Unticking the box on an event whose heats or final are already
              drawn changes a programme that may already have been run. */}
          {!form.directToFinal && hasDrawings && (
            <p className="muted">
              <span className="badge badge-warning">{t('events.heatsAndFinal')}</span>{' '}
              {t('adminEvents.directToFinalWarning')}
            </p>
          )}
          {/* A relay may be divided into form or house teams, or left undivided.
              The server refuses a change of kind while teams exist, so the note
              saying so appears as soon as the choice is moved. */}
          {relayEvent && (
            <div className="form-group">
              <label>{t('relay.kind')}</label>
              <select
                value={form.relayTeamKind}
                onChange={e =>
                  setForm(prev => ({
                    ...prev,
                    relayTeamKind: e.target.value as RelayTeamKind | '',
                  }))
                }
              >
                {RELAY_TEAM_KIND_OPTIONS.map(option => (
                  <option key={option.value} value={option.value}>
                    {t(option.labelKey)}
                  </option>
                ))}
              </select>
              <p className="muted">{t('relay.kindHint')}</p>
              {relayKindChanged && (
                <p className="muted">
                  <span className="badge badge-warning">{t('events.format')}</span>{' '}
                  {t('relay.kindLockedHint')}
                </p>
              )}
              <label className="checkbox-line">
                <input
                  type="checkbox"
                  checked={form.relayReservesAllowed}
                  onChange={e =>
                    setForm(prev => ({ ...prev, relayReservesAllowed: e.target.checked }))
                  }
                />
                {t('relay.reservesAllowed')}
              </label>
              <p className="muted">{t('relay.reservesHint')}</p>
              <div className="pill-actions mt-2">
                <Link href={`/admin/events/${eventId}/relay`} className="btn btn-sm btn-secondary">
                  {t('relay.openBoard')}
                </Link>
              </div>
            </div>
          )}
          <div className="flex gap-2 mt-2">
            <button type="submit" className="btn btn-primary" disabled={saving || gradeMismatch}>
              {saving ? t('common.saving') : t('common.saveChanges')}
            </button>
            <button type="button" onClick={handleToggleEnable} className="btn btn-secondary">
              {t('adminEvents.toggleEnable')}
            </button>
            <button type="button" onClick={handleDelete} className="btn btn-danger">
              {t('adminEvents.delete')}
            </button>
          </div>
        </form>
      </div>
      <div className="mt-2">
        <Link href={`/events/${eventId}`} className="btn btn-secondary">
          {t('events.backToEvent')}
        </Link>
      </div>
    </div>
  );
}
