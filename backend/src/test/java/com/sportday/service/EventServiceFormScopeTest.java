package com.sportday.service;

import com.sportday.dto.DraftRelayTeamRequest;
import com.sportday.dto.EventDTO;
import com.sportday.entity.Event;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The <strong>form</strong> an event may be scoped to — accepted, validated, exposed
 * and cleared.
 *
 * <p>Requirement, as confirmed: a form relay event is scoped to a form, so a "Form 1
 * 4x100M" holds the teams {@code 1A}, {@code 1B}, {@code 1C} and {@code 1D} from every
 * class of Form 1, and Form 2 is a separate event. The scope is written on the event,
 * so this is where it is read, refused and cleared.</p>
 *
 * <p>Two things must not break, and both are asserted here: <strong>a form on anything
 * that has no form teams is refused</strong> — a sprint, an undivided relay and a house
 * relay — rather than stored and ignored; and <strong>leaving the form out changes
 * nothing</strong>, because every relay already on the programme has no form and is
 * scoped by its grade exactly as it always was.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventServiceFormScopeTest {

    @Mock private EventRepository eventRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private EventGroupRepository eventGroupRepository;
    @Mock private EventResultRepository eventResultRepository;
    @Mock private SettingsService settingsService;
    @Mock private RecordService recordService;
    @Mock private SeasonService seasonService;
    @Mock private FinalQualificationService finalQualificationService;
    @Mock private RelayTeamService relayTeamService;
    /**
     * The readiness rule, mocked: these tests are about the form scope, so nothing
     * they touch is a half-built relay and the batch answer is empty.
     */
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

    private EventDTO request(Event.EventType type, String relayTeamKind, String form) {
        return EventDTO.builder()
                .type(type.name())
                .sex("MALE")
                .grade("B")
                .eventDate(LocalDate.of(2026, 10, 1))
                .relayTeamKind(relayTeamKind)
                .form(form)
                .build();
    }

    private Event existing(Event.EventType type, RelayTeamKind kind, String form) {
        return Event.builder()
                .id(7L)
                .name("Boys " + type.getDisplayName() + " · B Grade")
                .type(type)
                .category(type.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.B)
                .form(form)
                .eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512)
                .groupSize(type.getDefaultGroupSize())
                .enabled(true)
                .relayTeamKind(kind)
                .build();
    }

    // ================================================================ create

    @Test
    @DisplayName("a form relay is created with the form it is scoped to, and reports it")
    void createAcceptsAFormOnAFormRelay() {
        EventDTO created = service.createEvent(request(Event.EventType.RELAY_4X100M, "FORM", "1"));

        assertEquals("1", created.getForm());
        assertEquals("Form 1", created.getFormLabel());
        assertEquals("FORM", created.getRelayTeamKind());
        assertTrue(created.getRelay());

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(saved.capture());
        assertEquals("1", saved.getValue().getForm(), "and the row really carries it");
        assertTrue(saved.getValue().isFormScoped());
    }

    @Test
    @DisplayName("a form written with a leading zero is the same form the register reads")
    void createNormalisesTheForm() {
        EventDTO created = service.createEvent(request(Event.EventType.RELAY_4X100M, "FORM", "01"));

        // 01A is Form 1 by Student.formOf, so a caller sending 01 must get Form 1 rather
        // than a scope no student could ever match.
        assertEquals("1", created.getForm());
        assertEquals("Form 1", created.getFormLabel());
    }

    @Test
    @DisplayName("a create refuses a form on a non-relay")
    void createRefusesAFormOnANonRelay() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createEvent(request(Event.EventType.RUN_100M, null, "1")));

        assertTrue(error.getMessage().contains("100M"), error.getMessage());
        assertTrue(error.getMessage().contains("not a relay"), error.getMessage());
        assertTrue(error.getMessage().contains("form"), error.getMessage());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a create refuses a form on a house relay, which is divided by grade and house")
    void createRefusesAFormOnAHouseRelay() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createEvent(request(Event.EventType.RELAY_4X100M, "HOUSE", "1")));

        assertTrue(error.getMessage().contains("house relay"), error.getMessage());
        assertTrue(error.getMessage().contains("empty string"),
                "the way out is named: " + error.getMessage());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a create refuses a form on an undivided relay, and says which kind to send")
    void createRefusesAFormOnAnUndividedRelay() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createEvent(request(Event.EventType.RELAY_4X400M, null, "1")));

        assertTrue(error.getMessage().contains("not divided into form teams"),
                error.getMessage());
        assertTrue(error.getMessage().contains("FORM"), error.getMessage());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a create refuses a form that names no form")
    void createRefusesAFormThatIsNotANumber() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createEvent(request(Event.EventType.RELAY_4X100M, "FORM", "1A")));

        assertTrue(error.getMessage().contains("Unknown form"), error.getMessage());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a create refuses a form longer than the column, rather than letting the database refuse it")
    void createRefusesAnOverlongForm() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createEvent(request(Event.EventType.RELAY_4X100M, "FORM", "12345")));

        assertTrue(error.getMessage().contains("at most 4 digits"), error.getMessage());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a relay created without a form is scoped by its grade, as every live relay is")
    void createWithoutAFormInvitesNoScope() {
        EventDTO created = service.createEvent(request(Event.EventType.RELAY_4X100M, "FORM", null));

        assertNull(created.getForm());
        assertNull(created.getFormLabel());

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(saved.capture());
        assertNull(saved.getValue().getForm());
        assertFalse(saved.getValue().isFormScoped());

        // A blank string on a create is no scope either — there is nothing to clear.
        EventDTO blank = service.createEvent(request(Event.EventType.RELAY_4X100M, "FORM", "  "));
        assertNull(blank.getForm());
    }

    // ================================================================ update

    @Test
    @DisplayName("an update sets a form on a form relay")
    void updateSetsTheForm() {
        when(eventRepository.findById(7L)).thenReturn(
                Optional.of(existing(Event.EventType.RELAY_4X100M, RelayTeamKind.FORM, null)));

        EventDTO updated = service.updateEvent(7L,
                request(Event.EventType.RELAY_4X100M, null, "3"));

        assertEquals("3", updated.getForm());
        assertEquals("Form 3", updated.getFormLabel());
    }

    @Test
    @DisplayName("an empty string clears the form, which is how a form relay becomes a graded one")
    void updateClearsTheForm() {
        Event event = existing(Event.EventType.RELAY_4X100M, RelayTeamKind.FORM, "1");
        when(eventRepository.findById(7L)).thenReturn(Optional.of(event));

        EventDTO updated = service.updateEvent(7L,
                request(Event.EventType.RELAY_4X100M, null, ""));

        assertNull(updated.getForm());
        assertNull(updated.getFormLabel());
        assertNull(event.getForm(), "the row is cleared too");
        assertFalse(event.isFormScoped());
    }

    @Test
    @DisplayName("leaving the form out of an update does not disturb the one that is there")
    void updateLeavesTheFormAloneWhenItIsNotSent() {
        Event event = existing(Event.EventType.RELAY_4X100M, RelayTeamKind.FORM, "2");
        when(eventRepository.findById(7L)).thenReturn(Optional.of(event));

        EventDTO updated = service.updateEvent(7L,
                request(Event.EventType.RELAY_4X100M, null, null));

        assertEquals("2", updated.getForm());
        assertEquals("Form 2", updated.getFormLabel());
    }

    @Test
    @DisplayName("an update refuses to give a house relay a form")
    void updateRefusesAFormOnAHouseRelay() {
        when(eventRepository.findById(7L)).thenReturn(
                Optional.of(existing(Event.EventType.RELAY_4X100M, RelayTeamKind.HOUSE, null)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateEvent(7L, request(Event.EventType.RELAY_4X100M, null, "1")));

        assertTrue(error.getMessage().contains("house relay"), error.getMessage());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a form relay cannot become a house relay while it keeps its form")
    void updateRefusesAReKindThatWouldLeaveAFormOnAHouseRelay() {
        Event event = existing(Event.EventType.RELAY_4X100M, RelayTeamKind.FORM, "1");
        when(eventRepository.findById(7L)).thenReturn(Optional.of(event));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateEvent(7L,
                        request(Event.EventType.RELAY_4X100M, "HOUSE", null)));

        assertTrue(error.getMessage().contains("house relay"), error.getMessage());
        assertTrue(error.getMessage().contains("empty string"),
                "the way out is named: " + error.getMessage());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("clearing the form in the same request lets the re-kind through")
    void updateAllowsTheReKindWhenTheFormGoesWithIt() {
        Event event = existing(Event.EventType.RELAY_4X100M, RelayTeamKind.FORM, "1");
        when(eventRepository.findById(7L)).thenReturn(Optional.of(event));

        EventDTO updated = service.updateEvent(7L,
                request(Event.EventType.RELAY_4X100M, "HOUSE", ""));

        assertEquals("HOUSE", updated.getRelayTeamKind());
        assertNull(updated.getForm());
    }

    @Test
    @DisplayName("a form relay cannot become a sprint while it keeps its form")
    void updateRefusesASprintThatWouldKeepAForm() {
        Event event = existing(Event.EventType.RELAY_4X100M, RelayTeamKind.FORM, "1");
        when(eventRepository.findById(7L)).thenReturn(Optional.of(event));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateEvent(7L, request(Event.EventType.RUN_100M, "", null)));

        assertTrue(error.getMessage().contains("not a relay"), error.getMessage());
        assertTrue(error.getMessage().contains("empty string"), error.getMessage());
    }

    // ================================================= a draft, which is a relay too

    @Test
    @DisplayName("a draft relay event may be scoped to a form, and reports it")
    void aDraftMayBeScopedToAForm() {
        DraftRelayTeamRequest request = DraftRelayTeamRequest.builder()
                .type(Event.EventType.RELAY_4X100M.name())
                .sex("MALE")
                .grade("B")
                .relayTeamKind("FORM")
                .form("1")
                .eventDate(LocalDate.of(2026, 10, 1))
                .build();

        EventDTO created = service.createDraftEvent(request);

        assertTrue(created.getDraft());
        assertEquals("1", created.getForm());
        assertEquals("Form 1", created.getFormLabel());

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(saved.capture());
        assertEquals("1", saved.getValue().getForm());
    }

    @Test
    @DisplayName("a house draft is refused a form, exactly as a house relay is")
    void aHouseDraftMayNotBeScopedToAForm() {
        DraftRelayTeamRequest request = DraftRelayTeamRequest.builder()
                .type(Event.EventType.RELAY_4X100M.name())
                .sex("MALE").grade("B").relayTeamKind("HOUSE").form("1")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createDraftEvent(request));

        assertTrue(error.getMessage().contains("house relay"), error.getMessage());
        verify(eventRepository, never()).save(any());
    }
}
