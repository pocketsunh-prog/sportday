package com.sportday.service;

import com.sportday.dto.EnrollmentDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Season;
import com.sportday.entity.Student;
import com.sportday.entity.User;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.StudentRepository;
import com.sportday.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry rules for the sport day.
 *
 * <p>A student may hold at most <strong>two</strong> track entries (徑項) and
 * <strong>one</strong> field entry (田項). Entries are additionally rejected when
 * the event is disabled, when the event is run in the other sex division, when
 * the student is already entered, or when the event is full.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrollmentService {

    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final StudentRepository studentRepository;
    private final SettingsService settingsService;
    private final SeasonService seasonService;
    private final FinalQualificationService finalQualificationService;

    /** How many entries a student still has available, per category. */
    public record Quota(
            int trackUsed, int trackMax, int trackRemaining,
            int fieldUsed, int fieldMax, int fieldRemaining) {
    }

    @Transactional
    public EnrollmentDTO enrollUserToEvent(Long userId, Long eventId) {
        requireEnrollmentOpen();
        return enroll(userId, eventId);
    }

    /**
     * Enters a student. An administrator doing this on a student's behalf is not
     * stopped by a closed season — the school may be adding a late entry by hand.
     */
    private EnrollmentDTO enroll(Long userId, Long eventId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found"));

        requireActiveStudent(userId);
        if (!Boolean.TRUE.equals(event.getEnabled())) {
            throw new IllegalStateException("This event is closed — it has been disabled by the organiser.");
        }
        if (enrollmentRepository.existsByUserIdAndEventId(userId, eventId)) {
            throw new IllegalStateException("You are already entered in " + event.getName() + ".");
        }

        Student roster = studentRepository.findWithUserByUserId(userId).orElse(null);

        requireEventIsForTheStudent(roster, event);

        // Quota: at most 2 track (徑項) and 1 field (田項) per student.
        EventCategory category = event.getCategoryOrDefault();
        int max = settingsService.maxEntriesFor(category);
        long used = enrollmentRepository.countByUserAndCategory(
                userId, Enrollment.EnrollmentStatus.CONFIRMED, category);
        if (used >= max) {
            throw new IllegalStateException(String.format(
                    "You have already entered %d %s event(s), which is the maximum (%s: %d).",
                    used, category.getLabel(), category.getLabelZh(), max));
        }

        long confirmed = enrollmentRepository.countByEventIdAndStatus(
                eventId, Enrollment.EnrollmentStatus.CONFIRMED);
        if (event.getMaxParticipants() != null && confirmed >= event.getMaxParticipants()) {
            throw new IllegalStateException("This event is full (" + event.getMaxParticipants() + " entries).");
        }

        Enrollment enrollment = Enrollment.builder()
                .user(user)
                .event(event)
                .status(Enrollment.EnrollmentStatus.CONFIRMED)
                .build();
        Enrollment saved = enrollmentRepository.save(enrollment);
        // A sprint that has only a group's worth of entries runs straight to a final.
        finalQualificationService.syncFinalFormat(event);
        log.info("User {} entered event {} ({}, {})", userId, eventId, category, event.getSex());
        return EnrollmentDTO.from(saved, roster);
    }

    /**
     * Cancels an entry. A cancelled entry frees its quota slot but keeps the row
     * for the audit trail; re-entering the same event revives it.
     */
    @Transactional
    public void cancelEnrollment(Long userId, Long eventId) {
        Enrollment enrollment = enrollmentRepository.findByUserIdAndEventId(userId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Enrollment not found"));
        enrollment.setStatus(Enrollment.EnrollmentStatus.CANCELLED);
        enrollment.setEventGroup(null);
        enrollment.setLane(null);
        enrollmentRepository.save(enrollment);
        // Fewer entries may mean the event no longer needs heats and a final.
        finalQualificationService.syncFinalFormat(eventId);
    }

    /**
     * Enters a student on an administrator's behalf, reviving an entry they
     * withdrew from if there is one.
     *
     * <p>An administrator cannot use the student's own "enter" endpoint: that
     * refuses any second entry outright, which would leave a student who withdrew
     * unable to be entered again. Pressing the button for somebody already entered
     * is a no-op rather than an error, so a double click is harmless.</p>
     */
    @Transactional
    public EnrollmentDTO enrollOnBehalf(Long userId, Long eventId) {
        return enrollmentRepository.findByUserIdAndEventId(userId, eventId)
                .map(existing -> existing.getStatus() == Enrollment.EnrollmentStatus.CONFIRMED
                        // Already in, so just hand back what is there.
                        ? EnrollmentDTO.from(existing,
                                studentRepository.findWithUserByUserId(userId).orElse(null))
                        // Withdrawn before: put them back in, re-checking every rule.
                        : revive(userId, eventId))
                .orElseGet(() -> enroll(userId, eventId));
    }

    /** Re-activates a previously cancelled entry, re-checking every rule. */
    @Transactional
    public EnrollmentDTO reEnroll(Long userId, Long eventId) {
        requireEnrollmentOpen();
        return revive(userId, eventId);
    }

    private EnrollmentDTO revive(Long userId, Long eventId) {
        requireActiveStudent(userId);
        Enrollment enrollment = enrollmentRepository.findByUserIdAndEventId(userId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Enrollment not found"));
        if (enrollment.getStatus() == Enrollment.EnrollmentStatus.CONFIRMED) {
            throw new IllegalStateException("You are already entered in this event.");
        }
        Event event = enrollment.getEvent();
        if (!Boolean.TRUE.equals(event.getEnabled())) {
            throw new IllegalStateException("This event is closed — it has been disabled by the organiser.");
        }
        Student roster = studentRepository.findWithUserByUserId(userId).orElse(null);
        requireEventIsForTheStudent(roster, event);
        EventCategory category = event.getCategoryOrDefault();
        long used = enrollmentRepository.countByUserAndCategory(
                userId, Enrollment.EnrollmentStatus.CONFIRMED, category);
        if (used >= settingsService.maxEntriesFor(category)) {
            throw new IllegalStateException(String.format(
                    "You have already entered %d %s event(s), which is the maximum.",
                    used, category.getLabel()));
        }
        enrollment.setStatus(Enrollment.EnrollmentStatus.CONFIRMED);
        Enrollment saved = enrollmentRepository.save(enrollment);
        // Reviving an entry may take the event back over a group's worth.
        finalQualificationService.syncFinalFormat(event);
        return EnrollmentDTO.from(saved, roster);
    }

    @Transactional(readOnly = true)
    public List<EnrollmentDTO> getUserEnrollments(Long userId) {
        List<Enrollment> enrollments = enrollmentRepository.findMineWithEvent(
                userId, Enrollment.EnrollmentStatus.CONFIRMED);
        return toDto(enrollments);
    }

    @Transactional(readOnly = true)
    public List<EnrollmentDTO> getAllUserEnrollments(Long userId) {
        return toDto(enrollmentRepository.findByUserId(userId));
    }

    @Transactional(readOnly = true)
    public List<EnrollmentDTO> getEventEnrollments(Long eventId) {
        return toDto(enrollmentRepository.findByEventId(eventId));
    }

    @Transactional(readOnly = true)
    public boolean isUserEnrolled(Long userId, Long eventId) {
        return enrollmentRepository.findByUserIdAndEventId(userId, eventId)
                .map(e -> e.getStatus() == Enrollment.EnrollmentStatus.CONFIRMED)
                .orElse(false);
    }

    /**
     * The division and grade checks, in one place so that a student entering
     * themselves and an administrator entering them by hand are held to exactly the
     * same rules — the rule cannot be worked around by doing it for them.
     *
     * <p>An event belongs to <strong>exactly one grade</strong>, so a student may
     * only enter an event of their own grade: the A, B and C grades are never
     * ranked together. Which races a grade runs is now simply which events exist,
     * so there is no separate eligibility table to consult.</p>
     */
    private static void requireEventIsForTheStudent(Student roster, Event event) {
        if (roster == null) {
            return;
        }
        // Sex division: a student may only enter their own division's event.
        if (roster.getSex() != null && event.getSex() != null && roster.getSex() != event.getSex()) {
            throw new IllegalStateException(
                    "This is the " + event.getSex().getLabel() + " event and you are entered as "
                            + roster.getSex().getLabel() + ".");
        }
        // Grade: the event is run by one grade, and it must be the student's own.
        if (roster.getGrade() != null && event.getGrade() != null
                && roster.getGrade() != event.getGrade()) {
            throw new IllegalStateException(String.format(
                    "This is the %s %s and you are in the %s grade.",
                    event.getGrade().getLabel(),
                    event.getType() == null ? event.getName() : event.getType().getDisplayName(),
                    roster.getGrade().name()));
        }
    }

    /** Remaining entry allowance for a student, for the entry page. */
    /**
     * Refuses entries while the school has closed them for this year's sport day.
     * The switch lives on the year, so entries can be reopened without touching
     * every event.
     */
    private void requireEnrollmentOpen() {
        Season season = seasonService.currentSeason();
        if (season != null && !Boolean.TRUE.equals(season.getEnrollmentOpen())) {
            throw new IllegalStateException("Entries for the " + season.getYear()
                    + " sport day are closed. The organiser reopens them from the sport day "
                    + "settings when they are ready.");
        }
    }

    /**
     * Refuses entries for a student who is locked — someone the year's roster
     * upload did not include. Their history is kept, so this only stops new
     * entries; an administrator can unlock them on the register.
     */
    private void requireActiveStudent(Long userId) {
        Student roster = studentRepository.findWithUserByUserId(userId).orElse(null);
        if (roster != null && !Boolean.TRUE.equals(roster.getEnabled())) {
            throw new IllegalStateException("This student is locked because they are not on this "
                    + "year's student list, so they cannot be entered in an event. Unlock them on "
                    + "the student register first.");
        }
    }

    @Transactional(readOnly = true)
    public Quota getQuota(Long userId) {
        int trackUsed = (int) enrollmentRepository.countByUserAndCategory(
                userId, Enrollment.EnrollmentStatus.CONFIRMED, EventCategory.TRACK);
        int fieldUsed = (int) enrollmentRepository.countByUserAndCategory(
                userId, Enrollment.EnrollmentStatus.CONFIRMED, EventCategory.FIELD);
        int trackMax = settingsService.maxEntriesFor(EventCategory.TRACK);
        int fieldMax = settingsService.maxEntriesFor(EventCategory.FIELD);
        return new Quota(trackUsed, trackMax, Math.max(0, trackMax - trackUsed),
                fieldUsed, fieldMax, Math.max(0, fieldMax - fieldUsed));
    }

    private List<EnrollmentDTO> toDto(List<Enrollment> enrollments) {
        Map<Long, Student> rosters = new HashMap<>();
        for (Enrollment enrollment : enrollments) {
            if (enrollment.getUser() != null) {
                studentRepository.findWithUserByUserId(enrollment.getUser().getId())
                        .ifPresent(s -> rosters.put(enrollment.getUser().getId(), s));
            }
        }
        List<EnrollmentDTO> result = new ArrayList<>(enrollments.size());
        for (Enrollment enrollment : enrollments) {
            Student roster = enrollment.getUser() == null ? null : rosters.get(enrollment.getUser().getId());
            result.add(EnrollmentDTO.from(enrollment, roster));
        }
        return result;
    }
}
