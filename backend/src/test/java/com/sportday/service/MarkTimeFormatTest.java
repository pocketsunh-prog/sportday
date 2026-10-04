package com.sportday.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A race over 400M is timed on a stopwatch: a helper types 2 minutes 15 seconds, not
 * 135. The mark is still stored in seconds, so everything downstream is unchanged.
 */
class MarkTimeFormatTest {

    @Test
    @DisplayName("minutes and seconds become a total in seconds")
    void minutesAndSecondsBecomeSeconds() {
        assertEquals(0, MarkEntryService.totalSeconds(2, BigDecimal.valueOf(15))
                .compareTo(BigDecimal.valueOf(135)));
        assertEquals(0, MarkEntryService.totalSeconds(5, BigDecimal.valueOf(30))
                .compareTo(BigDecimal.valueOf(330)));
        assertEquals(0, MarkEntryService.totalSeconds(16, BigDecimal.valueOf(40))
                .compareTo(BigDecimal.valueOf(1000)), "a 5000M can run past sixteen minutes");
    }

    @Test
    @DisplayName("a fractional second is kept")
    void fractionalSecondsAreKept() {
        assertEquals(0, MarkEntryService.totalSeconds(4, new BigDecimal("07.25"))
                .compareTo(new BigDecimal("247.25")));
    }

    @Test
    @DisplayName("zero minutes is fine — a fast 800M may be under a minute")
    void zeroMinutesIsFine() {
        assertEquals(0, MarkEntryService.totalSeconds(0, new BigDecimal("58.4"))
                .compareTo(new BigDecimal("58.4")));
        assertEquals(0, MarkEntryService.totalSeconds(null, new BigDecimal("58.4"))
                .compareTo(new BigDecimal("58.4")), "a blank minutes box reads as none");
    }

    @Test
    @DisplayName("under a minute is just the seconds")
    void underAMinuteIsJustSeconds() {
        assertEquals(0, MarkEntryService.totalSeconds(null, BigDecimal.valueOf(45))
                .compareTo(BigDecimal.valueOf(45)));
    }

    @Test
    @DisplayName("60 or more seconds in the seconds box is refused, not silently carried")
    void sixtySecondsIsRefused() {
        // 1 minute 75 seconds is how somebody mistypes 2:15, so it must not become 135.
        assertNull(MarkEntryService.totalSeconds(1, BigDecimal.valueOf(75)));
        assertNull(MarkEntryService.totalSeconds(0, BigDecimal.valueOf(60)));
        assertNull(MarkEntryService.totalSeconds(2, BigDecimal.valueOf(-1)));
        assertNull(MarkEntryService.totalSeconds(-3, BigDecimal.valueOf(10)));
    }

    @Test
    @DisplayName("a stored mark splits back into minutes and the seconds left over")
    void aStoredMarkSplitsBack() {
        assertEquals(2, MarkEntryService.minutesOf(BigDecimal.valueOf(135)));
        assertEquals(0, MarkEntryService.secondsOf(BigDecimal.valueOf(135))
                .compareTo(BigDecimal.valueOf(15)));

        assertEquals(4, MarkEntryService.minutesOf(new BigDecimal("247.25")));
        assertEquals(0, MarkEntryService.secondsOf(new BigDecimal("247.25"))
                .compareTo(new BigDecimal("7.25")));

        assertEquals(0, MarkEntryService.minutesOf(BigDecimal.valueOf(58)));
        assertEquals(0, MarkEntryService.secondsOf(BigDecimal.valueOf(58))
                .compareTo(BigDecimal.valueOf(58)), "under a minute has no whole minutes");
    }

    @Test
    @DisplayName("a mark with nothing in it splits into nothing")
    void anEmptyMarkSplitsIntoNothing() {
        assertNull(MarkEntryService.minutesOf(null));
        assertNull(MarkEntryService.secondsOf(null));
    }

    @Test
    @DisplayName("the split and the join agree, so a saved time reads back the same")
    void splitAndJoinAgree() {
        for (String raw : new String[]{"58", "59.9", "60", "135", "247.25", "1000", "1800.5"}) {
            BigDecimal mark = new BigDecimal(raw);
            BigDecimal rejoined = MarkEntryService.totalSeconds(
                    MarkEntryService.minutesOf(mark), MarkEntryService.secondsOf(mark));
            assertEquals(0, mark.compareTo(rejoined), raw + " survives a round trip");
        }
    }
}
