package com.sportday.service;

import com.sportday.dto.RelayEventTeamsDTO;
import com.sportday.dto.RelayTeamDTO;
import com.sportday.dto.RelayTeamDerivationDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Grade;
import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamKind;
import com.sportday.entity.RelayTeamMember;
import com.sportday.entity.Sex;
import com.sportday.entity.Student;
import com.sportday.entity.User;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.EventRepository;
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
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Relay teams: how they are derived, and who may run in them.
 *
 * <p>Requirement 3: "have form class relay base on each form and house relay base
 * on each grade and house. teacher can help select student join different relay."
 * The rules a teacher cannot work around are asserted here, on the one service every
 * relay endpoint goes through:</p>
 *
 * <ul>
 *   <li>one team per class, or one per house, of the event's own grade and division;</li>
 *   <li>a runner must be in the event's division and grade — the rule entry uses;</li>
 *   <li>a house team's runner must be in that house, a class team's runner in that
 *       class (a legacy form-keyed team still takes its own form's runners);</li>
 *   <li>the same athlete cannot hold two legs of a team, or two legs of an event;</li>
 *   <li>the team's own size is respected, and reserves are opt-in — four runners and
 *       at most one reserve.</li>
 * </ul>
 *
 * <p>A team a <em>derive</em> makes is keyed with a class name, and the tests here
 * build their teams by hand with the older form keys; that the two agree is asserted
 * in {@code RelayTeamClassTeamTest}, which fills the teams a derive actually makes.</p>
 *
 * <p>The repositories are backed by three in-memory lists rather than mocked call by
 * call, so the tests exercise the service's real read-then-write behaviour —
 * including the legs it closes up after a removal and the two-phase write that sets
 * a running order without tripping the {@code (team_id, leg)} key.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayTeamServiceTest {

    private static final Long EVENT_ID = 42L;

    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;
    @Mock private EventRepository eventRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private TeacherClassService teacherClassService;
    /** The event's entries: no confirmed entry, so the board has no applicants. */
    @Mock private com.sportday.repository.EnrollmentRepository enrollmentRepository;

    private RelayTeamService service;

    /** The in-memory tables the repositories are backed by. */
    private final List<RelayTeam> teams = new ArrayList<>();
    private final List<RelayTeamMember> members = new ArrayList<>();
    private final Map<Long, Student> students = new LinkedHashMap<>();
    private long nextTeamId = 1;
    private long nextMemberId = 1;

    private Event event;
    private Student oneA;
    private Student oneB;
    private Student twoA;
    private Student twoC;

    @BeforeEach
    void setUp() {
        service = new RelayTeamService(relayTeamRepository, relayTeamMemberRepository,
                eventRepository, studentRepository, teacherClassService, enrollmentRepository);
        teams.clear();
        members.clear();
        students.clear();
        nextTeamId = 1;
        nextMemberId = 1;

        // 1A and 1B are Form 1, 2A and 2C are Form 2, 10B is Form 10.
        oneA = student(1L, "S0001", "1A", "Red", Grade.B, Sex.MALE);
        oneB = student(2L, "S0002", "1B", "Blue", Grade.B, Sex.MALE);
        twoA = student(3L, "S0003", "2A", "Red", Grade.B, Sex.MALE);
        twoC = student(4L, "S0004", "2C", "Green", Grade.B, Sex.MALE);
        student(10L, "S0010", "10B", "Yellow", Grade.B, Sex.MALE);

        event = relay(RelayTeamKind.FORM);
        wireRepositories();
    }

    // ============================================================== fixtures

    private Student student(long userId, String studentId, String className, String house,
                            Grade grade, Sex sex) {
        User user = User.builder().id(userId).username(studentId).fullName("Athlete " + studentId)
                .role(User.Role.STUDENT).enabled(true).build();
        Student student = Student.builder()
                .id(userId)
                .user(user)
                .studentId(studentId)
                .name("Athlete " + studentId)
                .dob(LocalDate.of(2011, 5, 5))
                .sex(sex)
                .className(className)
                .classNumber(1)
                .house(house)
                .grade(grade)
                .enabled(true)
                .build();
        students.put(userId, student);
        return student;
    }

    /** One more Form 1 runner, in the next class letter along. */
    private Student formOneRunner(long userId) {
        return student(userId, "S%04d".formatted(userId), "1" + (char) ('A' + userId % 26),
                "Red", Grade.B, Sex.MALE);
    }

    private Event relay(RelayTeamKind kind) {
        return Event.builder()
                .id(EVENT_ID)
                .name("Boys 4x100M Relay · B Grade")
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .grade(Grade.B)
                .eventDate(LocalDate.of(2026, 10, 1))
                .location("Main Sports Ground")
                .maxParticipants(512)
                .groupSize(24)
                .enabled(true)
                .relayTeamKind(kind)
                .build();
    }

    private RelayTeam team(String key) {
        RelayTeam team = RelayTeam.builder()
                .id(nextTeamId++)
                .event(event)
                .kind(event.getRelayTeamKind())
                .teamKey(key)
                .label(event.getRelayTeamKind().labelFor(key))
                .build();
        teams.add(team);
        return team;
    }

    /** Backs the repositories with the three in-memory lists above. */
    private void wireRepositories() {
        when(eventRepository.findById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (EVENT_ID.equals(id)) {
                return Optional.of(event);
            }
            return teams.stream()
                    .map(RelayTeam::getEvent)
                    .filter(candidate -> candidate != null && id.equals(candidate.getId()))
                    .findFirst();
        });

        when(relayTeamRepository.findById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return teams.stream().filter(team -> id.equals(team.getId())).findFirst();
        });
        when(relayTeamRepository.findByEventIdOrderByIdAsc(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return teams.stream()
                    .filter(team -> team.getEvent() != null && id.equals(team.getEvent().getId()))
                    .collect(Collectors.toList());
        });
        when(relayTeamRepository.countByEventId(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return teams.stream()
                    .filter(team -> team.getEvent() != null && id.equals(team.getEvent().getId()))
                    .count();
        });
        when(relayTeamRepository.save(any())).thenAnswer(invocation -> {
            RelayTeam saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(nextTeamId++);
            }
            if (!teams.contains(saved)) {
                teams.add(saved);
            }
            return saved;
        });
        doAnswer(invocation -> {
            teams.remove(invocation.getArgument(0));
            return null;
        }).when(relayTeamRepository).delete(any());
        doAnswer(invocation -> {
            teams.removeAll(invocation.getArgument(0));
            return null;
        }).when(relayTeamRepository).deleteAll(anyList());

        when(relayTeamMemberRepository.save(any())).thenAnswer(invocation -> {
            RelayTeamMember saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(nextMemberId++);
            }
            if (!members.contains(saved)) {
                members.add(saved);
            }
            return saved;
        });
        when(relayTeamMemberRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<RelayTeamMember> saved = invocation.getArgument(0);
            for (RelayTeamMember member : saved) {
                if (member.getId() == null) {
                    member.setId(nextMemberId++);
                }
                if (!members.contains(member)) {
                    members.add(member);
                }
            }
            return saved;
        });
        when(relayTeamMemberRepository.findByTeamIdOrderByLegAsc(anyLong()))
                .thenAnswer(invocation -> membersOf(invocation.getArgument(0)));
        when(relayTeamMemberRepository.countByTeamId(anyLong()))
                .thenAnswer(invocation -> (long) membersOf(invocation.getArgument(0)).size());
        when(relayTeamMemberRepository.existsByTeamIdAndLeg(anyLong(), any()))
                .thenAnswer(invocation -> membersOf(invocation.getArgument(0)).stream()
                        .anyMatch(member -> member.getLeg().equals(invocation.getArgument(1))));
        when(relayTeamMemberRepository.findByTeamIdAndUserId(anyLong(), anyLong()))
                .thenAnswer(invocation -> membersOf(invocation.getArgument(0)).stream()
                        .filter(member -> member.getUser() != null
                                && member.getUser().getId().equals(invocation.getArgument(1)))
                        .findFirst());
        when(relayTeamMemberRepository.findForEventAndUser(anyLong(), anyLong()))
                .thenAnswer(invocation -> membersOfEvent(invocation.getArgument(0)).stream()
                        .filter(member -> member.getUser() != null
                                && member.getUser().getId().equals(invocation.getArgument(1)))
                        .collect(Collectors.toList()));
        when(relayTeamMemberRepository.findForEventWithUser(anyLong()))
                .thenAnswer(invocation -> membersOfEvent(invocation.getArgument(0)));
        doAnswer(invocation -> {
            Long teamId = invocation.getArgument(0);
            members.removeIf(member -> member.getTeam() != null
                    && teamId.equals(member.getTeam().getId()));
            return null;
        }).when(relayTeamMemberRepository).deleteByTeamId(anyLong());
        doAnswer(invocation -> {
            members.remove(invocation.getArgument(0));
            return null;
        }).when(relayTeamMemberRepository).delete(any());

        when(studentRepository.findWithUserByUserId(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(students.get(invocation.getArgument(0))));
        when(studentRepository.findWithUserByUserIdIn(any())).thenAnswer(invocation -> {
            List<Long> ids = new ArrayList<>(invocation.getArgument(0));
            return ids.stream().map(students::get).filter(Objects::nonNull)
                    .collect(Collectors.toList());
        });
        when(studentRepository.findActiveBySexAndGrade(any(), any())).thenAnswer(invocation -> {
            Sex sex = invocation.getArgument(0);
            Grade grade = invocation.getArgument(1);
            return students.values().stream()
                    .filter(student -> student.getSex() == sex && student.getGrade() == grade)
                    .filter(student -> Boolean.TRUE.equals(student.getEnabled()))
                    .collect(Collectors.toList());
        });
    }

    private List<RelayTeamMember> membersOf(Long teamId) {
        return members.stream()
                .filter(member -> member.getTeam() != null && teamId.equals(member.getTeam().getId()))
                .sorted(Comparator.comparingInt(member -> member.getLeg() == null
                        ? Integer.MAX_VALUE : member.getLeg()))
                .collect(Collectors.toList());
    }

    private List<RelayTeamMember> membersOfEvent(Long eventId) {
        return members.stream()
                .filter(member -> member.getTeam() != null
                        && member.getTeam().getEvent() != null
                        && eventId.equals(member.getTeam().getEvent().getId()))
                .collect(Collectors.toList());
    }

    // ============================================================== the form

    @Test
    @DisplayName("a form is the leading digits of the class: 1A is Form 1 and 10B is Form 10")
    void formKeyReadsTheLeadingDigits() {
        assertEquals("1", RelayTeamService.formKeyOf("1A"));
        assertEquals("1", RelayTeamService.formKeyOf(" 1B "));
        assertEquals("1", RelayTeamService.formKeyOf("01A"));
        assertEquals("2", RelayTeamService.formKeyOf("2C"));
        assertEquals("6", RelayTeamService.formKeyOf("6A"));
        assertEquals("10", RelayTeamService.formKeyOf("10B"));
        assertEquals("12", RelayTeamService.formKeyOf("12A"));
        assertNull(RelayTeamService.formKeyOf("A1"));
        assertNull(RelayTeamService.formKeyOf(""));
        assertNull(RelayTeamService.formKeyOf(null));
    }

    // ========================================================== derivation

    @Test
    @DisplayName("a form relay gets one team per class present in the event's grade and division")
    void deriveCreatesOneTeamPerForm() {
        RelayTeamDerivationDTO result = service.deriveTeams(EVENT_ID, false);

        assertEquals(5, result.getCreated());
        assertEquals(0, result.getKept());
        assertEquals("FORM", result.getKind());
        // One team per class, not per form: 1A and 1B are two teams of Form 1, and the
        // order is school order — Form 10 comes last, not second.
        assertEquals(List.of("1A", "1B", "2A", "2C", "10B"), teamKeys());
        assertEquals(List.of("1A", "1B", "2A", "2C", "10B"), teamLabels());
        assertEquals(5, result.getEligibleStudents());
        assertEquals(5, result.getBoard().getTeamCount());
        assertEquals(4, result.getBoard().getLegsPerTeam());
        assertFalse(result.getBoard().getReservesAllowed());
        assertTrue(result.getBoard().getRelay());
    }

    @Test
    @DisplayName("a house relay gets one team per house of the event's grade")
    void deriveCreatesOneTeamPerHouse() {
        event.setRelayTeamKind(RelayTeamKind.HOUSE);

        RelayTeamDerivationDTO result = service.deriveTeams(EVENT_ID, false);

        assertEquals(4, result.getCreated());
        assertEquals(List.of("Blue", "Green", "Red", "Yellow"), teamKeys());
        // A house team IS that grade's team — the event is a B Grade one — so the
        // school's own wording names the grade and the house together.
        assertEquals(List.of("B Grade Blue", "B Grade Green", "B Grade Red", "B Grade Yellow"),
                teamLabels());
    }

    @Test
    @DisplayName("a student of another grade or division is not derived a team")
    void deriveIgnoresOtherGradesAndDivisions() {
        student(30L, "S0030", "3A", "Red", Grade.A, Sex.MALE);
        student(31L, "S0031", "1A", "Red", Grade.B, Sex.FEMALE);

        RelayTeamDerivationDTO result = service.deriveTeams(EVENT_ID, false);

        assertEquals(5, result.getCreated());
        assertEquals(List.of("1A", "1B", "2A", "2C", "10B"), teamKeys());
        assertEquals(5, result.getEligibleStudents());
    }

    @Test
    @DisplayName("deriving again is idempotent: it creates nothing and keeps what is there")
    void deriveIsIdempotent() {
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDerivationDTO second = service.deriveTeams(EVENT_ID, false);

        assertEquals(0, second.getCreated());
        assertEquals(5, second.getKept());
        assertEquals(5, teamKeys().size());
    }

    @Test
    @DisplayName("a team somebody runs in is kept even when the roster no longer calls for it")
    void deriveKeepsATeamWithRunners() {
        RelayTeam formThree = team("3");
        relayTeamMemberRepository.save(RelayTeamMember.builder()
                .team(formThree).user(oneA.getUser()).leg(1).build());

        RelayTeamDerivationDTO result = service.deriveTeams(EVENT_ID, true);

        assertEquals(1, result.getKeptWithRunners());
        assertEquals(0, result.getPruned());
        assertTrue(teamKeys().contains("3"), "a team with runners must survive a prune");
    }

    @Test
    @DisplayName("an empty team the roster no longer calls for is only dropped when asked")
    void derivePrunesAnEmptyStaleTeamOnlyWhenAsked() {
        team("3");

        RelayTeamDerivationDTO kept = service.deriveTeams(EVENT_ID, false);
        assertEquals(0, kept.getPruned());
        assertTrue(teamKeys().contains("3"));

        RelayTeamDerivationDTO pruned = service.deriveTeams(EVENT_ID, true);
        assertEquals(1, pruned.getPruned());
        assertFalse(teamKeys().contains("3"));
    }

    @Test
    @DisplayName("an undivided relay has no teams, and reports that rather than inventing any")
    void undividedRelayHasNoTeams() {
        event.setRelayTeamKind(null);

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);

        assertTrue(board.getRelay());
        assertNull(board.getRelayTeamKind());
        assertEquals(0, board.getTeamCount());
        assertTrue(board.getTeams().isEmpty());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.deriveTeams(EVENT_ID, false));
        assertTrue(error.getMessage().contains("nothing to derive"), error.getMessage());
    }

    @Test
    @DisplayName("an event that is not a relay is refused, because it has no relay teams")
    void nonRelayIsRefused() {
        event.setType(Event.EventType.RUN_100M);
        event.setRelayTeamKind(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getBoard(EVENT_ID));
        assertTrue(error.getMessage().contains("not a relay"), error.getMessage());
    }

    // ======================================================== selection rules

    @Test
    @DisplayName("a runner takes the next free leg, and the teacher rule is consulted")
    void addRunnerTakesTheNextFreeLeg() {
        RelayTeam formOne = team("1");

        RelayTeamDTO first = service.addRunner(formOne.getId(), 1L, null);
        assertEquals(1, first.getMemberCount().intValue());
        assertEquals(1, first.getMembers().get(0).getLeg().intValue());
        assertEquals("S0001", first.getMembers().get(0).getStudentId());
        assertEquals("1A", first.getMembers().get(0).getClassName());
        assertEquals("Red", first.getMembers().get(0).getHouse());
        assertFalse(first.getMembers().get(0).getReserve());
        assertFalse(first.getComplete());

        RelayTeamDTO second = service.addRunner(formOne.getId(), 2L, null);
        assertEquals(List.of(1, 2), second.getMembers().stream()
                .map(member -> member.getLeg()).collect(Collectors.toList()));

        // Who may do it is asked once per runner, from the one place the rule lives.
        verify(teacherClassService).requireMayHelp(oneA);
        verify(teacherClassService).requireMayHelp(oneB);
    }

    @Test
    @DisplayName("a class team refuses a runner from another class")
    void formTeamRefusesAnotherForm() {
        RelayTeam classOneA = team("1A");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(classOneA.getId(), twoA.getUser().getId(), null));
        assertTrue(error.getMessage().contains("1A"), error.getMessage());
        assertTrue(error.getMessage().contains("2A"), error.getMessage());
    }

    @Test
    @DisplayName("a house team refuses a runner from another house")
    void houseTeamRefusesAnotherHouse() {
        event.setRelayTeamKind(RelayTeamKind.HOUSE);
        RelayTeam red = team("Red");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(red.getId(), oneB.getUser().getId(), null));
        assertTrue(error.getMessage().contains("Blue House"), error.getMessage());
        assertTrue(error.getMessage().contains("Red"), error.getMessage());
    }

    @Test
    @DisplayName("a runner must be in the event's own grade")
    void refusesAnotherGrade() {
        Student senior = student(20L, "S0020", "1A", "Red", Grade.A, Sex.MALE);
        RelayTeam formOne = team("1");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(formOne.getId(), senior.getUser().getId(), null));
        assertTrue(error.getMessage().contains("A grade"), error.getMessage());
        assertTrue(error.getMessage().contains("B Grade"), error.getMessage());
    }

    @Test
    @DisplayName("a runner must be in the event's own division")
    void refusesAnotherDivision() {
        Student girl = student(21L, "S0021", "1A", "Red", Grade.B, Sex.FEMALE);
        RelayTeam formOne = team("1");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(formOne.getId(), girl.getUser().getId(), null));
        assertTrue(error.getMessage().contains("Girls"), error.getMessage());
        assertTrue(error.getMessage().contains("Boys"), error.getMessage());
    }

    @Test
    @DisplayName("a locked student cannot be named in a team")
    void refusesALockedStudent() {
        Student locked = student(22L, "S0022", "1A", "Red", Grade.B, Sex.MALE);
        locked.setEnabled(false);
        RelayTeam formOne = team("1");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(formOne.getId(), locked.getUser().getId(), null));
        assertTrue(error.getMessage().contains("locked"), error.getMessage());
    }

    @Test
    @DisplayName("the same athlete cannot hold two legs of one team")
    void refusesTheSameAthleteTwice() {
        RelayTeam formOne = team("1");
        service.addRunner(formOne.getId(), 1L, null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(formOne.getId(), 1L, null));
        assertTrue(error.getMessage().contains("already runs for"), error.getMessage());
    }

    @Test
    @DisplayName("two athletes cannot be down for the same leg")
    void refusesALegSomebodyElseHolds() {
        RelayTeam formOne = team("1");
        service.addRunner(formOne.getId(), 1L, 1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(formOne.getId(), 2L, 1));
        assertTrue(error.getMessage().contains("Leg 1"), error.getMessage());
        assertTrue(error.getMessage().contains("already taken"), error.getMessage());
    }

    @Test
    @DisplayName("one athlete cannot hold two legs of the same event, even across teams")
    void refusesTwoLegsOfOneEvent() {
        event.setRelayTeamKind(RelayTeamKind.HOUSE);
        RelayTeam red = team("Red");
        RelayTeam blue = team("Blue");
        service.addRunner(red.getId(), 1L, null);

        // The roster moves them to Blue — the one way an athlete can be eligible for
        // two teams of a single event, and exactly what the cross-team rule is for.
        oneA.setHouse("Blue");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(blue.getId(), 1L, null));
        assertTrue(error.getMessage().contains("already runs for"), error.getMessage());
        assertTrue(error.getMessage().contains("Red"), error.getMessage());
    }

    @Test
    @DisplayName("an athlete may run in a form relay and a house relay — different events")
    void formAndHouseRelaysAreDifferentEvents() {
        RelayTeam formOne = team("1");
        service.addRunner(formOne.getId(), 1L, null);

        Event houseRelay = Event.builder()
                .id(77L)
                .name("Boys 4x400M Relay · B Grade")
                .type(Event.EventType.RELAY_4X400M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .grade(Grade.B)
                .eventDate(LocalDate.of(2026, 10, 1))
                .enabled(true)
                .relayTeamKind(RelayTeamKind.HOUSE)
                .build();
        RelayTeam redOfHouseRelay = RelayTeam.builder().id(nextTeamId++).event(houseRelay)
                .kind(RelayTeamKind.HOUSE).teamKey("Red").label("Red").build();
        teams.add(redOfHouseRelay);

        RelayTeamDTO filled = service.addRunner(redOfHouseRelay.getId(), 1L, null);

        assertEquals(1, filled.getMemberCount());
        assertEquals("Red", filled.getLabel());
        assertEquals("Boys 4x400M Relay · B Grade", filled.getEventName());
    }

    @Test
    @DisplayName("a runner who is not on the register at all is refused")
    void refusesARunnerWithNoRosterRow() {
        RelayTeam formOne = team("1");

        assertThrows(ResourceNotFoundException.class,
                () -> service.addRunner(formOne.getId(), 999L, null));
    }

    // ======================================================= size and reserves

    @Test
    @DisplayName("a 4x100M team stops at four legs when reserves are not allowed")
    void refusesPastFourLegsWithoutReserves() {
        RelayTeam formOne = team("1");
        Student c = formOneRunner(5L);
        Student d = formOneRunner(6L);
        service.addRunner(formOne.getId(), 1L, null);
        service.addRunner(formOne.getId(), 2L, null);
        service.addRunner(formOne.getId(), c.getUser().getId(), null);
        RelayTeamDTO full = service.addRunner(formOne.getId(), d.getUser().getId(), null);

        assertTrue(full.getComplete());
        assertEquals(4, full.getMemberCap().intValue());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(formOne.getId(), formOneRunner(7L).getUser().getId(), null));
        assertTrue(error.getMessage().contains("all 4 of its legs filled"), error.getMessage());
        assertTrue(error.getMessage().contains("Allow reserves"), error.getMessage());
    }

    @Test
    @DisplayName("reserves are allowed past the legs, and one of them only, when the event says so")
    void allowsReservesUpToTwiceTheRace() {
        event.setRelayReservesAllowed(true);
        event.setRelayTeamSize(4);
        RelayTeam formOne = team("1");
        Student c = formOneRunner(5L);
        Student d = formOneRunner(6L);
        Student e = formOneRunner(7L);
        Student sixth = formOneRunner(8L);

        service.addRunner(formOne.getId(), 1L, null);
        service.addRunner(formOne.getId(), 2L, null);
        service.addRunner(formOne.getId(), c.getUser().getId(), null);
        service.addRunner(formOne.getId(), d.getUser().getId(), null);
        RelayTeamDTO withReserve = service.addRunner(formOne.getId(), e.getUser().getId(), null);

        // Four runners and one backup: the fifth is the reserve, and there is no sixth.
        assertEquals(5, withReserve.getMemberCap().intValue());
        assertEquals(5, withReserve.getMemberCount().intValue());
        assertTrue(withReserve.getMembers().get(4).getReserve(), "the fifth runner is a reserve");
        assertTrue(withReserve.getComplete(), "four legs filled is a complete team");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(formOne.getId(), sixth.getUser().getId(), null));
        assertTrue(error.getMessage().contains("full squad of 5"), error.getMessage());
    }

    @Test
    @DisplayName("the event's own team size is what the legs are checked against")
    void anEventMayRunALongerTeam() {
        event.setRelayTeamSize(6);
        event.setRelayReservesAllowed(true);
        RelayTeam formOne = team("1");
        List<Long> squad = new ArrayList<>();
        squad.add(1L);
        squad.add(2L);
        for (long userId = 5L; userId <= 9L; userId++) {
            squad.add(formOneRunner(userId).getUser().getId());
        }

        // Six legs and the single backup the school allows.
        assertEquals(7, event.getRelayMemberCap());
        for (Long userId : squad.subList(0, 6)) {
            service.addRunner(formOne.getId(), userId, null);
        }
        RelayTeamDTO six = service.getBoard(EVENT_ID).getTeams().get(0);

        assertEquals(6, six.getLegCount().intValue());
        assertEquals(6, six.getMemberCount().intValue());
        assertTrue(six.getMembers().stream().noneMatch(member -> member.getReserve()),
                "six legs and six runners is not a reserve");

        // A seventh teammate is eligible and there is squad room, so only the leg is
        // wrong — past both the six legs and the seven-strong squad.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addRunner(formOne.getId(), squad.get(6), 13));
        assertTrue(error.getMessage().contains("between 1 and 7"), error.getMessage());
    }

    @Test
    @DisplayName("a leg outside the team's size is refused with the size it does run")
    void refusesALegOutsideTheTeam() {
        RelayTeam formOne = team("1");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addRunner(formOne.getId(), 1L, 5));
        assertTrue(error.getMessage().contains("between 1 and 4"), error.getMessage());

        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> service.addRunner(formOne.getId(), 1L, 0));
        assertTrue(zero.getMessage().contains("between 1 and 4"), zero.getMessage());
    }

    // ================================================== removal and reordering

    @Test
    @DisplayName("removing a runner closes the legs up behind them")
    void removeClosesTheLegsUp() {
        RelayTeam formOne = team("1");
        Student c = formOneRunner(5L);
        service.addRunner(formOne.getId(), 1L, null);
        service.addRunner(formOne.getId(), 2L, null);
        service.addRunner(formOne.getId(), c.getUser().getId(), null);

        RelayTeamDTO after = service.removeRunner(formOne.getId(), 2L);

        assertEquals(2, after.getMemberCount().intValue());
        assertEquals(List.of(1, 2), after.getMembers().stream()
                .map(member -> member.getLeg()).collect(Collectors.toList()));
        assertEquals(List.of("S0001", "S0005"), after.getMembers().stream()
                .map(member -> member.getStudentId()).collect(Collectors.toList()));
        assertTrue(after.getMembers().stream().allMatch(member -> !member.getReserve()),
                "closing the legs up leaves no reserve behind");
    }

    @Test
    @DisplayName("removing somebody who is not in the team is a not-found, not a silent no-op")
    void removeRefusesAnAbsentRunner() {
        RelayTeam formOne = team("1");
        service.addRunner(formOne.getId(), 1L, null);

        assertThrows(ResourceNotFoundException.class,
                () -> service.removeRunner(formOne.getId(), 2L));
    }

    @Test
    @DisplayName("the running order is set leg 1 first")
    void reorderSetsTheLegs() {
        RelayTeam formOne = team("1");
        Student c = formOneRunner(5L);
        service.addRunner(formOne.getId(), 1L, null);
        service.addRunner(formOne.getId(), 2L, null);
        service.addRunner(formOne.getId(), c.getUser().getId(), null);

        RelayTeamDTO reordered = service.reorderLegs(formOne.getId(), List.of(5L, 1L, 2L));

        assertEquals(List.of(5L, 1L, 2L), reordered.getMembers().stream()
                .map(member -> member.getUserId()).collect(Collectors.toList()));
        assertEquals(List.of(1, 2, 3), reordered.getMembers().stream()
                .map(member -> member.getLeg()).collect(Collectors.toList()));
    }

    @Test
    @DisplayName("the running order must name exactly the team's own runners")
    void reorderRefusesAListThatIsNotTheTeam() {
        RelayTeam formOne = team("1");
        service.addRunner(formOne.getId(), 1L, null);
        service.addRunner(formOne.getId(), 2L, null);

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.reorderLegs(formOne.getId(), List.of(1L)));
        assertTrue(missing.getMessage().contains("exactly the 2 runner(s)"), missing.getMessage());

        IllegalArgumentException stranger = assertThrows(IllegalArgumentException.class,
                () -> service.reorderLegs(formOne.getId(), List.of(1L, 2L, 3L)));
        assertTrue(stranger.getMessage().contains("exactly the 2 runner(s)"), stranger.getMessage());

        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class,
                () -> service.reorderLegs(formOne.getId(), List.of(1L, 1L)));
        assertTrue(twice.getMessage().contains("twice"), twice.getMessage());
    }

    @Test
    @DisplayName("there is nothing to reorder in an empty team")
    void reorderRefusesAnEmptyTeam() {
        RelayTeam formOne = team("1");

        assertThrows(IllegalStateException.class,
                () -> service.reorderLegs(formOne.getId(), List.of(1L)));
    }

    // =========================================================== the caller

    @Test
    @DisplayName("a refusal from the class rule stops the runner being named at all")
    void aClassRefusalIsPropagated() {
        RelayTeam formOne = team("1");
        doThrow(new AccessDeniedException("2A is not one of your classes."))
                .when(teacherClassService).requireMayHelp(twoA);

        AccessDeniedException error = assertThrows(AccessDeniedException.class,
                () -> service.addRunner(formOne.getId(), twoA.getUser().getId(), null));

        assertEquals("2A is not one of your classes.", error.getMessage());
        assertTrue(membersOf(formOne.getId()).isEmpty(), "nothing may be written after a refusal");
    }

    @Test
    @DisplayName("removing a runner is held to the same class rule as naming one")
    void removalConsultsTheClassRule() {
        RelayTeam formOne = team("1");
        service.addRunner(formOne.getId(), 1L, null);
        doThrow(new AccessDeniedException("1A is not one of your classes."))
                .when(teacherClassService).requireMayHelp(oneA);

        assertThrows(AccessDeniedException.class,
                () -> service.removeRunner(formOne.getId(), 1L));
        assertEquals(1, membersOf(formOne.getId()).size(), "a refused removal changes nothing");
    }

    @Test
    @DisplayName("reordering asks about every runner whose leg it changes")
    void reorderConsultsTheClassRuleForEveryRunner() {
        RelayTeam formOne = team("1");
        service.addRunner(formOne.getId(), 1L, null);
        service.addRunner(formOne.getId(), 2L, null);
        doThrow(new AccessDeniedException("1B is not one of your classes."))
                .when(teacherClassService).requireMayHelp(oneB);

        assertThrows(AccessDeniedException.class,
                () -> service.reorderLegs(formOne.getId(), List.of(2L, 1L)));
    }

    // ========================================================== housekeeping

    @Test
    @DisplayName("an event's teams and their runners can be removed together")
    void removeTeamsTakesTheRunnersWithThem() {
        RelayTeam formOne = team("1");
        team("2");
        service.addRunner(formOne.getId(), 1L, null);

        int removed = service.removeTeamsForEvent(EVENT_ID);

        assertEquals(2, removed);
        assertTrue(teams.stream().noneMatch(team -> EVENT_ID.equals(team.getEvent().getId())));
        assertTrue(members.isEmpty(), "a runner row cannot outlive its team");
    }

    @Test
    @DisplayName("another event's teams are never touched")
    void otherEventsAreLeftAlone() {
        Event other = Event.builder().id(99L).name("Girls 4x100M Relay · B Grade")
                .type(Event.EventType.RELAY_4X100M).category(EventCategory.TRACK)
                .sex(Sex.FEMALE).grade(Grade.B).eventDate(LocalDate.of(2026, 10, 1))
                .enabled(true).relayTeamKind(RelayTeamKind.FORM).build();
        RelayTeam otherTeam = RelayTeam.builder().id(nextTeamId++).event(other)
                .kind(RelayTeamKind.FORM).teamKey("1").label("Form 1").build();
        teams.add(otherTeam);
        team("1");

        service.removeTeamsForEvent(EVENT_ID);

        assertEquals(1, teams.size());
        assertEquals(otherTeam.getId(), teams.get(0).getId());
    }

    // =============================================================== helpers

    private List<String> teamKeys() {
        return service.getBoard(EVENT_ID).getTeams().stream()
                .map(RelayTeamDTO::getTeamKey).collect(Collectors.toList());
    }

    private List<String> teamLabels() {
        return service.getBoard(EVENT_ID).getTeams().stream()
                .map(RelayTeamDTO::getLabel).collect(Collectors.toList());
    }
}
