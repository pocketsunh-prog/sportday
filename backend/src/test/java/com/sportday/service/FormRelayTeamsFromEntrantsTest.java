package com.sportday.service;

import com.sportday.dto.RelayEventTeamsDTO;
import com.sportday.dto.RelayTeamCreateRequest;
import com.sportday.dto.RelayTeamDTO;
import com.sportday.dto.RelayTeamDerivationDTO;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The school's rule for a <strong>form class relay</strong>: its teams are the
 * <strong>classes its entrants are in</strong> — one team per class that entered the
 * relay — and not the form's first two classes.
 *
 * <p>As the school put it: "form class relay should be at least 2 team enroll relay and
 * not the first two classes of the form the relay". The word that decides the rule is
 * <em>enroll</em>: what makes a team is a student with a <strong>confirmed entry</strong>
 * in the relay, the same reading of "entered" the applicant list beside this board uses,
 * and a class of the form that nobody entered from is <em>not</em> one of its teams any
 * more — which is what replaced the derive that made the form's first two classes
 * whether anybody had entered from them or not.</p>
 *
 * <p>What the file pins down, one test each:</p>
 * <ul>
 *   <li>the teams are the <strong>classes of the entrants</strong>, in class order, and a
 *       class of the form with no entrant is not a team even though the register holds
 *       its students;</li>
 *   <li>deriving twice gives the same teams, because they are read from the classes and
 *       not from the order the register came back in;</li>
 *   <li><strong>at least two teams are wanted, and one is not an error</strong>: one
 *       class with an entrant derives that one team, nothing is refused, and the relay is
 *       simply not ready to mark — and no entrant at all derives no teams, also without
 *       a refusal;</li>
 *   <li>a team from a class <strong>nobody entered from</strong> that already holds
 *       runners is <strong>kept</strong>, prune or no prune, so a relay derived before
 *       this rule stays printable until its runners are taken off its board — while an
 *       <em>empty</em> such team goes only when the caller asks to prune;</li>
 *   <li>an entrant whose account has <strong>no register row</strong> invents no team:
 *       there is no class to key one from;</li>
 *   <li>an entrant who is <strong>not in the relay's own form</strong> yields no team
 *       either: the eligibility rule would refuse every runner of such a team, and a team
 *       nobody could ever be named in is not a team;</li>
 *   <li>a two-team form relay with every leg filled is <strong>ready</strong> and its
 *       board renders both teams; a four-team relay of which two are empty is ready too,
 *       because an empty team is not in the race;</li>
 *   <li>a house relay is untouched: one team per house of the event's grade.</li>
 * </ul>
 *
 * <p>The register spans the classes on purpose — {@code 3A} to {@code 3D}, four athletes
 * each — with a Form 2 athlete, a girl and a locked athlete beside them, so a derive that
 * took the wrong classes, the wrong form or the wrong division would be visible rather
 * than accidentally right. The entries are the test's own: each test says which students
 * entered the relay, which is the fact the whole rule turns on. The repositories are
 * backed by in-memory lists, as in the other relay tests, so a derive, a selection and a
 * re-derive run against real read-then-write behaviour.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FormRelayTeamsFromEntrantsTest {

    private static final Long EVENT_ID = 42L;

    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;
    @Mock private EventRepository eventRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private TeacherClassService teacherClassService;
    /** The event's entries — the fact a form relay's teams are read from. */
    @Mock private EnrollmentRepository enrollmentRepository;

    private RelayTeamService service;
    /** The one readiness rule, over the same teams the service reads. */
    private RelayReadiness readiness;

    private final List<RelayTeam> teams = new ArrayList<>();
    private final List<RelayTeamMember> members = new ArrayList<>();
    private final Map<Long, Student> students = new LinkedHashMap<>();
    /** Who has entered this relay: the account ids with a confirmed entry. */
    private final Set<Long> entrants = new LinkedHashSet<>();
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
        entrants.clear();
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

    /**
     * Takes an entry in this relay for the given accounts — the fact the teams are read
     * from. An id with no register row is allowed on purpose: an account with no roster
     * row can enter an event, and the derive has to cope with it.
     */
    private void entered(Long... userIds) {
        entrants.addAll(List.of(userIds));
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
        // The event's entries, as the repository hands them over: one row per account
        // that entered, carrying its user. An entrant with no register row still has a
        // user — an account — which is exactly the case the derive has to survive.
        when(enrollmentRepository.findConfirmedWithUserByEvent(anyLong(), any()))
                .thenAnswer(invocation -> {
                    Long eventId = invocation.getArgument(0);
                    if (!EVENT_ID.equals(eventId)) {
                        return List.of();
                    }
                    return entrants.stream().map(userId -> Enrollment.builder()
                            .id(userId)
                            .event(event)
                            .user(students.containsKey(userId)
                                    ? students.get(userId).getUser()
                                    : User.builder().id(userId).username("U" + userId)
                                            .role(User.Role.STUDENT).enabled(true).build())
                            .status(Enrollment.EnrollmentStatus.CONFIRMED)
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

    // ================================== the teams are the classes that entered

    @Test
    @DisplayName("the teams are the classes of the entrants — 3B and 3D when they entered, not 3A and 3B")
    void theTeamsAreTheClassesOfTheEntrants() {
        entered(5L, 6L, 11L, 12L);

        RelayTeamDerivationDTO result = service.deriveTeams(EVENT_ID, false);

        assertEquals(2, result.getCreated());
        assertEquals(0, result.getKept());
        assertEquals("FORM", result.getKind());
        assertEquals(List.of("3B", "3D"), teamKeys());
        assertEquals(List.of("3B", "3D"), teamLabels());
        assertEquals(2, result.getBoard().getTeamCount());
        assertEquals(4, result.getBoard().getLegsPerTeam());

        // 3A and 3C hold students on the register, and nobody entered the relay from
        // them: they are NOT teams of it. That is the whole of the change.
        assertFalse(teamKeys().contains("3A"), "a class nobody entered from is not a team");
        assertFalse(teamKeys().contains("3C"), "nor is another one");
        assertFalse(teamKeys().contains("2A"), "Form 2 is a separate event");
    }

    @Test
    @DisplayName("the class order is the school's, whatever order the entrants came in")
    void theClassOrderIsTheSchools() {
        // Entered in the wrong order on purpose: the teams still read 3A, 3C.
        entered(16L, 1L);

        service.deriveTeams(EVENT_ID, false);

        assertEquals(List.of("3A", "3C"), teamKeys());
    }

    @Test
    @DisplayName("deriving twice gives the same teams — they are the classes', not the entries' order")
    void derivingTwiceGivesTheSameTeams() {
        entered(17L, 4L, 8L);
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDerivationDTO second = service.deriveTeams(EVENT_ID, false);

        assertEquals(0, second.getCreated());
        assertEquals(3, second.getKept());
        assertEquals(List.of("3A", "3B", "3C"), teamKeys());

        // And a third derive, this time pruning, still gives the same three.
        service.deriveTeams(EVENT_ID, true);
        assertEquals(List.of("3A", "3B", "3C"), teamKeys());
    }

    @Test
    @DisplayName("an entry is the confirmed one — the status is named, not assumed")
    void onlyAConfirmedEntryCounts() {
        entered(1L, 5L);

        service.deriveTeams(EVENT_ID, false);

        verify(enrollmentRepository, atLeastOnce())
                .findConfirmedWithUserByEvent(EVENT_ID, Enrollment.EnrollmentStatus.CONFIRMED);
        assertEquals(List.of("3A", "3B"), teamKeys());
    }

    // ==================================== two wanted, one or none is no error

    @Test
    @DisplayName("one class with an entrant derives that one team — and is simply not ready")
    void oneClassWithAnEntrantDerivesOneTeam() {
        entered(1L, 2L, 3L, 4L);

        RelayTeamDerivationDTO result = service.deriveTeams(EVENT_ID, false);

        // Nothing is refused: the school did not ask for an error, and one team is a
        // relay being built rather than a request that cannot be honoured.
        assertEquals(1, result.getCreated());
        assertEquals(List.of("3A"), teamKeys());

        // The floor of two is the readiness rule's, and it says so in its own words.
        assertTrue(readiness.shortfallOf(event).isPresent(), "one team is not a race yet");
        assertTrue(readiness.shortfallOf(event).orElseThrow().contains("at least 2"),
                readiness.shortfallOf(event).orElseThrow());
    }

    @Test
    @DisplayName("no entrant at all derives no teams — and that is no error either")
    void noEntrantDerivesNothing() {
        RelayTeamDerivationDTO result = service.deriveTeams(EVENT_ID, false);

        assertEquals(0, result.getCreated());
        assertTrue(teamKeys().isEmpty(), "nothing is invented when nobody entered");
        assertTrue(readiness.shortfallOf(event).isPresent(),
                "and the relay is not ready, in the readiness rule's own words");
    }

    @Test
    @DisplayName("a team from a class nobody entered from is kept while it holds runners")
    void aTeamWithRunnersIsKeptEvenWhenNobodyEnteredFromIt() {
        // A relay derived before this rule: four teams, and somebody already runs in 3D.
        team("3A");
        team("3B");
        team("3C");
        RelayTeam threeD = team("3D");
        relayTeamMemberRepository.save(RelayTeamMember.builder()
                .team(threeD).user(students.get(12L).getUser()).leg(1).build());
        // Only 3A and 3B have entrants now — or ever again.
        entered(1L, 5L);

        RelayTeamDerivationDTO kept = service.deriveTeams(EVENT_ID, false);

        assertEquals(0, kept.getCreated());
        assertEquals(0, kept.getPruned(), "nothing is dropped without a prune");
        assertEquals(1, kept.getKeptWithRunners(), "3D's runner is reported, not thrown away");
        assertEquals(List.of("3A", "3B", "3C", "3D"), teamKeys(),
                "the derive is additive: without a prune the four teams stay as they are");

        RelayTeamDerivationDTO trimmed = service.deriveTeams(EVENT_ID, true);

        assertEquals(2, trimmed.getKept(), "3A and 3B are the classes that entered");
        assertEquals(1, trimmed.getPruned(), "the empty 3C goes");
        assertEquals(1, trimmed.getKeptWithRunners(), "the 3D somebody runs in stays");
        assertEquals(List.of("3A", "3B", "3D"), teamKeys());
        assertEquals(1, membersOf(threeD.getId()).size(), "and its runner is untouched");
    }

    // ======================================= an entrant with no register row

    @Test
    @DisplayName("an entrant with no register row invents no team — there is no class to key one from")
    void anEntrantWithNoRegisterRowInventsNoTeam() {
        entered(1L, 99L);

        service.deriveTeams(EVENT_ID, false);

        assertEquals(List.of("3A"), teamKeys(), "the account with no roster row makes no class");

        // And with only that account entered, the relay derives nothing at all.
        teams.clear();
        entrants.clear();
        entered(99L);
        service.deriveTeams(EVENT_ID, false);
        assertTrue(teamKeys().isEmpty());
    }

    @Test
    @DisplayName("a locked student's entry makes no team — they are not on this year's list")
    void aLockedStudentsEntryMakesNoTeam() {
        entered(15L, 1L);

        service.deriveTeams(EVENT_ID, false);

        assertEquals(List.of("3A"), teamKeys(), "3E is off this year's list, so it is no team");
    }

    @Test
    @DisplayName("an entrant of another form makes no team: nobody could ever run in it")
    void anEntrantOfAnotherFormMakesNoTeam() {
        // 2A and 3A. The event is the Form 3 one, so a 2A team could never be filled:
        // the eligibility rule refuses a Form 2 runner here, and the register the teams
        // are filled from holds no Form 2 student. A team nobody can be named in is not
        // a team, so the relay derives 3A alone.
        entered(13L, 1L);

        service.deriveTeams(EVENT_ID, false);

        assertEquals(List.of("3A"), teamKeys());
        assertFalse(teamKeys().contains("2A"), "a class outside the relay's form is no team");
    }

    // ===================================================== ready, and accepted

    @Test
    @DisplayName("a two-team form relay with every leg filled is ready, and its board renders both")
    void aTwoTeamFormRelayIsReadyAndRenders() {
        entered(1L, 5L);
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
        // Four classes entered the relay, and the school has filled two of them so far.
        // The race is 3A against 3B, and it is ready: a team nobody has been named in is
        // not in the race, and the school's rule is two teams to four.
        entered(1L, 5L, 9L, 11L);
        service.deriveTeams(EVENT_ID, false);
        assertEquals(List.of("3A", "3B", "3C", "3D"), teamKeys());
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
    @DisplayName("a team made by hand is never the derive's to drop, and more than two are accepted")
    void handMadeTeamsAreLeftAlone() {
        entered(1L, 5L);
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

        RelayTeamDerivationDTO again = service.deriveTeams(EVENT_ID, true);
        assertEquals(0, again.getPruned(), "a hand-made team is never the derive's to drop");
        assertEquals(4, again.getBoard().getTeamCount());
        assertTrue(teamKeys().contains("3C Team"));
        assertTrue(teamKeys().contains("3D Team"));
    }

    @Test
    @DisplayName("a house relay still derives one team per house — this rule is the form relay's alone")
    void aHouseRelayStillDerivesOnePerHouse() {
        event.setRelayTeamKind(RelayTeamKind.HOUSE);
        event.setForm(null);
        event.setName("Boys 4x100M Relay · A Grade");
        // A house relay is not read from the entries at all: the register divides it.
        entered(1L);

        RelayTeamDerivationDTO result = service.deriveTeams(EVENT_ID, false);

        assertEquals(4, result.getCreated());
        assertEquals(List.of("Blue", "Green", "Red", "Yellow"), teamKeys());
        assertEquals(List.of("A Grade Blue", "A Grade Green", "A Grade Red", "A Grade Yellow"),
                teamLabels());
        assertNull(board().getForm());
    }

    @Test
    @DisplayName("a graded form relay with no form still keys one team per class of its grade")
    void anUnscopedFormRelayIsStillReadFromTheRegister() {
        event.setForm(null);
        // Nobody entered from 3A or 3C, and it makes no difference: with no form the
        // relay is scoped by its grade, exactly as it was before the form existed, and
        // the register of that grade is what divides it.
        entered(5L);

        service.deriveTeams(EVENT_ID, false);

        assertEquals(List.of("2A", "3A", "3B", "3C", "3D"), teamKeys());
    }
}
