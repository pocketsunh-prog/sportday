package com.sportday.service;

import com.sportday.dto.DraftRelayTeamRequest;
import com.sportday.dto.EventDTO;
import com.sportday.dto.RelayTeamCreateRequest;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Grade;
import com.sportday.entity.RelayTeamKind;
import com.sportday.entity.Sex;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.StandardDefaultRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A <strong>draft</strong> relay event: the event a school builds its hand-made relay
 * teams on before the race itself exists.
 *
 * <p>The school's requirement, as confirmed: "create relay event base on selected relay
 * team". The teams come first, and a relay team cannot exist without an event, so the
 * teams are collected on a draft. This asserts the two things that make a draft
 * honest:</p>
 *
 * <ol>
 *   <li><strong>a draft must not look like a real event to the school.</strong> It is
 *       excluded from the programme listing, from the dates the picker offers and from
 *       the past-events list, while a non-draft event on the same date is not — and it
 *       <em>is</em> the one listing a draft belongs in
 *       ({@code getDraftEvents}). Every existing row reads as a real event, because
 *       the flag is nullable and null reads as false;</li>
 *   <li><strong>a draft's teams cannot be destroyed as a side effect.</strong> Deleting
 *       an event removes its teams with it, so deleting a draft that still holds the
 *       teams it was made for is refused outright.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventServiceDraftTest {

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
     * The readiness rule, mocked: a draft may well be a half-built relay, but what a
     * draft's readiness <em>is</em> belongs to {@code RelayReadiness}, so the answer is
     * handed in rather than worked out here.
     */
    @Mock private RelayReadiness relayReadiness;
    /** The school's per-grade default standard, read when an event is created. */
    @Mock private StandardDefaultRepository standardDefaultRepository;

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

    /** An event on the programme, or a draft of one when told. */
    private static Event event(Long id, String name, Event.EventType type, Grade grade,
                               Boolean draft) {
        return Event.builder()
                .id(id)
                .name(name)
                .type(type)
                .category(type.getCategory())
                .sex(Sex.MALE)
                .grade(grade)
                .eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512)
                .groupSize(type.getDefaultGroupSize())
                .enabled(true)
                .draft(draft)
                .build();
    }

    /** A draft relay event, and the real relay event of the same race. */
    private static Event draft(Long id) {
        return event(id, "Boys 4x100M Relay · B Grade (teams)", Event.EventType.RELAY_4X100M,
                Grade.B, true);
    }

    private static Event real(Long id) {
        return event(id, "Boys 4x100M Relay · B Grade", Event.EventType.RELAY_4X100M, Grade.B, false);
    }

    // ============================================= a draft is not on the programme

    @Test
    @DisplayName("a draft event is excluded from the programme listing, and a real one is not")
    void aDraftIsNotOnTheProgramme() {
        Event real = real(1L);
        Event draft = draft(2L);
        when(eventRepository.findAll()).thenReturn(List.of(real, draft));

        List<EventDTO> programme = service.searchEvents(false, null, null);

        assertEquals(List.of(1L), programme.stream().map(EventDTO::getId).toList(),
                "the programme is the real event only: " + programme.stream()
                        .map(EventDTO::getName).toList());
    }

    @Test
    @DisplayName("the same holds with onlyEnabled, which is the filter the entry page uses")
    void aDraftIsNotOfferedForEntry() {
        Event real = real(1L);
        Event draft = draft(2L);
        when(eventRepository.findByEnabledTrue()).thenReturn(List.of(real, draft));

        // The fixture is a 4x100M relay, which is its own category now — not TRACK.
        List<EventDTO> enabled = service.searchEvents(true, Sex.MALE, EventCategory.RELAY);

        assertEquals(List.of(1L), enabled.stream().map(EventDTO::getId).toList(),
                "a draft never offers entry, however enabled it is");
    }

    @Test
    @DisplayName("a draft puts no date on the picker, and is not counted on one there")
    void aDraftIsNotOnTheDatePicker() {
        Event real = real(1L);
        Event draft = draft(2L);
        // A second real event on another day, so the counts are not all one.
        Event otherReal = Event.builder()
                .id(3L).name("Boys 100M · B Grade").type(Event.EventType.RUN_100M)
                .category(EventCategory.TRACK).sex(Sex.MALE).grade(Grade.B)
                .eventDate(LocalDate.of(2026, 10, 2)).maxParticipants(512).groupSize(8)
                .enabled(true).build();
        Event otherDraft = Event.builder()
                .id(4L).name("Girls 4x100M Relay · B Grade (teams)")
                .type(Event.EventType.RELAY_4X100M).category(EventCategory.TRACK)
                .sex(Sex.FEMALE).grade(Grade.B).eventDate(LocalDate.of(2026, 10, 3))
                .maxParticipants(512).groupSize(24).enabled(true).draft(true).build();
        when(eventRepository.findAll()).thenReturn(List.of(real, draft, otherReal, otherDraft));

        List<java.util.Map<String, Object>> dates = service.getEventDates();

        assertEquals(2, dates.size(), "only the days the programme runs on: " + dates);
        assertEquals("2026-10-02", dates.get(0).get("date"));
        assertEquals(1L, dates.get(0).get("eventCount"));
        assertEquals("2026-10-01", dates.get(1).get("date"));
        assertEquals(1L, dates.get(1).get("eventCount"),
                "the draft on the same day is not counted");
    }

    @Test
    @DisplayName("a draft is not in the past-events list, even once its date has been")
    void aDraftIsNotInThePastEvents() {
        Event pastDraft = Event.builder()
                .id(2L).name("Boys 4x100M Relay · B Grade (teams)")
                .type(Event.EventType.RELAY_4X100M).category(EventCategory.TRACK)
                .sex(Sex.MALE).grade(Grade.B).eventDate(LocalDate.now().minusDays(1))
                .maxParticipants(512).groupSize(24).enabled(true).draft(true).build();
        Event pastReal = Event.builder()
                .id(1L).name("Boys 4x100M Relay · B Grade")
                .type(Event.EventType.RELAY_4X100M).category(EventCategory.TRACK)
                .sex(Sex.MALE).grade(Grade.B).eventDate(LocalDate.now().minusDays(1))
                .maxParticipants(512).groupSize(24).enabled(true).build();
        when(eventRepository.findAll()).thenReturn(List.of(pastReal, pastDraft));

        List<EventDTO> past = service.getPastEvents();

        assertEquals(List.of(1L), past.stream().map(EventDTO::getId).toList(),
                "looking back must not show a race whose teams are still being collected");
    }

    @Test
    @DisplayName("the administrator's draft list is the one listing a draft belongs in")
    void aDraftIsListedWithTheDrafts() {
        when(eventRepository.findAll()).thenReturn(List.of(real(1L), draft(2L), draft(3L)));

        List<EventDTO> drafts = service.getDraftEvents();

        assertEquals(List.of(2L, 3L), drafts.stream().map(EventDTO::getId).sorted().toList(),
                "the drafts, and nothing else");
        assertTrue(drafts.stream().allMatch(EventDTO::getDraft),
                "each one says it is a draft, so a client needs no second source");
    }

    @Test
    @DisplayName("an event written before the flag existed is a real event, not a draft")
    void aRowWithNoFlagIsARealEvent() {
        Event legacy = event(1L, "Boys 100M · B Grade", Event.EventType.RUN_100M, Grade.B, null);
        when(eventRepository.findAll()).thenReturn(List.of(legacy));

        assertFalse(legacy.isDraft(), "null reads as false");
        assertEquals(List.of(1L), service.searchEvents(false, null, null).stream()
                .map(EventDTO::getId).toList());
        assertFalse(service.getEventDates().isEmpty(), "and it is still on the programme");
        assertFalse(service.getPastEvents().isEmpty(), "and still in the past list");
        assertEquals(List.of(), service.getDraftEvents());
    }

    // ================================================ creating a draft relay event

    @Test
    @DisplayName("a draft relay event is created with the teams given, and is marked a draft")
    void aDraftIsCreatedAroundItsTeams() {
        DraftRelayTeamRequest request = DraftRelayTeamRequest.builder()
                .type(Event.EventType.RELAY_4X100M.name())
                .sex("MALE")
                .grade("B")
                .relayTeamKind("FORM")
                .eventDate(LocalDate.of(2026, 10, 1))
                .teams(List.of(team("B Grade Yellow", 41L, 42L), team("B Grade Green", 43L)))
                .build();

        EventDTO created = service.createDraftEvent(request);

        assertTrue(created.getDraft(), "the answer says it is a draft");
        assertEquals(2, created.getTeamCount().intValue(), "and how many teams were taken");
        assertEquals("Boys 4x100M Relay · B Grade", created.getName(),
                "the programme's own name, so the draft reads as the race it will become");

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(saved.capture());
        assertTrue(saved.getValue().isDraft(), "and the row really is a draft");

        // Each team went through the ordinary hand-made-team call, so no rule is
        // restated here. One call per team, with the runners in the order given.
        ArgumentCaptor<RelayTeamCreateRequest> teams =
                ArgumentCaptor.forClass(RelayTeamCreateRequest.class);
        verify(relayTeamService, times(2)).createTeam(any(), teams.capture());
        assertEquals(List.of("B Grade Yellow", "B Grade Green"),
                teams.getAllValues().stream().map(RelayTeamCreateRequest::getName).toList());
        assertEquals(List.of(41L, 42L), teams.getAllValues().get(0).getUserIds(),
                "in the order given, so the first student listed runs leg 1");
    }

    @Test
    @DisplayName("a draft of anything that is not a relay is refused")
    void aDraftOfANonRelayIsRefused() {
        DraftRelayTeamRequest request = DraftRelayTeamRequest.builder()
                .type(Event.EventType.RUN_100M.name())
                .sex("MALE").grade("B").relayTeamKind("FORM")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createDraftEvent(request));

        assertTrue(error.getMessage().contains("not a relay event"), error.getMessage());
        verify(eventRepository, never()).save(any());
        verify(relayTeamService, never()).createTeam(any(), any());
    }

    @Test
    @DisplayName("a draft without a relay team kind is refused, because its teams are judged by it")
    void aDraftWithoutAKindIsRefused() {
        DraftRelayTeamRequest request = DraftRelayTeamRequest.builder()
                .type(Event.EventType.RELAY_4X100M.name())
                .sex("MALE").grade("B")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createDraftEvent(request));

        assertTrue(error.getMessage().contains("needs its kind"), error.getMessage());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("two teams of one draft may not share a name, and none is written when they do")
    void twoTeamsOfOneDraftMayNotShareAName() {
        DraftRelayTeamRequest request = DraftRelayTeamRequest.builder()
                .type(Event.EventType.RELAY_4X100M.name())
                .sex("MALE").grade("B").relayTeamKind("FORM")
                .teams(List.of(team("1A Boys", 41L), team("1a boys", 42L)))
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createDraftEvent(request));

        assertTrue(error.getMessage().contains("named twice"), error.getMessage());
        verify(relayTeamService, never()).createTeam(any(), any());
    }

    // ============================================= a draft's teams are protected

    @Test
    @DisplayName("deleting a draft that still holds its teams is refused")
    void aDraftWithTeamsCannotBeDeleted() {
        Event draft = draft(2L);
        when(eventRepository.findById(2L)).thenReturn(Optional.of(draft));
        when(relayTeamService.countTeamsForEvent(2L)).thenReturn(3L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.deleteEvent(2L));

        assertTrue(error.getMessage().contains("draft relay event"), error.getMessage());
        assertTrue(error.getMessage().contains("3 relay team(s)"), error.getMessage());
        assertTrue(error.getMessage().contains("Move the teams"),
                "the way out is named: " + error.getMessage());
        verify(eventRepository, never()).delete(any());
        verify(relayTeamService, never()).removeTeamsForEvent(any());
        verify(recordService, never()).detachForEvent(any());
    }

    @Test
    @DisplayName("an empty draft can be deleted, and an ordinary event with teams is unaffected")
    void anEmptyDraftAndAnOrdinaryEventAreStillDeletable() {
        Event emptyDraft = draft(2L);
        when(eventRepository.findById(2L)).thenReturn(Optional.of(emptyDraft));
        when(relayTeamService.countTeamsForEvent(2L)).thenReturn(0L);

        service.deleteEvent(2L);

        verify(eventRepository).delete(emptyDraft);

        // An ordinary event's teams still go with it, which is what deleting a race
        // the school is abandoning means.
        Event real = real(1L);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(real));
        when(relayTeamService.removeTeamsForEvent(1L)).thenReturn(4);

        service.deleteEvent(1L);

        verify(relayTeamService).removeTeamsForEvent(1L);
        verify(eventRepository).delete(real);
    }

    private static RelayTeamCreateRequest team(String name, Long... userIds) {
        RelayTeamCreateRequest request = new RelayTeamCreateRequest();
        request.setName(name);
        request.setUserIds(List.of(userIds));
        return request;
    }
}
