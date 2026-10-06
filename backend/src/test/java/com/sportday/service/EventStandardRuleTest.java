package com.sportday.service;

import com.sportday.dto.EventDTO;
import com.sportday.dto.EventGroupDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The <strong>required standard</strong>: the one rule that says which events carry
 * one, and the one rule that says which way round a mark meets it.
 *
 * <p>The requirement, as the school confirmed it: a standard belongs to the track
 * races of <strong>400M and over</strong> and to <strong>every field event</strong>,
 * and to nothing else — a 60M, 100M or 200M is not qualifying, neither are the
 * hurdles under 400M, and neither is a relay, which is now its own category and is
 * run and scored by team. Both halves are stated once in
 * {@link Event.EventType#carriesAStandard()} and
 * {@link Event.EventType#meetsStandard(java.math.BigDecimal, java.math.BigDecimal)},
 * and the tests below are the whole of that list, type by type, so a type added
 * later cannot slip in or out unnoticed.</p>
 *
 * <p>The direction is deliberately <em>not</em> decided by these tests or by the
 * rule: it is the codebase's existing lower-is-better rule
 * ({@link Event.EventType#isLowerBetter()}), so a time counts at or under the
 * standard and a distance at or over it. The boundary is tested both ways, at the
 * standard and a hair either side, because that is the case that matters: a mark
 * exactly on the standard is <strong>met</strong>.</p>
 */
class EventStandardRuleTest {

    /** Exactly the events the school sets a standard on — and the whole of it. */
    private static final Set<Event.EventType> QUALIFYING = EnumSet.of(
            Event.EventType.RUN_400M,
            Event.EventType.HURDLES_400M,
            Event.EventType.RUN_800M,
            Event.EventType.RUN_1500M,
            Event.EventType.RUN_5000M,
            Event.EventType.SHOT_PUT,
            Event.EventType.DISCUSSION_THROW,
            Event.EventType.JAVELIN_THROW,
            Event.EventType.HAMMER_THROW,
            Event.EventType.LONG_JUMP,
            Event.EventType.HIGH_JUMP,
            Event.EventType.TRIPLE_JUMP,
            Event.EventType.POLE_VAULT);

    /** True when a mark fell short — the grid's own `belowStandard`, stated here. */
    private static boolean below(Event.EventType type, String standard, String mark) {
        return !type.meetsStandard(
                mark == null ? null : new BigDecimal(mark),
                standard == null ? null : new BigDecimal(standard));
    }

    private static Event event(Event.EventType type, String standard) {
        return Event.builder()
                .id(1L)
                .name("Event")
                .type(type)
                .category(type.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.A)
                .standard(standard == null ? null : new BigDecimal(standard))
                .eventDate(LocalDate.of(2026, 10, 1))
                .groupSize(type.getDefaultGroupSize())
                .enabled(true)
                .build();
    }

    // ------------------------------------------------- which events carry one

    @Test
    @DisplayName("the qualifying list is exactly 400M/400M hurdles/800M/1500M/5000M and every field event")
    void theQualifyingListIsExactlyRight() {
        for (Event.EventType type : Event.EventType.values()) {
            assertEquals(QUALIFYING.contains(type), type.carriesAStandard(),
                    type + " must " + (QUALIFYING.contains(type) ? "" : "not ")
                            + "carry a required standard");
        }
        assertEquals(13, QUALIFYING.size(), "five track races and eight field events");
    }

    @Test
    @DisplayName("the sprints and the short hurdles do not carry one")
    void theShortEventsDoNotCarryOne() {
        assertFalse(Event.EventType.RUN_60M.carriesAStandard());
        assertFalse(Event.EventType.RUN_100M.carriesAStandard());
        assertFalse(Event.EventType.RUN_200M.carriesAStandard());
        assertFalse(Event.EventType.HURDLES_100M.carriesAStandard());
        assertFalse(Event.EventType.HURDLES_110M.carriesAStandard());
        // 400M is the boundary, and it is in: "400 or above" is the school's wording.
        assertTrue(Event.EventType.RUN_400M.carriesAStandard());
        // The 400M hurdles is a 400M race, and is in with it.
        assertTrue(Event.EventType.HURDLES_400M.carriesAStandard());
    }

    @Test
    @DisplayName("no relay carries one, whichever relay it is")
    void noRelayCarriesOne() {
        for (Event.EventType type : Event.EventType.values()) {
            if (type.getCategory() == EventCategory.RELAY) {
                assertFalse(type.carriesAStandard(),
                        type + " is a relay, run and scored by team, so it carries no standard");
            }
        }
        assertFalse(Event.EventType.RELAY_4X100M.carriesAStandard());
        assertFalse(Event.EventType.RELAY_4X400M.carriesAStandard());
    }

    @Test
    @DisplayName("every field event carries one, taken from the category rather than a second list")
    void everyFieldEventCarriesOne() {
        long fieldEvents = 0;
        for (Event.EventType type : Event.EventType.values()) {
            if (type.getCategory() == EventCategory.FIELD) {
                fieldEvents++;
                assertTrue(type.carriesAStandard(), type + " is a field event");
            }
        }
        assertEquals(8, fieldEvents, "the programme runs eight field events");
    }

    @Test
    @DisplayName("an event with no type carries none, so a half-built row cannot claim one")
    void anEventWithNoTypeCarriesNone() {
        Event bare = Event.builder().id(2L).name("No type").standard(new BigDecimal("12")).build();

        assertNull(bare.getType());
        EventDTO dto = EventDTO.from(bare);
        assertFalse(dto.getCarriesStandard());
        assertNull(dto.getStandard());
        assertNull(dto.getStandardLabel());
    }

    // -------------------------------------------------- the direction, at the line

    @Test
    @DisplayName("a track mark AT the standard is met; a hair over it is below")
    void aTrackMarkMeetsAtOrUnderTheStandard() {
        Event.EventType fourHundred = Event.EventType.RUN_400M;

        assertTrue(fourHundred.meetsStandard(new BigDecimal("60.000"), new BigDecimal("60")),
                "on the standard is met — the boundary the school asked about");
        assertTrue(fourHundred.meetsStandard(new BigDecimal("59.999"), new BigDecimal("60")),
                "under it is met");
        assertFalse(fourHundred.meetsStandard(new BigDecimal("60.001"), new BigDecimal("60")),
                "a hair over it is below");
        assertTrue(below(fourHundred, "60", "60.001"), "and reads as below");
        assertFalse(below(fourHundred, "60", "60.000"), "while the standard itself does not");
    }

    @Test
    @DisplayName("a field mark AT the standard is met; a hair under it is below")
    void aFieldMarkMeetsAtOrOverTheStandard() {
        Event.EventType shot = Event.EventType.SHOT_PUT;

        assertTrue(shot.meetsStandard(new BigDecimal("12.000"), new BigDecimal("12")),
                "on the standard is met");
        assertTrue(shot.meetsStandard(new BigDecimal("12.001"), new BigDecimal("12")),
                "over it is met");
        assertFalse(shot.meetsStandard(new BigDecimal("11.999"), new BigDecimal("12")),
                "a hair under it is below");
        assertTrue(below(shot, "12", "11.999"), "and reads as below");
        assertFalse(below(shot, "12", "12.000"), "while the standard itself does not");
    }

    @Test
    @DisplayName("the direction follows isLowerBetter, not the category, for every qualifying type")
    void theDirectionFollowsTheExistingRule() {
        for (Event.EventType type : QUALIFYING) {
            boolean lowerBetter = type.isLowerBetter();
            String standard = lowerBetter ? "60" : "12";
            // A mark one step the wrong way is below, and one step the right way is met.
            assertTrue(type.meetsStandard(new BigDecimal(standard), new BigDecimal(standard)),
                    type + ": the standard itself is met however the event is measured");
            assertFalse(
                    type.meetsStandard(
                            new BigDecimal(lowerBetter ? "60.001" : "11.999"),
                            new BigDecimal(standard)),
                    type + ": a mark past the standard in the losing direction is below");
        }
    }

    @Test
    @DisplayName("a missing mark, and an event with no standard, are never below standard")
    void nothingRecordedIsNeverBelow() {
        Event.EventType fourHundred = Event.EventType.RUN_400M;

        assertTrue(fourHundred.meetsStandard(null, new BigDecimal("60")),
                "no mark is not a failure");
        assertFalse(below(fourHundred, "60", null));
        assertTrue(fourHundred.meetsStandard(null, null), "and neither is no standard");
        assertFalse(below(fourHundred, null, "99"),
                "an event with no standard judges nothing, however slow the mark");
        assertFalse(below(fourHundred, null, null));
        assertTrue(Event.EventType.SHOT_PUT.meetsStandard(new BigDecimal("1"), null),
                "a field event with no standard judges nothing either");
    }

    // ------------------------------------------------------- out to the clients

    @Test
    @DisplayName("the event list says which events carry a standard, and reads the standard with its unit")
    void theEventDtoCarriesTheStandardAndItsUnit() {
        EventDTO track = EventDTO.from(event(Event.EventType.RUN_400M, "64.123"));
        assertEquals(0, new BigDecimal("64.123").compareTo(track.getStandard()));
        assertEquals("64.123 s", track.getStandardLabel());
        assertTrue(track.getCarriesStandard());

        EventDTO field = EventDTO.from(event(Event.EventType.SHOT_PUT, "12.500"));
        assertEquals("12.5 M", field.getStandardLabel(),
                "the number is trimmed and the unit is the event's own");
        assertTrue(field.getCarriesStandard());

        EventDTO none = EventDTO.from(event(Event.EventType.RUN_400M, null));
        assertNull(none.getStandard());
        assertNull(none.getStandardLabel(), "no standard, nothing to show");
        assertTrue(none.getCarriesStandard(), "but the event is still one that may carry one");
    }

    @Test
    @DisplayName("a sprint and a relay report no standard and no label, whatever the row holds")
    void aNonQualifyingEventReportsNothing() {
        EventDTO sprint = EventDTO.from(event(Event.EventType.RUN_100M, null));
        assertFalse(sprint.getCarriesStandard());
        assertNull(sprint.getStandard());
        assertNull(sprint.getStandardLabel());

        EventDTO relay = EventDTO.from(event(Event.EventType.RELAY_4X100M, null));
        assertFalse(relay.getCarriesStandard());

        // Belt and braces: a number left on a row by an older build is not published
        // as a standard on an event that cannot have one.
        EventDTO stale = EventDTO.from(event(Event.EventType.RUN_100M, "11.5"));
        assertNull(stale.getStandard());
        assertNull(stale.getStandardLabel());
    }

    @Test
    @DisplayName("the sheet's standard line reads in the sheet's own bilingual style, and is absent with no standard")
    void theSheetLineReadsBilingually() {
        assertEquals("標準 Standard 64.123 s",
                PdfSheetService.standardLine(EventGroupDTO.builder().standardLabel("64.123 s").build()));
        assertEquals("標準 Standard 12.5 M",
                PdfSheetService.standardLine(EventGroupDTO.builder().standardLabel("12.5 M").build()));
        assertNull(PdfSheetService.standardLine(EventGroupDTO.builder().build()),
                "no standard on the group, no line to print");
        assertNull(PdfSheetService.standardLine(EventGroupDTO.builder().standardLabel("  ").build()),
                "and a blank is no line either");
    }

    @Test
    @DisplayName("the standard is a header line, so it changes no sheet's columns or paper")
    void theStandardChangesNoColumn() {
        // The record and the standard are header lines. A column would have re-laid
        // out every sheet in the programme, including the ones with no standard, so
        // the column count and the width array are the same with and without one.
        EventGroupDTO trackHeat = EventGroupDTO.builder().category("TRACK").stage("HEAT").build();
        EventGroupDTO trackWithStandard = EventGroupDTO.builder()
                .category("TRACK").stage("HEAT").standardLabel("64.123 s").build();
        EventGroupDTO fieldHeat = EventGroupDTO.builder().category("FIELD").stage("HEAT").build();

        assertEquals(5, PdfSheetService.columnCount(trackHeat));
        assertEquals(5, PdfSheetService.columnCount(trackWithStandard),
                "the standard adds a line, not a column");
        assertEquals(7, PdfSheetService.columnCount(fieldHeat));
        assertEquals(5, PdfSheetService.widths(false, false, true).length,
                "a track heat's widths are exactly what they always were");
        assertEquals(7, PdfSheetService.widths(true, false, true).length);
    }
}
