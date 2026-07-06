package com.sportday.service;

import com.sportday.dto.EventResultDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.EventResult;
import com.sportday.entity.User;
import com.sportday.entity.Event;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventResultService {

    private final EventResultRepository resultRepository;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final EnrollmentRepository enrollmentRepository;

    public List<EventResultDTO> getResultsByEvent(Long eventId) {
        return resultRepository.findByEventIdOrderByMarkAsc(eventId).stream()
                .map(EventResultDTO::from)
                .collect(Collectors.toList());
    }

    public List<EventResultDTO> getResultsByUser(Long userId) {
        return resultRepository.findByUserId(userId).stream()
                .map(EventResultDTO::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public EventResultDTO recordResult(Long userId, Long eventId, BigDecimal mark, String unit, String notes) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found"));

        if (!enrollmentRepository.existsByUserIdAndEventId(userId, eventId)) {
            throw new IllegalStateException("User is not enrolled in this event");
        }

        EventResult result = resultRepository.findByUserIdAndEventId(userId, eventId)
                .orElse(null);

        if (result != null) {
            result.setMark(mark);
            result.setUnit(unit);
            result.setNotes(notes);
        } else {
            result = EventResult.builder()
                    .user(user)
                    .event(event)
                    .mark(mark)
                    .unit(unit)
                    .notes(notes)
                    .build();
        }

        return EventResultDTO.from(resultRepository.save(result));
    }

    public void deleteResult(Long id) {
        if (!resultRepository.existsById(id)) {
            throw new ResourceNotFoundException("Result not found with id: " + id);
        }
        resultRepository.deleteById(id);
    }
}
