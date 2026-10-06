package com.sportday.service;

import com.sportday.dto.EventDTO;
import com.sportday.entity.Event;
import com.sportday.entity.Grade;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Setting and clearing an event's <strong>required standard</strong> through
 * {@code PUT /api/events/{id}}.
 *
 * <p>The requirement is that an administrator can set a standard on a qualifying
 * event and <strong>clear it again</strong>, and that leaving the field out of a
 * request changes nothing — several callers already send partial bodies (the relay
 * board sends a kind and a form and nothing else), and a missing field must never
 * quietly wipe a number somebody typed.</p>
 *
 * <p>So the three cases are distinct and are all asserted here: a number sets it,
 * {@code clearStandard} removes it, and <em>neither</em> leaves it exactly as it
 * was. A standard is also refused on an event that does not carry one — judged on
 * the event the update would leave behind, so a standard cannot be smuggled onto a
 * sprint by changing the type in the same request — and a standard must be greater
 * than zero, because a time of zero is a typo rather than a qualifying mark.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventStandardUpdateTest {

    private static final long EVENT_ID = 7L;

    @Mock private EventRepository eventRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private EventGroupRepository eventGroupRepository;
    @Mock private EventResultRepository eventResultRepository;
    @Mock private SettingsService settingsService;
    @Mock private RecordService recordService;
    @Mock private SeasonService seasonService;
    @Mock private FinalQualificationService finalQualificationService;
    @Mock private RelayTeamService relayTeamService;
    @Mock private RelayReadiness relayReadiness;

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
        when(relayReadiness.shortfallsOf(any())).thenReturn(Map.of());
    }

    private static Event existing(Event.EventType type, String standard) {
        return Event.builder()
                .id(EVENT_ID)
                .name("Boys " + type.getDisplayName() + " · B Grade")
                .type(type)
                .category(type.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.B)
                .standard(standard == null ? null : new BigDecimal(standard))
                .eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512)
                .groupSize(type.getDefaultGroupSize())
                .enabled(true)
                .build();
    }

    /** A request that only carries the standard — what the standards page sends. */
    private static EventDTO standardRequest(String standard) {
        return EventDTO.builder()
                .standard(standard == null ? null : new BigDecimal(standard))
                .build();
    }

    // ------------------------------------------------------------------- set

    @Test
    @DisplayName("a standard is set on a qualifying event and reported with its unit")
    void setsTheStandard() {
        Event event = existing(Event.EventType.RUN_400M, null);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        EventDTO updated = service.updateEvent(EVENT_ID, standardRequest("64.123"));

        assertEquals(0, new BigDecimal("64.123").compareTo(updated.getStandard()));
        assertEquals("64.123 s", updated.getStandardLabel());
        assertTrue(updated.getCarriesStandard());

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(saved.capture());
        assertEquals(0, new BigDecimal("64.123").compareTo(saved.getValue().getStandard()),
                "and the row really carries it");
    }

    @Test
    @DisplayName("a field standard is set the same way, in metres")
    void setsAFieldStandard() {
        Event event = existing(Event.EventType.SHOT_PUT, null);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        EventDTO updated = service.updateEvent(EVENT_ID, standardRequest("12.5"));

        assertEquals("12.5 M", updated.getStandardLabel());
        assertEquals(0, new BigDecimal("12.5").compareTo(updated.getStandard()));
    }

    @Test
    @DisplayName("a standard replaces the one before it")
    void replacesTheStandard() {
        Event event = existing(Event.EventType.RUN_800M, "150");
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        EventDTO updated = service.updateEvent(EVENT_ID, standardRequest("145.5"));

        assertEquals(0, new BigDecimal("145.5").compareTo(updated.getStandard()));
        assertEquals(0, new BigDecimal("145.5").compareTo(event.getStandard()));
    }

    // ----------------------------------------------------------------- clear

    @Test
    @DisplayName("clearStandard removes the standard, which is the only way to say 'none'")
    void clearsTheStandard() {
        Event event = existing(Event.EventType.RUN_400M, "64.123");
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        EventDTO updated = service.updateEvent(EVENT_ID,
                EventDTO.builder().clearStandard(true).build());

        assertNull(updated.getStandard(), "the event carries no standard again");
        assertNull(updated.getStandardLabel());
        assertNull(event.getStandard(), "and the row is cleared too");
        assertTrue(updated.getCarriesStandard(),
                "though the event is still one that may be given a standard later");
    }

    @Test
    @DisplayName("clearing is allowed on an event that never had one, and changes nothing")
    void clearingWithoutOneIsHarmless() {
        Event event = existing(Event.EventType.RUN_100M, null);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        EventDTO updated = service.updateEvent(EVENT_ID,
                EventDTO.builder().clearStandard(true).build());

        assertNull(updated.getStandard());
        assertFalse(updated.getCarriesStandard(), "a 100M still carries no standard");
    }

    // ------------------------------------------------------------ leave alone

    @Test
    @DisplayName("a request that does not mention the standard leaves the one in place")
    void leavesTheStandardAloneWhenItIsNotSent() {
        Event event = existing(Event.EventType.RUN_1500M, "300");
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        // Exactly what the relay board and the event edit page send: no standard.
        EventDTO updated = service.updateEvent(EVENT_ID,
                EventDTO.builder().description("Moved to the main track").build());

        assertEquals(0, new BigDecimal("300").compareTo(updated.getStandard()),
                "the number somebody typed is still there");
        assertEquals("300 s", updated.getStandardLabel());
        assertNull(updated.getClearStandard(), "and nothing asked for it to be cleared");
    }

    @Test
    @DisplayName("an event with no standard is described exactly as it always was")
    void anEventWithNoStandardDescribesAsBefore() {
        Event event = existing(Event.EventType.RUN_100M, null);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        EventDTO updated = service.updateEvent(EVENT_ID, EventDTO.builder().build());

        assertNull(updated.getStandard());
        assertNull(updated.getStandardLabel());
        assertFalse(updated.getCarriesStandard());
        assertNull(event.getStandard());
    }

    // --------------------------------------------------------------- refused

    @Test
    @DisplayName("a standard on a 100M is refused, and the refusal names the rule")
    void refusesAStandardOnASprint() {
        Event event = existing(Event.EventType.RUN_100M, null);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateEvent(EVENT_ID, standardRequest("11.5")));

        assertTrue(error.getMessage().contains("100M"),
                "the refusal names the event type: " + error.getMessage());
        assertTrue(error.getMessage().contains("carries a required standard"),
                error.getMessage());
        assertTrue(error.getMessage().contains("400M"),
                "and says which events do: " + error.getMessage());
        assertTrue(error.getMessage().contains("field"), error.getMessage());
        // Nothing is stored: the refusal is thrown inside the update's transaction, so
        // the row keeps whatever it had.
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a standard on a relay is refused: a relay is its own category, run by team")
    void refusesAStandardOnARelay() {
        Event event = existing(Event.EventType.RELAY_4X100M, null);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateEvent(EVENT_ID, standardRequest("50")));

        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a standard of zero or less is refused: it is a typo, not a qualifying mark")
    void refusesAStandardThatIsNotPositive() {
        Event event = existing(Event.EventType.RUN_800M, null);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> service.updateEvent(EVENT_ID, standardRequest("0")));
        assertTrue(zero.getMessage().contains("greater than zero"), zero.getMessage());

        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> service.updateEvent(EVENT_ID, standardRequest("-5")));
        assertTrue(negative.getMessage().contains("greater than zero"), negative.getMessage());

        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a 400M cannot become a 100M while it keeps its standard")
    void refusesATypeChangeThatWouldKeepAStandard() {
        Event event = existing(Event.EventType.RUN_400M, "64.123");
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        // Judged on the event the update would leave behind: a 100M with a standard
        // is not an event that can have one, however it was reached.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateEvent(EVENT_ID,
                        EventDTO.builder().type(Event.EventType.RUN_100M.name()).build()));

        assertTrue(error.getMessage().contains("100M"), error.getMessage());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a 400M may become a 100M when the standard is cleared in the same request")
    void allowsTheTypeChangeWhenTheStandardGoesWithIt() {
        Event event = existing(Event.EventType.RUN_400M, "64.123");
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        EventDTO updated = service.updateEvent(EVENT_ID, EventDTO.builder()
                .type(Event.EventType.RUN_100M.name())
                .clearStandard(true)
                .build());

        assertEquals("RUN_100M", updated.getType());
        assertNull(updated.getStandard());
        assertFalse(updated.getCarriesStandard());
    }
}
