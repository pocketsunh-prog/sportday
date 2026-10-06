package com.sportday.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shape a long race is written in: <strong>M.SS.mmm</strong> — {@code 1.04.123},
 * {@code 0.48.123}.
 *
 * <p>Requirement: the mark-entry box takes one time in that shape, the result reads
 * in it, the parser accepts what a helper would actually type, and it refuses
 * nonsense with a reason instead of storing a wrong time.</p>
 *
 * <p>The thing that matters most is the round trip: what the box shows for a stored
 * mark must parse back to exactly that mark, at every boundary.</p>
 */
class StopwatchTimeTest {

    private static BigDecimal seconds(String text) {
        return StopwatchTime.parse(text);
    }

    private static void assertSeconds(String expected, String typed) {
        assertEquals(0, new BigDecimal(expected).compareTo(seconds(typed)),
                typed + " should mean " + expected + " seconds");
    }

    // -------------------------------------------------------- what is accepted

    @Test
    @DisplayName("minutes, seconds and milliseconds")
    void theFullShape() {
        assertSeconds("64.123", "1.04.123");
        assertSeconds("135.5", "2.15.500");
        assertSeconds("1000", "16.40.000");
        assertSeconds("0", "0.00.000");
    }

    @Test
    @DisplayName("one digit of seconds is seconds, not tenths")
    void aSingleDigitSecondsField() {
        // 1.4.123 is 1 minute 4.123 seconds: the field is seconds, so 4 is four of
        // them, and reading it as four tenths would be a wrong time stored silently.
        assertSeconds("64.123", "1.4.123");
        assertSeconds("94.5", "1.34.500");
    }

    @Test
    @DisplayName("under a minute keeps its leading zero")
    void aLeadingZeroMinute() {
        assertSeconds("48.123", "0.48.123");
        assertSeconds("9.999", "0.09.999");
    }

    @Test
    @DisplayName("no minute part at all: two fields are seconds and milliseconds")
    void twoFieldsAreSecondsAndMillis() {
        // The decision, stated: 48.123 has no minute part, so it is 48.123 seconds —
        // nothing else it could be. 135.5 is therefore 135.5 seconds, not 1:35.5.
        assertSeconds("48.123", "48.123");
        assertSeconds("135.5", "135.5");
        assertSeconds("59.9", "59.9");
    }

    @Test
    @DisplayName("a whole number of seconds, with or without a trailing part")
    void wholeSeconds() {
        assertSeconds("48", "48");
        assertSeconds("60", "60");
        assertSeconds("64", "1.04.000");
    }

    @Test
    @DisplayName("a short fraction is a fraction of a second, as a person means it")
    void shortFractions() {
        // Trailing zeros left off: 1.04.5 is 64.5 s, not 64.005 s.
        assertSeconds("64.5", "1.04.5");
        assertSeconds("48.05", "48.05");
        assertSeconds("48.005", "48.005");
        assertSeconds("64.5", "1:04.5");
    }

    @Test
    @DisplayName("a colon between the minutes and the rest, the way a stopwatch reads")
    void aColonIsAccepted() {
        assertSeconds("135", "2:15");
        assertSeconds("135.5", "2:15.5");
        assertSeconds("64.123", "1:04.123");
        assertSeconds("0.5", "0:00.5");
    }

    @Test
    @DisplayName("a comma is a full stop, as the rest of the grid already accepts")
    void aCommaIsADecimalPoint() {
        assertSeconds("64.123", "1,04,123");
        assertSeconds("48.123", "48,123");
    }

    @Test
    @DisplayName("spaces around a time are ignored")
    void spacesAreTrimmed() {
        assertSeconds("64.123", "  1.04.123  ");
    }

    // -------------------------------------------------------- what is refused

    @Test
    @DisplayName("seconds of 60 or more after a minute part, with the reason")
    void secondsOverFiftyNineAreRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> seconds("1.75.000"));
        assertTrue(error.getMessage().contains("1.75.000"), error.getMessage());
        assertTrue(error.getMessage().contains("under 60"), error.getMessage());

        assertThrows(IllegalArgumentException.class, () -> seconds("2.60.000"));
        assertThrows(IllegalArgumentException.class, () -> seconds("1:75"));
    }

    @Test
    @DisplayName("nonsense is refused, never read as a number")
    void nonsenseIsRefused() {
        for (String typed : new String[]{"", "   ", "abc", "1.04.1234", "1.2.3.4", "1.04.",
                "1..123", "-1.04.123", "+1.04", "1.004.123", "1:2:3", "48.123s", "1.04,123.5"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> seconds(typed), "\"" + typed + "\" must be refused");
            assertNotNull(error.getMessage(), typed);
            assertTrue(error.getMessage().contains(StopwatchTime.SHAPE),
                    "the refusal says what to write: " + error.getMessage());
        }
    }

    @Test
    @DisplayName("a blank box is refused too — clearing a mark is the caller's own word")
    void blankIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> StopwatchTime.parse(null));
        assertThrows(IllegalArgumentException.class, () -> StopwatchTime.parse(""));
    }

    // -------------------------------------------------------------- what it shows

    @Test
    @DisplayName("a stored mark reads as minutes, seconds and milliseconds")
    void theShapeItShows() {
        assertEquals("1.04.123", StopwatchTime.format(new BigDecimal("64.123")));
        assertEquals("0.48.123", StopwatchTime.format(new BigDecimal("48.123")));
        assertEquals("2.15.500", StopwatchTime.format(new BigDecimal("135.5")));
        assertEquals("16.40.000", StopwatchTime.format(new BigDecimal("1000")));
        assertEquals("0.00.000", StopwatchTime.format(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("the seconds are two digits and the milliseconds three, always")
    void theFieldsArePadded() {
        assertEquals("1.04.000", StopwatchTime.format(new BigDecimal("64")),
                "a whole number of seconds still carries its milliseconds");
        assertEquals("1.00.000", StopwatchTime.format(new BigDecimal("60")));
        assertEquals("0.09.500", StopwatchTime.format(new BigDecimal("9.5")));
        assertEquals("0.00.005", StopwatchTime.format(new BigDecimal("0.005")));
    }

    @Test
    @DisplayName("a time over an hour is minutes too — the storage allows it, so it is shown")
    void pastAnHour() {
        // The mark is a count of seconds with no ceiling of its own, so an hour is
        // sixty-one minutes and the shape says so rather than inventing an hours part.
        assertEquals("60.00.000", StopwatchTime.format(new BigDecimal("3600")));
        assertEquals("61.01.500", StopwatchTime.format(new BigDecimal("3661.5")));
        assertEquals("1439.59.999", StopwatchTime.format(new BigDecimal("86399.999")));
    }

    @Test
    @DisplayName("rounding to the thousandth happens before the split, so 59.9999 is a minute")
    void roundingNeverShowsSixtySeconds() {
        assertEquals("1.00.000", StopwatchTime.format(new BigDecimal("59.9999")));
    }

    @Test
    @DisplayName("no mark, no text")
    void nullInNullOut() {
        assertNull(StopwatchTime.format(null));
    }

    @Test
    @DisplayName("a negative value is not a performance and is not given fields")
    void aNegativeMarkIsShownAsStored() {
        assertEquals("-5", StopwatchTime.format(new BigDecimal("-5")));
    }

    // ------------------------------------------------------------- round trip

    @Test
    @DisplayName("the box shows exactly what parses back to the same mark, at every boundary")
    void theRoundTripIsExact() {
        String[] boundaries = {
                "0",            // nothing at all
                "0.001",        // the smallest thousandth the storage holds
                "9.999",
                "48.123",       // a relay under a minute — the leading-zero case
                "59.999",       // just under a minute
                "60",           // exactly a whole minute
                "60.001",
                "64.123",
                "99.999",
                "135.5",
                "599.999",
                "1000",
                "3600",         // an hour
                "3661.5",       // just past an hour
                "86399.999",    // the last thousandth of a day
                "9999999.999",  // the largest mark the column can hold
        };
        for (String raw : boundaries) {
            BigDecimal mark = new BigDecimal(raw);
            String shown = StopwatchTime.format(mark);
            BigDecimal parsed = StopwatchTime.parse(shown);
            assertEquals(0, mark.compareTo(parsed),
                    raw + " must survive the round trip: shown as " + shown
                            + " which parsed back as " + parsed);
            // And the text itself is stable: showing the parsed mark gives the same box.
            assertEquals(shown, StopwatchTime.format(parsed),
                    raw + " reads the same way twice");
        }
    }

    @Test
    @DisplayName("every accepted shape parses to the text it is shown as")
    void theShapesAgreeWithWhatIsShown() {
        String[] typed = {"1.04.123", "1.4.123", "0.48.123", "48.123", "48", "2:15.5", "1,04,123"};
        for (String text : typed) {
            BigDecimal mark = StopwatchTime.parse(text);
            // Whatever a helper types, it is stored as a number and then shown in the
            // one canonical shape — and that shape parses back to the same number.
            assertEquals(0, mark.compareTo(StopwatchTime.parse(StopwatchTime.format(mark))),
                    text + " is stored as " + mark + " and read back as "
                            + StopwatchTime.format(mark));
        }
    }
}
