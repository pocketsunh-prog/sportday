/**
 * A long race's time, written the way the school writes it:
 * **minutes . seconds . milliseconds**.
 *
 *   1.04.123   1 minute 4.123 seconds   (64.123 s, stored)
 *   0.48.123   under a minute           (48.123 s, stored)
 *   2.15.500   2 minutes 15.5 seconds   (135.5 s, stored)
 *
 * This is the **one home of the shape on the client**, and the twin of the
 * server's `StopwatchTime`: the grid calls `parseStopwatchTime` to check what a
 * helper typed and `formatStopwatchTime` to write a stored mark back into the box,
 * so what the box shows is exactly what the server parses back. The server parses
 * the typed text itself and refuses a malformed one, so this is a second pair of
 * eyes rather than the only check.
 *
 * Which events are timed this way is not decided here:
 * `EventDTO.timeInMinutes` comes from the server's own rule (the 400M and over,
 * and both relays).
 */

/** The shape a helper is told to write, for the column heading and the messages. */
export const STOPWATCH_SHAPE = 'M.SS.mmm';

/** A time written in the shape, used in the messages. */
export const STOPWATCH_EXAMPLE = '1.04.123';

/**
 * Why a typed time was refused, so the page can say it in the user's own language
 * rather than carrying English in the parser:
 *  - `shape`   — not a time of any accepted shape;
 *  - `seconds` — a seconds field of 60 or more after a minute part (`1.75.000`),
 *                which is how somebody mistypes 2:15.
 */
export type StopwatchProblem = 'shape' | 'seconds';

export type StopwatchParse =
  | { ok: true; milliseconds: number }
  | { ok: false; problem: StopwatchProblem };

const MILLIS_PER_SECOND = 1000;
const MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND;

/** True for a string of digits only. */
function isDigits(value: string): boolean {
  return /^\d+$/.test(value);
}

/** The milliseconds field as a fraction of a second: `5` is 0.500 s, `005` is 0.005 s. */
function millisOf(field: string): number {
  return Number(field.padEnd(3, '0'));
}

/**
 * Reads a time a helper typed. The accepted shapes are the server's own:
 * `1.04.123`, `1.4.123`, `0.48.123`, `48.123` (no minute part, so **seconds and
 * milliseconds**), `48` (whole seconds), a `:` between the minutes and the rest
 * (`2:15.5`), a comma for a full stop, and trailing zeros left off the
 * milliseconds (`1.04.5` is 64.5 s).
 */
export function parseStopwatchTime(typed: string): StopwatchParse {
  const bad = (problem: StopwatchProblem): StopwatchParse => ({ ok: false, problem });
  const raw = (typed ?? '').trim();
  if (raw === '') return bad('shape');
  // A lone comma is a decimal point in much of the world; the shape's own
  // separator is a full stop, so both are accepted and mean the same thing.
  const value = raw.replace(/,/g, '.');
  if (value.startsWith('-') || value.startsWith('+')) return bad('shape');

  if (value.includes(':')) {
    // A colon says the field after it really is seconds, so it must be under 60:
    // 1:75 is how somebody mistypes 2:15, and adding it up to 135 would store a
    // time nobody wrote.
    if (value.indexOf(':') !== value.lastIndexOf(':')) return bad('shape');
    const at = value.indexOf(':');
    const minutes = value.slice(0, at);
    const rest = value.slice(at + 1);
    if (!isDigits(minutes) || rest === '') return bad('shape');
    const dot = rest.indexOf('.');
    const seconds = dot < 0 ? rest : rest.slice(0, dot);
    const millis = dot < 0 ? null : rest.slice(dot + 1);
    if (!isDigits(seconds) || (millis !== null && !/^\d{1,3}$/.test(millis))) {
      return bad('shape');
    }
    if (Number(seconds) >= 60) return bad('seconds');
    return {
      ok: true,
      milliseconds:
        Number(minutes) * MILLIS_PER_MINUTE +
        Number(seconds) * MILLIS_PER_SECOND +
        (millis === null ? 0 : millisOf(millis)),
    };
  }

  const parts = value.split('.');
  if (parts.length === 1) {
    if (!isDigits(parts[0])) return bad('shape');
    return { ok: true, milliseconds: Number(parts[0]) * MILLIS_PER_SECOND };
  }
  if (parts.length === 2) {
    // No minute part at all: seconds and milliseconds, so 135.5 is 135.5 seconds.
    if (!isDigits(parts[0]) || !/^\d{1,3}$/.test(parts[1])) return bad('shape');
    return {
      ok: true,
      milliseconds: Number(parts[0]) * MILLIS_PER_SECOND + millisOf(parts[1]),
    };
  }
  if (parts.length === 3) {
    if (!/^\d{1,2}$/.test(parts[1]) || !/^\d{1,3}$/.test(parts[2])) return bad('shape');
    if (!isDigits(parts[0])) return bad('shape');
    if (Number(parts[1]) >= 60) return bad('seconds');
    return {
      ok: true,
      milliseconds:
        Number(parts[0]) * MILLIS_PER_MINUTE +
        Number(parts[1]) * MILLIS_PER_SECOND +
        millisOf(parts[2]),
    };
  }
  return bad('shape');
}

/** Two digits of seconds, three of milliseconds, and a leading zero minute. */
function formatMillis(total: number): string {
  const minutes = Math.floor(total / MILLIS_PER_MINUTE);
  const rest = total - minutes * MILLIS_PER_MINUTE;
  const wholeSeconds = Math.floor(rest / MILLIS_PER_SECOND);
  const millis = rest - wholeSeconds * MILLIS_PER_SECOND;
  return `${minutes}.${String(wholeSeconds).padStart(2, '0')}.${String(millis).padStart(3, '0')}`;
}

/** A stored mark of seconds, as the box shows it: `0.48.123`, `1.04.123`. */
export function formatStopwatchTime(seconds: number): string {
  return formatMillis(Math.round(seconds * MILLIS_PER_SECOND));
}

/**
 * What the box should hold for a typed time: the one shape, whatever a helper
 * typed — `1.4.123` becomes `1.04.123`. Null when it is not a time at all, which
 * is how the grid tells "the same mark, written differently" from "something was
 * typed".
 */
export function canonicalStopwatchTime(typed: string): string | null {
  const parsed = parseStopwatchTime(typed);
  return parsed.ok ? formatMillis(parsed.milliseconds) : null;
}
