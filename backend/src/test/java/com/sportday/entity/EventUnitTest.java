package com.sportday.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The unit a mark is recorded in, and the fact that it cannot drift.
 *
 * <p>Requirement: a field event is measured in metres — a shot put is entered in
 * M — and a track event in seconds.</p>
 */
class EventUnitTest {

    @Test
    @DisplayName("a field event is measured in metres and a track event in seconds")
    void theUnitFollowsTheEvent() {
        assertEquals("M", Event.EventType.SHOT_PUT.getDefaultUnit());
        assertEquals("M", Event.EventType.DISCUSSION_THROW.getDefaultUnit());
        assertEquals("M", Event.EventType.JAVELIN_THROW.getDefaultUnit());
        assertEquals("M", Event.EventType.LONG_JUMP.getDefaultUnit());
        assertEquals("M", Event.EventType.HIGH_JUMP.getDefaultUnit());
        assertEquals("M", Event.EventType.HAMMER_THROW.getDefaultUnit());
        assertEquals("s", Event.EventType.RUN_100M.getDefaultUnit());
        assertEquals("s", Event.EventType.RELAY_4X100M.getDefaultUnit());
    }

    @Test
    @DisplayName("the spelled-out unit a client sends is shortened to the event's own")
    void spelledOutUnitsAreShortened() {
        Event.EventType shot = Event.EventType.SHOT_PUT;
        assertEquals("M", shot.normaliseUnit("metres"));
        assertEquals("M", shot.normaliseUnit("Metres"));
        assertEquals("M", shot.normaliseUnit("m"));
        assertEquals("M", shot.normaliseUnit("  metre  "));

        Event.EventType sprint = Event.EventType.RUN_100M;
        assertEquals("s", sprint.normaliseUnit("seconds"));
        assertEquals("s", sprint.normaliseUnit("SEC"));
        assertEquals("s", sprint.normaliseUnit("s"));
    }

    @Test
    @DisplayName("a unit that contradicts the event is corrected rather than stored")
    void aWrongUnitIsCorrected() {
        // "seconds" on a shot put is a client mistake; the event knows better, and
        // a marking sheet must not end up saying a throw was timed.
        assertEquals("M", Event.EventType.SHOT_PUT.normaliseUnit("seconds"));
        assertEquals("s", Event.EventType.RUN_100M.normaliseUnit("metres"));
    }

    @Test
    @DisplayName("a blank unit falls back to the event's own")
    void aBlankUnitFallsBack() {
        assertEquals("M", Event.EventType.SHOT_PUT.normaliseUnit(null));
        assertEquals("M", Event.EventType.SHOT_PUT.normaliseUnit("   "));
        assertEquals("s", Event.EventType.RUN_400M.normaliseUnit(""));
    }

    @Test
    @DisplayName("an unusual unit a school really uses is left alone")
    void anUnrecognisedUnitIsKept() {
        // A primary school measuring a high jump in centimetres should not be
        // silently overruled.
        assertEquals("cm", Event.EventType.HIGH_JUMP.normaliseUnit("cm"));
        assertEquals("points", Event.EventType.OTHER.normaliseUnit("points"));
    }
}
