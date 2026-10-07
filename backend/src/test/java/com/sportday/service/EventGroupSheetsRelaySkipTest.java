package com.sportday.service;

import com.sportday.dto.EventGroupDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventGroup;
import com.sportday.entity.EventStage;
import com.sportday.entity.Grade;
import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamKind;
import com.sportday.entity.RelayTeamMember;
import com.sportday.entity.Sex;
import com.sportday.entity.User;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.FinalEntryRepository;
import com.sportday.repository.RelayTeamMemberRepository;
import com.sportday.repository.RelayTeamRepository;
import com.sportday.repository.StudentRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * The whole-programme print run and a half-built relay.
 *
 * <p>A school printing the programme must not be stopped by one relay whose teams are
 * still being built, and that relay must not appear on a helper's sheet either — its
 * marks cannot be entered yet. So the run <strong>skips</strong> it as it gathers the
 * groups, exactly as it already skips a draft event, and serves everything else.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventGroupSheetsRelaySkipTest {

    private static final long SPRINT_ID = 5L;
    private static final long SPRINT_GROUP_ID = 601L;
    private static final long READY_RELAY_ID = 21L;
    private static final long READY_RELAY_GROUP_ID = 701L;
    private static final long HALF_RELAY_ID = 22L;
    private static final long HALF_RELAY_GROUP_ID = 801L;
    private static final long DRAFT_RELAY_ID = 23L;
    private static final long DRAFT_RELAY_GROUP_ID = 901L;

    @Mock private EventGroupRepository groupRepository;
    @Mock private EventRepository eventRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private FinalEntryRepository finalEntryRepository;
    @Mock private RecordService recordService;
    @Mock private EventResultRepository resultRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;
    @Mock private RelayTeamRepository relayTeamRepository;

    private EventGroupService groups;

    private static User runner(long id) {
        return User.builder().id(id).username("S" + id).password("x").fullName("Runner " + id)
                .role(User.Role.STUDENT).enabled(true).build();
    }

    private static Event event(long id, String name, Event.EventType type, boolean draft) {
        return Event.builder()
                .id(id)
                .name(name)
                .type(type)
                .category(type.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.A)
                .groupSize(type.getDefaultGroupSize())
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .draft(draft)
                .relayTeamKind(type.isRelay() ? RelayTeamKind.FORM : null)
                .build();
    }

    private static EventGroup group(Event event, long id) {
        return EventGroup.builder()
                .id(id)
                .event(event)
                .groupNumber(1)
                .stage(EventStage.HEAT)
                .capacity(event.getGroupSize())
                .athleteCount(0)
                .build();
    }

    private static RelayTeam team(long id, Event event, String label) {
        return RelayTeam.builder().id(id).event(event).kind(RelayTeamKind.FORM)
                .teamKey(label).label(label).build();
    }

    private static List<RelayTeamMember> fullTeam(long firstMemberId, RelayTeam team) {
        List<RelayTeamMember> squad = new ArrayList<>(4);
        for (int leg = 1; leg <= 4; leg++) {
            squad.add(RelayTeamMember.builder().id(firstMemberId + leg - 1).team(team)
                    .user(runner(firstMemberId * 10 + leg)).leg(leg).build());
        }
        return squad;
    }

    private void relayTeams(Event relay, long groupId, RelayTeam... teams) {
        EventGroup heat = group(relay, groupId);
        when(groupRepository.findByEventIdOrderByGroupNumberAsc(relay.getId())).thenReturn(List.of(heat));
        when(relayTeamRepository.findByEventIdOrderByIdAsc(relay.getId())).thenReturn(List.of(teams));
        List<RelayTeamMember> legs = new ArrayList<>();
        long firstMemberId = relay.getId() * 100;
        for (RelayTeam team : teams) {
            legs.addAll(fullTeam(firstMemberId, team));
            firstMemberId += 10;
        }
        when(relayTeamMemberRepository.findForEventWithUser(relay.getId())).thenReturn(legs);
    }

    @BeforeEach
    void setUp() {
        groups = new EventGroupService(groupRepository, eventRepository, enrollmentRepository,
                studentRepository, finalEntryRepository, recordService, resultRepository,
                relayTeamMemberRepository,
                new RelayReadiness(relayTeamRepository, relayTeamMemberRepository));

        Event sprint = event(SPRINT_ID, "Boys 100M · A Grade", Event.EventType.RUN_100M, false);
        Event readyRelay = event(READY_RELAY_ID, "Boys 4x100M Relay · A Grade",
                Event.EventType.RELAY_4X100M, false);
        Event halfRelay = event(HALF_RELAY_ID, "Boys 4x100M Relay · B Grade",
                Event.EventType.RELAY_4X100M, false);
        Event draftRelay = event(DRAFT_RELAY_ID, "Boys 4x400M Relay · C Grade (teams)",
                Event.EventType.RELAY_4X400M, true);

        when(eventRepository.findAll()).thenReturn(List.of(sprint, readyRelay, halfRelay, draftRelay));
        when(eventRepository.existsById(anyLong())).thenReturn(true);
        when(eventRepository.findById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return List.of(sprint, readyRelay, halfRelay, draftRelay).stream()
                    .filter(candidate -> candidate.getId().equals(id))
                    .findFirst();
        });
        when(groupRepository.findByEventIdOrderByGroupNumberAsc(SPRINT_ID))
                .thenReturn(List.of(group(sprint, SPRINT_GROUP_ID)));
        // No heats' rosters are needed: the run is about which events are in it.
        when(enrollmentRepository.findByGroupWithUserOrdered(anyLong())).thenReturn(List.of());

        relayTeams(readyRelay, READY_RELAY_GROUP_ID, team(11L, readyRelay, "5A"),
                team(12L, readyRelay, "5B"));
        // One team of four: not ready, so held back.
        relayTeams(halfRelay, HALF_RELAY_GROUP_ID, team(21L, halfRelay, "6A"));
        // A draft with full teams is still a draft, so it is held back as it always was.
        relayTeams(draftRelay, DRAFT_RELAY_GROUP_ID, team(31L, draftRelay, "7A"),
                team(32L, draftRelay, "7B"));
    }

    @Test
    @DisplayName("the whole-programme run skips a not-ready relay and still serves the rest")
    void theProgrammeRunSkipsANotReadyRelay() {
        List<EventGroupDTO> rendered = groups.getGroupsWithAthletesFiltered(null, null);
        Set<Long> ids = new HashSet<>(rendered.stream().map(EventGroupDTO::getId).toList());

        assertEquals(Set.of(SPRINT_GROUP_ID, READY_RELAY_GROUP_ID), ids,
                "the individual event and the ready relay are on the run: " + ids);
        assertFalse(ids.contains(HALF_RELAY_GROUP_ID),
                "the half-built relay is not: " + ids);
        assertFalse(ids.contains(DRAFT_RELAY_GROUP_ID),
                "and neither is a draft, as it never was: " + ids);
    }

    @Test
    @DisplayName("a ready relay's groups carry the event's teams, in the order the grid marks them")
    void aReadyRelayCarriesItsTeams() {
        List<EventGroupDTO> rendered = groups.getGroupsWithAthletesFiltered(null, null);

        EventGroupDTO relay = rendered.stream()
                .filter(dto -> READY_RELAY_GROUP_ID == dto.getId())
                .findFirst().orElseThrow();
        // The sheet's lines are the event's teams, not the heat's entrants: a form
        // relay's teams are one per class of that form, and the students who entered
        // the event need not be the ones running in it.
        assertEquals(List.of("5A", "5B"), relay.getRelayTeamLabels(),
                "the teams travel with every group of the relay");

        EventGroupDTO sprint = rendered.stream()
                .filter(dto -> SPRINT_GROUP_ID == dto.getId())
                .findFirst().orElseThrow();
        assertNull(sprint.getRelayTeamLabels(), "an individual event carries no teams");
    }

    @Test
    @DisplayName("a relay that is ready is on the run like any other event")
    void aReadyRelayIsOnTheRun() {
        List<EventGroupDTO> rendered = groups.getGroupsWithAthletesFiltered(null, null);

        assertTrue(rendered.stream().anyMatch(dto -> READY_RELAY_GROUP_ID == dto.getId()),
                "two full teams is ready, so the race is printed");
    }

    @Test
    @DisplayName("the readiness skip does not narrow by division: it is the event's own state")
    void theSkipIsNotADivisionFilter() {
        List<EventGroupDTO> male = groups.getGroupsWithAthletesFiltered(Sex.MALE, null);

        Set<Long> ids = new HashSet<>(male.stream().map(EventGroupDTO::getId).toList());
        assertEquals(Set.of(SPRINT_GROUP_ID, READY_RELAY_GROUP_ID), ids, "same events: " + ids);
    }
}
