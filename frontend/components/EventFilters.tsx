'use client';

import { EventCategory, EventDTO, Grade, GRADES, SexCode } from '@/lib/api';
import { useI18n } from '@/lib/i18n';

/**
 * The four filters the marking grid and the print run share: **sex, grade,
 * category and event**. Nothing else — a school year, a date or an "only
 * enabled" switch is deliberately not one of them, because those two screens
 * are worked from the programme in front of the helper rather than from a
 * calendar.
 *
 * Sex, grade, category and event are all properties of an event, and an event
 * belongs to exactly one grade, so every one of them narrows the *event list*
 * rather than any one sheet. The caller does the narrowing (it already has the
 * event DTOs) and hands the result in as `events`; this control only offers the
 * four choices and renders the picker.
 *
 * The event control is either a **picker** — one event is chosen and its sheet
 * opens — or a **filter**, where `''` means every event and the caller renders
 * the matching set itself. `value.event` tells the two apart.
 */
export interface EventFilterValue {
  /** Short division code, `M` or `F`; `''` is every division. */
  sex: SexCode | '';
  /** `''` is every grade. */
  grade: Grade | '';
  /** `''` is both halves of the programme. */
  category: EventCategory | '';
  /**
   * `''` is every event. A non-empty value is the id of the chosen event when
   * `events.selectable`, and the chosen event *type* when it is not.
   */
  event: string;
}

export type EventFilterPatch = Partial<EventFilterValue>;

/** Which of the four event controls produced a change. */
export type EventFilterControl = 'sex' | 'grade' | 'category' | 'event';

interface Props {
  /**
   * A change to one of the four controls, with the `<select>` it came from so
   * the caller can put the displayed value back by hand when it declines the
   * change — which is what a cancelled unsaved-changes warning needs.
   */
  onChange: (
    control: EventFilterControl,
    value: string,
    select: HTMLSelectElement
  ) => void;
  value: EventFilterValue;
  /**
   * The events left after the caller's own narrowing. The event control is
   * built from these, so it can never offer something the other filters have
   * already excluded.
   */
  events: EventDTO[];
  /**
   * **Picker mode.** One event is chosen from `events` and its sheet opens;
   * without it the event control is a plain filter over the whole list.
   */
  selectable?: {
    /** The chosen event id, or `0` for "none chosen yet". */
    eventId: number;
    /** `true` when the event has fewer athletes than the caller needs. */
    isDisabled?: (event: EventDTO) => boolean;
    /** The "nothing chosen" option, e.g. `Choose an event`. */
    noneLabel: string;
    /** Groups the options under a heading each, e.g. `Track · Boys 徑項`. */
    groups?: Array<{ key: string; label: string; events: EventDTO[] }>;
    /** A note under the picker, e.g. how many thin events were left out. */
    note?: React.ReactNode;
  };
  /** Rendered after the event control, inside the same toolbar. */
  children?: React.ReactNode;
  /** The `id` prefix, so two of these on one screen keep unique labels. */
  idPrefix: string;
}

const DIVISIONS: Array<{ code: SexCode; sex: 'MALE' | 'FEMALE' }> = [
  { code: 'M', sex: 'MALE' },
  { code: 'F', sex: 'FEMALE' },
];

const CATEGORIES: EventCategory[] = ['TRACK', 'FIELD'];

export function EventFilters({
  value,
  onChange,
  events,
  selectable,
  children,
  idPrefix,
}: Props) {
  const { t, label } = useI18n();

  /**
   * The event types actually on offer, so the picker never lists a type the
   * current filters cannot produce. Ordered by their own label, which is what
   * the rest of the app orders an event list by.
   */
  const typeOptions = Array.from(new Set(events.map(event => event.type)))
    .map(type => ({ type, label: events.find(event => event.type === type)?.typeLabel ?? type }))
    .sort((a, b) => a.label.localeCompare(b.label));

  const groups = selectable?.groups ?? [{ key: 'all', label: '', events }];

  /* Every field is one control, so each change is handed straight back: the
     caller decides which of them disturbs a sheet that is already open. */
  const divisionSelect = (
    <div className="field">
      <label htmlFor={`${idPrefix}-sex`}>{t('eventFilters.sex')}</label>
      <select
        id={`${idPrefix}-sex`}
        value={value.sex}
        onChange={e => onChange('sex', e.target.value, e.target)}
      >
        <option value="">{t('eventFilters.allSexes')}</option>
        {DIVISIONS.map(division => (
          <option key={division.code} value={division.code}>
            {label('sex', division.sex)}
          </option>
        ))}
      </select>
    </div>
  );

  const gradeSelect = (
    <div className="field">
      <label htmlFor={`${idPrefix}-grade`}>{t('eventFilters.grade')}</label>
      <select
        id={`${idPrefix}-grade`}
        value={value.grade}
        onChange={e => onChange('grade', e.target.value, e.target)}
      >
        <option value="">{t('eventFilters.allGrades')}</option>
        {GRADES.map(grade => (
          <option key={grade} value={grade}>
            {label('grade', grade)}
          </option>
        ))}
      </select>
    </div>
  );

  const categorySelect = (
    <div className="field">
      <label htmlFor={`${idPrefix}-category`}>{t('eventFilters.category')}</label>
      <select
        id={`${idPrefix}-category`}
        value={value.category}
        onChange={e => onChange('category', e.target.value, e.target)}
      >
        <option value="">{t('eventFilters.allCategories')}</option>
        {CATEGORIES.map(category => (
          <option key={category} value={category}>
            {label('category', category)}
          </option>
        ))}
      </select>
    </div>
  );

  const eventSelect = selectable ? (
    <div className="field">
      <label htmlFor={`${idPrefix}-event`}>{t('eventFilters.eventType')}</label>
      <select
        id={`${idPrefix}-event`}
        value={selectable.eventId}
        onChange={e => onChange('event', e.target.value, e.target)}
      >
        <option value={0}>{selectable.noneLabel}</option>
        {groups.map(group =>
          group.label ? (
            <optgroup key={group.key} label={group.label}>
              {group.events.map(event => (
                <option
                  key={event.id}
                  value={event.id}
                  disabled={selectable.isDisabled?.(event) ?? false}
                >
                  {event.name}
                </option>
              ))}
            </optgroup>
          ) : (
            group.events.map(event => (
              <option
                key={event.id}
                value={event.id}
                disabled={selectable.isDisabled?.(event) ?? false}
              >
                {event.name}
              </option>
            ))
          )
        )}
      </select>
      {selectable.note}
    </div>
  ) : (
    <div className="field">
      <label htmlFor={`${idPrefix}-type`}>{t('eventFilters.eventType')}</label>
      <select
        id={`${idPrefix}-type`}
        value={value.event}
        onChange={e => onChange('event', e.target.value, e.target)}
      >
        <option value="">{t('eventFilters.allEventTypes')}</option>
        {typeOptions.map(option => (
          <option key={option.type} value={option.type}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );

  return (
    <>
      {divisionSelect}
      {gradeSelect}
      {categorySelect}
      {eventSelect}
      {children}
    </>
  );
}
