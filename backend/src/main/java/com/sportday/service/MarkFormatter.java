package com.sportday.service;

import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;

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
 *   135.5    an 800M        2.15.500s    2分15.500秒
 *   18.12    a shot put     18.12M       18.12米
 * </pre>
 *
 * <p>A time under a minute is just the seconds. Over a minute it gains a minutes
 * part, with the seconds padded to two digits so the columns line up — {@code 1.04}
 * rather than {@code 1.4}, which would read as a tenth of a second. The separator is
 * a full stop throughout, matching how the school writes times on paper.</p>
 *
 * <p>The number is trimmed of trailing zeros, so a mark entered as {@code 18.120}
 * reads back as {@code 18.12} and one entered as {@code 58} as {@code 58}.</p>
 */
public final class MarkFormatter {

    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    private MarkFormatter() {
    }

    /** True when this mark is a time rather than a distance or a height. */
    public static boolean isTime(Event.EventType type, String unit) {
        if (type != null) {
            return type.getCategory() == EventCategory.TRACK;
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
        if (!isTime(type, unit) || value.compareTo(SIXTY) < 0) {
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
