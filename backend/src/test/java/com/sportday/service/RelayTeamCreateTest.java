package com.sportday.service;

import com.sportday.dto.RelayEventTeamsDTO;
import com.sportday.dto.RelayTeamCreateRequest;
import com.sportday.dto.RelayTeamDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Grade;
import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamKind;
import com.sportday.entity.RelayTeamMember;
import com.sportday.entity.Sex;
import com.sportday.entity.Student;
import com.sportday.entity.User;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.RelayTeamMemberRepository;
import com.sportday.repository.RelayTeamRepository;
import com.sportday.repository.StudentRepository;
import com.sportday.repository.TeacherClassRepository;
import com.sportday.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

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
 * A relay team <strong>made by hand</strong> out of chosen students, under a name the
 * school typed.
 *
 * <p>The school's requirement, as confirmed: a teacher ticks the students who applied
 * to the relay and creates a team from them, typing the team's own name as free text —
 * {@code 1A}, {@code B Grade Yellow}, anything. The team is deliberately <em>not</em>
 * required to be one class or one house, which is exactly what a derived
 * {@code FORM} or {@code HOUSE} team cannot express.</p>
 *
 * <p>Three things are asserted here that nothing else can assert:</p>
 * <ol>
 *   <li>a team spans classes and houses, with the runners in leg order and the name
 *       the caller typed;</li>
 *   <li>every existing rule still refuses — blank, over-long and duplicate names, an
 *       ineligible student, a student already running in this event, a sixth runner —
 *       by <em>reusing</em> the rules rather than restating them;</li>
 *   <li><strong>a later derive leaves a hand-made team completely alone</strong> — not
 *       renamed, not re-keyed and, with {@code prune=true}, not pruned. This is the
 *       one most likely to be silently wrong, so it is asserted twice: once for a team
 *       with runners and once for an empty one, which is where a prune would bite.</li>
 * </ol>
 *
 * <p>The repositories are backed by in-memory lists, and {@link TeacherClassService} is
 * the <strong>real</strong> one, so the class rule is genuinely exercised rather than
 * stubbed away.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayTeamCreateTest {

    private static final Long EVENT_ID = 42L;
    private static final Long ADMIN_ID = 1L;
    private static final Long TEACHER_ID = 90L;

    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;
    @Mock private EventRepository eventRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private TeacherClassRepository teacherClassRepository;
    @Mock private UserRepository userRepository;
    @Mock private GradeCalculator gradeCalculator;
    /** The event's entries: unused here, but the board reads them for its applicants. */
    @Mock private EnrollmentRepository enrollmentRepository;

    private TeacherClassService teacherClassService;
    private RelayTeamService service;

    private final List<RelayTeam> teams = new ArrayList<>();
    private final List<RelayTeamMember> members = new ArrayList<>();
    private final Map<Long, Student> students = new LinkedHashMap<>();
    private long nextTeamId = 1;
    private long nextMemberId = 1;
    private long nextUserId = 100;

    private Event event;
    private User admin;
    private User teacher;

    @BeforeEach
    void setUp() {
        teacherClassService = new TeacherClassService(teacherClassRepository, studentRepository,
                userRepository, gradeCalculator);
        service = new RelayTeamService(relayTeamRepository, relayTeamMemberRepository,
                eventRepository, studentRepository, teacherClassService, enrollmentRepository);

        teams.clear();
        members.clear();
        students.clear();
        nextTeamId = 1;
        nextMemberId = 1;
        nextUserId = 100;

        admin = user(ADMIN_ID, "admin", User.Role.ADMIN);
        teacher = user(TEACHER_ID, "tchan", User.Role.TEACHER);

        event = Event.builder()
                .id(EVENT_ID)
                .name("Boys 4x100M Relay · B Grade")
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .grade(Grade.B)
                .eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512)
                .groupSize(24)
                .enabled(true)
                .relayTeamKind(RelayTeamKind.FORM)
                .build();

        // Three classes and three houses on the register, so a derive has something to
        // make teams out of and a hand-made team has something to sit beside.
        runner("1A", "Red");
        runner("1B", "Blue");
        runner("2A", "Green");
        runner("10B", "Yellow");

        wireRepositories();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ============================================================== fixtures

    private User user(Long id, String username, User.Role role) {
        return User.builder().id(id).username(username).password("x").fullName(username)
                .role(role).enabled(true).build();
    }

    /** One athlete on the register, in the event's own grade and division unless told otherwise. */
    private Student student(String className, String house, Grade grade, Sex sex) {
        long userId = nextUserId++;
        String studentId = "S%04d".formatted(userId);
        User account = User.builder().id(userId).username(studentId).fullName("Athlete " + studentId)
                .role(User.Role.STUDENT).enabled(true).build();
        Student roster = Student.builder()
                .id(userId)
                .user(account)
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
        students.put(userId, roster);
        return roster;
    }

    /** An athlete who may run in this event: B Grade, boys. */
    private Student runner(String className, String house) {
        return student(className, house, Grade.B, Sex.MALE);
    }

    private long userIdOf(Student student) {
        return student.getUser().getId();
    }

    /** Signs a staff account in the way the JWT filter does. */
    private void signedInAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user.getUsername(), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
        when(userRepository.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
    }

    /** Backs the repositories with the in-memory lists above. */
    private void wireRepositories() {
        when(eventRepository.findById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (EVENT_ID.equals(id)) {
                return Optional.of(event);
            }
            return teams.stream().map(RelayTeam::getEvent)
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
        when(relayTeamRepository.save(any())).thenAnswer(invocation -> storeTeam(invocation.getArgument(0)));
        // createTeam writes the team and then its runners, so it needs the id before it
        // reads anything back: saveAndFlush is the same in-memory write as save.
        when(relayTeamRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> storeTeam(invocation.getArgument(0)));
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

        when(studentRepository.findWithUserByUserId(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(students.get(invocation.getArgument(0))));
        // TeacherClassService re-resolves a student's class from the register by their
        // student id, so the check reads the class the register holds now.
        when(studentRepository.findByStudentId(anyString())).thenAnswer(invocation -> {
            String studentId = invocation.getArgument(0);
            return students.values().stream()
                    .filter(student -> studentId.equals(student.getStudentId()))
                    .findFirst();
        });
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

    private RelayTeam storeTeam(RelayTeam saved) {
        if (saved.getId() == null) {
            saved.setId(nextTeamId++);
        }
        if (!teams.contains(saved)) {
            teams.add(saved);
        }
        return saved;
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

    private RelayEventTeamsDTO board() {
        return service.getBoard(EVENT_ID);
    }

    private RelayTeamDTO teamNamed(String name) {
        return board().getTeams().stream()
                .filter(team -> name.equals(team.getLabel()) || name.equals(team.getTeamKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no team named " + name + " in "
                        + board().getTeams().stream().map(RelayTeamDTO::getLabel).toList()));
    }

    private RelayTeamCreateRequest request(String name, Long... userIds) {
        RelayTeamCreateRequest request = new RelayTeamCreateRequest();
        request.setName(name);
        request.setUserIds(List.of(userIds));
        return request;
    }

    // =============================================== a team out of chosen students

    @Test
    @DisplayName("a team is made from chosen students, named what the school typed, with leg 1 first")
    void createsATeamFromChosenStudentsInLegOrder() {
        signedInAs(admin);
        // The event is a form-class relay, so the team is one class's — and that binds
        // a team built by hand exactly as it binds a derived one. The name is still the
        // school's own free text, and the runners are still in the order given.
        Student first = runner("1A", "Red");
        Student second = runner("1A", "Blue");
        Student third = runner("1A", "Green");
        Student fourth = runner("1A", "Yellow");

        RelayTeamDTO created = service.createTeam(EVENT_ID,
                request("  B Grade Yellow  ", userIdOf(first), userIdOf(second), userIdOf(third),
                        userIdOf(fourth)));

        assertEquals("B Grade Yellow", created.getLabel(), "the name is the school's, trimmed");
        assertEquals("B Grade Yellow", created.getTeamKey(), "and it is the team's key");
        assertEquals(4, created.getMemberCount().intValue());
        assertTrue(created.getComplete(), "four runners is a complete team");
        assertEquals(List.of(first.getStudentId(), second.getStudentId(),
                        third.getStudentId(), fourth.getStudentId()),
                created.getMembers().stream().map(m -> m.getStudentId()).toList(),
                "in the order given, so leg 1 is the first student listed");
        assertEquals(List.of(1, 2, 3, 4),
                created.getMembers().stream().map(m -> m.getLeg()).toList());
        assertTrue(created.getMembers().stream().noneMatch(m -> Boolean.TRUE.equals(m.getReserve())),
                "the first four hold the four legs");

        assertEquals(EVENT_ID, created.getEventId());
    }

    @Test
    @DisplayName("a hand-made team reports itself as one, and belongs to no class and no house")
    void aHandMadeTeamCarriesNoKind() {
        signedInAs(admin);
        Student one = runner("1A", "Red");

        RelayTeamDTO created = service.createTeam(EVENT_ID, request("1A Boys", userIdOf(one)));

        assertTrue(created.getHandMade(), "the client is told this team was made by hand");
        assertNull(created.getKind(),
                "it is not one class's team and not one house's, so it has no derive kind");
        assertFalse(created.getComplete(), "one runner of four is incomplete, not refused");
        assertEquals(1, created.getMemberCount().intValue());
    }

    /** The event undivided is the state a relay is in while its teams are put together. */
    @Test
    @DisplayName("a hand-made team can be made on an event nobody has divided into form or house")
    void aHandMadeTeamNeedsNoDerivedKindOnItsEvent() {
        signedInAs(admin);
        event.setRelayTeamKind(null);

        RelayTeamDTO created = service.createTeam(EVENT_ID, request("Yellow Squad",
                userIdOf(runner("1A", "Red"))));

        assertEquals("Yellow Squad", created.getLabel());
        assertEquals(List.of("Yellow Squad"), board().getTeams().stream()
                .map(RelayTeamDTO::getLabel).toList(),
                "and the board reports it, though the event derives no teams at all");
        assertNull(board().getRelayTeamKind());
    }

    @Test
    @DisplayName("a team of fewer than four is saved and reported incomplete, as adding one at a time is")
    void aShortTeamIsIncompleteNotRefused() {
        signedInAs(admin);

        RelayTeamDTO created = service.createTeam(EVENT_ID,
                request("2A Seconds", userIdOf(runner("2A", "Red")), userIdOf(runner("2A", "Blue"))));

        assertEquals(2, created.getMemberCount().intValue());
        assertFalse(created.getComplete());
        assertEquals(4, created.getLegCount().intValue());
    }

    // =============================================================== the name

    @Test
    @DisplayName("a blank name is refused, and no team is written")
    void aBlankNameIsRefused() {
        signedInAs(admin);
        Student one = runner("1A", "Red");

        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> service.createTeam(EVENT_ID, request("   ", userIdOf(one))));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.createTeam(EVENT_ID, request(null, userIdOf(one))));
        IllegalArgumentException noBody = assertThrows(IllegalArgumentException.class,
                () -> service.createTeam(EVENT_ID, null));

        for (IllegalArgumentException error : List.of(blank, missing, noBody)) {
            assertTrue(error.getMessage().contains("needs a name"), error.getMessage());
        }
        assertTrue(teams.isEmpty(), "a refused name writes no team");
    }

    @Test
    @DisplayName("a name too long for a sheet is refused at the same 40 characters a rename uses")
    void anOverLongNameIsRefused() {
        signedInAs(admin);
        Student one = runner("1A", "Red");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTeam(EVENT_ID, request("x".repeat(41), userIdOf(one))));

        assertTrue(error.getMessage().contains("at most 40 characters"), error.getMessage());
        assertTrue(teams.isEmpty(), "a refused name writes no team");
        // The ceiling is not simply refusing: a name that fits is accepted.
        assertEquals("y".repeat(40),
                service.createTeam(EVENT_ID, request("y".repeat(40), userIdOf(one))).getLabel());
    }

    @Test
    @DisplayName("a name another team of the same event already has is refused, in any case")
    void aDuplicateNameIsRefused() {
        signedInAs(admin);
        // A derive made 1A; the school's own team cannot be called the same thing,
        // because a marking sheet lists a race's teams by name.
        service.deriveTeams(EVENT_ID, false);
        assertTrue(teams.stream().anyMatch(team -> "1A".equals(team.getLabel())));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTeam(EVENT_ID, request("1a", userIdOf(runner("1A", "Red")))));

        assertTrue(error.getMessage().contains("already the name of another team"),
                error.getMessage());
        assertEquals(1, teams.stream().filter(team -> "1A".equals(team.getLabel())).count(),
                "the refused team was not written");

        // A second hand-made team cannot take the first one's name either.
        service.createTeam(EVENT_ID, request("Yellow", userIdOf(runner("1B", "Blue"))));
        assertThrows(IllegalArgumentException.class,
                () -> service.createTeam(EVENT_ID, request("  yellow  ", userIdOf(runner("2A", "Red")))));
    }

    // ========================================================== eligibility

    @Test
    @DisplayName("a student outside the event's division or grade is refused, and the athlete is named")
    void anIneligibleStudentIsRefused() {
        signedInAs(admin);
        Student wrongGrade = student("1A", "Red", Grade.A, Sex.MALE);
        Student wrongSex = student("1A", "Red", Grade.B, Sex.FEMALE);

        IllegalStateException gradeError = assertThrows(IllegalStateException.class,
                () -> service.createTeam(EVENT_ID, request("Mixed", userIdOf(wrongGrade))));
        assertTrue(gradeError.getMessage().contains("B Grade"), gradeError.getMessage());
        assertTrue(gradeError.getMessage().contains(wrongGrade.getStudentId()),
                gradeError.getMessage());

        IllegalStateException sexError = assertThrows(IllegalStateException.class,
                () -> service.createTeam(EVENT_ID, request("Mixed", userIdOf(wrongSex))));
        assertTrue(sexError.getMessage().contains("Boys 4x100M Relay"), sexError.getMessage());

        assertTrue(teams.isEmpty(), "a refused runner leaves no team behind");
    }

    @Test
    @DisplayName("a student already on another team of this event is refused — one athlete, one leg")
    void aStudentAlreadyRunningInTheEventIsRefused() {
        signedInAs(admin);
        service.deriveTeams(EVENT_ID, false);
        Student already = runner("1A", "Red");
        service.addRunner(teamNamed("1A").getId(), userIdOf(already), null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createTeam(EVENT_ID, request("1A Seconds", userIdOf(already))));

        assertTrue(error.getMessage().contains("already runs for"), error.getMessage());
        assertTrue(error.getMessage().contains("1A"), error.getMessage());
        assertTrue(teams.stream().noneMatch(team -> "1A Seconds".equals(team.getLabel())),
                "the whole request is refused, not just the runner");
    }

    @Test
    @DisplayName("a student not on the register is refused")
    void anUnknownAccountIsRefused() {
        signedInAs(admin);

        assertThrows(com.sportday.exception.ResourceNotFoundException.class,
                () -> service.createTeam(EVENT_ID, request("Ghosts", 9999L)));
        assertTrue(teams.stream().noneMatch(team -> "Ghosts".equals(team.getLabel())),
                "an account with no register row writes nothing");
    }

    // ============================================ four runners and one reserve

    @Test
    @DisplayName("the fifth runner is the reserve, the sixth is refused however it is asked for")
    void fourRunnersAndOneReserve() {
        signedInAs(admin);
        event.setRelayReservesAllowed(true);
        Student one = runner("1A", "Red");
        Student two = runner("1A", "Red");
        Student three = runner("1A", "Red");
        Student four = runner("1A", "Red");
        Student five = runner("1A", "Red");
        Student six = runner("1A", "Red");

        RelayTeamDTO created = service.createTeam(EVENT_ID, request("1A Squad",
                userIdOf(one), userIdOf(two), userIdOf(three), userIdOf(four),
                userIdOf(five)));

        assertEquals(5, created.getMemberCount().intValue());
        assertEquals(5, created.getMemberCap().intValue(), "four legs and one backup");
        assertTrue(created.getComplete(), "a reserve does not make a team incomplete");
        assertFalse(created.getMembers().get(3).getReserve(), "leg 4 is the last leg");
        assertTrue(created.getMembers().get(4).getReserve(), "the fifth runner is the reserve");

        // A sixth in the request that creates the team...
        IllegalStateException tooMany = assertThrows(IllegalStateException.class,
                () -> service.createTeam(EVENT_ID, request("1A Six", userIdOf(one), userIdOf(two),
                        userIdOf(three), userIdOf(four), userIdOf(five), userIdOf(six))));
        assertTrue(tooMany.getMessage().contains("at most 5"), tooMany.getMessage());
        assertTrue(teams.stream().noneMatch(team -> "1A Six".equals(team.getLabel())),
                "and nothing at all is written");

        // ...and a sixth added to a team that is already full, exactly as before.
        IllegalStateException addedLater = assertThrows(IllegalStateException.class,
                () -> service.addRunner(created.getId(), userIdOf(six), null));
        assertTrue(addedLater.getMessage().contains("full squad of 5"), addedLater.getMessage());
        assertEquals(5, membersOf(created.getId()).size());
    }

    @Test
    @DisplayName("without reserves the fifth runner is already one too many")
    void withoutReservesTheCapIsFour() {
        signedInAs(admin);
        Student one = runner("1A", "Red");
        Student two = runner("1A", "Red");
        Student three = runner("1A", "Red");
        Student four = runner("1A", "Red");
        Student five = runner("1A", "Red");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createTeam(EVENT_ID, request("1A Five", userIdOf(one), userIdOf(two),
                        userIdOf(three), userIdOf(four), userIdOf(five))));

        assertTrue(error.getMessage().contains("no reserves"), error.getMessage());
        assertTrue(error.getMessage().contains("at most 4"), error.getMessage());
    }

    /** The same student listed twice is a request that cannot mean anything. */
    @Test
    @DisplayName("the same student named twice in one team is refused")
    void theSameStudentTwiceIsRefused() {
        signedInAs(admin);
        Student one = runner("1A", "Red");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTeam(EVENT_ID, request("Twice", userIdOf(one), userIdOf(one))));

        assertTrue(error.getMessage().contains("listed twice"), error.getMessage());
    }

    // ============================================================== the teacher

    @Test
    @DisplayName("a teacher may only build a team out of their own classes' students")
    void aTeacherMayOnlyUseTheirOwnClasses() {
        signedInAs(teacher);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID))
                .thenReturn(List.of("1A", "2A"));
        Student mine = runner("1A", "Red");
        Student notMine = runner("1B", "Blue");

        RelayTeamDTO created = service.createTeam(EVENT_ID,
                request("My Team", userIdOf(mine)));
        assertEquals(1, created.getMemberCount().intValue());

        AccessDeniedException error = assertThrows(AccessDeniedException.class,
                () -> service.createTeam(EVENT_ID, request("Not Mine", userIdOf(notMine))));
        assertEquals("1B is not one of your classes.", error.getMessage());
        assertTrue(teams.stream().noneMatch(team -> "Not Mine".equals(team.getLabel())),
                "a request with one student the teacher may not help writes nothing at all");
    }

    @Test
    @DisplayName("a teacher with no classes assigned can build no team at all")
    void aTeacherWithNoClassesBuildsNothing() {
        signedInAs(teacher);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID))
                .thenReturn(List.of());

        AccessDeniedException error = assertThrows(AccessDeniedException.class,
                () -> service.createTeam(EVENT_ID, request("Mine", userIdOf(runner("1A", "Red")))));

        assertTrue(error.getMessage().contains("no classes assigned"), error.getMessage());
    }

    @Test
    @DisplayName("a student account may not build a team, not even out of itself")
    void aStudentMayNotBuildATeam() {
        Student account = runner("1A", "Red");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                account.getUser().getUsername(), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        when(userRepository.findByUsername(account.getUser().getUsername()))
                .thenReturn(Optional.of(account.getUser()));

        AccessDeniedException error = assertThrows(AccessDeniedException.class,
                () -> service.createTeam(EVENT_ID, request("Ours", userIdOf(account))));

        assertTrue(error.getMessage().contains("Only a teacher or an administrator"),
                error.getMessage());
    }

    // ========================================= a later derive leaves it alone

    @Test
    @DisplayName("a later derive does not rename, re-key or prune a hand-made team")
    void aDeriveLeavesAHandMadeTeamAlone() {
        signedInAs(admin);
        // One class's students, because the event is a form-class relay — but a name the
        // school typed itself, which is what this test is about.
        Student mine = runner("1A", "Red");
        Student theirs = runner("1A", "Green");
        RelayTeamDTO created = service.createTeam(EVENT_ID,
                request("B Grade Yellow", userIdOf(mine), userIdOf(theirs)));
        Long id = created.getId();
        RelayTeam row = teams.stream().filter(team -> id.equals(team.getId())).findFirst().orElseThrow();
        assertEquals("B Grade Yellow", row.getTeamKey());

        // Derive the roster's own teams beside it, twice, and then prune as hard as the
        // caller can: the hand-made team is not the roster's, so none of it touches it.
        event.setRelayTeamKind(RelayTeamKind.FORM);
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDTO second = service.deriveTeams(EVENT_ID, true).getBoard().getTeams().stream()
                .filter(team -> id.equals(team.getId())).findFirst().orElseThrow();

        assertEquals("B Grade Yellow", second.getLabel(), "the typed name survives a derive");
        assertEquals("B Grade Yellow", second.getTeamKey(), "and the key it is matched on");
        assertTrue(second.getHandMade());
        assertNull(second.getKind(), "a derive never adopts it as one of its own");
        assertEquals(2, second.getMemberCount().intValue(), "and its runners are untouched");
        assertTrue(teams.stream().anyMatch(team -> id.equals(team.getId())),
                "and it is still there after prune=true");
        // The derived teams are there beside it, exactly as before.
        assertTrue(teams.stream().anyMatch(team -> "1A".equals(team.getTeamKey())
                && team.getKind() == RelayTeamKind.FORM));
    }

    /**
     * The sharp edge of the same rule: a hand-made team with nobody on it is exactly
     * the shape a prune deletes — empty, and not a key the roster ever wanted. It must
     * still survive, or a teacher who names a team before choosing its runners loses it
     * the next time anybody derives.
     */
    @Test
    @DisplayName("prune=true does not take away a hand-made team that has nobody in it yet")
    void aPruneKeepsAnEmptyHandMadeTeam() {
        signedInAs(admin);
        RelayTeamDTO empty = service.createTeam(EVENT_ID, request("Yellow Squad"));
        Long id = empty.getId();
        assertEquals(0, empty.getMemberCount().intValue());
        assertEquals("Yellow Squad", empty.getTeamKey());

        service.deriveTeams(EVENT_ID, true);

        assertTrue(teams.stream().anyMatch(team -> id.equals(team.getId())),
                "a team the school named itself is nobody's to prune");
        assertEquals("Yellow Squad", teamNamed("Yellow Squad").getLabel());
        // The derived teams the roster does call for are still pruned as always.
        assertTrue(teams.stream().anyMatch(team -> "1A".equals(team.getTeamKey())));
    }

    @Test
    @DisplayName("a derive never collides with a hand-made team's key, and vice versa")
    void aDeriveCannotTakeAHandMadeTeamsKey() {
        signedInAs(admin);
        // The school names its own team after a class the roster also derives a team
        // for. Different keys happen to be distinct anyway; what makes the pair legal
        // under (event_id, kind, team_key) is that the hand-made team has no kind.
        RelayTeamDTO mine = service.createTeam(EVENT_ID, request("1B Squad"));
        service.deriveTeams(EVENT_ID, false);

        Map<Long, RelayTeamDTO> onBoard = board().getTeams().stream()
                .collect(Collectors.toMap(RelayTeamDTO::getId, team -> team));
        assertTrue(onBoard.containsKey(mine.getId()),
                "the school's own team is still on the board: "
                        + board().getTeams().stream().map(RelayTeamDTO::getLabel).toList());
        assertEquals("1B Squad", onBoard.get(mine.getId()).getLabel());
        assertTrue(onBoard.get(mine.getId()).getHandMade());
        assertNull(onBoard.get(mine.getId()).getKind(),
                "it belongs to no kind, which is what keeps it out of the derive's key space");
        assertTrue(teams.stream().anyMatch(team -> team.getKind() == RelayTeamKind.FORM
                        && "1B".equals(team.getTeamKey())),
                "and the roster's own 1B is there beside it");
        // A hand-made team is read after the derived ones: it belongs to neither kind.
        assertTrue(board().getTeams().get(board().getTeams().size() - 1).getHandMade());
    }

    @Test
    @DisplayName("a hand-made team is filled one runner at a time, but never mixes classes")
    void aHandMadeTeamStillTakesRunnersAddedOneAtATime() {
        signedInAs(admin);
        RelayTeamDTO created = service.createTeam(EVENT_ID, request("Squad"));
        Student one = runner("2C", "Green");

        RelayTeamDTO filled = service.addRunner(created.getId(), userIdOf(one), null);

        assertEquals(1, filled.getMemberCount().intValue());
        assertEquals(one.getStudentId(), filled.getMembers().get(0).getStudentId());

        // The event is a form-class relay, so a team on it takes one class only — and
        // that binds a hand-made team too, even though its own name is not a class
        // name. A relay team never mixes; the rule comes from the event.
        IllegalStateException mixed = assertThrows(IllegalStateException.class,
                () -> service.addRunner(created.getId(), userIdOf(runner("1B", "Red")), null));
        assertTrue(mixed.getMessage().contains("cannot mix classes"), mixed.getMessage());
    }

    @Test
    @DisplayName("a hand-made team takes one class only, and a mixed one is refused outright")
    void aHandMadeTeamCannotMixClasses() {
        signedInAs(admin);
        Student one = runner("2C", "Green");
        Student sameClass = runner("2C", "Red");
        Student otherClass = runner("1B", "Red");
        // Neither of these holds a leg yet, so the only thing that can refuse the mixed
        // request is the class rule itself.
        Student fresh = runner("2C", "Green");
        Student freshOtherClass = runner("1B", "Red");

        RelayTeamDTO made = service.createTeam(EVENT_ID,
                request("Squad", userIdOf(one), userIdOf(sameClass)));
        assertEquals(2, made.getMemberCount().intValue(),
                "two students of one class make a team, whatever the team is called");

        IllegalStateException mixed = assertThrows(IllegalStateException.class,
                () -> service.createTeam(EVENT_ID,
                        request("Mixed", userIdOf(fresh), userIdOf(freshOtherClass))));
        assertTrue(mixed.getMessage().contains(freshOtherClass.getName()),
                "the refusal names the student who does not belong: " + mixed.getMessage());
        assertTrue(mixed.getMessage().contains("cannot mix classes"), mixed.getMessage());
        assertTrue(teams.stream().noneMatch(team -> "Mixed".equals(team.getLabel())),
                "and a refused squad writes nothing at all");
    }

    @Test
    @DisplayName("a house relay team takes one house only, whatever the classes are")
    void aHandMadeTeamCannotMixHouses() {
        signedInAs(admin);
        event.setRelayTeamKind(RelayTeamKind.HOUSE);
        Student green = runner("2C", "Green");
        Student greenToo = runner("2D", "Green");
        Student blue = runner("2C", "Blue");
        // Fresh runners, so the house rule is the only rule that can refuse the mix.
        Student greenFresh = runner("2C", "Green");
        Student blueFresh = runner("2D", "Blue");

        RelayTeamDTO made = service.createTeam(EVENT_ID,
                request("Green Squad", userIdOf(green), userIdOf(greenToo)));
        assertEquals(2, made.getMemberCount().intValue(),
                "two different classes are fine while the house is the same");

        IllegalStateException mixed = assertThrows(IllegalStateException.class,
                () -> service.createTeam(EVENT_ID,
                        request("Mixed House", userIdOf(greenFresh), userIdOf(blueFresh))));
        assertTrue(mixed.getMessage().contains(blueFresh.getName()),
                "the refusal names the student who does not belong: " + mixed.getMessage());
        assertTrue(mixed.getMessage().contains("cannot mix houses"), mixed.getMessage());
        assertTrue(teams.stream().noneMatch(team -> "Mixed House".equals(team.getLabel())),
                "and a refused squad writes nothing at all");
    }
}
