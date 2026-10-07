package com.sportday.service;

import com.sportday.entity.Event;
import com.sportday.entity.EventResult;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How a mark reads to a person.
 *
 * <p>A mark is stored as a plain number — seconds for a race, metres for a throw or
 * a jump — which is right for comparing and wrong for reading. This turns it into
 * the form the sport uses:</p>
 *
 * <pre>
 *   14.123   a 100M         14.123s      14.123秒
 *   64.123   a 400M         1.04.123s    1分04.123秒
 *   48.123   a 4x100M       0.48.123s    0分48.123秒
 *   135.5    an 800M        2.15.500s    2分15.500秒
 *   18.12    a shot put     18.12M       18.12米
 * </pre>
 *
 * <p><strong>A race timed on a stopwatch — the 400M and over, and both relays —
 * reads as {@code M.SS.mmm}</strong>, the shape the school writes: minutes,
 * seconds, milliseconds, all three fields always there. Which races those are is
 * {@link Event.EventType#usesMinutesAndSeconds()}, the one rule, and the shape
 * itself is {@link StopwatchTime}, the one home of it — this class only decides
 * <em>when</em> it applies. A time under a minute keeps its leading zero,
 * {@code 0.48.123}, so the column reads as one shape from top to bottom.</p>
 *
 * <p><strong>A sprint is unchanged.</strong> A 60M, a 100M, a 200M and the short
 * hurdles are timed in seconds alone — {@code 14.123} — and a field event reads in
 * metres. For a mark with no event type to go on, the old rule still holds: the
 * unit decides, and a time of a minute or more gains a minutes part with the
 * seconds padded to two digits so the columns line up — {@code 1.04} rather than
 * {@code 1.4}, which would read as a tenth of a second.</p>
 */
public final class MarkFormatter {

    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    private MarkFormatter() {
    }

    /** True when this mark is a time rather than a distance or a height. */
    public static boolean isTime(Event.EventType type, String unit) {
        if (type != null) {
            return !type.getCategory().isMeasuredInDistance();
        }
        // No type to go on — fall back to the unit the mark carries.
        return unit != null && switch (unit.trim().toLowerCase()) {
            case "s", "sec", "secs", "second", "seconds", "time" -> true;
            default -> unit.contains("秒");
        };
    }

    /**
     * The mark as it reads, with no unit: {@code 14.123}, {@code 1.04.123},
     * {@code 18.12}. Null in, null out.
     *
     * <p>A race timed on a stopwatch — 400M and over, and both relays — reads as
     * {@code M.SS.mmm} through {@link StopwatchTime}, whatever the size of the mark.
     * Everything else is unchanged: a short sprint is the seconds it was, trimmed of
     * trailing zeros, and a time of a minute or more with no event type to go on
     * keeps the old minutes-and-padded-seconds shape.</p>
     */
    public static String format(BigDecimal mark, Event.EventType type, String unit) {
        if (mark == null) {
            return null;
        }
        BigDecimal value = mark.stripTrailingZeros();
        // A negative or absurd value is not a performance; show it as stored rather
        // than inventing a minutes part for it.
        if (value.signum() < 0) {
            return value.toPlainString();
        }
        if (!isTime(type, unit)) {
            return value.toPlainString();
        }
        if (type != null && type.usesMinutesAndSeconds()) {
            // The one shape the school writes a long race in: 0.48.123, 1.04.123.
            return StopwatchTime.format(value);
        }
        if (value.compareTo(SIXTY) < 0) {
            return value.toPlainString();
        }
        BigDecimal[] split = value.divideAndRemainder(SIXTY);
        // divideAndRemainder keeps the dividend's scale, so 64.123 / 60 is 1.000
        // rather than 1 — strip it back to whole minutes.
        String minutes = split[0].setScale(0, RoundingMode.DOWN).toPlainString();
        return minutes + "." + secondsPart(split[1]);
    }

    /**
     * The mark with its unit: {@code 14.123s}, {@code 1.04.123s}, {@code 18.12M}.
     *
     * <p>The unit is normalised to the short form the sport uses, so a mark stored
     * with an older spelled-out unit still reads as {@code s} or {@code M}.</p>
     */
    public static String formatWithUnit(BigDecimal mark, Event.EventType type, String unit) {
        String formatted = format(mark, type, unit);
        if (formatted == null) {
            return null;
        }
        return formatted + shortUnit(type, unit);
    }

    /** {@code s} for a race, {@code M} for a distance or a height. */
    public static String shortUnit(Event.EventType type, String unit) {
        if (isTime(type, unit)) {
            return Event.EventType.UNIT_TRACK;
        }
        return Event.EventType.UNIT_FIELD;
    }

    /**
     * How an outcome reads in place of a mark: {@code ABS} or {@code DQ}.
     *
     * <p>An outcome only replaces a mark when there is no mark to show, which is
     * exactly {@link EventResult.Outcome#ABS} and {@link EventResult.Outcome#DQ} —
     * {@link EventResult.Outcome#RESULT} means a number was recorded, so there is
     * nothing to write and null comes back. A null outcome is null too.</p>
     */
    public static String formatOutcome(EventResult.Outcome outcome) {
        return outcome == null || !outcome.isNoMark() ? null : outcome.getLabel();
    }

    /**
     * The result as it reads: the outcome ({@code ABS} / {@code DQ}) when the
     * athlete produced no mark, otherwise the mark with its unit — {@code 14.123s},
     * {@code 18.12M}. Null in, null out.
     */
    public static String formatWithOutcome(EventResult.Outcome outcome, BigDecimal mark,
                                           Event.EventType type, String unit) {
        String label = formatOutcome(outcome);
        return label != null ? label : formatWithUnit(mark, type, unit);
    }

    /**
     * A stored result as a sheet shows it — the mark with its unit
     * ({@code 11.86s}, {@code 18.12M}), or {@code ABS} / {@code DQ} when the
     * athlete produced no number. Null only when there is no record at all.
     *
     * <p>Used where a <em>second</em> performance is shown beside the one being
     * written — a final sheet printing what each finalist ran in their heat — so
     * it falls back to the outcome's own name for the one case
     * {@link #formatWithOutcome} cannot read: a row that carries an outcome but no
     * number. Without that a reader would be shown nothing at all beside a record
     * that exists.</p>
     *
     * @param record       the stored result, or null when there is none
     * @param fallbackUnit the event's own unit, used when the record carries none
     */
    public static String formatRecord(EventResult record, Event.EventType type, String fallbackUnit) {
        if (record == null) {
            return null;
        }
        String unit = record.getUnit() == null || record.getUnit().isBlank()
                ? fallbackUnit
                : record.getUnit();
        String formatted = formatWithOutcome(record.getOutcomeOrDefault(), record.getMark(), type, unit);
        return formatted != null ? formatted : record.getOutcomeOrDefault().name();
    }

    /**
     * The seconds of a time longer than a minute, padded to two whole digits:
     * {@code 4.123} becomes {@code 04.123}, and a whole {@code 4} becomes {@code 04}.
     */
    private static String secondsPart(BigDecimal seconds) {
        BigDecimal whole = seconds.setScale(0, RoundingMode.DOWN);
        BigDecimal fraction = seconds.subtract(whole).stripTrailingZeros();

        String digits = whole.toPlainString();
        if (digits.length() < 2) {
            digits = "0" + digits;
        }
        if (fraction.signum() == 0) {
            // A whole number of seconds has no fraction to show: 1.04, not 1.04.000.
            return digits;
        }
        // "0.123" -> ".123"
        return digits + fraction.toPlainString().substring(1);
    }
}
