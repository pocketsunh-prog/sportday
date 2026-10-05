package com.sportday.entity;

import com.sportday.dto.EventDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A relay event says what kind of relay it is — requirement 3, and the part of it
 * that must not disturb anything already in the programme.
 *
 * <p>{@code relayTeamKind} is nullable, and a relay without one is simply
 * <strong>undivided</strong>, which is how every relay event in the live catalogue
 * behaves. These tests pin that down, together with the size of a team and the
 * reserve ceiling that goes with it.</p>
 */
class EventRelayKindTest {

    private Event relay(RelayTeamKind kind) {
        return Event.builder()
                .id(1L)
                .name("Boys 4x100M Relay · B Grade")
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .grade(Grade.B)
                .eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512)
                .groupSize(24)
                .enabled(true)
                .relayTeamKind(kind)
                .build();
    }

    private Event sprint() {
        return Event.builder()
                .id(2L)
                .name("Boys 100M · B Grade")
                .type(Event.EventType.RUN_100M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .grade(Grade.B)
                .eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512)
                .groupSize(8)
                .enabled(true)
                .build();
    }

    // ============================================================== the kind

    @Test
    @DisplayName("a relay may be a form relay, a house relay, or undivided")
    void aRelayMayBeEitherKindOrNeither() {
        assertTrue(relay(RelayTeamKind.FORM).isRelay());
        assertEquals(RelayTeamKind.FORM, relay(RelayTeamKind.FORM).getRelayTeamKind());
        assertEquals(RelayTeamKind.HOUSE, relay(RelayTeamKind.HOUSE).getRelayTeamKind());
        assertNull(relay(null).getRelayTeamKind(), "an undivided relay keeps no kind");
        assertTrue(relay(null).isRelay());
    }

    @Test
    @DisplayName("the kind reads as the school's words, and both spellings of form are accepted")
    void kindLabelsAndParsing() {
        assertEquals("Form", RelayTeamKind.FORM.getLabel());
        assertEquals("班際", RelayTeamKind.FORM.getLabelZh());
        assertEquals("House", RelayTeamKind.HOUSE.getLabel());
        assertEquals("社際", RelayTeamKind.HOUSE.getLabelZh());

        assertEquals(RelayTeamKind.FORM, RelayTeamKind.fromCode("form"));
        assertEquals(RelayTeamKind.FORM, RelayTeamKind.fromCode(" CLASS "));
        assertEquals(RelayTeamKind.HOUSE, RelayTeamKind.fromCode("house"));
        assertNull(RelayTeamKind.fromCode("mixed"));
        assertNull(RelayTeamKind.fromCode(""));
        assertNull(RelayTeamKind.fromCode(null));
    }

    @Test
    @DisplayName("a class team reads as its class and a house team reads as the house itself")
    void teamLabels() {
        // A class team is named by its class, which is what the school writes on the
        // sheet — "Form 1A" is neither the class nor the form.
        assertEquals("1A", RelayTeamKind.FORM.labelFor("1A"));
        assertEquals("10B", RelayTeamKind.FORM.labelFor(" 10B "));
        assertEquals("C Grade Yellow", RelayTeamKind.HOUSE.labelFor("C Grade Yellow"));
        assertEquals("Red", RelayTeamKind.HOUSE.labelFor("Red"));
        assertEquals("Form", RelayTeamKind.FORM.labelFor(null));
    }

    // ============================================================== the size

    @Test
    @DisplayName("both relays run four legs, and an event that is not a relay has none")
    void relayLegs() {
        assertEquals(4, Event.EventType.RELAY_4X100M.getDefaultRelayLegs());
        assertEquals(4, Event.EventType.RELAY_4X400M.getDefaultRelayLegs());
        assertEquals(0, Event.EventType.RUN_100M.getDefaultRelayLegs());
        assertEquals(0, Event.EventType.SHOT_PUT.getDefaultRelayLegs());
    }

    @Test
    @DisplayName("a team is four legs by default and refuses a fifth runner unless reserves are on")
    void theSizeAndTheReserveCeiling() {
        Event undivided = relay(null);
        assertEquals(4, undivided.getEffectiveRelayTeamSize());
        assertEquals(4, undivided.getRelayMemberCap());
        assertFalse(undivided.isRelayReservesAllowed(), "a null reserve flag reads as no");

        Event withReserves = relay(null);
        withReserves.setRelayReservesAllowed(true);
        assertEquals(4, withReserves.getEffectiveRelayTeamSize());
        // Four runners and one backup: the allowance is a single reserve, not a second
        // squad. A team that could name four of them is not the team the school entered.
        assertEquals(5, withReserves.getRelayMemberCap());
    }

    @Test
    @DisplayName("an event may run a longer team, and the reserve ceiling follows it")
    void anEventMayRunALongerTeam() {
        Event longer = relay(RelayTeamKind.FORM);
        longer.setRelayTeamSize(6);
        assertEquals(6, longer.getEffectiveRelayTeamSize());
        assertEquals(6, longer.getRelayMemberCap());

        longer.setRelayReservesAllowed(true);
        assertEquals(7, longer.getRelayMemberCap());
    }

    @Test
    @DisplayName("a size that was never set falls back to the race's own four legs")
    void aMissingSizeFallsBackToTheRace() {
        Event stored = relay(RelayTeamKind.HOUSE);
        stored.setRelayTeamSize(null);
        assertEquals(4, stored.getEffectiveRelayTeamSize());

        stored.setRelayTeamSize(0);
        assertEquals(4, stored.getEffectiveRelayTeamSize(), "zero legs is not a team");
    }

    @Test
    @DisplayName("an event that is not a relay has no legs at all, whatever is stored on it")
    void aNonRelayHasNoLegs() {
        Event sprint = sprint();
        assertEquals(0, sprint.getEffectiveRelayTeamSize());
        assertEquals(0, sprint.getRelayMemberCap());
        assertFalse(sprint.isRelay());
    }

    // =============================================================== the DTO

    @Test
    @DisplayName("the event DTO carries the kind, the size and the reserve switch")
    void theDtoCarriesTheRelaySettings() {
        EventDTO dto = EventDTO.from(relay(RelayTeamKind.FORM));

        assertTrue(dto.getRelay());
        assertEquals("FORM", dto.getRelayTeamKind());
        assertEquals("Form", dto.getRelayTeamKindLabel());
        assertEquals(4, dto.getRelayTeamSize());
        assertEquals(4, dto.getRelayMemberCap());
        assertFalse(dto.getRelayReservesAllowed());
    }

    @Test
    @DisplayName("an undivided relay reports itself as a relay with no kind")
    void theDtoOfAnUndividedRelay() {
        EventDTO dto = EventDTO.from(relay(null));

        assertTrue(dto.getRelay());
        assertNull(dto.getRelayTeamKind());
        assertNull(dto.getRelayTeamKindLabel());
        assertEquals(4, dto.getRelayTeamSize());
    }

    @Test
    @DisplayName("a sprint reports no relay settings at all")
    void theDtoOfASprint() {
        EventDTO dto = EventDTO.from(sprint());

        assertFalse(dto.getRelay());
        assertNull(dto.getRelayTeamKind());
        assertEquals(0, dto.getRelayTeamSize());
        assertEquals(0, dto.getRelayMemberCap());
        assertFalse(dto.getRelayReservesAllowed());
    }
}
