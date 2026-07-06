package com.sportday.service;

import com.sportday.dto.EventDTO;
import com.sportday.entity.Event;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final EnrollmentRepository enrollmentRepository;

    public List<EventDTO> getAllEvents() {
        return eventRepository.findAll().stream()
                .map(event -> {
                    long count = enrollmentRepository.countByEventIdAndStatus(
                            event.getId(), com.sportday.entity.Enrollment.EnrollmentStatus.CONFIRMED);
                    return EventDTO.from(event, (int) count);
                })
                .collect(Collectors.toList());
    }

    public List<EventDTO> getEnabledEvents() {
        return eventRepository.findByEnabledTrue().stream()
                .map(event -> {
                    long count = enrollmentRepository.countByEventIdAndStatus(
                            event.getId(), com.sportday.entity.Enrollment.EnrollmentStatus.CONFIRMED);
                    return EventDTO.from(event, (int) count);
                })
                .collect(Collectors.toList());
    }

    public EventDTO getEventById(Long id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + id));
        long count = enrollmentRepository.countByEventIdAndStatus(
                event.getId(), com.sportday.entity.Enrollment.EnrollmentStatus.CONFIRMED);
        return EventDTO.from(event, (int) count);
    }

    @Transactional
    public EventDTO createEvent(EventDTO eventDTO) {
        Event event = Event.builder()
                .name(eventDTO.getName())
                .description(eventDTO.getDescription())
                .type(Event.EventType.valueOf(eventDTO.getType()))
                .eventDate(eventDTO.getEventDate())
                .location(eventDTO.getLocation())
                .maxParticipants(eventDTO.getMaxParticipants())
                .enabled(true)
                .build();
        return EventDTO.from(eventRepository.save(event));
    }

    @Transactional
    public EventDTO updateEvent(Long id, EventDTO eventDTO) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + id));

        if (eventDTO.getName() != null) event.setName(eventDTO.getName());
        if (eventDTO.getDescription() != null) event.setDescription(eventDTO.getDescription());
        if (eventDTO.getType() != null) event.setType(Event.EventType.valueOf(eventDTO.getType()));
        if (eventDTO.getEventDate() != null) event.setEventDate(eventDTO.getEventDate());
        if (eventDTO.getLocation() != null) event.setLocation(eventDTO.getLocation());
        if (eventDTO.getMaxParticipants() != null) event.setMaxParticipants(eventDTO.getMaxParticipants());

        return EventDTO.from(eventRepository.save(event));
    }

    @Transactional
    public EventDTO setEventEnabled(Long id, boolean enabled) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + id));
        event.setEnabled(enabled);
        return EventDTO.from(eventRepository.save(event));
    }

    public void deleteEvent(Long id) {
        if (!eventRepository.existsById(id)) {
            throw new ResourceNotFoundException("Event not found with id: " + id);
        }
        eventRepository.deleteById(id);
    }
}
