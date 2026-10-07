package com.sportday.service;

import com.sportday.dto.EventDTO;
import com.sportday.entity.Event;
import com.sportday.entity.Grade;
import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamKind;
import com.sportday.entity.Sex;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.StandardDefaultRepository;
import com.sportday.repository.RelayTeamMemberRepository;
import com.sportday.repository.RelayTeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The event <strong>list</strong> carries the readiness rule.
 *
 * <p>Requirement: "on Print marking sheets or mark entry, should not show if relay no
 * enough team, at least 2 team". The refusing half of that rule lives in
 * {@link RelayReadiness} and is untouched; this is the <em>showing</em> half — every
 * {@code EventDTO} the programme hands out says whether the event may be marked yet, so
 * a picker can leave a half-built relay out instead of offering it and having the choice
 * refused with a 409.</p>
 *
 * <p>Two things are asserted here that nothing else can: the reason a list reports is
 * <strong>character for character the reason a refusal carries</strong> (one rule, one
 * sentence), and a list of a hundred-odd events costs <strong>no extra query per
 * event</strong> — the relays on it are read in one batch and the events that are not
 * relays are read not at all.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventServiceRelayReadinessTest {

    private static final long READY_RELAY_ID = 21L;
    private static final long HALF_RELAY_ID = 22L;
    private static final long ONE_TEAM_RELAY_ID = 23L;
    private static final long SPRINT_ID = 5L;
    private static final long FIELD_ID = 6L;

    @Mock private EventRepository eventRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private EventGroupRepository eventGroupRepository;
    @Mock private EventResultRepository eventResultRepository;
    @Mock private SettingsService settingsService;
    @Mock private RecordService recordService;
    @Mock private SeasonService seasonService;
    @Mock private FinalQualificationService finalQualificationService;
    @Mock private RelayTeamService relayTeamService;
    /** The school's per-grade default standard, read when an event is created. */
    @Mock private StandardDefaultRepository standardDefaultRepository;
    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;

    private EventService service;

    private static Event event(long id, String name, Event.EventType type, Grade grade) {
        return Event.builder()
                .id(id)
                .name(name)
                .type(type)
                .category(type.getCategory())
                .sex(Sex.MALE)
                .grade(grade)
                .eventDate(LocalDate.of(2026, 11, 6))
                .maxParticipants(512)
                .groupSize(type.getDefaultGroupSize())
                .enabled(true)
                .relayTeamKind(type.isRelay() ? RelayTeamKind.FORM : null)
                .build();
    }

    private static Event relay(long id, String name) {
        return event(id, name, Event.EventType.RELAY_4X100M, Grade.A);
    }

    private static RelayTeam team(long id, Event owner, String label) {
        return RelayTeam.builder().id(id).event(owner).kind(RelayTeamKind.FORM)
                .teamKey(label).label(label).build();
    }

    /** The runners of one team, as the batch lookup reports them: one id per leg. */
    private static void runners(List<Long> pooled, long teamId, int count) {
        for (int leg = 0; leg < count; leg++) {
            pooled.add(teamId);
        }
    }

    @BeforeEach
    void setUp() {
        // The rule itself is real, over mocked relay tables: the wording a list reports
        // is then the wording the rule builds, which is the point of the test.
        service = new EventService(eventRepository, enrollmentRepository, eventGroupRepository,
                eventResultRepository, settingsService, recordService, seasonService,
                finalQualificationService, relayTeamService,
                new RelayReadiness(relayTeamRepository, relayTeamMemberRepository),
                standardDefaultRepository);

        when(settingsService.maxEntriesFor(any())).thenReturn(2);
        when(enrollmentRepository.countByEventIdAndStatus(any(), any())).thenReturn(0L);
        when(enrollmentRepository.countUngroupedByEvent(any(), any())).thenReturn(0L);
        when(eventGroupRepository.countByEventId(any())).thenReturn(0L);
        when(relayTeamService.countTeamsForEvent(any())).thenReturn(0L);
    }

    /** Finds the one event in a list, so an assertion is about that event's flags. */
    private static EventDTO described(List<EventDTO> events, long id) {
        return events.stream().filter(event -> event.getId() == id).findFirst()
                .orElseThrow(() -> new AssertionError("event " + id + " is not on the list"));
    }

    // ------------------------------------------------------- what the list says

    @Test
    @DisplayName("a relay whose teams are all built reports ready, with no reason")
    void aReadyRelayReportsReady() {
        Event ready = relay(READY_RELAY_ID, "Boys 4x100M Relay · A Grade");
        Event sprint = event(SPRINT_ID, "Boys 100M · A Grade", Event.EventType.RUN_100M, Grade.A);
        when(eventRepository.findAll()).thenReturn(List.of(ready, sprint));
        when(relayTeamRepository.findForEvents(any()))
                .thenReturn(List.of(team(11L, ready, "1A"), team(12L, ready, "1B")));
        List<Long> legs = new ArrayList<>();
        runners(legs, 11L, 4);
        runners(legs, 12L, 4);
        when(relayTeamMemberRepository.findTeamIdsForEvents(any())).thenReturn(legs);

        List<EventDTO> events = service.getAllEvents();
        EventDTO relay = described(events, READY_RELAY_ID);

        assertTrue(relay.getRelayReady(), "two teams of four is ready");
        assertNull(relay.getReadinessReason(), "and ready carries no reason");
        assertTrue(described(events, SPRINT_ID).getRelayReady());
    }

    @Test
    @DisplayName("a relay with one team is not ready, in the very words the refusal uses")
    void aRelayWithOneTeamIsNotReady() {
        Event half = relay(ONE_TEAM_RELAY_ID, "Boys 4x100M Relay · A Grade");
        when(eventRepository.findAll()).thenReturn(List.of(half));
        when(relayTeamRepository.findForEvents(any()))
                .thenReturn(List.of(team(31L, half, "1A")));
        List<Long> legs = new ArrayList<>();
        runners(legs, 31L, 4);
        when(relayTeamMemberRepository.findTeamIdsForEvents(any())).thenReturn(legs);

        EventDTO described = described(service.getAllEvents(), ONE_TEAM_RELAY_ID);

        assertFalse(described.getRelayReady(), "one team is not a race");
        assertEquals("Boys 4x100M Relay · A Grade has 1 team(s) in the race, and a relay needs "
                        + "at least 2 before its marks can be entered. Build or fill another team "
                        + "first.",
                described.getReadinessReason());
        // The same sentence, from the one place that states it.
        assertEquals(RelayReadiness.shortfall(half, List.of(new RelayReadiness.TeamState(
                        31L, "1A", 4))).orElse(null),
                described.getReadinessReason(),
                "a list must not word the rule differently from a refusal");
    }

    @Test
    @DisplayName("two teams where one holds three runners is not ready, and names the short team")
    void aRelayWithAShortTeamIsNotReady() {
        Event half = relay(HALF_RELAY_ID, "Girls 4x100M Relay · A Grade");
        when(eventRepository.findAll()).thenReturn(List.of(half));
        when(relayTeamRepository.findForEvents(any()))
                .thenReturn(List.of(team(41L, half, "2A"), team(42L, half, "2B")));
        List<Long> legs = new ArrayList<>();
        runners(legs, 41L, 4);
        runners(legs, 42L, 3);
        when(relayTeamMemberRepository.findTeamIdsForEvents(any())).thenReturn(legs);

        EventDTO described = described(service.getAllEvents(), HALF_RELAY_ID);

        assertFalse(described.getRelayReady(), "one short team holds the whole relay back");
        assertEquals("2B has 3 of the 4 runners it needs, so Girls 4x100M Relay · A Grade cannot "
                        + "be marked yet. Fill that team first.",
                described.getReadinessReason());
    }

    @Test
    @DisplayName("an undivided relay — no teams at all — is not ready, and says how many it has")
    void anUndividedRelayIsNotReady() {
        Event undivided = relay(READY_RELAY_ID, "Boys 4x400M Relay · A Grade");
        undivided.setType(Event.EventType.RELAY_4X400M);
        undivided.setRelayTeamKind(null);
        when(eventRepository.findAll()).thenReturn(List.of(undivided));
        when(relayTeamRepository.findForEvents(any())).thenReturn(List.of());
        when(relayTeamMemberRepository.findTeamIdsForEvents(any())).thenReturn(List.of());

        EventDTO described = described(service.getAllEvents(), READY_RELAY_ID);

        assertFalse(described.getRelayReady(), "a relay with no teams has nobody to race");
        assertEquals("Boys 4x400M Relay · A Grade has 0 team(s) in the race, and a relay needs "
                + "at least 2 before its marks can be entered. Build or fill another team first.",
                described.getReadinessReason());
    }

    /**
     * The regression that matters most: the rule is about a relay's teams, and a race of
     * athletes has none. A sprint or a field event must read ready, with no reason, on
     * every list — otherwise a marks or print page would start hiding events it has
     * always shown.
     */
    @Test
    @DisplayName("an individual event is always ready with no reason — the regression that matters")
    void anIndividualEventIsAlwaysReady() {
        Event sprint = event(SPRINT_ID, "Boys 100M · A Grade", Event.EventType.RUN_100M, Grade.A);
        Event field = event(FIELD_ID, "Girls Long Jump · B Grade", Event.EventType.LONG_JUMP,
                Grade.B);
        when(eventRepository.findAll()).thenReturn(List.of(sprint, field));
        when(relayTeamRepository.findForEvents(any())).thenReturn(List.of());
        when(relayTeamMemberRepository.findTeamIdsForEvents(any())).thenReturn(List.of());

        List<EventDTO> events = service.getAllEvents();

        for (EventDTO dto : events) {
            assertTrue(dto.getRelayReady(), dto.getName() + " is never gated");
            assertNull(dto.getReadinessReason(), dto.getName() + " has no reason to give");
        }
        // And nothing was read for them: a race of athletes has no teams to be short of.
        verifyNoInteractions(relayTeamRepository, relayTeamMemberRepository);
    }

    /** The same answer reaches the filtered list the entry page loads. */
    @Test
    @DisplayName("the searched list carries readiness too")
    void theSearchedListCarriesReadiness() {
        Event half = relay(ONE_TEAM_RELAY_ID, "Boys 4x100M Relay · A Grade");
        Event sprint = event(SPRINT_ID, "Boys 100M · A Grade", Event.EventType.RUN_100M, Grade.A);
        when(eventRepository.findByEnabledTrue()).thenReturn(List.of(half, sprint));
        when(relayTeamRepository.findForEvents(any())).thenReturn(List.of(team(51L, half, "1A")));
        List<Long> legs = new ArrayList<>();
        runners(legs, 51L, 4);
        when(relayTeamMemberRepository.findTeamIdsForEvents(any())).thenReturn(legs);

        List<EventDTO> events = service.searchEvents(true, null, null);

        assertFalse(described(events, ONE_TEAM_RELAY_ID).getRelayReady());
        assertTrue(described(events, SPRINT_ID).getRelayReady());
        assertNull(described(events, SPRINT_ID).getReadinessReason());
    }

    /** The same answer reaches the single event the mark grid asks for by id. */
    @Test
    @DisplayName("one event asked for on its own carries readiness too")
    void oneEventCarriesReadiness() {
        Event half = relay(ONE_TEAM_RELAY_ID, "Boys 4x100M Relay · A Grade");
        when(eventRepository.findById(ONE_TEAM_RELAY_ID)).thenReturn(java.util.Optional.of(half));
        when(relayTeamRepository.findForEvents(any())).thenReturn(List.of(team(31L, half, "1A")));
        List<Long> legs = new ArrayList<>();
        runners(legs, 31L, 4);
        when(relayTeamMemberRepository.findTeamIdsForEvents(any())).thenReturn(legs);

        EventDTO described = service.getEventById(ONE_TEAM_RELAY_ID);

        assertFalse(described.getRelayReady(), "one team is not a race");
        assertEquals("Boys 4x100M Relay · A Grade has 1 team(s) in the race, and a relay needs "
                        + "at least 2 before its marks can be entered. Build or fill another team "
                        + "first.",
                described.getReadinessReason());
    }

    // ------------------------------------------------------------- what it costs

    /**
     * The cost the list is allowed to pay: <strong>one</strong> read of the relay teams
     * and <strong>one</strong> of their runners for the whole list, and nothing at all
     * for the hundred events that are not relays — never a query per event.
     */
    @Test
    @DisplayName("the list reads every relay's teams in one batch, and nothing per event")
    void theListReadsTheRelaysInOneBatch() {
        List<Event> programme = new ArrayList<>();
        for (long id = 1; id <= 100; id++) {
            programme.add(event(id, "Boys 100M · A Grade", Event.EventType.RUN_100M, Grade.A));
        }
        Event first = relay(101L, "Boys 4x100M Relay · A Grade");
        Event second = relay(102L, "Girls 4x100M Relay · A Grade");
        programme.add(first);
        programme.add(second);
        when(eventRepository.findAll()).thenReturn(programme);
        when(relayTeamRepository.findForEvents(any()))
                .thenReturn(List.of(team(11L, first, "1A"), team(12L, first, "1B"),
                        team(13L, second, "2A"), team(14L, second, "2B")));
        List<Long> legs = new ArrayList<>();
        runners(legs, 11L, 4);
        runners(legs, 12L, 4);
        runners(legs, 13L, 4);
        runners(legs, 14L, 4);
        when(relayTeamMemberRepository.findTeamIdsForEvents(any())).thenReturn(legs);

        List<EventDTO> events = service.getAllEvents();

        assertEquals(102, events.size());
        assertTrue(events.stream().allMatch(EventDTO::getRelayReady), "two relays, both built");
        // Two queries for the twelve relays of a real programme; one each here.
        verify(relayTeamRepository, times(1)).findForEvents(any());
        verify(relayTeamMemberRepository, times(1)).findTeamIdsForEvents(any());
        // And never the one-relay-at-a-time lookup, which is the query per event.
        verify(relayTeamRepository, never()).findByEventIdOrderByIdAsc(anyLong());
        verify(relayTeamMemberRepository, never()).findForEventWithUser(anyLong());
    }

    @Test
    @DisplayName("a list with no relay on it costs no relay query at all")
    void aListWithNoRelayCostsNothing() {
        List<Event> programme = new ArrayList<>();
        for (long id = 1; id <= 112; id++) {
            programme.add(event(id, "Boys 100M · A Grade", Event.EventType.RUN_100M, Grade.A));
        }
        when(eventRepository.findAll()).thenReturn(programme);

        List<EventDTO> events = service.getAllEvents();

        assertEquals(112, events.size());
        assertTrue(events.stream().allMatch(EventDTO::getRelayReady));
        verifyNoInteractions(relayTeamRepository, relayTeamMemberRepository);
    }
}
