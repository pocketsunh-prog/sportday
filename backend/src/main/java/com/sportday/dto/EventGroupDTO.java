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
    private String category;
    private String categoryLabel;
    private String sex;
    private String sexLabel;
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

    @Builder.Default
    private List<EnrollmentDTO> athletes = new ArrayList<>();

    public static EventGroupDTO from(EventGroup group) {
        var event = group.getEvent();
        var stage = group.getStageOrDefault();
        return EventGroupDTO.builder()
                .id(group.getId())
                .eventId(event == null ? null : event.getId())
                .eventName(event == null ? null : event.getName())
                .eventTypeLabel(event == null || event.getType() == null ? null : event.getType().getDisplayName())
                .category(event == null ? null : event.getCategoryOrDefault().name())
                .categoryLabel(event == null ? null : event.getCategoryOrDefault().getLabel())
                .sex(event == null || event.getSex() == null ? null : event.getSex().name())
                .sexLabel(event == null || event.getSex() == null ? null : event.getSex().getLabel())
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
