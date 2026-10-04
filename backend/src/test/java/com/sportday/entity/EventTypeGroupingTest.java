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
        // Written the way an athletics programme writes them, because that is what
        // fits a marking-sheet column: s for a time, M for a distance or height.
        assertEquals("s", Event.EventType.RUN_60M.getDefaultUnit());
        assertEquals("s", Event.EventType.RUN_100M.getDefaultUnit());
        assertEquals("s", Event.EventType.RUN_5000M.getDefaultUnit());
        assertEquals("s", Event.EventType.HURDLES_110M.getDefaultUnit());
        assertEquals("M", Event.EventType.LONG_JUMP.getDefaultUnit());
        assertEquals("M", Event.EventType.SHOT_PUT.getDefaultUnit());
        assertEquals("M", Event.EventType.POLE_VAULT.getDefaultUnit());
        // Every field event measures in metres, without exception.
        for (Event.EventType type : Event.EventType.values()) {
            if (type.getCategory() == EventCategory.FIELD) {
                assertEquals("M", type.getDefaultUnit(), type + " measures in metres");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Event.EventType.class)
    @DisplayName("every event type has a usable default unit")
    void everyTypeHasADefaultUnit(Event.EventType type) {
        String unit = type.getDefaultUnit();
        assertEquals(type.getCategory() == EventCategory.FIELD ? "M" : "s", unit,
                type + " has unit " + unit);
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

    // ---------------------------------------------------- which grades run what

    @Test
    @DisplayName("no C grade in the 1500M or the 110M hurdles, and only the A grade in the 5000M")
    void whichGradesRunAnEventType() {
        assertEquals(EnumSet.of(Grade.A), Event.EventType.RUN_5000M.allowedGrades());
        assertEquals(EnumSet.of(Grade.A, Grade.B), Event.EventType.RUN_1500M.allowedGrades());
        assertEquals(EnumSet.of(Grade.A, Grade.B), Event.EventType.HURDLES_110M.allowedGrades());

        assertFalse(Event.EventType.RUN_1500M.runsGrade(Grade.C));
        assertFalse(Event.EventType.HURDLES_110M.runsGrade(Grade.C));
        assertFalse(Event.EventType.RUN_5000M.runsGrade(Grade.B),
                "the B grade does not run the 5000M either");
        assertFalse(Event.EventType.RUN_5000M.runsGrade(Grade.C));

        // The 100M hurdles is the C grade's own event, and everything else is open.
        assertEquals(EnumSet.allOf(Grade.class), Event.EventType.HURDLES_100M.allowedGrades());
        for (Event.EventType type : Event.EventType.values()) {
            assertFalse(type.allowedGrades().isEmpty(), type + " is run by no grade at all");
        }
        assertTrue(Event.EventType.RUN_100M.runsGrade(Grade.A));
        assertTrue(Event.EventType.RUN_100M.runsGrade(Grade.B));
        assertTrue(Event.EventType.RUN_100M.runsGrade(Grade.C));
        assertTrue(Event.EventType.SHOT_PUT.runsGrade(Grade.C), "every field event is open");
        assertTrue(Event.EventType.RUN_800M.runsGrade(Grade.C));
    }

    @Test
    @DisplayName("the grades a type is run by are returned in programme order, A then B then C")
    void gradeOrderIsProgrammeOrder() {
        assertEquals(java.util.List.of(Grade.A, Grade.B, Grade.C),
                java.util.List.copyOf(Event.EventType.RUN_100M.allowedGrades()));
        assertEquals(java.util.List.of(Grade.A, Grade.B),
                java.util.List.copyOf(Event.EventType.RUN_1500M.allowedGrades()));
    }

    @Test
    @DisplayName("an event belongs to one grade, and the name says which")
    void theDefaultNameCarriesTheGrade() {
        assertEquals("Boys 100M · A Grade",
                com.sportday.service.EventService.defaultName(
                        Event.EventType.RUN_100M, Sex.MALE, Grade.A));
        assertEquals("Girls 1500M · B Grade",
                com.sportday.service.EventService.defaultName(
                        Event.EventType.RUN_1500M, Sex.FEMALE, Grade.B));
        assertNull(com.sportday.service.EventService.defaultName(
                        Event.EventType.RUN_100M, Sex.MALE, null),
                "an event with no grade has no name to be given");
    }
}
