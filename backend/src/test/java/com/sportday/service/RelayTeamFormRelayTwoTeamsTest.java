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
import com.sportday.repository.EnrollmentRepository;
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
 * The school's rule for a <strong>form class relay</strong>: it makes
 * <strong>two teams</strong>, not one per class of the form.
 *
 * <p>As the school put it, "form class relay event from 4 team to 2 team required",
 * and the option they chose was "make only 2 teams for a form relay in the first
 * place" — so this is the <em>derive</em> that changed. The readiness rule changed
 * only in what it counts: a relay may hold two teams to four, a team nobody has been
 * named in is not in the race, and two full teams beside two empty ones are ready —
 * which is the school's own case, a Form 2 relay holding 2A, 2B, 2C and 2D with 2B
 * and 2D still empty.</p>
 *
 * <p>What the file pins down, one test each:</p>
 * <ul>
 *   <li>a Form 3 relay derives {@code 3A} and {@code 3B} — the form's first two
 *       classes <strong>in class order</strong> — and not {@code 3C} or {@code 3D};</li>
 *   <li>deriving twice gives the same two, because the pair is read from the classes
 *       and not from the order the register came back in;</li>
 *   <li>a team that already holds runners survives a re-derive, and an empty extra
 *       from a four-team relay is dropped only when the caller asks to prune — which
 *       is how the school trims a relay that was derived before this rule;</li>
 *   <li>a two-team form relay with every leg filled is <strong>ready</strong> and its
 *       board renders both teams;</li>
 *   <li>a relay left holding four teams of which two are empty is ready too: the empty
 *       teams are not in the race and do not hold it back;</li>
 *   <li>a form with one eligible class derives one team and a form with none derives
 *       none — neither is an error, and one team is simply not ready to mark yet;</li>
 *   <li>a form with a third or fourth team — made by hand — is still <strong>accepted
 *       and ready</strong>: nothing refuses more than two and nothing demands
 *       exactly two, so the school may run two, three or four;</li>
 *   <li>a house relay is untouched: one team per house of the event's grade.</li>
 * </ul>
 *
 * <p>The register spans the classes on purpose — {@code 3A} to {@code 3D}, four
 * athletes each — with a Form 2 athlete, a girl and a locked athlete beside them, so
 * a derive that took the wrong two classes, the wrong form or the wrong division
 * would be visible rather than accidentally right. The repositories are backed by
 * in-memory lists, as in the other relay tests, so a derive, a selection and a
 * re-derive run against real read-then-write behaviour.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayTeamFormRelayTwoTeamsTest {

    private static final Long EVENT_ID = 42L;

    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;
    @Mock private EventRepository eventRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private TeacherClassService teacherClassService;
    /** The event's entries: no confirmed entry, so the board has no applicants. */
    @Mock private EnrollmentRepository enrollmentRepository;

    private RelayTeamService service;
    /** The one readiness rule, over the same teams the service reads. */
    private RelayReadiness readiness;

    private final List<RelayTeam> teams = new ArrayList<>();
    private final List<RelayTeamMember> members = new ArrayList<>();
    private final Map<Long, Student> students = new LinkedHashMap<>();
    private long nextTeamId = 1;
    private long nextMemberId = 1;

    private Event event;

    /**
     * The register, by account id — a Form 3 relay's field:
     *
     * <pre>
     *   1,  2,  3,  4   3A  A grade  boy   Red, Blue, Green, Yellow
     *   5,  6,  7,  8   3B  A grade  boy   Red, Blue, Green, Yellow
     *   9, 10, 16, 17   3C  A grade  boy   Red, Blue, Green, Yellow
     *  11, 12, 18, 19   3D  A grade  boy   Red, Blue, Green, Yellow
     *  13               2A  A grade  boy   Red     — Form 2: another event
     *  14               3A  A grade  girl  Red     — Form 3, but not this division
     *  15               3E  A grade  boy   Red     — locked: not on this year's list
     * </pre>
     */
    @BeforeEach
    void setUp() {
        service = new RelayTeamService(relayTeamRepository, relayTeamMemberRepository,
                eventRepository, studentRepository, teacherClassService, enrollmentRepository);
        readiness = new RelayReadiness(relayTeamRepository, relayTeamMemberRepository);
        teams.clear();
        members.clear();
        students.clear();
        nextTeamId = 1;
        nextMemberId = 1;

        // The selection paths never consult the class rule here: requireMayHelp is a
        // no-op stub, so every athlete in the register may be placed.
        when(teacherClassService.isAdmin()).thenReturn(true);

        student(1L, "3A", "Red");
        student(2L, "3A", "Blue");
        student(3L, "3A", "Green");
        student(4L, "3A", "Yellow");
        student(5L, "3B", "Red");
        student(6L, "3B", "Blue");
        student(7L, "3B", "Green");
        student(8L, "3B", "Yellow");
        student(9L, "3C", "Red");
        student(10L, "3C", "Blue");
        student(16L, "3C", "Green");
        student(17L, "3C", "Yellow");
        student(11L, "3D", "Red");
        student(12L, "3D", "Blue");
        student(18L, "3D", "Green");
        student(19L, "3D", "Yellow");
        student(13L, "2A", "Red");
        student(14L, "3A", "Red", Sex.FEMALE);
        student(15L, "3E", "Red", false);

        // A "Form 3 4x100M": scoped to Form 3, run in the Boys division.
        event = formEvent("3");

        wireRepositories();
    }

    // ============================================================== fixtures

    private Event formEvent(String form) {
        return Event.builder()
                .id(EVENT_ID)
                .name("Boys 4x100M Relay · Form " + form)
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .grade(Grade.A)
                .form(form)
                .eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512)
                .groupSize(24)
                .enabled(true)
                .relayTeamKind(RelayTeamKind.FORM)
                .build();
    }

    private Student student(long userId, String className, String house) {
        return student(userId, className, house, Sex.MALE, true);
    }

    private Student student(long userId, String className, String house, Sex sex) {
        return student(userId, className, house, sex, true);
    }

    private Student student(long userId, String className, String house, boolean enabled) {
        return student(userId, className, house, Sex.MALE, enabled);
    }

    private Student student(long userId, String className, String house, Sex sex, boolean enabled) {
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
                .grade(Grade.A)
                .enabled(enabled)
                .build();
        students.put(userId, roster);
        return roster;
    }

    /** Takes an athlete off this year's list — the register then offers them nobody. */
    private void lock(Long... userIds) {
        for (Long userId : userIds) {
            students.get(userId).setEnabled(false);
        }
    }

    /** A team already on the board, as an earlier derive left it. */
    private RelayTeam team(String key) {
        RelayTeam team = RelayTeam.builder()
                .id(nextTeamId++)
                .event(event)
                .kind(event.getRelayTeamKind())
                .teamKey(key)
                .label(event.getRelayTeamKind().labelFor(event.getGrade(), key))
                .build();
        teams.add(team);
        return team;
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
        when(relayTeamRepository.saveAndFlush(any())).thenAnswer(invocation -> {
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
        doAnswer(invocation -> {
            teams.remove(invocation.getArgument(0));
            return null;
        }).when(relayTeamRepository).delete(any());

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
        // The register query the derive asks: the event's division and its one form,
        // backed by the register above exactly as the repository's own method is.
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

    private RelayEventTeamsDTO board() {
        return service.getBoard(EVENT_ID);
    }

    private List<String> teamKeys() {
        return board().getTeams().stream().map(RelayTeamDTO::getTeamKey)
                .collect(Collectors.toList());
    }

    private List<String> teamLabels() {
        return board().getTeams().stream().map(RelayTeamDTO::getLabel)
                .collect(Collectors.toList());
    }

    private RelayTeamDTO teamNamed(String name) {
        return board().getTeams().stream()
                .filter(team -> name.equals(team.getLabel()) || name.equals(team.getTeamKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no team named " + name + " in "
                        + board().getTeams().stream().map(RelayTeamDTO::getLabel).toList()));
    }

    /** Names the given athletes for a team's legs, in the order given. */
    private void fill(RelayTeamDTO team, Long... userIds) {
        for (Long userId : userIds) {
            service.addRunner(team.getId(), userId, null);
        }
    }

    // =============================================== the derive makes exactly two

    @Test
    @DisplayName("a Form 3 relay derives 3A and 3B — the form's first two classes, in class order")
    void derivesTheFirstTwoClassesInOrder() {
        RelayTeamDerivationDTO result = service.deriveTeams(EVENT_ID, false);

        assertEquals(2, result.getCreated());
        assertEquals(0, result.getKept());
        assertEquals("FORM", result.getKind());
        assertEquals(List.of("3A", "3B"), teamKeys());
        assertEquals(List.of("3A", "3B"), teamLabels());
        assertEquals(2, result.getBoard().getTeamCount());
        assertEquals(4, result.getBoard().getLegsPerTeam());

        // Sixteen eligible athletes: the four boys of each of 3A, 3B, 3C and 3D. The
        // Form 2 boy, the girl and the locked athlete are none of this relay's field.
        assertEquals(16, result.getEligibleStudents());

        assertFalse(teamKeys().contains("3C"), "the form's third class is not derived");
        assertFalse(teamKeys().contains("3D"), "nor its fourth");
        assertFalse(teamKeys().contains("2A"), "Form 2 is a separate event");
    }

    @Test
    @DisplayName("deriving again gives the same two — the pair is the classes', not the register's order")
    void derivingTwiceGivesTheSameTwo() {
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDerivationDTO second = service.deriveTeams(EVENT_ID, false);

        assertEquals(0, second.getCreated());
        assertEquals(2, second.getKept());
        assertEquals(List.of("3A", "3B"), teamKeys());

        // And a third derive, after a class has been emptied of runners, still gives
        // 3A and 3B: the choice never wanders.
        service.deriveTeams(EVENT_ID, true);
        assertEquals(List.of("3A", "3B"), teamKeys());
    }

    @Test
    @DisplayName("a team that already holds runners survives a re-derive; an empty extra goes only on a prune")
    void aTeamWithRunnersSurvivesAReDerive() {
        // A relay derived before this rule: four teams, and somebody already runs in 3D.
        team("3A");
        team("3B");
        team("3C");
        RelayTeam threeD = team("3D");
        relayTeamMemberRepository.save(RelayTeamMember.builder()
                .team(threeD).user(students.get(12L).getUser()).leg(1).build());

        RelayTeamDerivationDTO kept = service.deriveTeams(EVENT_ID, false);

        assertEquals(0, kept.getCreated());
        assertEquals(0, kept.getPruned(), "nothing is dropped without a prune");
        assertEquals(1, kept.getKeptWithRunners(), "3D's runner is reported, not thrown away");
        assertEquals(List.of("3A", "3B", "3C", "3D"), teamKeys(),
                "the derive is additive: without a prune the four teams stay as they are");

        RelayTeamDerivationDTO trimmed = service.deriveTeams(EVENT_ID, true);

        assertEquals(2, trimmed.getKept(), "3A and 3B are the roster's own two");
        assertEquals(1, trimmed.getPruned(), "the empty 3C goes");
        assertEquals(1, trimmed.getKeptWithRunners(), "the 3D somebody runs in stays");
        assertEquals(List.of("3A", "3B", "3D"), teamKeys());
        assertEquals(1, membersOf(threeD.getId()).size(), "and its runner is untouched");
    }

    @Test
    @DisplayName("a class of the form with no eligible athlete is passed over for the next one")
    void aClassThatCannotFieldARunnerIsNotOneOfTheTwo() {
        // 3A's four boys are locked, so the form's first two classes that CAN field a
        // runner are 3B and 3C.
        lock(1L, 2L, 3L, 4L);

        service.deriveTeams(EVENT_ID, false);

        assertEquals(List.of("3B", "3C"), teamKeys());
    }

    // ===================================================== ready, and accepted

    @Test
    @DisplayName("a two-team form relay with every leg filled is ready, and its board renders both")
    void aTwoTeamFormRelayIsReadyAndRenders() {
        service.deriveTeams(EVENT_ID, false);
        fill(teamNamed("3A"), 1L, 2L, 3L, 4L);
        fill(teamNamed("3B"), 5L, 6L, 7L, 8L);

        assertTrue(readiness.shortfallOf(event).isEmpty(),
                "two teams, each holding its four: " + readiness.shortfallOf(event).orElse(""));
        // The gate the mark grid and the print run ask. Two derived teams is exactly
        // its floor, so it passes without a third team being built by hand.
        readiness.requireRelayIsReadyToMark(event);

        RelayEventTeamsDTO rendered = service.getBoard(EVENT_ID);
        assertEquals(2, rendered.getTeamCount());
        assertEquals(4, rendered.getLegsPerTeam());
        assertEquals(List.of("3A", "3B"),
                rendered.getTeams().stream().map(RelayTeamDTO::getTeamKey).toList());
        for (RelayTeamDTO team : rendered.getTeams()) {
            assertEquals(4, team.getMemberCount().intValue(), team.getLabel() + " is full");
            assertTrue(team.getComplete(), team.getLabel() + " is complete");
            assertEquals(List.of(1, 2, 3, 4),
                    team.getMembers().stream().map(member -> member.getLeg()).toList(),
                    team.getLabel() + " runs legs 1 to 4 in order");
        }
    }

    @Test
    @DisplayName("four teams of which two are empty is ready too — the empty ones are not in the race")
    void aFourTeamRelayWithTwoEmptyTeamsIsReady() {
        // The school's own live case: a relay that already held 3A, 3B, 3C and 3D, with
        // 3A and 3B filled and 3C and 3D still empty ("2B: Short — 4 runner(s) needed").
        // The race is 3A against 3B, and it is ready: a team nobody has been named in is
        // not in the race, and the school's rule is two teams to four.
        team("3A");
        team("3B");
        team("3C");
        team("3D");
        fill(teamNamed("3A"), 1L, 2L, 3L, 4L);
        fill(teamNamed("3B"), 5L, 6L, 7L, 8L);

        assertTrue(readiness.isReady(event), "two full teams beside two empty ones: "
                + readiness.shortfallOf(event).orElse("ready"));
        readiness.requireRelayIsReadyToMark(event);
        assertEquals(4, service.getBoard(EVENT_ID).getTeamCount(),
                "and all four teams are still on the board");

        // A team that is *half* named is in the race, and does hold it back.
        fill(teamNamed("3C"), 9L, 10L);
        assertFalse(readiness.isReady(event), "a half-filled third team is in the race");
        assertTrue(readiness.shortfallOf(event).orElseThrow().contains("3C has 2 of the 4"),
                readiness.shortfallOf(event).orElseThrow());
    }

    @Test
    @DisplayName("three or four teams are accepted and ready — nothing refuses more than two")
    void threeOrFourTeamsAreAcceptedAndReady() {
        service.deriveTeams(EVENT_ID, false);
        fill(teamNamed("3A"), 1L, 2L, 3L, 4L);
        fill(teamNamed("3B"), 5L, 6L, 7L, 8L);

        // A third and a fourth team, made by hand out of the form's other two classes.
        // The ceiling is not this rule's to lower: a school may run three or four.
        service.createTeam(EVENT_ID, new RelayTeamCreateRequest("3C Team", List.of(9L, 10L, 16L, 17L)));
        assertTrue(readiness.isReady(event), "three full teams are a race");

        service.createTeam(EVENT_ID, new RelayTeamCreateRequest("3D Team", List.of(11L, 12L, 18L, 19L)));
        assertTrue(readiness.isReady(event), "and so are four");
        assertEquals(4, service.getBoard(EVENT_ID).getTeamCount());

        // A re-derive leaves both hand-made teams where they are.
        RelayTeamDerivationDTO again = service.deriveTeams(EVENT_ID, true);
        assertEquals(0, again.getPruned(), "a hand-made team is never the derive's to drop");
        assertEquals(4, again.getBoard().getTeamCount());
        assertTrue(teamKeys().contains("3C Team"));
        assertTrue(teamKeys().contains("3D Team"));
    }

    // ==================================== fewer than two classes, and the house relay

    @Test
    @DisplayName("a form with one eligible class derives that one team — and is simply not ready")
    void aFormWithOneEligibleClassDerivesOneTeam() {
        lock(5L, 6L, 7L, 8L, 9L, 10L, 16L, 17L, 11L, 12L, 18L, 19L);

        RelayTeamDerivationDTO result = service.deriveTeams(EVENT_ID, false);

        assertEquals(1, result.getCreated());
        assertEquals(List.of("3A"), teamKeys());
        assertTrue(readiness.shortfallOf(event).isPresent(), "one team is not a race yet");
        assertTrue(readiness.shortfallOf(event).orElseThrow().contains("at least 2"),
                readiness.shortfallOf(event).orElseThrow());
    }

    @Test
    @DisplayName("a form with no eligible class derives no teams at all, and is no error")
    void aFormWithNoEligibleClassDerivesNothing() {
        lock(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 16L, 17L, 11L, 12L, 18L, 19L);

        RelayTeamDerivationDTO result = service.deriveTeams(EVENT_ID, false);

        assertEquals(0, result.getCreated());
        assertEquals(0, result.getEligibleStudents());
        assertTrue(teamKeys().isEmpty(), "nothing is derived from an empty field");
    }

    @Test
    @DisplayName("a house relay still derives one team per house — this rule is the form relay's alone")
    void aHouseRelayStillDerivesOnePerHouse() {
        event.setRelayTeamKind(RelayTeamKind.HOUSE);
        event.setForm(null);
        event.setName("Boys 4x100M Relay · A Grade");

        RelayTeamDerivationDTO result = service.deriveTeams(EVENT_ID, false);

        assertEquals(4, result.getCreated());
        assertEquals(List.of("Blue", "Green", "Red", "Yellow"), teamKeys());
        assertEquals(List.of("A Grade Blue", "A Grade Green", "A Grade Red", "A Grade Yellow"),
                teamLabels());
        assertNull(board().getForm());
    }
}
