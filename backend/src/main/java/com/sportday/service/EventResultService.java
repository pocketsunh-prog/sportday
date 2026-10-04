package com.sportday.service;

import com.sportday.dto.EventResultDTO;
import com.sportday.entity.EventResult;
import com.sportday.entity.User;
import com.sportday.entity.Event;
import com.sportday.entity.EventStage;
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
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventResultService {

    private final EventResultRepository resultRepository;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final RecordService recordService;

    public List<EventResultDTO> getResultsByEvent(Long eventId) {
        Set<Long> recordHolders = recordService.recordResultIds();
        return resultRepository.findByEventIdOrderByMarkAsc(eventId).stream()
                .map(result -> {
                    EventResultDTO dto = EventResultDTO.from(result);
                    dto.setNewRecord(recordHolders.contains(result.getId()));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    public List<EventResultDTO> getResultsByUser(Long userId) {
        Set<Long> recordHolders = recordService.recordResultIds();
        return resultRepository.findByUserId(userId).stream()
                .map(result -> {
                    EventResultDTO dto = EventResultDTO.from(result);
                    dto.setNewRecord(recordHolders.contains(result.getId()));
                    return dto;
                })
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
        // The unit follows from the event, so "seconds" on a field event still ends
        // up stored as the metres that event is measured in.
        unit = event.getType() == null ? unit : event.getType().normaliseUnit(unit);

        EventResult result = resultRepository
                .findByUserIdAndEventIdAndStage(userId, eventId, EventStage.HEAT)
                .orElse(null);

        if (result != null) {
            result.setMark(mark);
            result.setUnit(unit);
            result.setNotes(notes);
        } else {
            result = EventResult.builder()
                    .user(user)
                    .event(event)
                    // The single-result endpoint is the heat/straight-final path; the
                    // final stage is filled in from the mark-entry grid.
                    .stage(EventStage.HEAT)
                    .mark(mark)
                    .unit(unit)
                    .notes(notes)
                    .build();
        }

        EventResult saved = resultRepository.save(result);
        resultRepository.flush();
        // A record-breaking mark takes the record straight away.
        recordService.considerResult(saved);
        EventResultDTO dto = EventResultDTO.from(saved);
        dto.setNewRecord(recordService.recordResultIds().contains(saved.getId()));
        return dto;
    }

    @Transactional
    public void deleteResult(Long id) {
        EventResult result = resultRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Result not found with id: " + id));
        // Let a school record go of this mark before deleting it, or the record's
        // foreign key blocks the delete.
        recordService.detachForResult(id);
        resultRepository.delete(result);
        resultRepository.flush();
        // The record may now belong to somebody else, or to nobody.
        recordService.considerResult(result);
    }
}
