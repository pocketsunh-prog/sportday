/** Display helpers shared by the SportDay screens. */

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
