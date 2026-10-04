package com.sportday.service;

import com.sportday.dto.EnrollmentDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.Student;
import com.sportday.entity.User;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.StudentRepository;
import com.sportday.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A student may only enter an event of their own grade.
 *
 * <p>Requirement: an event is per type, division <em>and</em> grade, so a C-grade
 * student is refused a place in the A-grade race — and an administrator entering
 * them by hand is held to exactly the same rule, so it cannot be worked around by
 * doing it for them.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnrollmentGradeTest {

    private static final Long STUDENT_USER = 11L;

    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private EventRepository eventRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private SettingsService settingsService;
    @Mock private SeasonService seasonService;
    @Mock private FinalQualificationService finalQualificationService;

    @InjectMocks private EnrollmentService service;

    private Student cGradeStudent;

    @BeforeEach
    void setUp() {
        User user = User.builder().id(STUDENT_USER).username("S0001")
                .fullName("Chan Tai Man").enabled(true).build();
        cGradeStudent = Student.builder()
                .id(1L)
                .user(user)
                .studentId("S0001")
                .name("Chan Tai Man")
                .dob(LocalDate.of(2012, 5, 5))
                .grade(Grade.C)
                .sex(Sex.MALE)
                .className("3A")
                .classNumber(1)
                .enabled(true)
                .build();

        when(userRepository.findById(STUDENT_USER)).thenReturn(Optional.of(user));
        when(studentRepository.findWithUserByUserId(STUDENT_USER)).thenReturn(Optional.of(cGradeStudent));
        when(enrollmentRepository.existsByUserIdAndEventId(anyLong(), anyLong())).thenReturn(false);
        when(enrollmentRepository.findByUserIdAndEventId(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(enrollmentRepository.countByUserAndCategory(anyLong(), any(), any())).thenReturn(0L);
        when(enrollmentRepository.countByEventIdAndStatus(anyLong(), any())).thenReturn(0L);
        when(enrollmentRepository.save(any(Enrollment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(settingsService.maxEntriesFor(any())).thenReturn(2);
        when(seasonService.currentSeason()).thenReturn(null);
    }

    private Event event(long id, Event.EventType type, Sex sex, Grade grade) {
        return Event.builder()
                .id(id)
                .name(EventService.defaultName(type, sex, grade))
                .type(type)
                .category(type.getCategory())
                .sex(sex)
                .grade(grade)
                .eventDate(LocalDate.of(2026, 11, 6))
                .maxParticipants(512)
                .groupSize(type.getDefaultGroupSize())
                .enabled(true)
                .directToFinal(true)
                .build();
    }

    private void givenEvent(Event event) {
        when(eventRepository.findById(event.getId())).thenReturn(Optional.of(event));
    }

    // ------------------------------------------------------------ the student

    @Test
    @DisplayName("a C-grade student is refused a place in the A-grade 1500M")
    void aCGradeStudentCannotEnterAnAGradeEvent() {
        Event aGrade = event(2L, Event.EventType.RUN_1500M, Sex.MALE, Grade.A);
        givenEvent(aGrade);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.enrollUserToEvent(STUDENT_USER, 2L));

        assertEquals("This is the A Grade 1500M and you are in the C grade.", error.getMessage());
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }

    @Test
    @DisplayName("...and is accepted by the C-grade 1500M, which is a different event")
    void aCGradeStudentEntersTheirOwnGradeEvent() {
        Event cGrade = event(3L, Event.EventType.RUN_1500M, Sex.MALE, Grade.C);
        givenEvent(cGrade);
        when(enrollmentRepository.countByUserAndCategory(
                eq(STUDENT_USER), eq(Enrollment.EnrollmentStatus.CONFIRMED), eq(EventCategory.TRACK)))
                .thenReturn(0L);

        EnrollmentDTO entered = service.enrollUserToEvent(STUDENT_USER, 3L);

        assertEquals("Boys 1500M · C Grade", entered.getEventName());
        assertEquals("C", entered.getGrade());
        verify(enrollmentRepository).save(any(Enrollment.class));
    }

    // --------------------------------------------------------- on their behalf

    @Test
    @DisplayName("an administrator entering a student by hand is bound by the same rule")
    void anAdministratorCannotEnterAForeignGradeEvent() {
        Event aGrade = event(2L, Event.EventType.RUN_1500M, Sex.MALE, Grade.A);
        givenEvent(aGrade);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.enrollOnBehalf(STUDENT_USER, 2L));

        assertEquals("This is the A Grade 1500M and you are in the C grade.", error.getMessage());
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }

    @Test
    @DisplayName("...and can still enter them in the grade's own event")
    void anAdministratorCanEnterTheirOwnGradeEvent() {
        Event cGrade = event(3L, Event.EventType.RUN_1500M, Sex.MALE, Grade.C);
        givenEvent(cGrade);

        EnrollmentDTO entered = service.enrollOnBehalf(STUDENT_USER, 3L);

        assertEquals("C", entered.getGrade());
        verify(enrollmentRepository).save(any(Enrollment.class));
    }

    @Test
    @DisplayName("a withdrawn entry cannot be revived into a race the student's grade does not run")
    void revivingAForeignGradeEntryIsRefused() {
        Event aGrade = event(2L, Event.EventType.RUN_1500M, Sex.MALE, Grade.A);
        Enrollment cancelled = Enrollment.builder()
                .id(1L)
                .user(cGradeStudent.getUser())
                .event(aGrade)
                .status(Enrollment.EnrollmentStatus.CANCELLED)
                .build();
        when(enrollmentRepository.findByUserIdAndEventId(STUDENT_USER, 2L))
                .thenReturn(Optional.of(cancelled));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.enrollOnBehalf(STUDENT_USER, 2L));

        assertEquals("This is the A Grade 1500M and you are in the C grade.", error.getMessage());
        assertEquals(Enrollment.EnrollmentStatus.CANCELLED, cancelled.getStatus(),
                "the entry is left as it was");
    }

    // -------------------------------------------------------- the other checks

    @Test
    @DisplayName("the division check still stands beside the grade one")
    void theDivisionCheckStillApplies() {
        Event girlsEvent = event(4L, Event.EventType.RUN_100M, Sex.FEMALE, Grade.C);
        givenEvent(girlsEvent);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.enrollUserToEvent(STUDENT_USER, 4L));

        assertTrue(error.getMessage().contains("Girls"), error.getMessage());
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }
}
