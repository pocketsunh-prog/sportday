package com.sportday.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which events run straight to a final.
 *
 * <p>Requirement: 60M, 100M and 200M (and 400M) may have a final; every other event
 * goes direct to a final, and the check box defaults to direct.</p>
 */
class EventFinalFormatTest {

    private Event event(Event.EventType type, Boolean directToFinal) {
        return Event.builder()
                .id(1L)
                .name(type.getDisplayName())
                .type(type)
                .category(type.getCategory())
                .sex(Sex.MALE)
                .groupSize(type.getDefaultGroupSize())
                .enabled(true)
                .directToFinal(directToFinal)
                .build();
    }

    @Test
    @DisplayName("a new event runs straight to a final unless asked otherwise")
    void theDefaultIsDirectToFinal() {
        // A null is what a row written before the flag existed carries, and what a
        // request that does not mention it leaves behind. Both mean "direct".
        assertTrue(event(Event.EventType.RUN_100M, null).isDirectToFinal());
        assertTrue(event(Event.EventType.RUN_100M, true).isDirectToFinal());
        assertFalse(event(Event.EventType.RUN_100M, false).isDirectToFinal());
    }

    @Test
    @DisplayName("only 60M, 100M, 200M and 400M may be run as heats and a final")
    void onlyTheShortSprintsMayHaveAFinal() {
        assertTrue(event(Event.EventType.RUN_60M, null).mayHaveFinal());
        assertTrue(event(Event.EventType.RUN_100M, null).mayHaveFinal());
        assertTrue(event(Event.EventType.RUN_200M, null).mayHaveFinal());
        assertTrue(event(Event.EventType.RUN_400M, null).mayHaveFinal());
    }

    @ParameterizedTest
    @EnumSource(value = Event.EventType.class,
            names = {"RUN_60M", "RUN_100M", "RUN_200M", "RUN_400M"},
            mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every other event goes straight to a final")
    void everyOtherEventIsDirect(Event.EventType type) {
        assertFalse(event(type, null).mayHaveFinal(),
                type + " is decided by its own run");
        assertTrue(event(type, null).isDirectToFinal());
    }

    @Test
    @DisplayName("a distance event is direct even when it is printed as several sheets")
    void aDistanceEventIsDirect() {
        // 800M has 24 to a sheet, so it is often split — but that is for printing,
        // not a final. It is still decided by its own run.
        Event eightHundred = event(Event.EventType.RUN_800M, null);
        assertFalse(eightHundred.mayHaveFinal());
        assertFalse(eightHundred.runsAFinal());
    }

    @Test
    @DisplayName("a final is only in play when the event allows one and the school asked")
    void aFinalNeedsBoth() {
        assertFalse(event(Event.EventType.RUN_100M, null).runsAFinal(),
                "direct by default, so no final");
        assertFalse(event(Event.EventType.RUN_100M, true).runsAFinal());
        assertTrue(event(Event.EventType.RUN_100M, false).runsAFinal(),
                "unticked, so heats and a final");
        assertFalse(event(Event.EventType.RUN_800M, false).runsAFinal(),
                "an 800M cannot have a final however it is set");
    }

    @Test
    @DisplayName("a race over 400M is timed in minutes and seconds")
    void longRacesAreTimedInMinutes() {
        assertTrue(event(Event.EventType.RUN_800M, null).usesMinutesAndSeconds());
        assertTrue(event(Event.EventType.RUN_1500M, null).usesMinutesAndSeconds());
        assertTrue(event(Event.EventType.RUN_5000M, null).usesMinutesAndSeconds());
    }

    @ParameterizedTest
    @EnumSource(value = Event.EventType.class,
            names = {"RUN_800M", "RUN_1500M", "RUN_5000M"},
            mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("400M and below, and every field event, is not")
    void shortRacesAndFieldEventsAreNot(Event.EventType type) {
        assertFalse(event(type, null).usesMinutesAndSeconds(),
                type + " is recorded as a plain number");
    }
}
