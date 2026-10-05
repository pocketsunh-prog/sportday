package com.sportday.service;

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
 * The one rule that stops a relay team mixing, applied to a team the school built
 * <strong>by hand</strong>.
 *
 * <p>The requirement, as the school confirmed it: a form-class relay is one class's
 * and a house relay is one house's, and that binds a hand-made team exactly as it
 * binds a derived one. A hand-made team carries no {@code kind} of its own — that is
 * what keeps a derive away from it — so the rule has to be read from the
 * <strong>event</strong>. Judging the team by its own kind would exempt it from the
 * very rule the school needs.</p>
 *
 * <p>Asserted here, each against the real service and its real read-then-write
 * behaviour:</p>
 * <ul>
 *   <li>a form relay takes four students of <strong>one class</strong>, and one
 *       student of another class refuses the whole request, naming them, with
 *       <strong>nothing written</strong>;</li>
 *   <li>the same on a house relay: one house accepted, a second house refused;</li>
 *   <li>a member of another <strong>grade</strong> is refused on a house relay too —
 *       the event belongs to one grade, so that is the event's own division-and-grade
 *       rule, and this asserts it still covers the house case;</li>
 *   <li>an athlete may still hold a leg in a form relay <em>and</em> a leg in a house
 *       relay, because those are two different events — the rule must not be tightened
 *       into one leg per athlete overall;</li>
 *   <li>and a relay with no kind at all is undivided: there is no class rule and no
 *       house rule to break, so a hand-made team of any students is allowed there, which
 *       is the state the school builds its draft teams in.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayTeamMixingRuleTest {

    private static final Long EVENT_ID = 42L;
    private static final Long HOUSE_EVENT_ID = 43L;
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

    private Event formEvent;
    private Event houseEvent;

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

        formEvent = relayEvent(EVENT_ID, "Boys 4x100M Relay · B Grade", RelayTeamKind.FORM);
        houseEvent = relayEvent(HOUSE_EVENT_ID, "Boys 4x400M Relay · B Grade", RelayTeamKind.HOUSE);

        signedInAsAdmin();
        wireRepositories();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ============================================================== fixtures

    private Event relayEvent(Long id, String name, RelayTeamKind kind) {
        return Event.builder()
                .id(id)
                .name(name)
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .grade(Grade.B)
                .eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512)
                .groupSize(24)
                .enabled(true)
                .relayTeamKind(kind)
                .build();
    }

    /** One athlete on the register, in the event's own division and grade. */
    private Student runner(String className, String house) {
        return student(className, house, Grade.B);
    }

    private Student student(String className, String house, Grade grade) {
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
                .className(className)
                .classNumber(1)
                .house(house)
                .grade(grade)
                .enabled(true)
                .build();
        students.put(userId, roster);
        return roster;
    }

    private static long userIdOf(Student student) {
        return student.getUser().getId();
    }

    private RelayTeamCreateRequest request(String name, Long... userIds) {
        RelayTeamCreateRequest request = new RelayTeamCreateRequest();
        request.setName(name);
        request.setUserIds(List.of(userIds));
        return request;
    }

    private void signedInAsAdmin() {
        User admin = User.builder().id(ADMIN_ID).username("admin").password("x").fullName("admin")
                .role(User.Role.ADMIN).enabled(true).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                admin.getUsername(), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + admin.getRole().name()))));
        when(userRepository.findByUsername(admin.getUsername())).thenReturn(Optional.of(admin));
    }

    private void wireRepositories() {
        when(eventRepository.findById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (EVENT_ID.equals(id)) {
                return Optional.of(formEvent);
            }
            if (HOUSE_EVENT_ID.equals(id)) {
                return Optional.of(houseEvent);
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
        when(relayTeamRepository.save(any())).thenAnswer(invocation ->
                storeTeam(invocation.getArgument(0)));
        when(relayTeamRepository.saveAndFlush(any())).thenAnswer(invocation ->
                storeTeam(invocation.getArgument(0)));

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
                        .anyMatch(member -> Objects.equals(member.getLeg(),
                                invocation.getArgument(1))));
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

    /** Every team the repository holds for an event, read fresh. */
    private List<RelayTeam> teamsOn(Long eventId) {
        return relayTeamRepository.findByEventIdOrderByIdAsc(eventId);
    }

    // ================================================== a form-class relay

    @Test
    @DisplayName("a hand-made team of one class is accepted on a form relay")
    void oneClassIsAccepted() {
        Student first = runner("1A", "Red");
        Student second = runner("1A", "Blue");
        Student third = runner("1A", "Green");
        Student fourth = runner("1A", "Yellow");

        RelayTeamDTO created = service.createTeam(EVENT_ID, request("1A Squad",
                userIdOf(first), userIdOf(second), userIdOf(third), userIdOf(fourth)));

        assertEquals("1A Squad", created.getLabel());
        assertEquals(4, created.getMemberCount().intValue());
        assertTrue(created.getComplete(), "four students of one class is a complete team");
        assertEquals(List.of("1A", "1A", "1A", "1A"), created.getMembers().stream()
                .map(member -> member.getClassName()).toList());
    }

    @Test
    @DisplayName("one student of another class refuses the whole hand-made team, naming them")
    void anotherClassRefusesTheWholeTeamAndNamesTheStudent() {
        Student sameOne = runner("1A", "Red");
        Student sameTwo = runner("1A", "Blue");
        Student sameThree = runner("1A", "Green");
        Student outsider = runner("2A", "Yellow");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createTeam(EVENT_ID, request("1A Squad",
                        userIdOf(sameOne), userIdOf(sameTwo), userIdOf(sameThree),
                        userIdOf(outsider))));

        assertTrue(error.getMessage().contains(outsider.getName()),
                "the refusal names the student who does not belong: " + error.getMessage());
        assertTrue(error.getMessage().contains("2A"), error.getMessage());
        assertTrue(error.getMessage().contains("1A"), error.getMessage());
        assertTrue(error.getMessage().contains("cannot mix classes"), error.getMessage());
        assertTrue(teams.isEmpty(), "a refused request leaves no team behind");
        assertTrue(members.isEmpty(), "and no runners either");
    }

    @Test
    @DisplayName("a hand-made team mixing two forms — 1A and 2A — is refused on a form relay")
    void twoFormsAreRefused() {
        Student oneA = runner("1A", "Red");
        Student twoA = runner("2A", "Red");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createTeam(EVENT_ID, request("B Grade Yellow",
                        userIdOf(oneA), userIdOf(twoA))));

        assertTrue(error.getMessage().contains(twoA.getName()),
                "the 2A student is the one who does not belong: " + error.getMessage());
        assertTrue(error.getMessage().contains("1A"), error.getMessage());
        assertTrue(error.getMessage().contains("2A"), error.getMessage());
        assertTrue(teams.isEmpty(), "and nothing is written");
    }

    @Test
    @DisplayName("the class is compared without case or spacing, as the register writes it")
    void theClassIsComparedCanonically() {
        Student first = runner("1a", "Red");
        Student second = runner(" 1A ", "Blue");

        RelayTeamDTO created = service.createTeam(EVENT_ID,
                request("1A Squad", userIdOf(first), userIdOf(second)));

        assertEquals(2, created.getMemberCount().intValue(),
                "1a and \" 1A \" are the same class as 1A");
    }

    // ======================================================== a house relay

    @Test
    @DisplayName("a hand-made team of one house is accepted on a house relay, whatever the classes")
    void oneHouseIsAccepted() {
        Student first = runner("2C", "Green");
        Student second = runner("2D", "Green");

        RelayTeamDTO created = service.createTeam(HOUSE_EVENT_ID, request("Green Squad",
                userIdOf(first), userIdOf(second)));

        assertEquals(2, created.getMemberCount().intValue(),
                "two classes are fine while the house is the same");
        assertEquals(List.of("Green", "Green"), created.getMembers().stream()
                .map(member -> member.getHouse()).toList());
    }

    @Test
    @DisplayName("a hand-made team of two houses is refused on a house relay, naming the student")
    void twoHousesAreRefused() {
        Student green = runner("2C", "Green");
        Student blue = runner("2C", "Blue");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createTeam(HOUSE_EVENT_ID, request("Mixed House",
                        userIdOf(green), userIdOf(blue))));

        assertTrue(error.getMessage().contains(blue.getName()),
                "the refusal names the student who does not belong: " + error.getMessage());
        assertTrue(error.getMessage().contains("Green"), error.getMessage());
        assertTrue(error.getMessage().contains("Blue"), error.getMessage());
        assertTrue(error.getMessage().contains("cannot mix houses"), error.getMessage());
        assertTrue(teams.isEmpty(), "and nothing is written");
    }

    /**
     * The event already belongs to one grade, so a member of another grade is refused
     * by the event's own division-and-grade rule, before any house is read. This
     * asserts that the existing check still covers the house case, so no separate
     * grade test was needed.
     */
    @Test
    @DisplayName("on a house relay, a member of another grade is refused by the event's own grade rule")
    void anotherGradeIsRefusedOnAHouseRelay() {
        Student green = runner("2C", "Green");
        Student seniorGreen = student("3A", "Green", Grade.A);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createTeam(HOUSE_EVENT_ID, request("Green Squad",
                        userIdOf(green), userIdOf(seniorGreen))));

        assertTrue(error.getMessage().contains(seniorGreen.getName()),
                "the athlete of another grade is named: " + error.getMessage());
        assertTrue(error.getMessage().contains("A grade"), error.getMessage());
        assertTrue(error.getMessage().contains("B Grade"), error.getMessage());
        assertTrue(teams.isEmpty(), "and nothing is written");
    }

    // ============================================ one leg per event, not per athlete

    /**
     * The rule is one leg per <strong>event</strong>. A form relay and a house relay
     * are two races, so the same athlete may hold a leg in each — and this is the half
     * of the requirement that must not be broken while the mixing rule is tightened.
     */
    @Test
    @DisplayName("an athlete may hold a leg in a form relay and a leg in a house relay")
    void aLegInEachKindOfRelayIsAllowed() {
        Student athlete = runner("1A", "Red");

        RelayTeamDTO formTeam = service.createTeam(EVENT_ID,
                request("1A Squad", userIdOf(athlete)));
        RelayTeamDTO houseTeam = service.createTeam(HOUSE_EVENT_ID,
                request("Red House Squad", userIdOf(athlete)));

        assertEquals(1, formTeam.getMemberCount().intValue(), "the form relay leg stands");
        assertEquals(1, houseTeam.getMemberCount().intValue(), "and so does the house relay leg");
        assertEquals(EVENT_ID, formTeam.getEventId());
        assertEquals(HOUSE_EVENT_ID, houseTeam.getEventId());

        // Two legs of two events, and the second team really exists.
        assertEquals(2, members.size(), "one leg in each event");
        assertEquals(1, teamsOn(EVENT_ID).size());
        assertEquals(1, teamsOn(HOUSE_EVENT_ID).size());
    }

    @Test
    @DisplayName("the same athlete still cannot hold two legs of the same event")
    void twoLegsOfOneEventAreStillRefused() {
        Student athlete = runner("1A", "Red");
        service.createTeam(EVENT_ID, request("1A Squad", userIdOf(athlete)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createTeam(EVENT_ID, request("1A Seconds", userIdOf(athlete))));

        assertTrue(error.getMessage().contains("already runs for"), error.getMessage());
        assertTrue(error.getMessage().contains("only one leg of an event"), error.getMessage());
    }

    // ==================================================== an undivided relay

    /**
     * A relay with no kind is undivided: it has no class rule and no house rule to
     * break, so nothing refuses a hand-made team there. That is the decision taken —
     * refusing it would forbid the flow the school actually uses, because a relay
     * starts undivided and the school builds its teams before dividing it.
     */
    @Test
    @DisplayName("an undivided relay takes a hand-made team of any students, and then binds it")
    void anUndividedRelayAllowsAMixedHandMadeTeam() {
        formEvent.setRelayTeamKind(null);
        Student oneA = runner("1A", "Red");
        Student twoA = runner("2A", "Green");

        RelayTeamDTO created = service.createTeam(EVENT_ID,
                request("Yellow Squad", userIdOf(oneA), userIdOf(twoA)));

        assertEquals(2, created.getMemberCount().intValue(),
                "with no kind there is no class rule to break");
        assertNull(created.getKind(), "the team is still a hand-made one, of no kind");
    }

    @Test
    @DisplayName("once that relay is divided, the same hand-made team refuses a runner who would mix")
    void anUndividedRelaysTeamIsBoundOnceTheEventIsDivided() {
        formEvent.setRelayTeamKind(null);
        Student oneA = runner("1A", "Red");
        Student twoA = runner("2A", "Green");
        Student anotherTwoA = runner("2A", "Blue");

        RelayTeamDTO created = service.createTeam(EVENT_ID,
                request("Yellow Squad", userIdOf(oneA), userIdOf(twoA)));
        assertEquals(2, created.getMemberCount().intValue());

        // The school divides the relay. The teams made before it are not migrated or
        // pruned — a derive never touches a hand-made team — but the rule now applies
        // to anybody joining one.
        formEvent.setRelayTeamKind(RelayTeamKind.FORM);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(created.getId(), userIdOf(anotherTwoA), null));

        assertTrue(error.getMessage().contains(anotherTwoA.getName()),
                "the rule comes from the event, so it binds a team built before the split: "
                        + error.getMessage());
        assertTrue(error.getMessage().contains("cannot mix classes"), error.getMessage());
        assertEquals(2, membersOf(created.getId()).size(), "and the refusal writes nothing");
    }
}
