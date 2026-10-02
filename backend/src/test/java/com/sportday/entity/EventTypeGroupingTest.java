package com.sportday.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Requirement 4: 8 athletes per group for 60/100/200/400, 24 for 800 and above.
 * Requirement 5: the same split decides whether the marking sheet is A5 or A4.
 */
class EventTypeGroupingTest {

    private static final Set<Event.EventType> SHORT_SPRINTS =
            EnumSet.of(Event.EventType.RUN_60M, Event.EventType.RUN_100M,
                    Event.EventType.RUN_200M, Event.EventType.RUN_400M);

    @Test
    @DisplayName("60/100/200/400 default to 8 per group and an A5 sheet")
    void shortSprintsAreEightPerGroup() {
        for (Event.EventType type : SHORT_SPRINTS) {
            assertTrue(type.isShortSprint(), type + " should be a short sprint");
            assertEquals(8, type.getDefaultGroupSize(), type + " should be 8 per group");
        }
    }

    @Test
    @DisplayName("800 and above, and every field event, default to 24 per group and an A4 sheet")
    void everythingElseIsTwentyFourPerGroup() {
        for (Event.EventType type : Event.EventType.values()) {
            if (SHORT_SPRINTS.contains(type)) {
                continue;
            }
            assertFalse(type.isShortSprint(), type + " should not be a short sprint");
            assertEquals(24, type.getDefaultGroupSize(), type + " should be 24 per group");
        }
    }

    @Test
    @DisplayName("60M exists in the catalogue")
    void sixtyMetresIsOffered() {
        assertEquals(EventCategory.TRACK, Event.EventType.RUN_60M.getCategory());
        assertEquals("60M", Event.EventType.RUN_60M.getDisplayName());
    }

    @Test
    @DisplayName("track events are 徑項 and field events are 田項")
    void categoriesMatchTheSportDayWording() {
        assertEquals(EventCategory.TRACK, Event.EventType.RUN_100M.getCategory());
        assertEquals(EventCategory.TRACK, Event.EventType.RUN_800M.getCategory());
        assertEquals(EventCategory.TRACK, Event.EventType.RUN_1500M.getCategory());
        assertEquals(EventCategory.FIELD, Event.EventType.LONG_JUMP.getCategory());
        assertEquals(EventCategory.FIELD, Event.EventType.SHOT_PUT.getCategory());
        assertEquals("徑項", EventCategory.TRACK.getLabelZh());
        assertEquals("田項", EventCategory.FIELD.getLabelZh());
    }

    @ParameterizedTest
    @EnumSource(Event.EventType.class)
    @DisplayName("every event type has a category and a positive group size")
    void everyTypeIsFullySpecified(Event.EventType type) {
        assertNotNull(type.getCategory());
        assertNotNull(type.getDisplayName());
        assertTrue(type.getDefaultGroupSize() > 0);
    }

    @Test
    @DisplayName("track events default to seconds and field events to metres")
    void defaultUnits() {
        assertEquals("seconds", Event.EventType.RUN_60M.getDefaultUnit());
        assertEquals("seconds", Event.EventType.RUN_100M.getDefaultUnit());
        assertEquals("seconds", Event.EventType.RUN_5000M.getDefaultUnit());
        assertEquals("seconds", Event.EventType.HURDLES_110M.getDefaultUnit());
        assertEquals("metres", Event.EventType.LONG_JUMP.getDefaultUnit());
        assertEquals("metres", Event.EventType.SHOT_PUT.getDefaultUnit());
        assertEquals("metres", Event.EventType.POLE_VAULT.getDefaultUnit());
    }

    @ParameterizedTest
    @EnumSource(Event.EventType.class)
    @DisplayName("every event type has a usable default unit")
    void everyTypeHasADefaultUnit(Event.EventType type) {
        String unit = type.getDefaultUnit();
        assertTrue("seconds".equals(unit) || "metres".equals(unit), type + " has unit " + unit);
    }

    @Test
    @DisplayName("a student may enter 2 track events and 1 field event")
    void entryLimits() {
        assertEquals(2, EventCategory.TRACK.getMaxEntriesPerStudent());
        assertEquals(1, EventCategory.FIELD.getMaxEntriesPerStudent());
    }

    @Test
    @DisplayName("a new event is enabled and inherits type defaults")
    void newEventDefaults() {
        Event event = Event.builder()
                .name("Boys 100M")
                .type(Event.EventType.RUN_100M)
                .sex(Sex.MALE)
                .eventDate(java.time.LocalDate.of(2026, 11, 6))
                .build();
        event.applyTypeDefaults();

        assertEquals(EventCategory.TRACK, event.getCategory());
        assertEquals(8, event.getGroupSize());
        assertTrue(event.isShortSprint());
    }

    @Test
    @DisplayName("an explicit group size overrides the type default")
    void groupSizeCanBeOverridden() {
        Event event = Event.builder()
                .name("Boys 100M")
                .type(Event.EventType.RUN_100M)
                .sex(Sex.MALE)
                .eventDate(java.time.LocalDate.of(2026, 11, 6))
                .groupSize(6)
                .build();
        event.applyTypeDefaults();
        assertEquals(6, event.getGroupSize());
    }

    @Test
    @DisplayName("sex codes and labels round-trip")
    void sexParsing() {
        assertEquals(Sex.MALE, Sex.fromCode("M"));
        assertEquals(Sex.MALE, Sex.fromCode("male"));
        assertEquals(Sex.MALE, Sex.fromCode("男"));
        assertEquals(Sex.FEMALE, Sex.fromCode("F"));
        assertEquals(Sex.FEMALE, Sex.fromCode("Girls"));
        assertEquals(Sex.FEMALE, Sex.fromCode("女"));
        assertNull(Sex.fromCode(""));
        assertNull(Sex.fromCode("x"));
        assertEquals("M", Sex.MALE.getCode());
        assertEquals("F", Sex.FEMALE.getCode());
    }
}
