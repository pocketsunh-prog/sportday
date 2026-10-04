package com.sportday.service;

import com.sportday.entity.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a result reads.
 *
 * <p>Requirement: a 100M reads {@code 14.123s}, a 400M {@code 1.04.123s}, a shot put
 * {@code 18.12M}.</p>
 */
class MarkFormatterTest {

    private static final Event.EventType HUNDRED = Event.EventType.RUN_100M;
    private static final Event.EventType FOUR_HUNDRED = Event.EventType.RUN_400M;
    private static final Event.EventType EIGHT_HUNDRED = Event.EventType.RUN_800M;
    private static final Event.EventType SHOT = Event.EventType.SHOT_PUT;

    private static String time(String mark, Event.EventType type) {
        return MarkFormatter.format(new BigDecimal(mark), type, "s");
    }

    // ------------------------------------------------- the shapes asked for

    @Test
    @DisplayName("a 100M under a minute is just the seconds")
    void aHundredMetresReadsAsSeconds() {
        assertEquals("14.123", time("14.123", HUNDRED));
        assertEquals("14.123s", MarkFormatter.formatWithUnit(
                new BigDecimal("14.123"), HUNDRED, "s"));
    }

    @Test
    @DisplayName("a 400M over a minute gains a minutes part, seconds padded to two digits")
    void fourHundredMetresReadsAsMinutes() {
        assertEquals("1.04.123", time("64.123", FOUR_HUNDRED));
        assertEquals("1.04.123s", MarkFormatter.formatWithUnit(
                new BigDecimal("64.123"), FOUR_HUNDRED, "s"));
    }

    @Test
    @DisplayName("a shot put reads in metres")
    void aShotPutReadsInMetres() {
        assertEquals("18.12", MarkFormatter.format(new BigDecimal("18.12"), SHOT, "M"));
        assertEquals("18.12M", MarkFormatter.formatWithUnit(new BigDecimal("18.12"), SHOT, "M"));
    }

    @Test
    @DisplayName("the unit is the short one the sport uses, whatever was stored")
    void theUnitIsNormalised() {
        // Marks stored before the units were shortened still read correctly.
        assertEquals("14.123s", MarkFormatter.formatWithUnit(
                new BigDecimal("14.123"), HUNDRED, "seconds"));
        assertEquals("18.12M", MarkFormatter.formatWithUnit(
                new BigDecimal("18.12"), SHOT, "metres"));
        assertEquals("18.12M", MarkFormatter.formatWithUnit(
                new BigDecimal("18.12"), SHOT, "米"));
    }

    // ----------------------------------------------------------- boundaries

    @Test
    @DisplayName("a time just under a minute has no minutes part")
    void justUnderAMinuteHasNoMinutes() {
        assertEquals("59.999", time("59.999", HUNDRED));
        assertEquals("59", time("59", HUNDRED));
    }

    @Test
    @DisplayName("a time of exactly a minute reads as one minute, no seconds")
    void exactlyAMinute() {
        assertEquals("1.00", time("60", FOUR_HUNDRED));
    }

    @Test
    @DisplayName("a whole number of seconds past a minute is padded, not bare")
    void wholeSecondsArePadded() {
        // "1.4" would read as a tenth of a second, so the seconds field is padded.
        assertEquals("1.04", time("64", FOUR_HUNDRED));
        assertEquals("2.05", time("125", EIGHT_HUNDRED));
    }

    @Test
    @DisplayName("a long race keeps its fractions")
    void aLongRaceKeepsItsFractions() {
        assertEquals("2.15.5", time("135.5", EIGHT_HUNDRED));
        assertEquals("2.15.25", time("135.25", EIGHT_HUNDRED));
        assertEquals("16.40", time("1000", Event.EventType.RUN_5000M));
    }

    @Test
    @DisplayName("a ten-second 100M is not mistaken for a long time")
    void shortTimesAreNotSplit() {
        assertEquals("9.58", time("9.58", HUNDRED));
        assertEquals("1.5", time("1.5", HUNDRED));
    }

    // -------------------------------------------------------------- details

    @Test
    @DisplayName("trailing zeros are trimmed, so a mark reads as it was meant")
    void trailingZerosAreTrimmed() {
        assertEquals("18.12", MarkFormatter.format(new BigDecimal("18.1200"), SHOT, "M"));
        assertEquals("18.12M", MarkFormatter.formatWithUnit(new BigDecimal("18.120"), SHOT, "M"));
        assertEquals("58", time("58.00", HUNDRED));
        assertEquals("2.15.5", time("135.500", EIGHT_HUNDRED));
    }

    @Test
    @DisplayName("a field event is measured, never timed, however large the number")
    void aFieldEventIsNeverTimed() {
        // 64.2 metres is a long throw, not a minute and four seconds.
        assertEquals("64.2", MarkFormatter.format(new BigDecimal("64.2"), SHOT, "M"));
        assertEquals("18.12M", MarkFormatter.formatWithUnit(new BigDecimal("18.12"), SHOT, "M"));
        assertFalse(MarkFormatter.isTime(SHOT, "M"));
    }

    @Test
    @DisplayName("no mark, no text")
    void noMarkMeansNothingToShow() {
        assertNull(MarkFormatter.format(null, HUNDRED, "s"));
        assertNull(MarkFormatter.formatWithUnit(null, HUNDRED, "s"));
    }

    @Test
    @DisplayName("without an event type the unit decides")
    void theUnitDecidesWhenThereIsNoType() {
        assertEquals("1.04.123", MarkFormatter.format(new BigDecimal("64.123"), null, "s"));
        assertEquals("14.123s", MarkFormatter.formatWithUnit(new BigDecimal("14.123"), null, "seconds"));
        assertEquals("18.12M", MarkFormatter.formatWithUnit(new BigDecimal("18.12"), null, "metres"));
        assertEquals("64.2M", MarkFormatter.formatWithUnit(new BigDecimal("64.2"), null, "M"),
                "a bare M is a measurement, so 64.2 is not a time");
    }

    @Test
    @DisplayName("a negative mark is shown as stored rather than given a minutes part")
    void aNegativeMarkIsNotSplit() {
        assertEquals("-5", MarkFormatter.format(new BigDecimal("-5"), HUNDRED, "s"));
    }
}
