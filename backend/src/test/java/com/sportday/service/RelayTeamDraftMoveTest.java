package com.sportday.service;

import com.sportday.dto.RelayEventTeamsDTO;
import com.sportday.dto.RelayTeamCreateRequest;
import com.sportday.dto.RelayTeamDTO;
import com.sportday.dto.RelayTeamMoveDTO;
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
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A relay event built <strong>around</strong> teams the school made by hand.
 *
 * <p>The school's requirement, as confirmed: "create relay event base on selected
 * relay team". A relay team cannot exist without an event — {@code RelayTeam.event} is
 * a non-null foreign key, and both the marking sheet and the mark grid reach a team
 * through its event — so the teams are built on a <strong>draft</strong> relay event
 * and moved onto the real one. This asserts the move and, more importantly, every way
 * it is refused:</p>
 *
 * <ol>
 *   <li>the teams and their runners land on the target, and the draft is left empty
 *       and still there;</li>
 *   <li>a name already used in the target refuses the whole move, and
 *       <strong>nothing moved</strong> — every team is still on the draft, which is
 *       the half of the requirement a naive implementation gets wrong;</li>
 *   <li>a target that is not a relay is refused;</li>
 *   <li>a runner who is not in the target's grade is refused;</li>
 *   <li>a real event's teams are not moved at all;</li>
 *   <li>and a draft's teams cannot be destroyed as a side effect of deleting the
 *       draft: the shared call that clearing an event's teams goes through refuses
 *       them, and only the draft's own explicit request discards them.</li>
 * </ol>
 *
 * <p>The repositories are backed by in-memory lists and {@link TeacherClassService} is
 * the <strong>real</strong> one, so the eligibility rules are genuinely exercised
 * rather than stubbed away.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayTeamDraftMoveTest {

    private static final Long DRAFT_ID = 42L;
    private static final Long TARGET_ID = 77L;
    private static final Long ADMIN_ID = 1L;

    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;
    @Mock private EventRepository eventRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private TeacherClassRepository teacherClassRepository;
    @Mock private UserRepository userRepository;
    @Mock private GradeCalculator gradeCalculator;
    @Mock private EnrollmentRepository enrollmentRepository;

    private TeacherClassService teacherClassService;
    private RelayTeamService service;

    private final List<RelayTeam> teams = new ArrayList<>();
    private final List<RelayTeamMember> members = new ArrayList<>();
    private final Map<Long, Student> students = new LinkedHashMap<>();
    private long nextTeamId = 1;
    private long nextMemberId = 1;
    private long nextUserId = 100;

    private Event draft;
    private Event target;
    private User admin;

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

        // The draft the school builds its teams on, and the real relay event the race
        // will be run as. Same type, division and grade, so the teams fit both.
        draft = relayEvent(DRAFT_ID, "Boys 4x100M Relay · B Grade (teams)", Grade.B, true);
        target = relayEvent(TARGET_ID, "Boys 4x100M Relay · B Grade", Grade.B, false);

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

    private static Event relayEvent(Long id, String name, Grade grade, boolean isDraft) {
        return Event.builder()
                .id(id)
                .name(name)
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .grade(grade)
                .eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512)
                .groupSize(24)
                .enabled(true)
                .relayTeamKind(RelayTeamKind.FORM)
                .draft(isDraft)
                .build();
    }

    /** One athlete on the register, in the event's own grade and division unless told otherwise. */
    private Student student(Grade grade) {
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
                .sex(Sex.MALE)
                .className("1A")
                .classNumber(1)
                .house("Red")
                .grade(grade)
                .enabled(true)
                .build();
        students.put(userId, roster);
        return roster;
    }

    private Student runner() {
        return student(Grade.B);
    }

    private static long userIdOf(Student student) {
        return student.getUser().getId();
    }

    /** Signs a staff account in the way the JWT filter does. */
    private void signedInAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                admin.getUsername(), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + admin.getRole().name()))));
        when(userRepository.findByUsername(admin.getUsername())).thenReturn(Optional.of(admin));
    }

    /** A team built by hand on the draft, out of the students given, in leg order. */
    private RelayTeamDTO makeTeam(String name, Long... userIds) {
        RelayTeamCreateRequest request = new RelayTeamCreateRequest();
        request.setName(name);
        request.setUserIds(List.of(userIds));
        return service.createTeam(DRAFT_ID, request);
    }

    /** A team that already races in the target event, so its name is taken there. */
    private RelayTeam teamOnTarget(String name) {
        RelayTeam team = RelayTeam.builder()
                .id(nextTeamId++)
                .event(target)
                .kind(null)
                .teamKey(name)
                .label(name)
                .handMade(true)
                .nameOverridden(true)
                .build();
        teams.add(team);
        return team;
    }

    // ============================================================== plumbing

    private void wireRepositories() {
        when(eventRepository.findById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (DRAFT_ID.equals(id)) {
                return Optional.of(draft);
            }
            if (TARGET_ID.equals(id)) {
                return Optional.of(target);
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
        doAnswer(invocation -> {
            Long teamId = invocation.getArgument(0);
            members.removeIf(member -> member.getTeam() != null
                    && teamId.equals(member.getTeam().getId()));
            return null;
        }).when(relayTeamMemberRepository).deleteByTeamId(anyLong());

        when(studentRepository.findWithUserByUserId(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(students.get(invocation.getArgument(0))));
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

    /** The teams the repository says are on an event, read fresh. */
    private List<String> teamsOn(Long eventId) {
        return relayTeamRepository.findByEventIdOrderByIdAsc(eventId).stream()
                .map(RelayTeam::getLabel)
                .collect(Collectors.toList());
    }

    private RelayEventTeamsDTO board(Long eventId) {
        return service.getBoard(eventId);
    }

    // ==================================================== the move that is wanted

    @Test
    @DisplayName("a draft's teams move onto the target, and the draft is left with none")
    void theTeamsMoveAndTheDraftIsEmptied() {
        signedInAsAdmin();
        Student one = runner();
        Student two = runner();
        Student three = runner();
        Student four = runner();
        makeTeam("B Grade Yellow", userIdOf(one), userIdOf(two), userIdOf(three));
        makeTeam("B Grade Green", userIdOf(four));

        RelayTeamMoveDTO moved = service.moveTeamsToEvent(DRAFT_ID, TARGET_ID);

        assertEquals(2, moved.getTeamsMoved().intValue(), "both teams moved");
        assertEquals(4, moved.getRunnersMoved().intValue(), "with their four runners");
        assertEquals(TARGET_ID, moved.getTargetEventId());
        assertEquals(List.of("B Grade Yellow", "B Grade Green"), moved.getTeamLabels());
        assertNotNull(moved.getTargetEvent(), "and the target is described on the answer");
        assertEquals("Boys 4x100M Relay · B Grade", moved.getTargetEvent().getName());
        assertFalse(Boolean.TRUE.equals(moved.getTargetEvent().getDraft()),
                "the target is a real event, not a draft");

        assertEquals(List.of("B Grade Yellow", "B Grade Green"), teamsOn(TARGET_ID),
                "the teams are on the target");
        assertEquals(List.of(), teamsOn(DRAFT_ID), "and the draft has none");
        assertEquals(4, membersOfEvent(TARGET_ID).size(), "the legs went with them");
        assertEquals(0, membersOfEvent(DRAFT_ID).size());
    }

    @Test
    @DisplayName("the draft event is kept, so the teams it held are not destroyed by the move")
    void theDraftIsKeptAfterTheMove() {
        signedInAsAdmin();
        makeTeam("B Grade Yellow", userIdOf(runner()));
        makeTeam("B Grade Green", userIdOf(runner()));

        service.moveTeamsToEvent(DRAFT_ID, TARGET_ID);

        // The draft is still readable and still a draft: emptying it is not deleting
        // it, and the teams are only on the target now.
        RelayEventTeamsDTO draftBoard = board(DRAFT_ID);
        assertEquals(DRAFT_ID, draftBoard.getEventId());
        assertEquals(List.of(), draftBoard.getTeams().stream().map(RelayTeamDTO::getLabel).toList());
        assertEquals(2, board(TARGET_ID).getTeams().size(), "both are on the target");
    }

    @Test
    @DisplayName("a move of a draft with no teams is a no-op that reports nothing moved")
    void anEmptyDraftMovesNothing() {
        signedInAsAdmin();

        RelayTeamMoveDTO moved = service.moveTeamsToEvent(DRAFT_ID, TARGET_ID);

        assertEquals(0, moved.getTeamsMoved().intValue());
        assertEquals(0, moved.getRunnersMoved().intValue());
        assertEquals(List.of(), moved.getTeamLabels());
    }

    // ======================================================== the refusals

    @Test
    @DisplayName("a name already used in the target refuses the move, and nothing moves")
    void aNameAlreadyInTheTargetRefusesEverything() {
        signedInAsAdmin();
        Student one = runner();
        Student two = runner();
        makeTeam("B Grade Yellow", userIdOf(one), userIdOf(two));
        // The very name, already on the event the move is aimed at.
        teamOnTarget("B Grade Yellow");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.moveTeamsToEvent(DRAFT_ID, TARGET_ID));

        assertTrue(error.getMessage().contains("B Grade Yellow"), error.getMessage());
        assertTrue(error.getMessage().contains("already the name of another team"),
                error.getMessage());

        // Nothing moved: the draft still holds its team and the target still holds
        // only the one that was already there.
        assertEquals(List.of("B Grade Yellow"), teamsOn(DRAFT_ID),
                "the team is still on the draft — the move is all or nothing");
        assertEquals(List.of("B Grade Yellow"), teamsOn(TARGET_ID),
                "and the target is unchanged");
        assertEquals(2, membersOfEvent(DRAFT_ID).size(), "with its runners still on it");
        assertEquals(0, membersOfEvent(TARGET_ID).size());
    }

    @Test
    @DisplayName("no team of a refused move is left behind on the target")
    void aRefusalLeavesNoTeamPartMoved() {
        signedInAsAdmin();
        // Two teams, the second of which collides: the first must not have been moved
        // before the second was judged.
        makeTeam("B Grade Green", userIdOf(runner()));
        makeTeam("B Grade Yellow", userIdOf(runner()));
        teamOnTarget("B Grade Yellow");

        assertThrows(IllegalArgumentException.class,
                () -> service.moveTeamsToEvent(DRAFT_ID, TARGET_ID));

        assertEquals(List.of("B Grade Green", "B Grade Yellow"), teamsOn(DRAFT_ID));
        assertEquals(List.of("B Grade Yellow"), teamsOn(TARGET_ID));
    }

    @Test
    @DisplayName("a move onto something that is not a relay event is refused")
    void aMoveOntoANonRelayIsRefused() {
        signedInAsAdmin();
        Event sprint = Event.builder()
                .id(99L).name("Boys 100M · B Grade").type(Event.EventType.RUN_100M)
                .category(EventCategory.TRACK).sex(Sex.MALE).grade(Grade.B)
                .eventDate(LocalDate.of(2026, 10, 1)).enabled(true).groupSize(8).build();
        when(eventRepository.findById(99L)).thenReturn(Optional.of(sprint));
        makeTeam("B Grade Yellow", userIdOf(runner()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.moveTeamsToEvent(DRAFT_ID, 99L));

        assertTrue(error.getMessage().contains("not a relay event"), error.getMessage());
        assertEquals(List.of("B Grade Yellow"), teamsOn(DRAFT_ID), "still on the draft");
        assertEquals(List.of(), teamsOn(99L));
    }

    @Test
    @DisplayName("a runner outside the target's grade refuses the move")
    void aRunnerOutsideTheTargetsGradeRefusesTheMove() {
        signedInAsAdmin();
        // A C-grade athlete can be named on the draft only because the draft's grade
        // agrees — here it does not, so the draft itself refuses them; the move is
        // asserted through a team whose runners fit the draft and not the target.
        Student aGrade = student(Grade.A);
        Event aGradeDraft = relayEvent(50L, "Boys 4x100M Relay · A Grade (teams)", Grade.A, true);
        when(eventRepository.findById(50L)).thenReturn(Optional.of(aGradeDraft));
        RelayTeamCreateRequest request = new RelayTeamCreateRequest();
        request.setName("A Grade Yellow");
        request.setUserIds(List.of(userIdOf(aGrade)));
        service.createTeam(50L, request);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.moveTeamsToEvent(50L, TARGET_ID));

        assertTrue(error.getMessage().contains("A grade"), error.getMessage());
        assertEquals(List.of("A Grade Yellow"), teamsOn(50L), "still on its own draft");
        assertEquals(List.of(), teamsOn(TARGET_ID));
    }

    @Test
    @DisplayName("a runner no longer on this year's list refuses the move")
    void aLockedRunnerRefusesTheMove() {
        signedInAsAdmin();
        Student locked = runner();
        makeTeam("B Grade Yellow", userIdOf(locked));
        locked.setEnabled(false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.moveTeamsToEvent(DRAFT_ID, TARGET_ID));

        assertTrue(error.getMessage().contains("not on this year's student list"),
                error.getMessage());
        assertEquals(List.of("B Grade Yellow"), teamsOn(DRAFT_ID), "still on the draft");
    }

    @Test
    @DisplayName("a move of a real event's teams is refused, so a race is never quietly emptied")
    void aRealEventsTeamsAreNotMoved() {
        signedInAsAdmin();
        makeTeam("B Grade Yellow", userIdOf(runner()));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.moveTeamsToEvent(TARGET_ID, DRAFT_ID));

        assertTrue(error.getMessage().contains("not a draft relay event"), error.getMessage());
        assertEquals(List.of("B Grade Yellow"), teamsOn(DRAFT_ID), "nothing moved either way");
    }

    @Test
    @DisplayName("a move without a target is refused before anything is read")
    void aMoveWithoutATargetIsRefused() {
        signedInAsAdmin();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.moveTeamsToEvent(DRAFT_ID, null));

        assertTrue(error.getMessage().contains("target event's id"), error.getMessage());
    }

    // ================================================= a draft's teams are protected

    @Test
    @DisplayName("a draft's teams cannot be cleared by the call an event delete goes through")
    void aDraftsTeamsAreNotClearedByTheSharedRemove() {
        signedInAsAdmin();
        makeTeam("B Grade Yellow", userIdOf(runner()));
        makeTeam("B Grade Green", userIdOf(runner()));

        // EventService.deleteEvent removes an event's teams with it through exactly
        // this call, so this is the side effect a draft must not suffer.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.removeTeamsForEvent(DRAFT_ID));

        assertTrue(error.getMessage().contains("draft relay event"), error.getMessage());
        assertEquals(2, teamsOn(DRAFT_ID).size(), "the selections are untouched");
        assertEquals(2, membersOfEvent(DRAFT_ID).size(), "with their runners");
    }

    @Test
    @DisplayName("an ordinary event's teams are still cleared by that same call")
    void anOrdinaryEventsTeamsAreStillRemoved() {
        signedInAsAdmin();
        teamOnTarget("B Grade Yellow");

        int removed = service.removeTeamsForEvent(TARGET_ID);

        assertEquals(1, removed);
        assertEquals(List.of(), teamsOn(TARGET_ID));
    }

    @Test
    @DisplayName("discarding a draft's teams is its own request, and only a draft's to make")
    void discardingADraftsTeamsIsDeliberateAndDraftOnly() {
        signedInAsAdmin();
        makeTeam("B Grade Yellow", userIdOf(runner()));
        makeTeam("B Grade Green", userIdOf(runner()));

        int discarded = service.discardDraftTeams(DRAFT_ID);

        assertEquals(2, discarded, "the school asked for exactly this");
        assertEquals(List.of(), teamsOn(DRAFT_ID));
        assertEquals(0, membersOfEvent(DRAFT_ID).size(), "and the legs went with the teams");

        // The same request aimed at a real event is refused, because an ordinary
        // event's teams are cleared the ordinary way.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.discardDraftTeams(TARGET_ID));
        assertTrue(error.getMessage().contains("not a draft relay event"), error.getMessage());
    }
}
