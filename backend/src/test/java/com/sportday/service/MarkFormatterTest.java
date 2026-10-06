package com.sportday.service;

import com.sportday.entity.Event;
import com.sportday.entity.EventResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a result reads.
 *
 * <p>Requirement: a 100M reads {@code 14.123s}, a 400M {@code 1.04.123s}, a shot put
 * {@code 18.12M}.</p>
 *
 * <p>And, for a race timed on a stopwatch — the 400M and over and the two relays —
 * the school's own shape <strong>{@code M.SS.mmm}</strong>: minutes, seconds and
 * milliseconds, all three fields, a leading zero minute when the race was under a
 * minute ({@code 0.48.123s}). A sprint is deliberately untouched.</p>
 */
class MarkFormatterTest {

    private static final Event.EventType HUNDRED = Event.EventType.RUN_100M;
    private static final Event.EventType FOUR_HUNDRED = Event.EventType.RUN_400M;
    private static final Event.EventType EIGHT_HUNDRED = Event.EventType.RUN_800M;
    private static final Event.EventType RELAY = Event.EventType.RELAY_4X100M;
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
    @DisplayName("a 400M under a minute keeps its leading zero minute")
    void fourHundredMetresUnderAMinuteKeepsZero() {
        assertEquals("0.48.123", time("48.123", FOUR_HUNDRED));
        assertEquals("0.48.123s", MarkFormatter.formatWithUnit(
                new BigDecimal("48.123"), FOUR_HUNDRED, "s"));
    }

    @Test
    @DisplayName("a relay is timed exactly like the race it is")
    void aRelayReadsAsTheRaceItIs() {
        assertEquals("0.44.000", time("44", RELAY),
                "a 4x100M under a minute still reads as minutes, seconds and milliseconds");
        assertEquals("1.03.500", time("63.5", Event.EventType.RELAY_4X400M));
    }

    @Test
    @DisplayName("the 400M hurdles is one of the races timed on a stopwatch")
    void theFourHundredHurdlesReadsAsMinutes() {
        assertEquals("1.02.000", time("62", Event.EventType.HURDLES_400M));
    }

    @Test
    @DisplayName("a short sprint is unchanged, whatever the mark")
    void aShortSprintIsUnchanged() {
        assertEquals("14.123", time("14.123", HUNDRED));
        assertEquals("21.5", time("21.5", Event.EventType.RUN_200M));
        assertEquals("7.4", time("7.4", Event.EventType.RUN_60M));
        assertEquals("13.8", time("13.8", Event.EventType.HURDLES_100M));
        assertEquals("14.2", time("14.2", Event.EventType.HURDLES_110M));
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
    @DisplayName("a time just under a minute has no minutes part on a sprint")
    void justUnderAMinuteHasNoMinutes() {
        assertEquals("59.999", time("59.999", HUNDRED));
        assertEquals("59", time("59", HUNDRED));
        // The same mark on a 400M is a stopped time, so it keeps the leading zero.
        assertEquals("0.59.999", time("59.999", FOUR_HUNDRED));
    }

    @Test
    @DisplayName("a time of exactly a minute reads as one minute, no seconds")
    void exactlyAMinute() {
        assertEquals("1.00.000", time("60", FOUR_HUNDRED));
    }

    @Test
    @DisplayName("a whole number of seconds past a minute is padded, not bare")
    void wholeSecondsArePadded() {
        // "1.4" would read as a tenth of a second, so the seconds field is padded —
        // and the milliseconds are three digits, so 1.04.000 and not 1.04.
        assertEquals("1.04.000", time("64", FOUR_HUNDRED));
        assertEquals("2.05.000", time("125", EIGHT_HUNDRED));
    }

    @Test
    @DisplayName("a long race keeps its fractions as milliseconds")
    void aLongRaceKeepsItsFractions() {
        assertEquals("2.15.500", time("135.5", EIGHT_HUNDRED));
        assertEquals("2.15.250", time("135.25", EIGHT_HUNDRED));
        assertEquals("16.40.000", time("1000", Event.EventType.RUN_5000M));
    }

    @Test
    @DisplayName("a ten-second 100M is not mistaken for a long time")
    void shortTimesAreNotSplit() {
        assertEquals("9.58", time("9.58", HUNDRED));
        assertEquals("1.5", time("1.5", HUNDRED));
    }

    // -------------------------------------------------------------- details

    @Test
    @DisplayName("trailing zeros are trimmed on a sprint, and filled in on a long race")
    void trailingZeros() {
        assertEquals("18.12", MarkFormatter.format(new BigDecimal("18.1200"), SHOT, "M"));
        assertEquals("18.12M", MarkFormatter.formatWithUnit(new BigDecimal("18.120"), SHOT, "M"));
        assertEquals("58", time("58.00", HUNDRED), "a sprint reads as the seconds it is");
        assertEquals("0.58.000", time("58.00", FOUR_HUNDRED),
                "the same mark on a stopped race carries all three fields");
        assertEquals("2.15.500", time("135.500", EIGHT_HUNDRED));
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
        // With no type there is no rule saying "timed on a stopwatch", so the old
        // shape stands: a minutes part only over a minute, and no filled-in fields.
        assertEquals("1.04", MarkFormatter.format(new BigDecimal("64"), null, "s"));
    }

    @Test
    @DisplayName("a negative mark is shown as stored rather than given a minutes part")
    void aNegativeMarkIsNotSplit() {
        assertEquals("-5", MarkFormatter.format(new BigDecimal("-5"), HUNDRED, "s"));
        assertEquals("-5", MarkFormatter.format(new BigDecimal("-5"), FOUR_HUNDRED, "s"));
    }

    // ------------------------------------------------------------ outcomes

    @Test
    @DisplayName("ABS and DQ read as themselves, in place of a mark")
    void outcomesReadAsThemselves() {
        assertEquals("ABS", MarkFormatter.formatOutcome(EventResult.Outcome.ABS));
        assertEquals("DQ", MarkFormatter.formatOutcome(EventResult.Outcome.DQ));
    }

    @Test
    @DisplayName("there is nothing to write for a mark that was recorded")
    void aResultHasNoOutcomeToShow() {
        assertNull(MarkFormatter.formatOutcome(EventResult.Outcome.RESULT),
                "RESULT means a number was produced, so the number is what shows");
        assertNull(MarkFormatter.formatOutcome(null),
                "and a row from before outcomes existed is a result too");
    }

    @Test
    @DisplayName("the outcome stands in for the mark, and a mark is still a mark")
    void theOutcomeStandsInForTheMark() {
        assertEquals("ABS", MarkFormatter.formatWithOutcome(
                EventResult.Outcome.ABS, null, HUNDRED, "s"));
        assertEquals("DQ", MarkFormatter.formatWithOutcome(
                EventResult.Outcome.DQ, null, SHOT, "M"));
        // A recorded performance is formatted exactly as it always was.
        assertEquals("18.12M", MarkFormatter.formatWithOutcome(
                EventResult.Outcome.RESULT, new BigDecimal("18.12"), SHOT, "M"));
        assertEquals("1.04.123s", MarkFormatter.formatWithOutcome(
                null, new BigDecimal("64.123"), FOUR_HUNDRED, "s"));
        assertNull(MarkFormatter.formatWithOutcome(null, null, HUNDRED, "s"));
    }

    // ------------------------------------- a stored row, as a sheet shows it

    private static EventResult stored(EventResult.Outcome outcome, String mark, String unit) {
        return EventResult.builder()
                .outcome(outcome)
                .mark(mark == null ? null : new BigDecimal(mark))
                .unit(unit)
                .build();
    }

    @Test
    @DisplayName("a stored result reads with its unit, or as ABS / DQ when it has no mark")
    void aStoredResultReads() {
        assertEquals("11.86s", MarkFormatter.formatRecord(
                stored(EventResult.Outcome.RESULT, "11.860", "s"), HUNDRED, "s"));
        assertEquals("1.04.123s", MarkFormatter.formatRecord(
                stored(EventResult.Outcome.RESULT, "64.123", null), FOUR_HUNDRED, "seconds"),
                "the event's own unit stands in when the row carries none");
        assertEquals("18.12M", MarkFormatter.formatRecord(
                stored(EventResult.Outcome.RESULT, "18.12", "M"), SHOT, "M"));
        assertEquals("ABS", MarkFormatter.formatRecord(
                stored(EventResult.Outcome.ABS, null, null), HUNDRED, "s"));
        assertEquals("DQ", MarkFormatter.formatRecord(
                stored(EventResult.Outcome.DQ, null, null), HUNDRED, "s"));
        assertNull(MarkFormatter.formatRecord(null, HUNDRED, "s"),
                "no record at all is nothing to show");
    }

    @Test
    @DisplayName("a row with an outcome but no number still says what it was")
    void anOutcomeWithNoNumberStillReads() {
        // The one case the plain formatter cannot read, so a final sheet showing a
        // heat beside the mark being written never shows a blank where a record is.
        assertEquals("RESULT", MarkFormatter.formatRecord(
                stored(EventResult.Outcome.RESULT, null, "s"), HUNDRED, "s"));
        assertEquals("RESULT", MarkFormatter.formatRecord(
                stored(null, null, null), HUNDRED, "s"));
    }
}
