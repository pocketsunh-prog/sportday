package com.sportday.dto;

import com.sportday.entity.Event;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventDTO {
    private Long id;
    private String name;
    private String description;
    private String type;
    private LocalDate eventDate;
    private String location;
    private Integer maxParticipants;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private Integer enrolledCount;

    public static EventDTO from(Event event) {
        return EventDTO.builder()
                .id(event.getId())
                .name(event.getName())
                .description(event.getDescription())
                .type(event.getType().name())
                .eventDate(event.getEventDate())
                .location(event.getLocation())
                .maxParticipants(event.getMaxParticipants())
                .enabled(event.getEnabled())
                .createdAt(event.getCreatedAt())
                .build();
    }

    public static EventDTO from(Event event, int enrolledCount) {
        EventDTO dto = from(event);
        dto.setEnrolledCount(enrolledCount);
        return dto;
    }
}
