package com.sportday.entity;

import com.sportday.service.MarkFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A relay is its own category.
 *
 * <p>The school's requirement: <em>"move relay from track type to create new type
 * relay"</em> — a relay is run and scored by <strong>team</strong> and never by an
 * individual, so it is neither a track event nor a field one, and the two relay
 * event types declare {@link EventCategory#RELAY}. The two ways of dividing a relay
 * are unchanged: one team per grade × house, or one per form and class.</p>
 *
 * <p>A third value is dangerous precisely because so much of the code asks a
 * two-way question — {@code TRACK} or {@code FIELD} — and a relay that fell through
 * the wrong side of one would become a <em>field</em> event: its marks read as
 * distances, its sheet given three attempts to fill in. The tests below pin the
 * decisions that had to be made, and the one that matters most is that a relay's
 * mark is still a <strong>time</strong>.</p>
 */
class EventCategoryRelayTest {

    @Test
    @DisplayName("the two relay types declare RELAY, and they are the only two that do")
    void onlyTheTwoRelayTypesAreRelays() {
        assertEquals(EventCategory.RELAY, Event.EventType.RELAY_4X100M.getCategory());
        assertEquals(EventCategory.RELAY, Event.EventType.RELAY_4X400M.getCategory());

        List<Event.EventType> relays = Arrays.stream(Event.EventType.values())
                .filter(type -> type.getCategory() == EventCategory.RELAY)
                .toList();
        assertEquals(List.of(Event.EventType.RELAY_4X100M, Event.EventType.RELAY_4X400M), relays,
                "the 4x100M and the 4x400M are the only relay types");
        // And no relay is left behind on TRACK.
        for (Event.EventType type : relays) {
            assertTrue(type.isRelay(), type + " is a relay by its own type rule too");
            assertNotEquals(EventCategory.TRACK, type.getCategory());
        }
    }

    @Test
    @DisplayName("a relay is neither the track nor the field family")
    void aRelayIsItsOwnFamily() {
        assertNotEquals(EventCategory.TRACK, Event.EventType.RELAY_4X100M.getCategory());
        assertNotEquals(EventCategory.FIELD, Event.EventType.RELAY_4X100M.getCategory());

        // The one helper the two-way tests were rewritten onto: only the field
        // family is measured, so a relay answers "not measured" — like the track.
        assertFalse(EventCategory.RELAY.isMeasuredInDistance());
        assertFalse(EventCategory.TRACK.isMeasuredInDistance());
        assertTrue(EventCategory.FIELD.isMeasuredInDistance());
    }

    @Test
    @DisplayName("a relay mark is a TIME, not a distance — the regression that matters most")
    void aRelayMarkIsATime() {
        // The event knows what it is, so the type decides before the stored unit does:
        // a 4x100M is a race, and reads as a time even if a mark carries a stray unit.
        assertTrue(MarkFormatter.isTime(Event.EventType.RELAY_4X100M, "s"));
        assertTrue(MarkFormatter.isTime(Event.EventType.RELAY_4X400M, "s"),
                "the event's own type decides, not the unit beside the mark");

        assertEquals("0.48.123s", MarkFormatter.formatWithUnit(
                new BigDecimal("48.123"), Event.EventType.RELAY_4X100M, "s"),
                "a relay is timed the school's way: minutes, seconds and milliseconds, "
                        + "a leading zero minute when it is under one");
        assertEquals("0.48.123s", MarkFormatter.formatWithUnit(
                new BigDecimal("48.123"), Event.EventType.RELAY_4X100M, "metres"),
                "a relay is never printed as a distance, whatever unit a row carries");
        assertTrue(MarkFormatter.formatWithUnit(
                        new BigDecimal("48.123"), Event.EventType.RELAY_4X100M, "s").endsWith("s"),
                "and its unit is the short one the sport uses");
        assertFalse(MarkFormatter.formatWithUnit(
                        new BigDecimal("48.123"), Event.EventType.RELAY_4X100M, "s").contains("M"),
                "a 4x100M time must never read as a distance");
    }

    @Test
    @DisplayName("a 4x400M reads exactly as a 400M does, minutes part and all")
    void aRelayTimeReadsLikeTheRaceItIs() {
        String asRelay = MarkFormatter.formatWithUnit(
                new BigDecimal("64.123"), Event.EventType.RELAY_4X400M, "s");
        String asFourHundred = MarkFormatter.formatWithUnit(
                new BigDecimal("64.123"), Event.EventType.RUN_400M, "s");
        String asDistance = MarkFormatter.formatWithUnit(
                new BigDecimal("64.123"), Event.EventType.SHOT_PUT, "M");

        assertEquals("1.04.123s", asRelay);
        assertEquals(asFourHundred, asRelay, "a relay is timed like the race it is");
        assertNotEquals(asDistance, asRelay, "and not like a throw");
    }

    @Test
    @DisplayName("a relay is decided the way a race is: the smallest time wins, timed in seconds")
    void aRelayIsLowerBetter() {
        assertTrue(Event.EventType.RELAY_4X100M.isLowerBetter());
        assertTrue(Event.EventType.RELAY_4X400M.isLowerBetter());
        assertFalse(Event.EventType.SHOT_PUT.isLowerBetter());

        assertEquals("s", Event.EventType.RELAY_4X100M.getDefaultUnit());
        assertEquals("s", Event.EventType.RELAY_4X400M.getDefaultUnit());
        assertEquals("M", Event.EventType.SHOT_PUT.getDefaultUnit());
    }

    @Test
    @DisplayName("a student may hold a house relay and a class relay: the allowance is two, not one")
    void theRelayAllowanceIsTwo() {
        // One team in the A grade house relay and one in the Form 1 class relay are
        // two events, and the school asks for both. Capped at the field's single
        // entry, the second would be refused outright.
        assertEquals(2, EventCategory.RELAY.getMaxEntriesPerStudent(),
                "a house relay and a class relay are two entries");
        assertNotEquals(1, EventCategory.RELAY.getMaxEntriesPerStudent());
        assertEquals(2, EventCategory.TRACK.getMaxEntriesPerStudent());
        assertEquals(1, EventCategory.FIELD.getMaxEntriesPerStudent());
    }

    @Test
    @DisplayName("the relay label reads 接力 Relay, in the style of 徑項/田項")
    void theRelayLabel() {
        assertEquals("接力", EventCategory.RELAY.getLabelZh());
        assertEquals("Relay", EventCategory.RELAY.getLabelEn());
        assertEquals("接力 Relay", EventCategory.RELAY.getLabel());
        // Consistent with the wording the rest of the app already uses for a relay
        // (接力項目 on the relay screens, 接力 Relay on the relay results sheet).
        assertEquals("徑項", EventCategory.TRACK.getLabelZh());
        assertEquals("田項", EventCategory.FIELD.getLabelZh());
        assertEquals(3, EventCategory.values().length);
    }

    @Test
    @DisplayName("fromCode accepts the relay category and its codes, and still rejects nonsense")
    void fromCodeAcceptsRelay() {
        assertEquals(EventCategory.RELAY, EventCategory.fromCode("RELAY"));
        assertEquals(EventCategory.RELAY, EventCategory.fromCode("relay"));
        assertEquals(EventCategory.RELAY, EventCategory.fromCode("  Relay  "));
        assertEquals(EventCategory.RELAY, EventCategory.fromCode("接力"));
        assertEquals(EventCategory.RELAY, EventCategory.fromCode("R"));
        // The event-type codes are accepted too, in the same style as the other two.
        assertEquals(EventCategory.RELAY, EventCategory.fromCode("RELAY_4X100M"));
        assertEquals(EventCategory.RELAY, EventCategory.fromCode("RELAY_4X400M"));
    }

    @Test
    @DisplayName("the two categories that were already there still parse, and nonsense is still refused")
    void fromCodeLeavesTheOldValuesAlone() {
        assertEquals(EventCategory.TRACK, EventCategory.fromCode("TRACK"));
        assertEquals(EventCategory.TRACK, EventCategory.fromCode("track"));
        assertEquals(EventCategory.TRACK, EventCategory.fromCode("T"));
        assertEquals(EventCategory.TRACK, EventCategory.fromCode("徑項"));
        assertEquals(EventCategory.FIELD, EventCategory.fromCode("FIELD"));
        assertEquals(EventCategory.FIELD, EventCategory.fromCode("F"));
        assertEquals(EventCategory.FIELD, EventCategory.fromCode("田項"));

        assertNull(EventCategory.fromCode(null), "no code at all is not a category");
        assertNull(EventCategory.fromCode(""));
        assertNull(EventCategory.fromCode("   "));
        assertNull(EventCategory.fromCode("hurdles"));
        assertNull(EventCategory.fromCode("4x100M"),
                "a distance is not a category, however much it looks like a relay");
        assertNull(EventCategory.fromCode("TEAMS"),
                "and neither is anything else the reader has no rule for");
    }

    @Test
    @DisplayName("an event stores the relay category its type declares")
    void anEventInheritsTheRelayCategory() {
        Event relay = Event.builder()
                .name("Boys 4x100M Relay · A Grade")
                .type(Event.EventType.RELAY_4X100M)
                .sex(Sex.MALE)
                .eventDate(java.time.LocalDate.of(2026, 11, 6))
                .build();
        relay.applyTypeDefaults();

        assertEquals(EventCategory.RELAY, relay.getCategory());
        assertEquals(EventCategory.RELAY, relay.getCategoryOrDefault());
        assertTrue(relay.isRelay());
    }
}
