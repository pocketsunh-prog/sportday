package com.sportday.service;

import com.sportday.dto.RelayEventTeamsDTO;
import com.sportday.dto.RelayTeamCreateRequest;
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
 * A relay scoped to a <strong>form</strong>: the event the school writes as "Form 1
 * 4x100M", whose derived teams are one per class its <strong>entrants are in</strong> —
 * {@code 1A}, {@code 1B}, {@code 1C} and {@code 1D} here — in class order.
 *
 * <p>The school's requirement, as confirmed: a form relay event is scoped to a
 * <em>form</em>, so a Form 1 event is read from the classes of Form 1 <strong>whatever
 * grade its students are in</strong>, and Form 2 is a separate event. That is the whole
 * point of the feature and the thing nothing else in the suite can notice: every other
 * relay test builds its register inside one grade, where a form-scoped derive and a
 * grade-scoped one give the same answer. (The rule that a form relay's teams are the
 * classes of its <em>entrants</em> — and that one class, or none, is not an error — has
 * its own file, {@code FormRelayTeamsFromEntrantsTest}; this one covers the scope.)</p>
 *
 * <p>So the register here spans grades on purpose. {@code 1A} holds an A-grade boy and
 * a B-grade boy, {@code 1B} and {@code 1D} hold C-grade boys only, and the event itself
 * is a B-grade event — the grade it must keep, because an event is run by exactly one
 * grade, but which a form-scoped event does not judge its runners by.</p>
 *
 * <p>Who entered the relay is part of the fixture: every class of Form 1 that can field
 * a runner has an entrant, and the two classes that cannot — {@code 1E}, whose only
 * athlete is locked, and {@code 1F}, whose only athlete is a girl — have one too, so the
 * file proves that an entry is not enough on its own.</p>
 *
 * <p>The other half of the file is the <strong>regression guard</strong>: the twelve
 * relay events already on the programme have no form, so an event with no form must
 * derive and refuse exactly as it did before the form existed. Those tests assert it
 * against the same register, on the same event with the form taken away.</p>
 *
 * <p>The repositories are backed by in-memory lists, as in the other relay tests, so a
 * derive, a selection and a re-derive run against real read-then-write behaviour rather
 * than a scripted sequence of mock answers.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayTeamFormScopeTest {

    private static final Long EVENT_ID = 42L;

    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;
    @Mock private EventRepository eventRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private TeacherClassService teacherClassService;
    /** The event's entries: a form relay's teams are the classes its entrants are in. */
    @Mock private com.sportday.repository.EnrollmentRepository enrollmentRepository;

    private RelayTeamService service;

    private final List<RelayTeam> teams = new ArrayList<>();
    private final List<RelayTeamMember> members = new ArrayList<>();
    private final Map<Long, Student> students = new LinkedHashMap<>();
    /** Who has entered this relay, by account id. */
    private final java.util.Set<Long> entrants = new java.util.LinkedHashSet<>();
    private long nextTeamId = 1;
    private long nextMemberId = 1;

    private Event event;

    /**
     * The register, by account id:
     *
     * <pre>
     *   1  1A   A grade  boy   Red     — the right form, the wrong grade
     *   2  1A   B grade  boy   Red
     *   3  1B   C grade  boy   Blue    — the right form and grade for 1D's event? no: C
     *   4  1C   A grade  boy   Green
     *   5  1D   C grade  boy   Yellow  — 1D's only athlete, and he is not in B grade
     *   6  2A   B grade  boy   Red     — Form 2: a separate event
     *   7  10B  B grade  boy   Yellow  — Form 10: never folded into Form 1
     *   8  1E   B grade  boy   Red     — locked: not on this year's list
     *   9  1F   A grade  girl  Red     — Form 1, but not this division
     *  10  1B   B grade  boy   Blue
     *  11  1C   B grade  boy   Green
     * </pre>
     */
    @BeforeEach
    void setUp() {
        service = new RelayTeamService(relayTeamRepository, relayTeamMemberRepository,
                eventRepository, studentRepository, teacherClassService, enrollmentRepository);
        teams.clear();
        members.clear();
        students.clear();
        nextTeamId = 1;
        nextMemberId = 1;

        // The selection paths never consult the class rule here: requireMayHelp is a
        // no-op stub, so every athlete in the register may be placed.
        when(teacherClassService.isAdmin()).thenReturn(true);

        student(1L, "1A", Grade.A, Sex.MALE, "Red", true);
        student(2L, "1A", Grade.B, Sex.MALE, "Red", true);
        student(3L, "1B", Grade.C, Sex.MALE, "Blue", true);
        student(4L, "1C", Grade.A, Sex.MALE, "Green", true);
        student(5L, "1D", Grade.C, Sex.MALE, "Yellow", true);
        student(6L, "2A", Grade.B, Sex.MALE, "Red", true);
        student(7L, "10B", Grade.B, Sex.MALE, "Yellow", true);
        student(8L, "1E", Grade.B, Sex.MALE, "Red", false);
        student(9L, "1F", Grade.A, Sex.FEMALE, "Red", true);
        student(10L, "1B", Grade.B, Sex.MALE, "Blue", true);
        student(11L, "1C", Grade.B, Sex.MALE, "Green", true);

        // Who entered the relay: a class of the form that can field a runner, plus 1E
        // (whose only athlete is locked) and 1F (whose only athlete is a girl), so the
        // file says both that the teams are the entrants' classes and that an entry
        // alone is not enough to make one.
        entered(1L, 3L, 4L, 5L, 8L, 9L);

        // A "Form 1 4x100M": scoped to Form 1, run in the Boys division, and it is a
        // B-grade event only because every event must name one grade.
        event = formEvent("1");

        wireRepositories();
    }

    /** Takes an entry in this relay for the given accounts. */
    private void entered(Long... userIds) {
        entrants.addAll(List.of(userIds));
    }

    // ============================================================== fixtures

    private Event formEvent(String form) {
        return Event.builder()
                .id(EVENT_ID)
                .name("Boys 4x100M Relay · Form " + form)
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .grade(Grade.B)
                .form(form)
                .eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512)
                .groupSize(24)
                .enabled(true)
                .relayTeamKind(RelayTeamKind.FORM)
                .build();
    }

    private Student student(long userId, String className, Grade grade, Sex sex, String house,
                            boolean enabled) {
        User user = User.builder().id(userId).username("S%04d".formatted(userId))
                .fullName("Athlete " + userId).role(User.Role.STUDENT).enabled(true).build();
        Student roster = Student.builder()
                .id(userId)
                .user(user)
                .studentId("S%04d".formatted(userId))
                .name("Athlete " + userId)
                .dob(LocalDate.of(2011, 5, 5))
                .sex(sex)
                .className(className)
                .classNumber(1)
                .house(house)
                .grade(grade)
                .enabled(enabled)
                .build();
        students.put(userId, roster);
        return roster;
    }

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
        when(relayTeamRepository.countByEventId(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return teams.stream()
                    .filter(team -> team.getEvent() != null && id.equals(team.getEvent().getId()))
                    .count();
        });

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
        // The two register queries, each backed by the register above exactly as its
        // own SQL is: the graded one is the existing query, the form one is the new
        // query, and each test asserts the other was never asked.
        when(studentRepository.findActiveBySexAndGrade(any(), any())).thenAnswer(invocation -> {
            Sex sex = invocation.getArgument(0);
            Grade grade = invocation.getArgument(1);
            return students.values().stream()
                    .filter(student -> student.getSex() == sex && student.getGrade() == grade)
                    .filter(student -> Boolean.TRUE.equals(student.getEnabled()))
                    .collect(Collectors.toList());
        });
        when(studentRepository.findActiveBySexAndForm(any(), any())).thenAnswer(invocation -> {
            Sex sex = invocation.getArgument(0);
            String form = invocation.getArgument(1);
            return students.values().stream()
                    .filter(student -> student.getSex() == sex)
                    .filter(student -> Boolean.TRUE.equals(student.getEnabled()))
                    .filter(student -> form != null
                            && form.equalsIgnoreCase(Student.formOf(student.getClassName())))
                    .collect(Collectors.toList());
        });
        // The event's entries, as the repository hands them over: one row per account
        // that entered, carrying its user.
        when(enrollmentRepository.findConfirmedWithUserByEvent(anyLong(), any()))
                .thenAnswer(invocation -> {
                    if (!EVENT_ID.equals(invocation.getArgument(0))) {
                        return List.of();
                    }
                    return entrants.stream().map(userId -> com.sportday.entity.Enrollment.builder()
                            .id(userId)
                            .event(event)
                            .user(students.containsKey(userId)
                                    ? students.get(userId).getUser()
                                    : com.sportday.entity.User.builder().id(userId)
                                            .username("U" + userId)
                                            .role(com.sportday.entity.User.Role.STUDENT)
                                            .enabled(true).build())
                            .status(com.sportday.entity.Enrollment.EnrollmentStatus.CONFIRMED)
                            .enrolledAt(LocalDate.of(2026, 9, 1).atStartOfDay())
                            .build())
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

    private RelayEventTeamsDTO board() {
        return service.getBoard(EVENT_ID);
    }

    private List<String> teamKeys() {
        return board().getTeams().stream().map(RelayTeamDTO::getTeamKey)
                .collect(Collectors.toList());
    }

    private RelayTeamDTO teamNamed(String name) {
        return board().getTeams().stream()
                .filter(team -> name.equals(team.getLabel()) || name.equals(team.getTeamKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no team named " + name + " in "
                        + board().getTeams().stream().map(RelayTeamDTO::getLabel).toList()));
    }

    // ============================================== a form event is scoped to its form

    @Test
    @DisplayName("a Form 1 event derives one team per class its entrants are in, across every grade")
    void derivesEveryClassOfTheFormAcrossGrades() {
        service.deriveTeams(EVENT_ID, false);

        // The form spans every grade, and that is what decides WHO may run — a 1A team
        // takes a 1A student whatever grade they are. The classes are the ones the
        // entrants are in, in class order.
        assertEquals(List.of("1A", "1B", "1C", "1D"), teamKeys());
        assertEquals(List.of("1A", "1B", "1C", "1D"),
                board().getTeams().stream().map(RelayTeamDTO::getLabel).toList());

        assertEquals(Grade.A, students.get(4L).getGrade(),
                "1C holds an A-grade athlete, from a B-grade event — the form is the scope");
        assertTrue(teamKeys().contains("1C"));

        // A class of the form whose entrants are not on this year's list, or are not in
        // this division, is no team: nobody could ever be named in one.
        assertFalse(teamKeys().contains("1E"), "1E's entrant is locked, so it is no team");
        assertFalse(teamKeys().contains("1F"), "1F's entrant is a girl, and this is the boys' relay");

        // Another form is another event: neither Form 2 nor Form 10 has a team here.
        assertFalse(teamKeys().contains("2A"), "Form 2 is a separate event");
        assertFalse(teamKeys().contains("10B"), "and Form 10 is not folded into Form 1");
    }

    @Test
    @DisplayName("a class of the form nobody entered from is not a team of the relay")
    void aClassNobodyEnteredFromIsNoTeam() {
        // 1C and 1D hold eligible boys, and nobody entered the relay from them: the two
        // classes are NOT teams. That is the rule that replaced the form's first two
        // classes, and it is the one thing a register-only derive cannot see.
        entrants.clear();
        entered(1L, 3L);

        service.deriveTeams(EVENT_ID, false);

        assertEquals(List.of("1A", "1B"), teamKeys());
        assertFalse(teamKeys().contains("1C"), "1C has students, but no entrant");
        assertFalse(teamKeys().contains("1D"), "and neither has 1D");
    }

    @Test
    @DisplayName("the board says which form it is drawn from")
    void theBoardCarriesTheFormScope() {
        service.deriveTeams(EVENT_ID, false);

        assertEquals("1", board().getForm());
        assertEquals("Form 1", board().getFormLabel());
    }

    @Test
    @DisplayName("the derive reads the form query and not the grade query")
    void theDeriveReadsTheRegisterByForm() {
        service.deriveTeams(EVENT_ID, false);

        verify(studentRepository).findActiveBySexAndForm(Sex.MALE, "1");
        verify(studentRepository, never()).findActiveBySexAndGrade(any(), any());
    }

    @Test
    @DisplayName("a class of the form whose students cannot run yields no team")
    void aClassWithNoEligibleStudentYieldsNoTeam() {
        RelayTeamDerivationDTO result = service.deriveTeams(EVENT_ID, false);

        // 1E's only athlete is locked — not on this year's list — and 1F holds a girl,
        // who is not in this division. Neither class can field a runner, so neither is
        // a team; the seven Form 1 boys on the list are the field.
        assertFalse(teamKeys().contains("1E"), "a locked athlete's class is no team");
        assertFalse(teamKeys().contains("1F"), "and neither is a class of another division");
        assertEquals(7, result.getEligibleStudents());
    }

    @Test
    @DisplayName("a form event still derives with no grade set: its teams do not come from one")
    void aFormEventDoesNotNeedAGrade() {
        event.setGrade(null);

        service.deriveTeams(EVENT_ID, false);

        assertEquals(List.of("1A", "1B", "1C", "1D"), teamKeys());
    }

    // ==================================== who may run: the form, not the grade

    @Test
    @DisplayName("a runner of the right form but the wrong grade is accepted — the whole point")
    void aRunnerOfTheRightFormAndTheWrongGradeIsAccepted() {
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDTO classOneA = teamNamed("1A");

        // Athlete 1 is in 1A — Form 1 — and in the A grade, while the event is a
        // B-grade event. On a graded event that alone would refuse him.
        RelayTeamDTO filled = service.addRunner(classOneA.getId(), 1L, null);

        assertEquals(1, filled.getMemberCount().intValue());
        assertEquals("S0001", filled.getMembers().get(0).getStudentId());
        assertEquals(Grade.A, students.get(1L).getGrade());
    }

    @Test
    @DisplayName("a class team may be filled from a grade the event does not belong to")
    void aClassTeamMayBeFilledFromAnotherGrade() {
        service.deriveTeams(EVENT_ID, false);

        // 1A holds an A-grade athlete and this is a B-grade event — the right form and
        // the right class, so he runs. (The derived pair is 1A and 1B; a third class is
        // the school's to add, so this asks the same question of a class that is there.)
        RelayTeamDTO filled = service.addRunner(teamNamed("1A").getId(), 1L, null);

        assertEquals("1A", filled.getLabel());
        assertEquals(1, filled.getMemberCount().intValue());
        assertEquals("S0001", filled.getMembers().get(0).getStudentId());
        assertEquals(Grade.A, students.get(1L).getGrade());
        assertFalse(filled.getComplete(), "one runner of four: saved and reported incomplete");
    }

    @Test
    @DisplayName("a runner of another form is refused, and the refusal names both forms")
    void aRunnerOfAnotherFormIsRefused() {
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDTO classOneA = teamNamed("1A");

        // Athlete 6 is in 2A — Form 2 — in the event's own grade and division.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(classOneA.getId(), 6L, null));

        assertTrue(error.getMessage().contains("Form 2"), error.getMessage());
        assertTrue(error.getMessage().contains("Form 1"), error.getMessage());
        assertTrue(error.getMessage().contains("Boys 4x100M Relay · Form 1"), error.getMessage());
        assertTrue(membersOf(classOneA.getId()).isEmpty(), "nothing is written after a refusal");
    }

    @Test
    @DisplayName("a runner of another division is still refused on a form event")
    void aRunnerOfAnotherDivisionIsRefused() {
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDTO classOneA = teamNamed("1A");

        // Athlete 9 is in 1F — the right form — but is a girl on a boys' event.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(classOneA.getId(), 9L, null));

        assertTrue(error.getMessage().contains("is entered as"), error.getMessage());
        assertTrue(error.getMessage().contains("Boys"), error.getMessage());
        assertTrue(membersOf(classOneA.getId()).isEmpty());
    }

    @Test
    @DisplayName("a 1A team still refuses a 1B runner: the team is one class, whatever the form")
    void aTeamStillTakesOneClassOnly() {
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDTO classOneA = teamNamed("1A");

        // Athlete 3 is in 1B — the right form, the right division — and still cannot
        // run for 1A.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(classOneA.getId(), 3L, null));

        assertTrue(error.getMessage().contains("1B"), error.getMessage());
        assertTrue(error.getMessage().contains("cannot mix classes"), error.getMessage());
        assertTrue(membersOf(classOneA.getId()).isEmpty());
    }

    @Test
    @DisplayName("a team made by hand on a form event cannot mix classes either")
    void aHandMadeTeamCannotMixClasses() {
        // 2 is in 1A and 3 is in 1B: the same form, two classes. The event's kind is
        // what the rule is read from, and it is FORM.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createTeam(EVENT_ID,
                        new RelayTeamCreateRequest("Form 1 All Stars", List.of(2L, 3L))));

        assertTrue(error.getMessage().contains("1B"), error.getMessage());
        assertTrue(error.getMessage().contains("cannot mix classes"), error.getMessage());
        assertTrue(teams.isEmpty(), "nothing is written after a refusal");
    }

    // ================================= no form: exactly the behaviour of the live events

    @Test
    @DisplayName("an event with no form derives by grade, exactly as it did before the form existed")
    void anEventWithNoFormDerivesByGrade() {
        event.setForm(null);

        RelayTeamDerivationDTO result = service.deriveTeams(EVENT_ID, false);

        // It is the grade query the derive asked, once, exactly as before — and never
        // the form one. Checked before the board is read below, because a board read
        // asks the register once too: it is what the runners each team may still be
        // given are offered from.
        verify(studentRepository).findActiveBySexAndGrade(Sex.MALE, Grade.B);
        verify(studentRepository, never()).findActiveBySexAndForm(any(), any());

        // The event's own grade is B, so only Form 1's B-grade classes are teams: 1A
        // and 1B, plus 1C, 2A and 10B. 1D — 1D holds a C-grade athlete alone — is not.
        assertEquals(List.of("1A", "1B", "1C", "2A", "10B"), teamKeys());
        assertFalse(teamKeys().contains("1D"), "a class of another grade is not a team");
        assertEquals(5, result.getEligibleStudents());

        assertNull(board().getForm(), "no form, so no scope is invented");
        assertNull(board().getFormLabel());
    }

    @Test
    @DisplayName("an event with no form still refuses by grade, form or no form")
    void anEventWithNoFormStillRefusesByGrade() {
        event.setForm(null);
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDTO classOneA = teamNamed("1A");

        // Athlete 1 is in 1A in the A grade; the event is the B-grade event. Without a
        // form there is nothing to admit him by, so he is refused — which is the
        // behaviour the twelve live relay events keep.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(classOneA.getId(), 1L, null));

        assertTrue(error.getMessage().contains("A grade"), error.getMessage());
        assertTrue(error.getMessage().contains("B Grade"), error.getMessage());
        assertTrue(membersOf(classOneA.getId()).isEmpty());
    }

    @Test
    @DisplayName("an event with no grade is refused, as a graded event always was")
    void aGradedEventStillNeedsAGrade() {
        event.setForm(null);
        event.setGrade(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.deriveTeams(EVENT_ID, false));

        assertTrue(error.getMessage().contains("has no grade"), error.getMessage());
    }

    // ================================================== the house relay is unchanged

    @Test
    @DisplayName("a house relay is one team per grade × house, exactly as before")
    void aHouseRelayIsUnchanged() {
        event.setRelayTeamKind(RelayTeamKind.HOUSE);
        event.setForm(null);
        event.setName("Boys 4x100M Relay · B Grade");

        service.deriveTeams(EVENT_ID, false);

        // The B-grade boys' houses: Red (1A, 2A), Blue (1B), Green (1C), Yellow (10B).
        assertEquals(List.of("Blue", "Green", "Red", "Yellow"), teamKeys());
        assertEquals(List.of("B Grade Blue", "B Grade Green", "B Grade Red", "B Grade Yellow"),
                board().getTeams().stream().map(RelayTeamDTO::getLabel).toList());
        assertNull(board().getForm());
    }

    @Test
    @DisplayName("a house relay still refuses a member of another grade")
    void aHouseRelayStillRefusesAnotherGrade() {
        event.setRelayTeamKind(RelayTeamKind.HOUSE);
        event.setForm(null);
        event.setName("Boys 4x100M Relay · B Grade");
        service.deriveTeams(EVENT_ID, false);

        // Athlete 3 is in 1B in the C grade, and 1B's B-grade teammate is in "Blue".
        RelayTeamDTO blue = teamNamed("B Grade Blue");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(blue.getId(), 3L, null));

        assertTrue(error.getMessage().contains("C grade"), error.getMessage());
        assertTrue(error.getMessage().contains("B Grade"), error.getMessage());
    }

    @Test
    @DisplayName("a house relay that somehow carries a form is refused, with the way out")
    void aHouseRelayMayNotBeFormScoped() {
        event.setRelayTeamKind(RelayTeamKind.HOUSE);
        event.setForm("1");

        // Nothing the API can create, because EventService refuses to give a house
        // relay a form — but a row that ended up that way is a fault, not a scope, and
        // deriving it would divide the race by one rule while judging runners by
        // another. It is refused with the way out named.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.deriveTeams(EVENT_ID, false));

        assertTrue(error.getMessage().contains("only a form relay is scoped to a form"),
                error.getMessage());
        assertTrue(error.getMessage().contains("empty string"), error.getMessage());
        assertTrue(teams.isEmpty());
    }
}
