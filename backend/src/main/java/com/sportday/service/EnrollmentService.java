package com.sportday.service;

import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.User;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EnrollmentService {

    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;

    @Transactional
    public Enrollment enrollUserToEvent(Long userId, Long eventId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found"));

        if (!event.getEnabled()) {
            throw new IllegalStateException("Event is not enabled");
        }

        if (enrollmentRepository.existsByUserIdAndEventId(userId, eventId)) {
            throw new IllegalStateException("User already enrolled in this event");
        }

        long currentEnrollments = enrollmentRepository.countByEventIdAndStatus(
                eventId, Enrollment.EnrollmentStatus.CONFIRMED);
        if (currentEnrollments >= event.getMaxParticipants()) {
            throw new IllegalStateException("Event is full");
        }

        Enrollment enrollment = Enrollment.builder()
                .user(user)
                .event(event)
                .status(Enrollment.EnrollmentStatus.CONFIRMED)
                .build();

        return enrollmentRepository.save(enrollment);
    }

    @Transactional
    public void cancelEnrollment(Long userId, Long eventId) {
        Enrollment enrollment = enrollmentRepository.findByUserIdAndEventId(userId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Enrollment not found"));
        enrollment.setStatus(Enrollment.EnrollmentStatus.CANCELLED);
        enrollmentRepository.save(enrollment);
    }

    public List<Enrollment> getUserEnrollments(Long userId) {
        return enrollmentRepository.findByUserId(userId);
    }

    public List<Enrollment> getEventEnrollments(Long eventId) {
        return enrollmentRepository.findByEventId(eventId);
    }

    public boolean isUserEnrolled(Long userId, Long eventId) {
        return enrollmentRepository.existsByUserIdAndEventId(userId, eventId);
    }
}
