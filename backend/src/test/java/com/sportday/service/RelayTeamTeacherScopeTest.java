package com.sportday.service;

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
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Who may put a student into a relay team.
 *
 * <p>Requirement 3: "teacher can help select student join different relay". A teacher
 * may name a runner only from a class assigned to them; an administrator may name
 * anybody. This test wires the <strong>real</strong> {@link TeacherClassService} ??the
 * one place that rule lives ??into {@link RelayTeamService}, so it fails if the relay
 * selection path ever stops asking, or starts answering that question for itself.</p>
 *
 * <p>Every other rule is satisfied on purpose: both students are Form 1, in the
 * event's grade and division, in the team's form, and there is a leg free. The only
 * thing that can refuse them is the class rule.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayTeamTeacherScopeTest {

    private static final Long EVENT_ID = 42L;
    private static final Long TEACHER_ID = 90L;

    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;
    @Mock private EventRepository eventRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private TeacherClassRepository teacherClassRepository;
    @Mock private UserRepository userRepository;
    @Mock private GradeCalculator gradeCalculator;

    /** The real rule, and the service under test wired to it. */
    private TeacherClassService teacherClassService;
    private RelayTeamService service;

    private final List<RelayTeamMember> members = new ArrayList<>();
    private long nextMemberId = 1;

    private Event event;
    private RelayTeam formOne;
    private User teacher;
    private User admin;
    private Student oneA;
    private Student oneB;

    @BeforeEach
    void setUp() {
        teacherClassService = new TeacherClassService(teacherClassRepository, studentRepository,
                userRepository, gradeCalculator);
        service = new RelayTeamService(relayTeamRepository, relayTeamMemberRepository,
                eventRepository, studentRepository, teacherClassService);

        members.clear();
        nextMemberId = 1;

        teacher = user(TEACHER_ID, "tchan", User.Role.TEACHER);
        admin = user(1L, "admin", User.Role.ADMIN);
        oneA = student(11L, "S0011", "1A");
        oneB = student(12L, "S0012", "1B");

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
        formOne = RelayTeam.builder().id(1L).event(event).kind(RelayTeamKind.FORM)
                .teamKey("1").label("Form 1").build();

        wire();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private User user(Long id, String username, User.Role role) {
        return User.builder().id(id).username(username).password("x").fullName(username)
                .role(role).enabled(true).build();
    }

    private Student student(Long userId, String studentId, String className) {
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
                .house("Red")
                .grade(Grade.B)
                .enabled(true)
                .build();
        when(studentRepository.findWithUserByUserId(userId)).thenReturn(Optional.of(roster));
        when(studentRepository.findByStudentId(studentId)).thenReturn(Optional.of(roster));
        return roster;
    }

    private void wire() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(relayTeamRepository.findById(1L)).thenReturn(Optional.of(formOne));
        when(relayTeamMemberRepository.findByTeamIdOrderByLegAsc(anyLong()))
                .thenAnswer(invocation -> members.stream()
                        .sorted(Comparator.comparingInt(RelayTeamMember::getLeg))
                        .collect(Collectors.toList()));
        when(relayTeamMemberRepository.countByTeamId(anyLong()))
                .thenAnswer(invocation -> (long) members.size());
        when(relayTeamMemberRepository.findForEventAndUser(anyLong(), anyLong()))
                .thenReturn(List.of());
        when(relayTeamMemberRepository.existsByTeamIdAndLeg(anyLong(), any())).thenReturn(false);
        when(relayTeamMemberRepository.save(any())).thenAnswer(invocation -> {
            RelayTeamMember saved = invocation.getArgument(0);
            saved.setId(nextMemberId++);
            members.add(saved);
            return saved;
        });
        when(relayTeamMemberRepository.findByTeamIdAndUserId(anyLong(), anyLong()))
                .thenAnswer(invocation -> members.stream()
                        .filter(member -> member.getUser().getId().equals(invocation.getArgument(1)))
                        .findFirst());
        doAnswer(invocation -> {
            members.remove(invocation.getArgument(0));
            return null;
        }).when(relayTeamMemberRepository).delete(any());
        when(studentRepository.findWithUserByUserIdIn(any())).thenAnswer(invocation -> {
            List<Long> ids = new ArrayList<>(invocation.getArgument(0));
            List<Student> found = new ArrayList<>();
            if (ids.contains(oneA.getUser().getId())) found.add(oneA);
            if (ids.contains(oneB.getUser().getId())) found.add(oneB);
            return found;
        });
    }

    /** Signs a staff account in the way the JWT filter does. */
    private void signedInAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user.getUsername(), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
        when(userRepository.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
    }

    // ================================================= a teacher with a class

    @Test
    @DisplayName("a teacher names a runner from one of their own classes")
    void aTeacherNamesTheirOwnClass() {
        signedInAs(teacher);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID)).thenReturn(List.of("1A"));

        var team = service.addRunner(formOne.getId(), oneA.getUser().getId(), null);

        assertEquals(1, team.getMemberCount().intValue());
        assertEquals("S0011", team.getMembers().get(0).getStudentId());
    }

    @Test
    @DisplayName("a teacher is refused a runner from another class, and the refusal names the class")
    void aTeacherIsRefusedAnotherClass() {
        signedInAs(teacher);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID)).thenReturn(List.of("1A"));

        // 1B is Form 1 too, so every other rule lets them run: only the class rule
        // stands in the way, which is exactly what is being asserted.
        AccessDeniedException error = assertThrows(AccessDeniedException.class,
                () -> service.addRunner(formOne.getId(), oneB.getUser().getId(), null));

        assertEquals("1B is not one of your classes.", error.getMessage());
        assertTrue(members.isEmpty(), "a refused runner is not written");
    }

    @Test
    @DisplayName("a teacher with no classes assigned can help nobody")
    void aTeacherWithNoClassesHelpsNobody() {
        signedInAs(teacher);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID)).thenReturn(List.of());

        AccessDeniedException error = assertThrows(AccessDeniedException.class,
                () -> service.addRunner(formOne.getId(), oneA.getUser().getId(), null));

        assertTrue(error.getMessage().contains("no classes assigned"), error.getMessage());
    }

    @Test
    @DisplayName("a teacher may not take a runner out of a team either")
    void aTeacherCannotRemoveAnotherClass() {
        signedInAs(admin);
        service.addRunner(formOne.getId(), oneB.getUser().getId(), null);

        signedInAs(teacher);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID)).thenReturn(List.of("1A"));

        assertThrows(AccessDeniedException.class,
                () -> service.removeRunner(formOne.getId(), oneB.getUser().getId()));
        assertEquals(1, members.size(), "a refused removal leaves the leg alone");
    }

    // ======================================================== an administrator

    @Test
    @DisplayName("an administrator names a runner of any class")
    void anAdminNamesAnyClass() {
        signedInAs(admin);

        var team = service.addRunner(formOne.getId(), oneB.getUser().getId(), null);

        assertEquals("S0012", team.getMembers().get(0).getStudentId());
        verify(teacherClassRepository, never()).findClassNamesByUserIdOrderByClassNameAsc(anyLong());
    }

    @Test
    @DisplayName("an administrator may take a runner out again")
    void anAdminRemovesAnyRunner() {
        signedInAs(admin);
        service.addRunner(formOne.getId(), oneA.getUser().getId(), null);

        var after = service.removeRunner(formOne.getId(), oneA.getUser().getId());

        assertEquals(0, after.getMemberCount().intValue());
    }

    // ============================================================== nobody else

    @Test
    @DisplayName("a student account may not name a runner, not even themselves")
    void aStudentMayNotNameARunner() {
        Student account = oneA;
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                account.getUser().getUsername(), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        when(userRepository.findByUsername(account.getUser().getUsername()))
                .thenReturn(Optional.of(account.getUser()));

        AccessDeniedException error = assertThrows(AccessDeniedException.class,
                () -> service.addRunner(formOne.getId(), oneA.getUser().getId(), null));

        assertTrue(error.getMessage().contains("Only a teacher or an administrator"),
                error.getMessage());
    }
}
