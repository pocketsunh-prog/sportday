package com.sportday.dto;

import com.sportday.entity.EventGroup;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * One heat/group plus its roster, ready to render as a marking sheet.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventGroupDTO {

    private Long id;
    private Long eventId;
    private String eventName;
    private String eventTypeLabel;

    /** The event type code, e.g. {@code RUN_800M} — what decides the time format. */
    private String eventType;
    private String category;
    private String categoryLabel;
    private String sex;
    private String sexLabel;

    /** The one grade the event is run by — what the school record is keyed on. */
    private String grade;
    private String gradeLabel;

    private Integer groupNumber;
    private String label;

    /** {@code HEAT} or {@code FINAL}. */
    private String stage;

    /** Localised label for {@link #stage}, e.g. {@code Heat 初賽}. */
    private String stageLabel;

    private Integer capacity;
    private Integer athleteCount;

    /** {@code A5} for 60/100/200/400, otherwise {@code A4}. */
    private String sheetSize;

    // ---------------------------------------------- the school record to beat

    /**
     * The event's own record, read the way every mark reads — {@code 7.406s},
     * {@code 1.04.123s}, {@code 18.12M} — or null when there is no record yet.
     *
     * <p>It belongs to the event, not to the sheet: every group of an event carries
     * the same one, and the marking sheet prints it once in its header. It is
     * resolved while the group is built rather than by the renderer, so a
     * whole-programme print run costs one record lookup per event.</p>
     */
    private String recordDisplayMark;

    /**
     * The event's <strong>required standard</strong> with its unit - {@code 64.123 s}
     * - or null for an event that carries none. Null prints no line at all, which is
     * what keeps a sheet for an event without a standard exactly as it was.
     */
    private String standardLabel;

    /** Who holds the record, when that is known. Null for a record with no name. */
    private String recordHolderName;

    /** When the record was set, when that is known. */
    private java.time.LocalDate recordAchievedOn;

    @Builder.Default
    private List<EnrollmentDTO> athletes = new ArrayList<>();

    public static EventGroupDTO from(EventGroup group) {
        var event = group.getEvent();
        var stage = group.getStageOrDefault();
        return EventGroupDTO.builder()
                .id(group.getId())
                .eventId(event == null ? null : event.getId())
                .eventName(event == null ? null : event.getName())
                .eventType(event == null || event.getType() == null ? null : event.getType().name())
                .eventTypeLabel(event == null || event.getType() == null ? null : event.getType().getDisplayName())
                .category(event == null ? null : event.getCategoryOrDefault().name())
                .categoryLabel(event == null ? null : event.getCategoryOrDefault().getLabel())
                .sex(event == null || event.getSex() == null ? null : event.getSex().name())
                .sexLabel(event == null || event.getSex() == null ? null : event.getSex().getLabel())
                .grade(event == null || event.getGrade() == null ? null : event.getGrade().name())
                .gradeLabel(event == null || event.getGrade() == null ? null : event.getGrade().getLabel())
                .groupNumber(group.getGroupNumber())
                .label(group.getLabel())
                .stage(stage.name())
                .stageLabel(stage.getLabelEn() + " " + stage.getLabelZh())
                .capacity(group.getCapacity())
                .athleteCount(group.getAthleteCount())
                .sheetSize(event != null && event.isShortSprint() ? "A5" : "A4")
                .build();
    }
}
