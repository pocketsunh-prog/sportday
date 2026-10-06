package com.sportday.service;

import com.sportday.dto.EnrollmentDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.SportDaySettings;
import com.sportday.entity.Student;
import com.sportday.entity.User;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.SportDaySettingsRepository;
import com.sportday.repository.StudentRepository;
import com.sportday.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * What a relay costs a student, now that a relay is its own category.
 *
 * <p>The school's rule: a relay may be one team per <strong>grade × house</strong>
 * (the A/B/C grade house relay) or one per <strong>form and class</strong> (the
 * Form 1 to 6 class relay). A student may hold a leg in <em>both</em> — a house relay
 * and a class relay are two events — so the relay allowance must not be the field's
 * single entry. The tests below use the real {@link SettingsService} over the
 * school's own defaults, so what a relay is allowed is what the shipped settings
 * actually produce, not what a mock was told to say.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayCategoryQuotaTest {

    private static final Long STUDENT_USER = 11L;
    private static final Long HOUSE_RELAY = 5L;
    private static final Long CLASS_RELAY = 6L;

    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private EventRepository eventRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private SportDaySettingsRepository settingsRepository;
    @Mock private SeasonService seasonService;
    @Mock private FinalQualificationService finalQualificationService;

    private SettingsService settingsService;
    private EnrollmentService service;

    @BeforeEach
    void setUp() {
        // The real settings service on the defaults the school runs on: the settings
        // row does not exist yet, so it is created with 2 track / 1 field. Nothing
        // here stubs RELAY, so the relay allowance is whatever the code decides.
        settingsService = new SettingsService(settingsRepository);
        when(settingsRepository.findById(SportDaySettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(settingsRepository.save(any(SportDaySettings.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service = new EnrollmentService(enrollmentRepository, userRepository, eventRepository,
                studentRepository, settingsService, seasonService, finalQualificationService);

        User user = User.builder().id(STUDENT_USER).username("S0001")
                .fullName("Chan Tai Man").enabled(true).build();
        Student roster = Student.builder()
                .id(1L)
                .user(user)
                .studentId("S0001")
                .name("Chan Tai Man")
                .dob(LocalDate.of(2009, 5, 5))
                .grade(Grade.A)
                .sex(Sex.MALE)
                .className("5A")
                .classNumber(1)
                .enabled(true)
                .build();

        when(userRepository.findById(STUDENT_USER)).thenReturn(Optional.of(user));
        when(studentRepository.findWithUserByUserId(STUDENT_USER)).thenReturn(Optional.of(roster));
        when(enrollmentRepository.existsByUserIdAndEventId(anyLong(), anyLong())).thenReturn(false);
        when(enrollmentRepository.countByEventIdAndStatus(anyLong(), any())).thenReturn(0L);
        when(enrollmentRepository.save(any(Enrollment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(eventRepository.findById(HOUSE_RELAY))
                .thenReturn(Optional.of(relay(HOUSE_RELAY, "Boys 4x100M Relay · A Grade")));
        when(eventRepository.findById(CLASS_RELAY))
                .thenReturn(Optional.of(relay(CLASS_RELAY, "Boys 4x100M Relay · Form 5")));
    }

    /** A relay event of the A grade house relay's division, category and all. */
    private static Event relay(long id, String name) {
        return Event.builder()
                .id(id)
                .name(name)
                .type(Event.EventType.RELAY_4X100M)
                .category(Event.EventType.RELAY_4X100M.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.A)
                .eventDate(LocalDate.of(2026, 11, 6))
                .maxParticipants(512)
                .groupSize(24)
                .enabled(true)
                .directToFinal(true)
                .build();
    }

    private void alreadyHolding(int relays) {
        when(enrollmentRepository.countByUserAndCategory(
                eq(STUDENT_USER), eq(Enrollment.EnrollmentStatus.CONFIRMED), eq(EventCategory.RELAY)))
                .thenReturn((long) relays);
    }

    @Test
    @DisplayName("the allowance a relay is measured against is two, not the field's single entry")
    void theRelayAllowanceIsTwo() {
        assertEquals(2, settingsService.maxEntriesFor(EventCategory.RELAY),
                "a house relay and a class relay are two entries");
        assertNotEquals(1, settingsService.maxEntriesFor(EventCategory.RELAY));
        assertEquals(2, settingsService.maxEntriesFor(EventCategory.TRACK));
        assertEquals(1, settingsService.maxEntriesFor(EventCategory.FIELD));
    }

    @Test
    @DisplayName("a student holding a house relay may still be entered in a class relay")
    void aHouseRelayDoesNotBlockAClassRelay() {
        // One team already: a leg in the A grade house relay.
        alreadyHolding(1);

        EnrollmentDTO entered = service.enrollUserToEvent(STUDENT_USER, CLASS_RELAY);

        assertEquals("Boys 4x100M Relay · Form 5", entered.getEventName());
        assertEquals(EventCategory.RELAY.name(), entered.getCategory(),
                "the entry carries the relay's own category");
        verify(enrollmentRepository).save(any(Enrollment.class));
    }

    @Test
    @DisplayName("a third relay is refused, and the refusal names the relay allowance")
    void aThirdRelayIsRefused() {
        alreadyHolding(2);

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> service.enrollUserToEvent(STUDENT_USER, CLASS_RELAY));

        assertTrue(refusal.getMessage().contains("already entered 2 接力 Relay event(s)"),
                "the refusal names the relay and the count: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("(接力: 2)"),
                "and the allowance it is measured against: " + refusal.getMessage());
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }

    @Test
    @DisplayName("a relay is counted as RELAY, never against the student's two track entries")
    void aRelayIsCountedAsItsOwnCategory() {
        // Two individual track entries already held, and no relay yet. The relay must
        // still be enterable: it is a different family, with an allowance of its own.
        alreadyHolding(0);

        service.enrollUserToEvent(STUDENT_USER, HOUSE_RELAY);

        verify(enrollmentRepository).countByUserAndCategory(
                STUDENT_USER, Enrollment.EnrollmentStatus.CONFIRMED, EventCategory.RELAY);
        verify(enrollmentRepository, never()).countByUserAndCategory(
                anyLong(), any(), eq(EventCategory.TRACK));
    }

    @Test
    @DisplayName("the field allowance is untouched: a relay is not measured as a field event")
    void theFieldAllowanceIsNotUsedForARelay() {
        // The field allowance is 1, so if a relay were measured against it the second
        // relay would be refused. It is not: one relay in hand, a second is accepted.
        alreadyHolding(1);

        assertDoesNotThrow(() -> service.enrollUserToEvent(STUDENT_USER, CLASS_RELAY));
        verify(enrollmentRepository, never()).countByUserAndCategory(
                anyLong(), any(), eq(EventCategory.FIELD));
    }
}
