package com.sportday.service;

import com.sportday.dto.RelayApplicantDTO;
import com.sportday.dto.RelayEventTeamsDTO;
import com.sportday.dto.RelayTeamDTO;
import com.sportday.entity.Enrollment;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The students who <strong>applied</strong> to a relay event, as the board beside the
 * teams reports them: who they are, which form, class and house they are in, and
 * which team each is already on.
 *
 * <p>This is the list the new page ticks through to make teams, so the things that
 * matter are asserted here rather than left to the page:</p>
 * <ul>
 *   <li>only <strong>confirmed</strong> entrants of <strong>this</strong> event — a
 *       withdrawn or still-pending entry is not an applicant;</li>
 *   <li>each with form, class, house and house code;</li>
 *   <li>the team an applicant is already on, and a count of those still unplaced;</li>
 *   <li>the register's own order — form numerically, then class, then class number;</li>
 *   <li>a teacher sees only their own classes, and an administrator sees all;</li>
 *   <li>and it costs a fixed number of queries for the event, not one per applicant:
 *       the class scope is asked <strong>once</strong> for the page.</li>
 * </ul>
 *
 * <p>The <strong>real</strong> {@link TeacherClassService} is wired in, as in
 * {@code RelayTeamTeacherScopeTest}, so the teacher scope asserted here is the rule
 * itself and not a stub of it.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayApplicantListTest {

    private static final Long EVENT_ID = 42L;
    private static final Long TEACHER_ID = 90L;

    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;
    @Mock private EventRepository eventRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private TeacherClassRepository teacherClassRepository;
    @Mock private UserRepository userRepository;
    @Mock private GradeCalculator gradeCalculator;

    private TeacherClassService teacherClassService;
    private RelayTeamService service;

    private final List<RelayTeam> teams = new ArrayList<>();
    private final List<RelayTeamMember> members = new ArrayList<>();
    private final List<Enrollment> enrollments = new ArrayList<>();
    private final Map<Long, Student> students = new LinkedHashMap<>();

    private Event event;
    private User admin;
    private User teacher;
    private Student oneAFirst;
    private Student oneASecond;
    private Student oneAThird;
    private Student oneB;
    private Student tenB;

    @BeforeEach
    void setUp() {
        teacherClassService = new TeacherClassService(teacherClassRepository, studentRepository,
                userRepository, gradeCalculator);
        service = new RelayTeamService(relayTeamRepository, relayTeamMemberRepository,
                eventRepository, studentRepository, teacherClassService, enrollmentRepository);

        teams.clear();
        members.clear();
        enrollments.clear();
        students.clear();

        admin = user(1L, "admin", User.Role.ADMIN);
        teacher = user(TEACHER_ID, "tchan", User.Role.TEACHER);

        // Two 1A students, so the order within one class is visible too: Chan is
        // class number 1 and Chao is number 2, and the register reads 1 first however
        // the names sort.
        oneAFirst = student(11L, "S0011", "Chan Tai Man", "1A", 1, "Red");
        oneASecond = student(12L, "S0012", "Chao Mei Ling", "1A", 2, "Green");
        oneAThird = student(16L, "S0016", "Yip Ho Yin", "1A", 3, "Red");
        oneB = student(13L, "S0013", "Lee Ka Yan", "1B", 1, " blue ");
        tenB = student(14L, "S0014", "Wong Siu Fung", "10B", 1, "Yellow");

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

        wire();
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

    private Student student(Long userId, String studentId, String name, String className,
                           int classNumber, String house) {
        User account = User.builder().id(userId).username(studentId).fullName(name)
                .role(User.Role.STUDENT).enabled(true).build();
        Student roster = Student.builder()
                .id(userId)
                .user(account)
                .studentId(studentId)
                .name(name)
                .dob(LocalDate.of(2011, 5, 5))
                .sex(Sex.MALE)
                .className(className)
                .classNumber(classNumber)
                .house(house)
                .grade(Grade.B)
                .enabled(true)
                .build();
        students.put(userId, roster);
        return roster;
    }

    /** An entry in this event, confirmed unless the status says otherwise. */
    private Enrollment entered(Student student, Enrollment.EnrollmentStatus status) {
        Enrollment entry = Enrollment.builder()
                .id((long) (enrollments.size() + 1))
                .user(student.getUser())
                .event(event)
                .status(status)
                .build();
        enrollments.add(entry);
        return entry;
    }

    private RelayTeam team(Long id, RelayTeamKind kind, String key, String label) {
        RelayTeam relayTeam = RelayTeam.builder().id(id).event(event).kind(kind)
                .teamKey(key).label(label).build();
        teams.add(relayTeam);
        return relayTeam;
    }

    private RelayTeamMember runs(RelayTeam team, Student student, int leg) {
        RelayTeamMember member = RelayTeamMember.builder().id((long) (members.size() + 1))
                .team(team).user(student.getUser()).leg(leg).build();
        members.add(member);
        return member;
    }

    private void wire() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(relayTeamRepository.findByEventIdOrderByIdAsc(EVENT_ID)).thenAnswer(i -> List.copyOf(teams));
        when(relayTeamMemberRepository.findForEventWithUser(EVENT_ID)).thenAnswer(i -> List.copyOf(members));
        when(relayTeamMemberRepository.findByTeamIdOrderByLegAsc(anyLong())).thenAnswer(invocation -> {
            Long teamId = invocation.getArgument(0);
            return members.stream()
                    .filter(member -> member.getTeam() != null && teamId.equals(member.getTeam().getId()))
                    .sorted(Comparator.comparingInt(RelayTeamMember::getLeg))
                    .toList();
        });
        when(studentRepository.findWithUserByUserIdIn(any())).thenAnswer(invocation -> {
            List<Long> ids = new ArrayList<>(invocation.getArgument(0));
            return students.values().stream()
                    .filter(roster -> ids.contains(roster.getUser().getId()))
                    .toList();
        });
        when(studentRepository.findDistinctClassNames()).thenAnswer(i -> students.values().stream()
                .map(Student::getClassName)
                .distinct()
                .sorted()
                .toList());
        // The register a team is offered runners from, filtered the way the queries
        // filter it: by division and grade, or by division and form.
        when(studentRepository.findActiveBySexAndGrade(any(), any())).thenAnswer(invocation -> {
            Sex sex = invocation.getArgument(0);
            Grade grade = invocation.getArgument(1);
            return students.values().stream()
                    .filter(roster -> roster.getSex() == sex && roster.getGrade() == grade)
                    .toList();
        });
        when(studentRepository.findActiveBySexAndForm(any(), anyString())).thenAnswer(invocation -> {
            Sex sex = invocation.getArgument(0);
            String form = invocation.getArgument(1);
            return students.values().stream()
                    .filter(roster -> roster.getSex() == sex && form.equals(roster.getForm()))
                    .toList();
        });
        when(relayTeamRepository.findById(anyLong())).thenAnswer(invocation -> {
            Long teamId = invocation.getArgument(0);
            return teams.stream().filter(team -> teamId.equals(team.getId())).findFirst();
        });
        // The entries, filtered the way the query filters them: by event and by status.
        when(enrollmentRepository.findConfirmedWithUserByEvent(anyLong(), any())).thenAnswer(invocation -> {
            Long eventId = invocation.getArgument(0);
            Enrollment.EnrollmentStatus status = invocation.getArgument(1);
            return enrollments.stream()
                    .filter(entry -> entry.getStatus() == status)
                    .filter(entry -> entry.getEvent() != null
                            && eventId.equals(entry.getEvent().getId()))
                    .toList();
        });
        when(userRepository.findByUsername(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(switch (invocation.getArgument(0).toString()) {
                    case "admin" -> admin;
                    case "tchan" -> teacher;
                    default -> null;
                }));
    }

    private void signedInAs(User caller) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                caller.getUsername(), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + caller.getRole().name()))));
    }

    // ============================================ the runners a team may still be given

    /** One team's "add a runner" list, by team id. */
    private static List<Long> candidatesOf(RelayEventTeamsDTO board, Long teamId) {
        return board.getTeams().stream()
                .filter(team -> teamId.equals(team.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no team " + teamId + " on the board"))
                .getCandidates().stream()
                .map(RelayApplicantDTO::getUserId)
                .toList();
    }

    @Test
    @DisplayName("a derived team is offered the register's students of its own class")
    void aDerivedTeamIsOfferedItsOwnClass() {
        signedInAs(admin);
        RelayTeam oneATeam = team(1L, RelayTeamKind.FORM, "1A", "1A");
        team(2L, RelayTeamKind.FORM, "1B", "1B");
        // A team somebody made by hand: no class's and no house's, so the register
        // offers it nobody and it is filled through the tick list instead.
        teams.add(RelayTeam.builder().id(3L).event(event).teamKey("Mixed").label("Mixed")
                .handMade(true).build());
        // One 1A student runs for 1A, another for the hand-made team.
        runs(oneATeam, oneAFirst, 1);
        runs(teams.get(2), oneASecond, 1);

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);

        assertEquals(List.of(16L), candidatesOf(board, 1L),
                "1A's own class, minus the two already running in this event");
        assertEquals(List.of(13L), candidatesOf(board, 2L), "1B's own class");
        assertTrue(candidatesOf(board, 3L).isEmpty(),
                "a hand-made team is no class's, so nothing is offered for it");

        // Every candidate is a student who is on no team yet, and carries the
        // register's own facts so the list can be rendered without another lookup.
        RelayApplicantDTO candidate = board.getTeams().stream()
                .filter(team -> 1L == team.getId())
                .findFirst().orElseThrow()
                .getCandidates().get(0);
        assertEquals("S0016", candidate.getStudentRef());
        assertEquals("Yip Ho Yin", candidate.getName());
        assertEquals("1A", candidate.getClassName());
        assertEquals("Red", candidate.getHouse());
        assertEquals(Boolean.FALSE, candidate.getPlaced());
        assertNull(candidate.getTeamId(), "a candidate is on no team by construction");
        assertNull(candidate.getTeamLabel());
    }

    @Test
    @DisplayName("a form relay is offered its form's classes, not the students who entered")
    void aFormRelayIsOfferedItsFormsClasses() {
        signedInAs(admin);
        // A Form 1 relay: teams 1A and 1B whatever grade their students are in. The
        // entrants below are Form 5 and 6 students — the live shape of a relay that
        // was entered before it was scoped to a form — and they can join no team.
        event.setForm("1");
        team(1L, RelayTeamKind.FORM, "1A", "1A");
        team(2L, RelayTeamKind.FORM, "1B", "1B");
        Student formFive = student(21L, "S0021", "Ng Ka Ho", "5A", 1, "Red");
        entered(formFive, Enrollment.EnrollmentStatus.CONFIRMED);

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);

        assertEquals(List.of(21L), board.getApplicants().stream()
                        .map(RelayApplicantDTO::getUserId).toList(),
                "the applicant list is still the entrants");
        assertEquals(List.of(11L, 12L, 16L), candidatesOf(board, 1L),
                "1A is offered the register's Form 1 class 1A — nobody on a team yet");
        assertEquals(List.of(13L), candidatesOf(board, 2L));
        assertFalse(candidatesOf(board, 1L).contains(21L),
                "a Form 5 entrant cannot run in a Form 1 relay and is offered no team");
    }

    @Test
    @DisplayName("a runner removed from a team is offered back to it: Remove moves them to Add a runner")
    void aRemovedRunnerIsOfferedBack() {
        signedInAs(admin);
        RelayTeam oneA = team(1L, RelayTeamKind.FORM, "1A", "1A");
        RelayTeamMember first = runs(oneA, oneAFirst, 2);
        runs(oneA, oneASecond, 3);
        // The runner also entered the event, so both views of the board can be held to
        // the same answer: placed before the removal, unplaced after it.
        entered(oneAFirst, Enrollment.EnrollmentStatus.CONFIRMED);
        when(relayTeamMemberRepository.findByTeamIdAndUserId(1L, 11L)).thenReturn(Optional.of(first));
        when(studentRepository.findWithUserByUserId(11L)).thenReturn(Optional.of(oneAFirst));
        // The mock repository must really forget the leg, as the delete does.
        doAnswer(invocation -> {
            members.remove(invocation.getArgument(0));
            return null;
        }).when(relayTeamMemberRepository).delete(any(RelayTeamMember.class));

        // Before: only the third 1A student is unplaced, so that is all the team is
        // offered, and the applicant list says the runner is on 1A.
        assertEquals(List.of(16L), candidatesOf(service.getBoard(EVENT_ID), 1L));
        RelayApplicantDTO placed = service.getBoard(EVENT_ID).getApplicants().get(0);
        assertEquals(Boolean.TRUE, placed.getPlaced());
        assertEquals("1A", placed.getTeamLabel());

        service.removeRunner(1L, 11L);

        // After: the runner is on no team, so the add-a-runner list offers them
        // straight back — and the legs are closed up behind them.
        assertEquals(List.of(11L, 16L), candidatesOf(service.getBoard(EVENT_ID), 1L),
                "the removed runner is back in the team's Add a runner list");
        RelayTeamDTO after = service.getBoard(EVENT_ID).getTeams().get(0);
        assertEquals(1, after.getMembers().size());
        assertEquals(12L, after.getMembers().get(0).getUserId());
        assertEquals(1, after.getMembers().get(0).getLeg(), "leg 2 closes up to leg 1");
        RelayApplicantDTO unplaced = service.getBoard(EVENT_ID).getApplicants().get(0);
        assertEquals(Boolean.FALSE, unplaced.getPlaced(), "and the applicant list agrees");
        assertNull(unplaced.getTeamId());
    }

    @Test
    @DisplayName("a teacher is offered only the classes they may act for, and nobody else")
    void aTeacherIsOfferedOnlyTheirOwnClasses() {
        signedInAs(teacher);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID))
                .thenReturn(List.of("1A"));
        team(1L, RelayTeamKind.FORM, "1A", "1A");
        team(2L, RelayTeamKind.FORM, "1B", "1B");

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);

        assertEquals(List.of(11L, 12L, 16L), candidatesOf(board, 1L),
                "the teacher's own class, everybody in it unplaced");
        assertTrue(candidatesOf(board, 2L).isEmpty(),
                "and nothing for a class they may not act for — the add would be refused");
    }

    // ============================================ who is an applicant, and what they carry

    @Test
    @DisplayName("only this event's confirmed entrants are applicants")
    void onlyConfirmedEntrantsOfThisEvent() {
        signedInAs(admin);
        entered(oneAFirst, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(oneB, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(tenB, Enrollment.EnrollmentStatus.CONFIRMED);
        // Withdrawn, and entered but not confirmed: neither is an applicant.
        entered(oneASecond, Enrollment.EnrollmentStatus.CANCELLED);
        Student pending = student(15L, "S0015", "Ng Wai", "1B", 2, "Red");
        entered(pending, Enrollment.EnrollmentStatus.PENDING);

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);
        List<Long> applicants = board.getApplicants().stream()
                .map(RelayApplicantDTO::getUserId).toList();

        assertEquals(List.of(11L, 13L, 14L), applicants,
                "the confirmed entrants of this event, and nobody else");
        assertEquals(3, board.getApplicantCount());
        assertFalse(applicants.contains(12L), "a withdrawn entry is not an applicant");
        assertFalse(applicants.contains(15L), "an unconfirmed entry is not an applicant");
        // The status and the event are what the query filters on, so they cannot creep
        // back in by another route.
        verify(enrollmentRepository).findConfirmedWithUserByEvent(
                EVENT_ID, Enrollment.EnrollmentStatus.CONFIRMED);
    }

    @Test
    @DisplayName("every applicant carries form, class, house and house code")
    void everyApplicantCarriesFormClassHouseAndCode() {
        signedInAs(admin);
        entered(oneAFirst, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(oneB, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(tenB, Enrollment.EnrollmentStatus.CONFIRMED);

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);
        RelayApplicantDTO first = board.getApplicants().get(0);
        RelayApplicantDTO third = board.getApplicants().get(2);

        assertEquals("S0011", first.getStudentRef());
        assertEquals("Chan Tai Man", first.getName());
        assertEquals("1", first.getForm());
        assertEquals("1A", first.getClassName());
        assertEquals(1, first.getClassNumber());
        assertEquals("1A 1", first.getClassLabel());
        assertEquals("Red", first.getHouse());
        assertEquals("R", first.getHouseCode());
        assertNull(first.getTeamId());
        assertNull(first.getTeamLabel());
        assertFalse(first.getPlaced());

        assertEquals("10B", third.getClassName());
        assertEquals("10", third.getForm(), "10B is Form 10");
        assertEquals("Yellow", third.getHouse());
        assertEquals("Y", third.getHouseCode());
    }

    @Test
    @DisplayName("the house name is kept in full and only the code is added")
    void theHouseNameIsKeptInFull() {
        signedInAs(admin);
        entered(oneB, Enrollment.EnrollmentStatus.CONFIRMED);

        RelayApplicantDTO applicant = service.getBoard(EVENT_ID).getApplicants().get(0);

        assertEquals(" blue ", applicant.getHouse(), "the register's own value is not rewritten");
        assertEquals("B", applicant.getHouseCode(), "but the code is matched case-insensitively");
    }

    @Test
    @DisplayName("applicants read in school order: form, then class, then class number")
    void applicantsAreInSchoolOrder() {
        signedInAs(admin);
        entered(tenB, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(oneASecond, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(oneB, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(oneAFirst, Enrollment.EnrollmentStatus.CONFIRMED);

        List<String> names = service.getBoard(EVENT_ID).getApplicants().stream()
                .map(RelayApplicantDTO::getName).toList();

        // 1A before 1B before 10B — a plain string order would put 10B first — and
        // within 1A, class number 1 before class number 2, whatever the names sort as.
        assertEquals(List.of("Chan Tai Man", "Chao Mei Ling", "Lee Ka Yan", "Wong Siu Fung"),
                names);
    }

    // ================================================= already on a team, and counts

    @Test
    @DisplayName("an applicant already on a team reports it, and the counts say who is left")
    void anApplicantOnATeamReportsIt() {
        signedInAs(admin);
        RelayTeam formOne = team(1L, RelayTeamKind.FORM, "1A", "1A");
        runs(formOne, oneASecond, 1);
        entered(oneAFirst, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(oneASecond, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(oneB, Enrollment.EnrollmentStatus.CONFIRMED);

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);

        RelayApplicantDTO placed = board.getApplicants().stream()
                .filter(a -> a.getUserId().equals(12L)).findFirst().orElseThrow();
        assertEquals(1L, placed.getTeamId());
        assertEquals("1A", placed.getTeamLabel());
        assertTrue(placed.getPlaced());

        RelayApplicantDTO unplaced = board.getApplicants().stream()
                .filter(a -> a.getUserId().equals(11L)).findFirst().orElseThrow();
        assertNull(unplaced.getTeamId(), "an applicant on no team has no team");
        assertNull(unplaced.getTeamLabel());
        assertFalse(unplaced.getPlaced());

        assertEquals(3, board.getApplicantCount());
        assertEquals(1, board.getPlacedCount());
        assertEquals(2, board.getUnplacedCount());
        // The teams beside them are untouched by any of this.
        assertEquals(1, board.getTeamCount());
        assertEquals(1, board.getRunnerCount());
    }

    @Test
    @DisplayName("a confirmed entry whose account has no register row is not an applicant")
    void anAccountWithNoRegisterRowIsNotAnApplicant() {
        signedInAs(admin);
        entered(oneAFirst, Enrollment.EnrollmentStatus.CONFIRMED);
        // An account the register knows nothing about: no class, no form, no house, so
        // it could not be named in a team either.
        User legacy = user(77L, "legacy", User.Role.STUDENT);
        enrollments.add(Enrollment.builder().id(9L).user(legacy).event(event)
                .status(Enrollment.EnrollmentStatus.CONFIRMED).build());

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);

        assertEquals(List.of(11L), board.getApplicants().stream()
                .map(RelayApplicantDTO::getUserId).toList());
    }

    @Test
    @DisplayName("an undivided relay still reports its applicants, with no teams and no placements")
    void anUndividedRelayStillReportsApplicants() {
        signedInAs(admin);
        event.setRelayTeamKind(null);
        entered(oneAFirst, Enrollment.EnrollmentStatus.CONFIRMED);

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);

        assertEquals(0, board.getTeamCount());
        assertEquals(1, board.getApplicantCount());
        assertEquals(0, board.getPlacedCount());
        assertEquals(1, board.getUnplacedCount());
        assertNull(board.getApplicants().get(0).getTeamId());
    }

    @Test
    @DisplayName("an event nobody entered has no applicants and does not fail")
    void anEventWithNoApplicants() {
        signedInAs(admin);

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);

        assertTrue(board.getApplicants().isEmpty());
        assertEquals(0, board.getApplicantCount());
        assertEquals(0, board.getUnplacedCount());
    }

    // ============================================================== the teacher scope

    @Test
    @DisplayName("a teacher is shown only the applicants of their own classes")
    void aTeacherSeesOnlyTheirOwnClasses() {
        entered(oneAFirst, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(oneASecond, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(oneB, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(tenB, Enrollment.EnrollmentStatus.CONFIRMED);
        signedInAs(teacher);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID))
                .thenReturn(List.of("1A"));
        team(1L, RelayTeamKind.FORM, "1A", "1A");

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);
        List<Long> applicants = board.getApplicants().stream()
                .map(RelayApplicantDTO::getUserId).toList();

        assertEquals(List.of(11L, 12L), applicants, "1A only");
        assertFalse(applicants.contains(13L), "1B is not one of this teacher's classes");
        assertFalse(applicants.contains(14L), "10B is not one of this teacher's classes");
        // The teams themselves are not filtered: a teacher has to see the team they
        // are filling, whichever classes its runners come from.
        assertEquals(1, board.getTeamCount());
        // Asked once for the whole page, not once per applicant.
        verify(teacherClassRepository, times(1))
                .findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID);
    }

    @Test
    @DisplayName("a teacher with no classes assigned is shown no applicant at all")
    void aTeacherWithNoClassesSeesNobody() {
        entered(oneAFirst, Enrollment.EnrollmentStatus.CONFIRMED);
        signedInAs(teacher);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID))
                .thenReturn(List.of());

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);

        assertTrue(board.getApplicants().isEmpty(),
                "a teacher with no classes may help nobody, so nobody is offered");
        assertEquals(0, board.getUnplacedCount());
    }

    @Test
    @DisplayName("an administrator is shown every applicant")
    void anAdminSeesEverybody() {
        entered(oneAFirst, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(oneB, Enrollment.EnrollmentStatus.CONFIRMED);
        signedInAs(admin);

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);

        assertEquals(2, board.getApplicantCount());
        verify(teacherClassRepository, never())
                .findClassNamesByUserIdOrderByClassNameAsc(anyLong());
    }

    @Test
    @DisplayName("on a house relay a teacher sees their own class's applicants, but the whole team")
    void aHouseRelayShowsTheWholeTeamAndTheirOwnApplicants() {
        event.setRelayTeamKind(RelayTeamKind.HOUSE);
        RelayTeam red = team(1L, RelayTeamKind.HOUSE, "Red", "C Grade Red");
        RelayTeam blue = team(2L, RelayTeamKind.HOUSE, "Blue", "C Grade Blue");
        runs(red, oneAFirst, 1);
        runs(blue, oneB, 1);
        entered(oneAFirst, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(oneB, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(tenB, Enrollment.EnrollmentStatus.CONFIRMED);
        signedInAs(teacher);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID))
                .thenReturn(List.of("1A"));

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);

        // The teacher's own applicant: the 1A athlete they may place.
        assertEquals(List.of(11L), board.getApplicants().stream()
                .map(RelayApplicantDTO::getUserId).toList());
        // But both house teams are there, with all their runners named — a house team
        // spans classes, so hiding the other athletes would leave the teacher unable
        // to see who is running with whom.
        assertEquals(2, board.getTeamCount());
        assertEquals(List.of("C Grade Blue", "C Grade Red"),
                board.getTeams().stream().map(t -> t.getLabel()).sorted().toList());
        assertTrue(board.getTeams().stream()
                        .anyMatch(t -> t.getMembers().stream()
                                .anyMatch(m -> "S0013".equals(m.getStudentId()))),
                "the 1B runner is still shown on the Blue team");
    }

    // ============================================================== the cost

    @Test
    @DisplayName("the applicant list costs the event a fixed few queries, not one per applicant")
    void theApplicantListDoesNotQueryPerApplicant() {
        entered(oneAFirst, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(oneASecond, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(oneB, Enrollment.EnrollmentStatus.CONFIRMED);
        entered(tenB, Enrollment.EnrollmentStatus.CONFIRMED);
        signedInAs(teacher);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID))
                .thenReturn(List.of("1A", "1B", "10B"));

        RelayEventTeamsDTO board = service.getBoard(EVENT_ID);

        assertEquals(4, board.getApplicantCount());
        // One query for the entries, one for the register rows behind them (and one
        // more for the team rosters when the event has teams) — never a lookup per
        // applicant.
        verify(enrollmentRepository, times(1))
                .findConfirmedWithUserByEvent(EVENT_ID, Enrollment.EnrollmentStatus.CONFIRMED);
        verify(studentRepository, times(1)).findWithUserByUserIdIn(any());
        verify(studentRepository, never()).findWithUserByUserId(anyLong());
        verify(studentRepository, never()).findWithUserByClassName(anyString());
        verify(teacherClassRepository, times(1))
                .findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID);
    }
}
