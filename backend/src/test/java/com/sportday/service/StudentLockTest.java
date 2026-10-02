package com.sportday.service;

import com.sportday.dto.StudentDTO;
import com.sportday.entity.Student;
import com.sportday.entity.Sex;
import com.sportday.entity.User;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.StudentRepository;
import com.sportday.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Locking a student who is not on the year's list.
 *
 * <p>Requirement: an administrator uploads all students every year, and a student
 * not on that list is locked.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentLockTest {

    @Mock private StudentRepository studentRepository;
    @Mock private UserRepository userRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private EventResultRepository eventResultRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private GradeCalculator gradeCalculator;
    @Mock private StudentPasswordPolicy passwordPolicy;
    @Mock private StudentImportParser parser;
    @Mock private StudentSampleDataGenerator generator;

    @InjectMocks private StudentService service;

    private Student student(boolean enabled) {
        User user = User.builder()
                .id(3L)
                .username("S0007")
                .password("x")
                .role(User.Role.STUDENT)
                .enabled(enabled)
                .build();
        return Student.builder()
                .id(7L)
                .user(user)
                .studentId("S0007")
                .name("Chan Tai Man")
                .dob(LocalDate.of(2012, 5, 5))
                .sex(Sex.MALE)
                .className("1A")
                .classNumber(7)
                .house("Red")
                .enabled(enabled)
                .build();
    }

    @Test
    @DisplayName("locking a student also locks their login, so they cannot sign in")
    void lockingAlsoBlocksTheLogin() {
        Student existing = student(true);
        when(studentRepository.findByStudentId("S0007")).thenReturn(Optional.of(existing));

        StudentDTO locked = service.setStudentLocked("S0007", true);

        assertFalse(existing.getEnabled(), "the roster record is locked");
        assertFalse(existing.getUser().getEnabled(), "and so is the account they sign in with");
        assertFalse(locked.getEnabled());
        verify(studentRepository).save(existing);
        verify(userRepository).save(existing.getUser());
    }

    @Test
    @DisplayName("unlocking restores the login, because a student may come back")
    void unlockingRestoresTheLogin() {
        Student existing = student(false);
        when(studentRepository.findByStudentId("S0007")).thenReturn(Optional.of(existing));

        service.setStudentLocked("S0007", false);

        assertTrue(existing.getEnabled());
        assertTrue(existing.getUser().getEnabled());
    }

    @Test
    @DisplayName("locking keeps the student's history — nothing is deleted")
    void lockingKeepsTheHistory() {
        Student existing = student(true);
        when(studentRepository.findByStudentId("S0007")).thenReturn(Optional.of(existing));

        service.setStudentLocked("S0007", true);

        // The whole point of a lock rather than a delete: entries, results and any
        // record they hold survive.
        verify(enrollmentRepository, never()).delete(any());
        verify(eventResultRepository, never()).delete(any());
        verify(studentRepository, never()).delete(any(Student.class));
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    @DisplayName("an unknown student id is reported, not silently ignored")
    void unknownStudentIsReported() {
        when(studentRepository.findByStudentId("NOPE")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.setStudentLocked("NOPE", true));
    }

    @Test
    @DisplayName("the register can be narrowed to just the locked students")
    void theRegisterCanListLockedStudents() {
        Student active = student(true);
        Student locked = student(false);
        when(studentRepository.findAllWithUser()).thenReturn(java.util.List.of(active, locked));
        when(studentRepository.findAll()).thenReturn(java.util.List.of(active, locked));
        when(gradeCalculator.referenceDate()).thenReturn(LocalDate.of(2026, 10, 1));

        assertEquals(1, service.listStudents(null, null, null, null, false).size(),
                "only the locked one");
        assertEquals(1, service.listStudents(null, null, null, null, true).size(),
                "only the active one");
        assertEquals(2, service.listStudents(null, null, null, null, null).size(),
                "and both without a filter");
        assertEquals(1, service.countLocked());
    }
}
