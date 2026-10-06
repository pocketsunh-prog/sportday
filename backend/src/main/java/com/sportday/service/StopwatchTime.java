package com.sportday.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A long race's time, written the way the school writes it on paper:
 * <strong>minutes . seconds . milliseconds</strong>.
 *
 * <pre>
 *   1.04.123   1 minute 4.123 seconds   (64.123 s, stored)
 *   0.48.123   under a minute           (48.123 s, stored)
 *   2.15.500   2 minutes 15.5 seconds   (135.5 s, stored)
 *   16.40.000  a 5000M                  (1000 s, stored)
 * </pre>
 *
 * <p><strong>This is the one home of the shape.</strong> A mark is still
 * <em>stored</em> as a plain number of seconds — that is what the placings, the
 * records and the championships compare — and this class is the only place that
 * turns it into the three fields a person reads, or reads those fields back.
 * {@link MarkFormatter} shows it, the mark-entry grid is drawn from it, and the
 * grid's save is validated by the same {@link #parse(String)}: a box the grid
 * filled by {@link #format(BigDecimal)} always parses back to the very mark it
 * came from, and nothing anywhere else may reinterpret a typed time.</p>
 *
 * <h2>Which events are timed this way</h2>
 * <p>Not this class's business: {@link com.sportday.entity.Event.EventType#usesMinutesAndSeconds()}
 * is the one rule — the 400M and over (including the 400M hurdles) and the two
 * relays. This class only knows the shape.</p>
 *
 * <h2>What a helper may type</h2>
 * <p>Forgiving where forgiveness is unambiguous, and refusing the rest with a
 * reason rather than storing a wrong time:</p>
 *
 * <ul>
 *   <li>{@code 1.04.123} — minutes, seconds, milliseconds;</li>
 *   <li>{@code 1.4.123} — the same time: the seconds field is 1 or 2 digits, so
 *       {@code 4} is four seconds, never four tenths;</li>
 *   <li>{@code 0.48.123} — a leading zero minute, as the school writes it;</li>
 *   <li>{@code 48.123} — <strong>no minute part: seconds and milliseconds</strong>.
 *       Two fields are always a time under a minute, exactly like the two-box
 *       entry it replaces, so {@code 135.5} is 135.5 seconds (2.15.500) and not
 *       1 minute 35.5 seconds;</li>
 *   <li>{@code 48} — whole seconds;</li>
 *   <li>{@code 2:15.5} — a colon between the minutes and the rest, the way a
 *       stopwatch and the sheet both write it. After a colon the field really is
 *       seconds, so it must be under 60;</li>
 *   <li>a comma for a full stop, because a lone comma is a decimal point in much
 *       of the world: {@code 1,04,123};</li>
 *   <li>trailing zeros left off the milliseconds, which is how people write a
 *       fraction: {@code 1.04.5} is 1 minute 4<em>.5</em> seconds (64.500 s), and
 *       {@code 0.48.05} is 48.05 s. Five thousandths is written {@code 005}.</li>
 * </ul>
 *
 * <p>Refused, each with its own message: a seconds field of 60 or more where a
 * minute part is already there — {@code 1.75.000} and {@code 1:75} are how somebody
 * mistypes 2:15, so they are never added up to 135 behind their back — more than
 * three millisecond digits, four fields, a negative or signed value, letters, and
 * anything else that is not one of the shapes above.</p>
 */
public final class StopwatchTime {

    /**
     * The shape a helper is told to write, for the grid's column heading and for
     * the server's refusals.
     */
    public static final String SHAPE = "M.SS.mmm";

    /** A time written in the shape, used in the messages. */
    public static final String EXAMPLE = "1.04.123";

    /** The seconds a long race's time is written in, and the milliseconds' scale. */
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    private static final int MILLIS_SCALE = 3;

    private static final java.util.regex.Pattern WHOLE_SECONDS =
            java.util.regex.Pattern.compile("\\d+");

    private StopwatchTime() {
    }

    // ------------------------------------------------------------------ show

    /**
     * A stored mark of seconds as the school writes it: {@code 1.04.123},
     * {@code 0.48.123}, {@code 2.15.500}, {@code 16.40.000}. Null in, null out.
     *
     * <p>Every field is always written, so the shape is one shape and the columns
     * line up: the minutes take a leading zero when the race was under a minute,
     * the seconds are two digits, and the milliseconds are three. A value with
     * more than three decimals is rounded to the thousandth — the mark itself is
     * stored to three decimals, so nothing real is ever lost.</p>
     *
     * <p>A negative value — not a performance, and not something the school's
     * shape can express — is shown as it is stored rather than given fields.</p>
     */
    public static String format(BigDecimal seconds) {
        if (seconds == null) {
            return null;
        }
        BigDecimal value = seconds.stripTrailingZeros();
        if (value.signum() < 0) {
            return value.toPlainString();
        }
        // Rounded before it is split, so 59.9999 becomes 1.00.000 and never 0.60.000.
        BigDecimal scaled = value.setScale(MILLIS_SCALE, RoundingMode.HALF_UP);
        BigDecimal[] split = scaled.divideAndRemainder(SIXTY);
        // divideAndRemainder keeps the dividend's scale, so 64.123 / 60 is 1.000
        // rather than 1 — strip it back to whole minutes.
        long minutes = split[0].setScale(0, RoundingMode.DOWN).longValueExact();
        BigDecimal wholeSeconds = split[1].setScale(0, RoundingMode.DOWN);
        int millis = split[1].subtract(wholeSeconds)
                .movePointRight(MILLIS_SCALE)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
        return minutes + "." + twoDigits(wholeSeconds.intValueExact()) + "." + threeDigits(millis);
    }

    // ------------------------------------------------------------------ read

    /**
     * A time a helper typed, as the total number of seconds it means.
     *
     * <p>The accepted shapes are in the class comment. Anything else is refused
     * with an {@link IllegalArgumentException} whose message says what was wrong
     * and what to write instead — the caller turns it into the row's own error, so
     * a mistyped time can never be stored as a number that merely looks plausible.
     * Blank is refused too: a caller that means "clear this mark" says so itself,
     * before it comes here.</p>
     *
     * @throws IllegalArgumentException when the text is not one of the shapes
     */
    public static BigDecimal parse(String text) {
        String typed = text == null ? "" : text.trim();
        if (typed.isEmpty()) {
            throw new IllegalArgumentException(
                    "No time was given — write " + SHAPE + ", e.g. " + EXAMPLE + ".");
        }
        // A lone comma is a decimal point in much of the world; the shape's own
        // separator is a full stop, so both are accepted and mean the same thing.
        String value = typed.replace(',', '.');
        if (value.startsWith("-") || value.startsWith("+")) {
            throw notATime(typed);
        }
        if (value.indexOf(':') >= 0) {
            return parseWithColon(typed, value);
        }

        String[] parts = value.split("\\.", -1);
        if (parts.length == 1) {
            return wholeSeconds(typed, parts[0]);
        }
        if (parts.length == 2) {
            return secondsAndMillis(typed, parts[0], parts[1]);
        }
        if (parts.length == 3) {
            return minutesSecondsMillis(typed, parts[0], parts[1], parts[2]);
        }
        // Four fields or more — 1.2.3.4 — is not a time of any shape.
        throw notATime(typed);
    }

    /**
     * {@code 2:15.5} — the minutes, then the rest of the time.
     *
     * <p>A colon says the field after it is <em>seconds</em>, so it has to be under a
     * whole minute for the same reason the third field of {@code 1.75.000} does:
     * {@code 1:75} is how somebody mistypes 2:15, and reading it as 135 seconds would
     * store the wrong time without saying so.</p>
     */
    private static BigDecimal parseWithColon(String typed, String value) {
        if (value.indexOf(':') != value.lastIndexOf(':')) {
            throw notATime(typed);
        }
        int at = value.indexOf(':');
        String minutes = value.substring(0, at);
        String rest = value.substring(at + 1);
        if (!WHOLE_SECONDS.matcher(minutes).matches() || rest.isEmpty()) {
            throw notATime(typed);
        }
        int dot = rest.indexOf('.');
        String seconds = dot < 0 ? rest : rest.substring(0, dot);
        String millis = dot < 0 ? null : rest.substring(dot + 1);
        if (!WHOLE_SECONDS.matcher(seconds).matches()
                || (millis != null && !isMillis(millis))) {
            throw notATime(typed);
        }
        BigDecimal wholeSeconds = new BigDecimal(seconds);
        if (wholeSeconds.compareTo(SIXTY) >= 0) {
            throw secondsUnderSixty(typed);
        }
        BigDecimal fraction = millis == null ? BigDecimal.ZERO : millisValue(millis);
        return new BigDecimal(minutes).multiply(SIXTY)
                .add(wholeSeconds)
                .add(fraction)
                .setScale(MILLIS_SCALE);
    }

    /** {@code 48} — whole seconds, and nothing else. */
    private static BigDecimal wholeSeconds(String typed, String seconds) {
        if (!WHOLE_SECONDS.matcher(seconds).matches()) {
            throw notATime(typed);
        }
        return new BigDecimal(seconds).setScale(MILLIS_SCALE);
    }

    /** {@code 48.123} — seconds and milliseconds, with no minute part. */
    private static BigDecimal secondsAndMillis(String typed, String seconds, String millis) {
        if (!WHOLE_SECONDS.matcher(seconds).matches() || !isMillis(millis)) {
            throw notATime(typed);
        }
        return new BigDecimal(seconds).add(millisValue(millis)).setScale(MILLIS_SCALE);
    }

    /** {@code 1.04.123} — minutes, seconds and milliseconds. */
    private static BigDecimal minutesSecondsMillis(String typed, String minutes,
                                                   String seconds, String millis) {
        if (!WHOLE_SECONDS.matcher(minutes).matches() || !isMillis(millis)) {
            throw notATime(typed);
        }
        if (!seconds.matches("\\d{1,2}")) {
            // Three digits of seconds — 1.004.123 — is not a field of this shape.
            throw notATime(typed);
        }
        BigDecimal wholeSeconds = new BigDecimal(seconds);
        if (wholeSeconds.compareTo(SIXTY) >= 0) {
            throw secondsUnderSixty(typed);
        }
        return new BigDecimal(minutes).multiply(SIXTY)
                .add(wholeSeconds)
                .add(millisValue(millis))
                .setScale(MILLIS_SCALE);
    }

    /** True when the milliseconds field is one to three digits. */
    private static boolean isMillis(String millis) {
        return millis.matches("\\d{1,3}");
    }

    /**
     * The milliseconds field as a fraction of a second: {@code 123} is 0.123 s and
     * {@code 5} is 0.500 s — the digits are a decimal fraction of a second with the
     * trailing zeros left off, which is what someone who types {@code 1.04.5} means
     * by it. Five thousandths of a second is written {@code 005}.
     */
    private static BigDecimal millisValue(String millis) {
        StringBuilder digits = new StringBuilder(millis);
        while (digits.length() < MILLIS_SCALE) {
            digits.append('0');
        }
        return new BigDecimal(digits.toString()).movePointLeft(MILLIS_SCALE);
    }

    private static IllegalArgumentException notATime(String typed) {
        return new IllegalArgumentException("\"" + typed + "\" is not a time — write " + SHAPE
                + ", e.g. " + EXAMPLE + ", 0.48.123 or 48.123.");
    }

    /**
     * The refusal for a seconds field of 60 or more where a minute part is already
     * there: 1 minute 75 seconds is how somebody mistypes 2:15, so it is never added
     * up to 135 seconds behind their back.
     */
    private static IllegalArgumentException secondsUnderSixty(String typed) {
        return new IllegalArgumentException("\"" + typed + "\": the seconds part of a time must "
                + "be under 60 — write 2 minutes 15 seconds as 2.15.000, not as 1.75.000.");
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private static String threeDigits(int value) {
        StringBuilder digits = new StringBuilder(String.valueOf(value));
        while (digits.length() < MILLIS_SCALE) {
            digits.insert(0, '0');
        }
        return digits.toString();
    }
}
