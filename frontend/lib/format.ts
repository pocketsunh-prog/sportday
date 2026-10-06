/** Display helpers shared by the SportDay screens. */

import type { Lang } from './i18n';

/**
 * The `label` helper from `useI18n()`, narrowed to the one prefix this module
 * needs. The full helper is assignable to it, so call sites pass `label`
 * straight through.
 */
export type UnitLabel = (prefix: 'unit', value: string | null | undefined) => string;

/** A mark split for display: the mark itself, then the unit that follows it. */
export interface ResultMark {
  /** The mark as the sport writes it: `14.123`, `1.04.123`, `18.12`. */
  value: string;
  /** The unit that follows it, localised: `s` / `秒`, `M` / `米`. */
  suffix: string;
}

/**
 * Splits a result into its mark and its unit.
 *
 * The API sends `displayMark` already formatted with its unit — `14.123s` on a
 * sprint, `1.04.123s` on a race timed on a stopwatch (the 400M and over, and both
 * relays), `0.48.123s` when such a race was under a minute, `18.12M` in the
 * field. The full stops and the filled-in fields are the notation the school
 * itself writes, so the mark is never re-derived from `mark` / `unit` and never
 * reformatted.
 *
 * The unit, though, is a word the reader wants in their own language: `s` and
 * `M` are language-neutral on the sheet but the school asked for 秒 and 米 in
 * Chinese. So the trailing unit is taken off `displayMark` and replaced with
 * `label('unit', …)`; in English it is kept exactly as the API wrote it.
 *
 * A result with no `displayMark` (an older server, a mark entered by hand) falls
 * back to the raw `mark` and the localised `unit`, which is how every mark was
 * rendered before the API formatted them.
 *
 * Callers render the two parts separately — `<strong>{value}</strong>{suffix}` —
 * so the mark stays emphasised and the unit does not, and the two sit together
 * with no space, exactly as `displayMark` writes them.
 */
export function resultMark(
  displayMark: string | null | undefined,
  mark: number | string | null | undefined,
  unit: string | null | undefined,
  lang: Lang,
  label: UnitLabel,
): ResultMark {
  const display = (displayMark ?? '').trim();
  if (display) {
    // A mark always ends in its unit: `s` on the track, `M` in the field.
    const parts = /^(.*[0-9.])([A-Za-z]+)$/.exec(display);
    if (!parts) return { value: display, suffix: '' };
    return {
      value: parts[1],
      suffix: lang === 'zh' ? label('unit', parts[2]) : parts[2],
    };
  }
  return {
    value: mark === null || mark === undefined ? '-' : String(mark),
    suffix: unit ? label('unit', unit) : '',
  };
}

/**
 * Formats an ISO date (`2026-10-01` or a full timestamp) as `YYYY-MM-DD`.
 *
 * Deliberately does not go through `new Date(...).toLocaleDateString()`: the
 * backend sends plain calendar dates, which `Date` would read as UTC midnight
 * and could render as the previous day in a negative-offset timezone.
 */
export function formatDate(value?: string | null): string {
  if (!value) return '-';
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
  if (!match) return value;
  return `${match[1]}-${match[2]}-${match[3]}`;
}

/** Formats a timestamp as `YYYY-MM-DD HH:mm`, e.g. the `enrolledAt` field. */
export function formatDateTime(value?: string | null): string {
  if (!value) return '-';
  const match = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/.exec(value);
  if (!match) return formatDate(value);
  return `${match[1]}-${match[2]}-${match[3]} ${match[4]}:${match[5]}`;
}

/** Renders a `{ A: 12, B: 30 }` count map as `A: 12 · B: 30`. */
export function formatCounts(counts?: Record<string, number> | null): string {
  if (!counts) return '-';
  const entries = Object.entries(counts);
  if (entries.length === 0) return '-';
  return entries.map(([key, value]) => `${key}: ${value}`).join(' · ');
}
