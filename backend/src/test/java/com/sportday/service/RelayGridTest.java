package com.sportday.service;

import com.sportday.dto.BulkMarkRequest;
import com.sportday.dto.MarkRowDTO;
import com.sportday.dto.MarkSheetDTO;
import com.sportday.entity.*;
import com.sportday.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * A relay's mark-entry grid.
 *
 * <p>A 4x100M is scored by <strong>team</strong> — one time for the four runners
 * together — so its grid lists teams and the mark is recorded against the team. An
 * individual event keeps the athlete-per-row grid.</p>
 *
 * <p>A relay is only marked once it is <strong>ready</strong>: at least two teams
 * <em>in the race</em>, and every team in the race holding its four runners
 * ({@link RelayReadiness}). A half-built relay's grid is <em>refused</em> with the
 * reason, so the fixture here is two teams of four runners — the boundary at which a
 * relay may be marked at all.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayGridTest {

    private static final long EVENT_ID = 1L;
    private static final long TEAM_A_ID = 10L;
    private static final long TEAM_B_ID = 20L;
    private static final long ANCHOR_ID = 61L;

    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private EventRepository eventRepository;
    @Mock private EventGroupRepository groupRepository;
    @Mock private EventResultRepository resultRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private UserRepository userRepository;
    @Mock private FinalEntryRepository finalEntryRepository;
    @Mock private EventGroupService eventGroupService;
    @Mock private RecordService recordService;
    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;

    private MarkEntryService service;
    private Event event;
    private RelayTeam teamA;
    private RelayTeam teamB;
    private User anchor;
    private List<RelayTeamMember> teamALegs;
    private List<RelayTeamMember> teamBLegs;

    private static User runner(long id, String name) {
        return User.builder().id(id).username("S" + id).password("x").fullName(name)
                .role(User.Role.STUDENT).enabled(true).build();
    }

    private static Student roster(User user) {
        return Student.builder().id(user.getId()).user(user).studentId(user.getUsername())
                .name(user.getFullName()).dob(LocalDate.of(2011, 5, 5)).sex(Sex.MALE)
                .className("5A").classNumber(1).house("Red").grade(Grade.A).enabled(true)
                .build();
    }

    private static RelayTeamMember leg(long id, RelayTeam team, User user, int number) {
        return RelayTeamMember.builder().id(id).team(team).user(user).leg(number).build();
    }

    /** Four runners, legs 1..4 — what a full team of a 4x100M holds. */
    private static List<RelayTeamMember> fullTeam(long firstMemberId, RelayTeam team,
                                                 List<User> runners) {
        List<RelayTeamMember> squad = new ArrayList<>(4);
        for (int leg = 1; leg <= 4; leg++) {
            squad.add(leg(firstMemberId + leg - 1, team, runners.get(leg - 1), leg));
        }
        return squad;
    }

    private List<RelayTeamMember> allLegs() {
        List<RelayTeamMember> all = new ArrayList<>(teamALegs);
        all.addAll(teamBLegs);
        return all;
    }

    private List<Student> allRosters() {
        return allLegs().stream().map(member -> roster(member.getUser())).toList();
    }

    @BeforeEach
    void setUp() {
        service = new MarkEntryService(enrollmentRepository, eventRepository, groupRepository,
                resultRepository, studentRepository, userRepository, finalEntryRepository,
                eventGroupService, recordService, relayTeamRepository, relayTeamMemberRepository,
                new RelayReadiness(relayTeamRepository, relayTeamMemberRepository));

        event = Event.builder().id(EVENT_ID).name("Boys 4x100M Relay · A Grade")
                .type(Event.EventType.RELAY_4X100M).category(EventCategory.TRACK)
                .sex(Sex.MALE).grade(Grade.A).eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512).groupSize(24).enabled(true)
                .relayTeamKind(RelayTeamKind.FORM).build();

        teamA = RelayTeam.builder().id(TEAM_A_ID).event(event).kind(RelayTeamKind.FORM)
                .teamKey("5A").label("5A").build();
        teamB = RelayTeam.builder().id(TEAM_B_ID).event(event).kind(RelayTeamKind.FORM)
                .teamKey("5B").label("5B").build();

        anchor = runner(ANCHOR_ID, "Chan Tai Man");
        List<User> runners = List.of(anchor, runner(62L, "Lee Siu Ming"),
                runner(63L, "Wong Ka Yan"), runner(64L, "Ho Cheuk Yiu"),
                runner(65L, "Ng Chun Hei"), runner(66L, "Cheung Wing Yan"),
                runner(67L, "Lam Ho Yin"), runner(68L, "Tsang Mei Ling"));
        teamALegs = fullTeam(1L, teamA, runners.subList(0, 4));
        teamBLegs = fullTeam(5L, teamB, runners.subList(4, 8));

        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(recordService.recordResultIds()).thenReturn(Set.of());
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(anyLong(), any()))
                .thenReturn(List.of());
        // Two teams of four: exactly the boundary at which a relay is ready.
        when(relayTeamRepository.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(teamA, teamB));
        when(relayTeamMemberRepository.findForEventWithUser(EVENT_ID)).thenReturn(allLegs());
        when(relayTeamMemberRepository.findByTeamIdOrderByLegAsc(TEAM_A_ID)).thenReturn(teamALegs);
        when(relayTeamMemberRepository.findByTeamIdOrderByLegAsc(TEAM_B_ID)).thenReturn(teamBLegs);
        when(studentRepository.findWithUserByUserIdIn(any())).thenReturn(allRosters());
    }

    @Test
    @DisplayName("a relay grid is one row per team, identified by the team, not one per athlete")
    void theGridListsTeams() {
        MarkSheetDTO sheet = service.getMarkSheet(EVENT_ID, null, null, EventStage.HEAT);

        assertEquals(2, sheet.getRows().size(), "two teams, two rows: " + sheet.getRows());
        MarkRowDTO first = sheet.getRows().get(0);
        assertEquals(TEAM_A_ID, first.getTeamId(), "the row is the team");
        assertEquals("5A", first.getTeamLabel(), "named as the school names it");
        MarkRowDTO second = sheet.getRows().get(1);
        assertEquals(TEAM_B_ID, second.getTeamId(), "and the second team has its own row");
        assertEquals("5B", second.getTeamLabel());
        // The school's requirement: the line is read by the team's name, so the grid
        // does not carry who is running for it. The runners are listed on the relay
        // board, leg by leg, which is the only place that needs them.
        assertNull(first.getTeamMembers(),
                "and the runners are not carried onto the line: " + first.getTeamMembers());
        assertNull(second.getTeamMembers(),
                "for either team: " + second.getTeamMembers());
        // The four legs are one row, not four.
        assertNotEquals(null, first.getUserId(), "the row still hangs off a user");
    }

    @Test
    @DisplayName("exactly two full teams is ready: the grid opens at the boundary")
    void exactlyTwoFullTeamsIsReady() {
        // The fixture is the boundary itself: 2 teams, 4 runners each. One team fewer
        // or one runner fewer is refused by the tests below.
        MarkSheetDTO sheet = assertDoesNotThrow(
                () -> service.getMarkSheet(EVENT_ID, null, null, EventStage.HEAT));

        assertEquals(2, sheet.getRows().size(), "both teams are on the grid");
    }

    @Test
    @DisplayName("one team of four is not ready: the grid is refused, naming the shortfall")
    void oneTeamIsNotReady() {
        when(relayTeamRepository.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(teamA));
        when(relayTeamMemberRepository.findForEventWithUser(EVENT_ID)).thenReturn(teamALegs);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.getMarkSheet(EVENT_ID, null, null, EventStage.HEAT));

        assertTrue(error.getMessage().startsWith("Boys 4x100M Relay · A Grade has 1 team(s) in the race"),
                "the refusal names the event and what it has in the race: " + error.getMessage());
        assertTrue(error.getMessage().contains("at least 2"),
                "and the rule it falls short of: " + error.getMessage());
        assertTrue(error.getMessage().contains("Build or fill another team first."),
                "and what to do about it: " + error.getMessage());
    }

    @Test
    @DisplayName("a team short of its runners holds the whole relay back, and is named")
    void aShortTeamHoldsTheRelayBack() {
        when(relayTeamMemberRepository.findByTeamIdOrderByLegAsc(TEAM_B_ID))
                .thenReturn(teamBLegs.subList(0, 3));
        when(relayTeamMemberRepository.findForEventWithUser(EVENT_ID))
                .thenReturn(allLegs().subList(0, 7));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.getMarkSheet(EVENT_ID, null, null, EventStage.HEAT));

        assertTrue(error.getMessage().startsWith("5B has 3 of the 4 runners it needs"),
                "the short team is named with its shortfall: " + error.getMessage());
        assertTrue(error.getMessage()
                        .contains("so Boys 4x100M Relay · A Grade cannot be marked yet."),
                "and the relay it holds back: " + error.getMessage());
        assertTrue(error.getMessage().contains("Fill that team first."),
                "and what to do about it: " + error.getMessage());
    }

    @Test
    @DisplayName("an individual event keeps one row per athlete, with no team on it")
    void anIndividualGridIsUnchanged() {
        event.setType(Event.EventType.RUN_100M);
        event.setRelayTeamKind(null);
        when(relayTeamRepository.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of());
        Enrollment one = Enrollment.builder().id(1L).user(anchor).event(event)
                .status(Enrollment.EnrollmentStatus.CONFIRMED).build();
        when(enrollmentRepository.findConfirmedWithUserByEvent(EVENT_ID,
                Enrollment.EnrollmentStatus.CONFIRMED)).thenReturn(List.of(one));

        MarkSheetDTO sheet = service.getMarkSheet(EVENT_ID, null, null, EventStage.HEAT);

        assertFalse(sheet.getRows().isEmpty(), "the athlete is listed");
        assertNull(sheet.getRows().get(0).getTeamId(), "with no team on the row");
        assertNull(sheet.getRows().get(0).getTeamLabel());
        assertNull(sheet.getRows().get(0).getTeamMembers(),
                "and no team's runners either");
        assertEquals("Chan Tai Man", sheet.getRows().get(0).getName(),
                "the row is the athlete, named as the register names them");
        assertEquals("S61", sheet.getRows().get(0).getStudentRef(),
                "and numbered as the register numbers them");
    }

    @Test
    @DisplayName("an undivided relay is refused with its reason, not shown as an athlete grid")
    void anUndividedRelayIsRefusedWithTheReason() {
        // Every relay event in the programme starts like this: no kind, so no teams.
        // Zero teams is not ready — a relay cannot be marked at all — so it is refused
        // rather than quietly falling back to a grid of its athletes, which would score
        // the race the wrong way and look like a race with nothing in it.
        event.setRelayTeamKind(null);
        when(relayTeamRepository.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of());
        when(relayTeamMemberRepository.findForEventWithUser(EVENT_ID)).thenReturn(List.of());
        Enrollment one = Enrollment.builder().id(1L).user(anchor).event(event)
                .status(Enrollment.EnrollmentStatus.CONFIRMED).build();
        when(enrollmentRepository.findConfirmedWithUserByEvent(EVENT_ID,
                Enrollment.EnrollmentStatus.CONFIRMED)).thenReturn(List.of(one));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.getMarkSheet(EVENT_ID, null, null, EventStage.HEAT));

        assertTrue(error.getMessage().startsWith("Boys 4x100M Relay · A Grade has 0 team(s) in the race"),
                "the refusal says the relay has no teams in the race at all: " + error.getMessage());
        assertTrue(error.getMessage().contains("at least 2"),
                "and the rule it falls short of: " + error.getMessage());
        // The athletes are never reached: the grid is refused before it is built.
        verify(enrollmentRepository, never()).findConfirmedWithUserByEvent(any(), any());
    }

    @Test
    @DisplayName("saving a team's time records it against the team, not against one runner")
    void savingATeamMarkRecordsTheTeam() {
        when(enrollmentRepository.findConfirmedWithUserByEvent(EVENT_ID,
                Enrollment.EnrollmentStatus.CONFIRMED)).thenReturn(List.of(
                Enrollment.builder().id(1L).user(anchor).event(event)
                        .status(Enrollment.EnrollmentStatus.CONFIRMED).build()));
        when(userRepository.getReferenceById(ANCHOR_ID)).thenReturn(anchor);
        when(resultRepository.save(any(EventResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(relayTeamRepository.getReferenceById(TEAM_A_ID)).thenReturn(teamA);

        BulkMarkRequest request = BulkMarkRequest.builder()
                .stage("HEAT")
                .rows(List.of(BulkMarkRequest.Entry.builder()
                        .userId(ANCHOR_ID)
                        .teamId(TEAM_A_ID)
                        .mark(new BigDecimal("48.123"))
                        .build()))
                .build();

        service.saveMarks(EVENT_ID, request);

        var saved = org.mockito.ArgumentCaptor.forClass(EventResult.class);
        verify(resultRepository).save(saved.capture());
        assertSame(teamA, saved.getValue().getRelayTeam(),
                "the time is the team's, so the team is on the result");
        assertEquals(new BigDecimal("48.123"), saved.getValue().getMark());
        assertEquals(ANCHOR_ID, saved.getValue().getUser().getId(),
                "and the row still names a user, as every result must");
    }

    @Test
    @DisplayName("marks cannot be saved into a relay that is not ready either")
    void savingIntoANotReadyRelayIsRefused() {
        when(relayTeamRepository.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(teamA));
        when(relayTeamMemberRepository.findForEventWithUser(EVENT_ID)).thenReturn(teamALegs);

        BulkMarkRequest request = BulkMarkRequest.builder()
                .stage("HEAT")
                .rows(List.of(BulkMarkRequest.Entry.builder()
                        .userId(ANCHOR_ID)
                        .teamId(TEAM_A_ID)
                        .mark(new BigDecimal("48.123"))
                        .build()))
                .build();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.saveMarks(EVENT_ID, request));

        assertTrue(error.getMessage().contains("has 1 team(s) in the race"), error.getMessage());
        verify(resultRepository, never()).save(any(EventResult.class));
    }
}
