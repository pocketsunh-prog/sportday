package com.sportday.dto;

import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
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

    /** Enum name, e.g. {@code RUN_100M}. */
    private String type;

    /** Printable event name, e.g. {@code 100M}. */
    private String typeLabel;

    /** {@code TRACK} (徑項) or {@code FIELD} (田項). */
    private String category;
    private String categoryLabel;

    /** {@code MALE} or {@code FEMALE} — the division this event is run in. */
    private String sex;
    private String sexLabel;

    private LocalDate eventDate;
    private String location;
    private Integer maxParticipants;

    /** Athletes per heat: 8 for 60/100/200/400, 24 otherwise. */
    private Integer groupSize;

    /** True when this event is laid out 8 to a group on an A5 marking sheet. */
    private Boolean shortSprint;

    /** {@code A5} or {@code A4} — the marking sheet paper size for this event. */
    private String sheetSize;

    private Boolean enabled;
    private LocalDateTime createdAt;
    private Integer enrolledCount;

    /** Number of heats currently generated for this event. */
    private Long groupCount;

    /** Confirmed entries not yet placed in a heat. */
    private Long ungroupedCount;

    /**
     * How many events of this category a student may enter. Filled in by
     * {@code EventService} from the editable settings rather than from a constant,
     * so the entry page reflects whatever an administrator has configured.
     */
    private Integer maxEntriesPerStudent;

    /** The school year this event belongs to. */
    private Long seasonId;
    private Integer seasonYear;
    private String seasonName;

    public static EventDTO from(Event event) {
        Event.EventType type = event.getType();
        EventCategory category = event.getCategoryOrDefault();
        return EventDTO.builder()
                .id(event.getId())
                .name(event.getName())
                .description(event.getDescription())
                .type(type == null ? null : type.name())
                .typeLabel(type == null ? null : type.getDisplayName())
                .category(category.name())
                .categoryLabel(category.getLabel())
                .sex(event.getSex() == null ? null : event.getSex().name())
                .sexLabel(event.getSex() == null ? null : event.getSex().getLabel())
                .eventDate(event.getEventDate())
                .location(event.getLocation())
                .maxParticipants(event.getMaxParticipants())
                .groupSize(event.getGroupSize())
                .shortSprint(event.isShortSprint())
                .sheetSize(event.isShortSprint() ? "A5" : "A4")
                .enabled(event.getEnabled())
                .createdAt(event.getCreatedAt())
                .seasonId(event.getSeason() == null ? null : event.getSeason().getId())
                .seasonYear(event.getSeason() == null ? null : event.getSeason().getYear())
                .seasonName(event.getSeason() == null ? null : event.getSeason().getDisplayName())
                .build();
    }

    public static EventDTO from(Event event, int enrolledCount) {
        EventDTO dto = from(event);
        dto.setEnrolledCount(enrolledCount);
        return dto;
    }
}
