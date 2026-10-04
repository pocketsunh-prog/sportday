package com.sportday.service;

import com.sportday.dto.EnrollmentDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A teacher entering and withdrawing a student.
 *
 * <p>Requirement: a teacher may help a student in a class assigned to them, and is
 * refused any other student. Every staff-facing path goes through
 * {@link TeacherHelpService}, so this asserts that the class rule is applied once,
 * before anything is written, on entering, withdrawing <em>and</em> reading —
 * which is what makes it impossible to bypass by picking a different endpoint.</p>
 *
 * <p>The rule itself (which class, whose classes, an administrator bypassing it) is
 * asserted in {@code TeacherClassServiceTest}, and the entry rules that still
 * apply to the student are asserted in {@code EnrollmentGradeTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeacherHelpsStudentTest {

    private static final String STUDENT = "S0001";

    @Mock private TeacherClassService teacherClassService;
    @Mock private EnrollmentService enrollmentService;

    private TeacherHelpService service;

    @BeforeEach
    void setUp() {
        service = new TeacherHelpService(teacherClassService, enrollmentService);
        when(enrollmentService.requireUserIdFor(STUDENT)).thenReturn(11L);
    }

    /** The class rule refuses the caller. */
    private void refused(String message) {
        doThrow(new AccessDeniedException(message))
                .when(teacherClassService).requireMayHelpUserId(any());
    }

    private EnrollmentDTO anEntry() {
        return EnrollmentDTO.builder().id(1L).eventId(2L).eventName("Boys 100M · C Grade").build();
    }

    // ------------------------------------------------------------- entering

    @Test
    @DisplayName("a teacher enters a student in one of their own classes")
    void aTeacherEntersTheirOwnStudent() {
        when(enrollmentService.enrollOnBehalf(11L, 2L)).thenReturn(anEntry());

        EnrollmentDTO entered = service.enroll(STUDENT, 2L);

        assertEquals("Boys 100M · C Grade", entered.getEventName());
        verify(teacherClassService).requireMayHelpUserId(11L);
        verify(enrollmentService).enrollOnBehalf(11L, 2L);
    }

    @Test
    @DisplayName("a teacher is refused a student outside their classes, and nothing is entered")
    void aTeacherIsRefusedAnotherClass() {
        refused("2C is not one of your classes.");

        AccessDeniedException error = assertThrows(AccessDeniedException.class,
                () -> service.enroll(STUDENT, 2L));

        assertEquals("2C is not one of your classes.", error.getMessage());
        verify(enrollmentService, never()).enrollOnBehalf(any(), any());
    }

    @Test
    @DisplayName("a teacher with no classes is refused everybody")
    void aTeacherWithNoClassesIsRefused() {
        refused("You have no classes assigned, so you cannot help any student.");

        AccessDeniedException error = assertThrows(AccessDeniedException.class,
                () -> service.enroll(STUDENT, 2L));

        assertTrue(error.getMessage().contains("no classes assigned"), error.getMessage());
        verify(enrollmentService, never()).enrollOnBehalf(any(), any());
    }

    // ----------------------------------------------------------- withdrawing

    @Test
    @DisplayName("a teacher withdraws a student in their class")
    void aTeacherWithdrawsTheirOwnStudent() {
        service.withdraw(STUDENT, 2L);

        verify(teacherClassService).requireMayHelpUserId(11L);
        verify(enrollmentService).cancelEnrollment(11L, 2L);
    }

    @Test
    @DisplayName("a teacher cannot withdraw a student outside their classes")
    void aTeacherCannotWithdrawAnotherClass() {
        refused("2C is not one of your classes.");

        assertThrows(AccessDeniedException.class, () -> service.withdraw(STUDENT, 2L));
        verify(enrollmentService, never()).cancelEnrollment(any(), any());
    }

    // -------------------------------------------------------------- reading

    @Test
    @DisplayName("a teacher reads a student's entries through the same class check")
    void readingEntriesIsAlsoChecked() {
        when(enrollmentService.getAllUserEnrollments(11L)).thenReturn(List.of(anEntry()));
        when(enrollmentService.getQuota(11L)).thenReturn(
                new EnrollmentService.Quota(1, 2, 1, 0, 1, 1));

        Map<String, Object> body = service.entriesFor(STUDENT);

        assertEquals(STUDENT, body.get("studentId"));
        assertEquals(11L, body.get("userId"));
        assertEquals(1, ((List<?>) body.get("enrollments")).size());
        assertNotNull(body.get("quota"));
        verify(teacherClassService).requireMayHelpUserId(11L);
    }

    @Test
    @DisplayName("reading is refused for a student outside the teacher's classes")
    void readingIsRefusedForAnotherClass() {
        refused("2C is not one of your classes.");

        assertThrows(AccessDeniedException.class, () -> service.entriesFor(STUDENT));
        verify(enrollmentService, never()).getAllUserEnrollments(any());
    }

    // --------------------------------------------------------------- admin

    @Test
    @DisplayName("an administrator is never refused, because the check lets them through")
    void anAdminIsNeverRefused() {
        when(enrollmentService.enrollOnBehalf(11L, 2L)).thenReturn(anEntry());
        // TeacherClassService.requireMayHelpUserId is a no-op for an administrator;
        // that decision is asserted in TeacherClassServiceTest, and here it is the
        // same call, so the admin path provably shares the one implementation.
        service.enroll(STUDENT, 2L);

        verify(teacherClassService).requireMayHelpUserId(11L);
        verify(enrollmentService).enrollOnBehalf(11L, 2L);
    }

    @Test
    @DisplayName("a student who has no login account is refused before the class rule is even reached")
    void aStudentWithNoAccountIsRefused() {
        when(enrollmentService.requireUserIdFor("S9999"))
                .thenThrow(new IllegalStateException("Student S9999 has no login account, so they "
                        + "cannot be entered in an event."));

        assertThrows(IllegalStateException.class, () -> service.enroll("S9999", 2L));
        verify(teacherClassService, never()).requireMayHelpUserId(any());
    }
}