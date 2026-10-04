package com.sportday.service;

import com.sportday.entity.Student;
import com.sportday.entity.Sex;
import com.sportday.entity.User;
import com.sportday.repository.StudentRepository;
import com.sportday.repository.TeacherClassRepository;
import com.sportday.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Who a teacher may help.
 *
 * <p>Requirement: "A teacher may only help students in the classes assigned to
 * them. Not any student." An administrator is still allowed any student, and a
 * teacher with no classes at all can help nobody — a refusal, not a silent
 * allow.</p>
 *
 * <p>The rule is asserted on the service that every entry path goes through, and
 * the role wiring — the request rules and the {@code @PreAuthorize} on each
 * controller — in {@link TeacherSecurityWiringTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeacherClassServiceTest {

    private static final Long TEACHER_ID = 90L;
    private static final String TEACHER_USERNAME = "tchan";

    @Mock private TeacherClassRepository teacherClassRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private UserRepository userRepository;
    @Mock private GradeCalculator gradeCalculator;

    @InjectMocks private TeacherClassService service;

    private User teacher;
    private User admin;

    @BeforeEach
    void setUp() {
        teacher = User.builder()
                .id(TEACHER_ID)
                .username(TEACHER_USERNAME)
                .fullName("Chan Tai Man")
                .password("x")
                .role(User.Role.TEACHER)
                .enabled(true)
                .build();
        admin = User.builder()
                .id(1L)
                .username("admin")
                .password("x")
                .role(User.Role.ADMIN)
                .enabled(true)
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Puts a role on the security context the way the JWT filter would. */
    private void signedInAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user.getUsername(), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
        when(userRepository.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
    }

    private Student student(String studentId, String className) {
        return Student.builder()
                .id(7L)
                .studentId(studentId)
                .name("Wong Siu Ming")
                .dob(LocalDate.of(2012, 5, 5))
                .sex(Sex.MALE)
                .className(className)
                .classNumber(3)
                .house("Red")
                .enabled(true)
                .build();
    }

    private void givenStudent(String studentId, String className, Long userId) {
        Student roster = student(studentId, className);
        roster.setUser(User.builder().id(userId).username(studentId).build());
        when(studentRepository.findByStudentId(studentId)).thenReturn(Optional.of(roster));
        when(studentRepository.findWithUserByUserId(userId)).thenReturn(Optional.of(roster));
    }

    // ------------------------------------------------- a teacher with classes

    @Test
    @DisplayName("a teacher may help a student in one of their own classes")
    void aTeacherHelpsTheirOwnClass() {
        signedInAs(teacher);
        givenStudent("S0001", "1A", 11L);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID)).thenReturn(List.of("1A", "3B"));

        assertDoesNotThrow(() -> service.requireMayHelpUserId(11L));
        assertDoesNotThrow(() -> service.requireMayHelp(student("S0001", "1A")));
    }

    @Test
    @DisplayName("a teacher is refused a student outside their classes, and the refusal names the class")
    void aTeacherIsRefusedAnotherClass() {
        signedInAs(teacher);
        givenStudent("S0002", "2C", 12L);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID)).thenReturn(List.of("1A", "3B"));

        AccessDeniedException error = assertThrows(AccessDeniedException.class,
                () -> service.requireMayHelpUserId(12L));

        assertEquals("2C is not one of your classes.", error.getMessage());
    }

    @Test
    @DisplayName("the class check is exact: 1A does not admit 1AB, and 1A admits ' 1a '")
    void theClassCheckNormalisesBothSides() {
        signedInAs(teacher);
        givenStudent("S0003", "1a", 13L);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID)).thenReturn(List.of("1A"));

        assertDoesNotThrow(() -> service.requireMayHelpUserId(13L));
    }

    // --------------------------------------------------- a teacher with none

    @Test
    @DisplayName("a teacher with no classes can help nobody, and is told why")
    void aTeacherWithNoClassesHelpsNobody() {
        signedInAs(teacher);
        givenStudent("S0001", "1A", 11L);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID)).thenReturn(List.of());

        AccessDeniedException error = assertThrows(AccessDeniedException.class,
                () -> service.requireMayHelpUserId(11L));

        assertTrue(error.getMessage().contains("no classes assigned"), error.getMessage());
    }

    @Test
    @DisplayName("...and their student list is empty, never the whole school")
    void aTeacherWithNoClassesSeesNoStudents() {
        signedInAs(teacher);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID)).thenReturn(List.of());

        assertTrue(service.studentsMayHelp(teacher, null).isEmpty());
        verify(studentRepository, never()).findAllWithUser();
    }

    // ------------------------------------------------------------ an admin

    @Test
    @DisplayName("an administrator is never refused, whatever the student's class")
    void anAdminIsNeverRefused() {
        signedInAs(admin);
        givenStudent("S0002", "2C", 12L);
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(admin.getId())).thenReturn(List.of());

        assertDoesNotThrow(() -> service.requireMayHelpUserId(12L));
        assertDoesNotThrow(() -> service.requireMayHelp(student("S0002", "2C")));
    }

    @Test
    @DisplayName("an administrator may help a student the register gives no class for")
    void anAdminMayHelpAStudentWithNoClass() {
        signedInAs(admin);
        when(studentRepository.findByStudentId("S9999")).thenReturn(Optional.of(student("S9999", null)));

        assertDoesNotThrow(() -> service.requireMayHelp(student("S9999", null)));
    }

    // ------------------------------------------------------------ everybody else

    @Test
    @DisplayName("a manager who is not a teacher may not act for a student")
    void aManagerIsNotATeacher() {
        User manager = User.builder().id(5L).username("mgr").password("x")
                .role(User.Role.MANAGER).enabled(true).build();
        signedInAs(manager);
        givenStudent("S0001", "1A", 11L);

        assertThrows(AccessDeniedException.class, () -> service.requireMayHelpUserId(11L));
    }

    @Test
    @DisplayName("a signed-out caller is refused rather than let through")
    void nobodySignedInIsRefused() {
        SecurityContextHolder.clearContext();
        givenStudent("S0001", "1A", 11L);

        assertThrows(AccessDeniedException.class, () -> service.requireMayHelpUserId(11L));
    }

    // ----------------------------------------------------- class assignments

    @Test
    @DisplayName("assigning classes replaces the old list and normalises the names")
    void assigningReplacesTheList() {
        service.assignClasses(teacher, java.util.Set.of(" 1a ", "3b", "1A", " "));

        verify(teacherClassRepository).deleteByUserId(TEACHER_ID);
        verify(teacherClassRepository).flush();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.sportday.entity.TeacherClass>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(teacherClassRepository).saveAll(captor.capture());
        assertEquals(java.util.Set.of("1A", "3B"),
                new java.util.LinkedHashSet<>(
                        captor.getValue().stream().map(com.sportday.entity.TeacherClass::getClassName).toList()),
                "the old list is gone, the duplicates are dropped and the names are normalised");
        assertTrue(captor.getValue().stream().allMatch(row -> row.getUser() == teacher),
                "every assignment belongs to the teacher it was made for");
    }

    @Test
    @DisplayName("a teacher's assigned classes are reported as stored")
    void assignedClassesAreListed() {
        when(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(TEACHER_ID)).thenReturn(List.of("1A", "3B"));
        assertEquals(List.of("1A", "3B"), service.assignedClasses(teacher));
    }
}
