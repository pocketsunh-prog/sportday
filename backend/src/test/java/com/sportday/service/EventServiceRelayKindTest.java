package com.sportday.service;

import com.sportday.dto.EventDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Grade;
import com.sportday.entity.RelayTeamKind;
import com.sportday.entity.Sex;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * An event may be told what kind of relay it is — and only a relay may be.
 *
 * <p>Requirement 3 adds {@code relayTeamKind} to an event. The two things that must
 * not break are asserted here: an undivided relay stays undivided (the live
 * catalogue's relays are not given a kind behind the school's back), and a non-relay
 * is refused a kind rather than storing one nothing could ever fill.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventServiceRelayKindTest {

    @Mock private EventRepository eventRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private EventGroupRepository eventGroupRepository;
    @Mock private EventResultRepository eventResultRepository;
    @Mock private SettingsService settingsService;
    @Mock private RecordService recordService;
    @Mock private SeasonService seasonService;
    @Mock private FinalQualificationService finalQualificationService;
    @Mock private RelayTeamService relayTeamService;

    @InjectMocks private EventService service;

    @BeforeEach
    void setUp() {
        when(eventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(settingsService.maxEntriesFor(any())).thenReturn(2);
        when(seasonService.currentSeason()).thenReturn(null);
        when(enrollmentRepository.countByEventIdAndStatus(any(), any())).thenReturn(0L);
        when(enrollmentRepository.countUngroupedByEvent(any(), any())).thenReturn(0L);
        when(eventGroupRepository.countByEventId(any())).thenReturn(0L);
        when(relayTeamService.countTeamsForEvent(any())).thenReturn(0L);
    }

    private EventDTO request(Event.EventType type, String relayTeamKind) {
        return EventDTO.builder()
                .type(type.name())
                .sex("MALE")
                .grade("B")
                .eventDate(LocalDate.of(2026, 10, 1))
                .relayTeamKind(relayTeamKind)
                .build();
    }

    private Event existing(Event.EventType type, RelayTeamKind kind) {
        return Event.builder()
                .id(7L)
                .name(type.getDisplayName() + " · B Grade")
                .type(type)
                .category(type.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.B)
                .eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512)
                .groupSize(type.getDefaultGroupSize())
                .enabled(true)
                .relayTeamKind(kind)
                .build();
    }

    // ================================================================ create

    @Test
    @DisplayName("a create refuses to give a non-relay a relay team kind")
    void createRefusesAKindOnANonRelay() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createEvent(request(Event.EventType.RUN_100M, "HOUSE")));

        assertTrue(error.getMessage().contains("100M"), error.getMessage());
        assertTrue(error.getMessage().contains("not a relay"), error.getMessage());
        assertTrue(error.getMessage().contains("4x100M"), error.getMessage());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a create refuses a relay team kind nobody recognises")
    void createRefusesAnUnknownKind() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createEvent(request(Event.EventType.RELAY_4X100M, "MIXED")));

        assertTrue(error.getMessage().contains("Unknown relay team kind"), error.getMessage());
    }

    @Test
    @DisplayName("a form relay is created with its kind, four legs and no reserves")
    void createAcceptsARelayKind() {
        EventDTO created = service.createEvent(request(Event.EventType.RELAY_4X100M, "FORM"));

        assertEquals("FORM", created.getRelayTeamKind());
        assertEquals("Form", created.getRelayTeamKindLabel());
        assertTrue(created.getRelay());
        assertEquals(4, created.getRelayTeamSize());
        assertEquals(4, created.getRelayMemberCap());
        assertFalse(created.getRelayReservesAllowed());
    }

    @Test
    @DisplayName("a relay created without a kind stays undivided, as the catalogue's relays are")
    void createLeavesARelayUndividedByDefault() {
        EventDTO created = service.createEvent(request(Event.EventType.RELAY_4X400M, null));

        assertTrue(created.getRelay());
        assertNull(created.getRelayTeamKind(), "no kind was asked for, so none is invented");
        assertEquals(4, created.getRelayTeamSize(), "but a team is still four legs");
    }

    @Test
    @DisplayName("a create honours a longer team and an explicit reserve switch")
    void createHonoursTeamSizeAndReserves() {
        EventDTO supplied = request(Event.EventType.RELAY_4X100M, "HOUSE");
        supplied.setRelayTeamSize(6);
        supplied.setRelayReservesAllowed(true);

        EventDTO created = service.createEvent(supplied);

        assertEquals(6, created.getRelayTeamSize());
        assertEquals(12, created.getRelayMemberCap());
        assertTrue(created.getRelayReservesAllowed());
    }

    @Test
    @DisplayName("a team size nobody could run is refused rather than stored")
    void createRefusesAnAbsurdTeamSize() {
        EventDTO supplied = request(Event.EventType.RELAY_4X100M, "FORM");
        supplied.setRelayTeamSize(400);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createEvent(supplied));
        assertTrue(error.getMessage().contains("at most 16 legs"), error.getMessage());
    }

    @Test
    @DisplayName("a non-relay silently keeps no relay size or reserve switch")
    void createIgnoresRelaySettingsOnANonRelay() {
        EventDTO supplied = request(Event.EventType.RUN_100M, null);
        supplied.setRelayTeamSize(6);
        supplied.setRelayReservesAllowed(true);

        EventDTO created = service.createEvent(supplied);

        assertEquals(0, created.getRelayTeamSize());
        assertFalse(created.getRelayReservesAllowed());
    }

    // ================================================================ update

    @Test
    @DisplayName("an update refuses to give a non-relay a relay team kind")
    void updateRefusesAKindOnANonRelay() {
        when(eventRepository.findById(7L)).thenReturn(Optional.of(existing(Event.EventType.RUN_100M, null)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateEvent(7L, request(Event.EventType.RUN_100M, "FORM")));
        assertTrue(error.getMessage().contains("not a relay"), error.getMessage());
    }

    @Test
    @DisplayName("an update refuses to change what kind of relay an event is while it has teams")
    void updateRefusesAReKindWithTeamsInPlace() {
        when(eventRepository.findById(7L)).thenReturn(
                Optional.of(existing(Event.EventType.RELAY_4X100M, RelayTeamKind.FORM)));
        when(relayTeamService.countTeamsForEvent(7L)).thenReturn(2L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.updateEvent(7L, request(Event.EventType.RELAY_4X100M, "HOUSE")));

        assertTrue(error.getMessage().contains("already has 2 relay team(s)"), error.getMessage());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("an empty string clears the kind, which is how a relay becomes undivided again")
    void updateClearsTheKind() {
        Event event = existing(Event.EventType.RELAY_4X100M, RelayTeamKind.FORM);
        when(eventRepository.findById(7L)).thenReturn(Optional.of(event));

        EventDTO updated = service.updateEvent(7L, request(Event.EventType.RELAY_4X100M, ""));

        assertNull(updated.getRelayTeamKind());
        assertNull(event.getRelayTeamKind());
        assertEquals(4, updated.getRelayTeamSize(), "it is still a relay with four-leg teams");
    }

    @Test
    @DisplayName("leaving the kind out of an update does not disturb the one that is there")
    void updateLeavesTheKindAloneWhenItIsNotSent() {
        Event event = existing(Event.EventType.RELAY_4X100M, RelayTeamKind.HOUSE);
        when(eventRepository.findById(7L)).thenReturn(Optional.of(event));

        EventDTO updated = service.updateEvent(7L, request(Event.EventType.RELAY_4X100M, null));

        assertEquals("HOUSE", updated.getRelayTeamKind());
    }

    @Test
    @DisplayName("turning a relay into a sprint while it still carries a kind is refused, with the way out")
    void updateRefusesARelayThatWouldBecomeASprintWithAKind() {
        when(eventRepository.findById(7L)).thenReturn(
                Optional.of(existing(Event.EventType.RELAY_4X100M, RelayTeamKind.HOUSE)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateEvent(7L, request(Event.EventType.RUN_100M, null)));

        assertTrue(error.getMessage().contains("not a relay"), error.getMessage());
        assertTrue(error.getMessage().contains("empty string"), error.getMessage());
    }

    @Test
    @DisplayName("clearing the kind in the same request lets the type change through")
    void updateAllowsTheTypeChangeWhenTheKindGoesWithIt() {
        Event event = existing(Event.EventType.RELAY_4X100M, RelayTeamKind.HOUSE);
        event.setRelayTeamSize(4);
        event.setRelayReservesAllowed(true);
        when(eventRepository.findById(7L)).thenReturn(Optional.of(event));

        EventDTO updated = service.updateEvent(7L, request(Event.EventType.RUN_100M, ""));

        assertFalse(updated.getRelay());
        assertNull(updated.getRelayTeamKind());
        assertEquals(0, updated.getRelayTeamSize());
        assertFalse(updated.getRelayReservesAllowed(), "a sprint allows no relay reserves");
    }

    @Test
    @DisplayName("an update may raise the team size and allow reserves on a relay")
    void updateSetsTheTeamSizeAndReserves() {
        when(eventRepository.findById(7L)).thenReturn(
                Optional.of(existing(Event.EventType.RELAY_4X400M, RelayTeamKind.FORM)));
        EventDTO supplied = request(Event.EventType.RELAY_4X400M, null);
        supplied.setRelayTeamSize(5);
        supplied.setRelayReservesAllowed(true);

        EventDTO updated = service.updateEvent(7L, supplied);

        assertEquals(5, updated.getRelayTeamSize());
        assertEquals(10, updated.getRelayMemberCap());
        assertTrue(updated.getRelayReservesAllowed());
    }

    // ================================================================ delete

    @Test
    @DisplayName("deleting an event takes its relay teams with it")
    void deleteRemovesTheTeams() {
        Event event = existing(Event.EventType.RELAY_4X100M, RelayTeamKind.FORM);
        when(eventRepository.findById(7L)).thenReturn(Optional.of(event));
        when(relayTeamService.removeTeamsForEvent(7L)).thenReturn(3);

        service.deleteEvent(7L);

        verify(relayTeamService).removeTeamsForEvent(7L);
        verify(eventRepository).delete(event);
    }

    @Test
    @DisplayName("a relay event made a sprint has its relay size and reserves switched off")
    void relaySettingsAreClearedByTheEntityItself() {
        Event event = existing(Event.EventType.RELAY_4X100M, null);
        event.setRelayTeamSize(4);
        event.setRelayReservesAllowed(true);
        when(eventRepository.findById(7L)).thenReturn(Optional.of(event));

        service.updateEvent(7L, request(Event.EventType.RUN_100M, ""));

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(saved.capture());
        assertNull(saved.getValue().getRelayTeamSize());
        assertFalse(saved.getValue().isRelayReservesAllowed());
    }
}
