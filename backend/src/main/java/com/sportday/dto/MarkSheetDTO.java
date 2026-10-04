package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the mark-entry grid needs for one event: the athletes entered, the
 * heat each was drawn into, the marks recorded so far, and the filter options.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarkSheetDTO {

    private Long eventId;
    private String eventName;
    private String eventType;
    private String eventTypeLabel;
    private String category;
    private String categoryLabel;
    private String sex;
    private String sexLabel;
    private LocalDate eventDate;
    private String location;
    private Integer groupSize;
    private String sheetSize;

    /**
     * How many marks each athlete gets in this event: three attempts for a field
     * event, one performance for a track event. What the grid renders.
     */
    private Integer attemptCount;

    /** True for a field event, so the grid knows to take the best attempt. */
    private Boolean fieldEvent;

    /**
     * True for a race longer than 400M, where a time is typed as minutes and seconds
     * rather than as a bare count of seconds — a helper writes 2:15, not 135.
     */
    private Boolean timeInMinutes;

    /** {@code HEAT} or {@code FINAL} — which stage this grid is for. */
    private String stage;

    /** Localised stage label, e.g. {@code Heat 初賽}. */
    private String stageLabel;

    /** True when the event has a final drawn, so the UI can offer the final grid. */
    private boolean finalDrawn;

    /** How many athletes the final takes (the event's group size). */
    private Integer finalSize;

    /** Pre-fill for the unit column: {@code seconds} for track, {@code metres} for field. */
    private String defaultUnit;

    /** Every heat of the event, so the grid can be filtered by group. */
    @Builder.Default
    private List<GroupOption> groups = new ArrayList<>();

    /** Grade bands actually present among the entries, e.g. {@code [A, B, C]}. */
    @Builder.Default
    private List<String> grades = new ArrayList<>();

    /** Entries matching the current filter. */
    private int totalAthletes;

    /** How many of those already have a mark. */
    private int markedCount;

    @Builder.Default
    private List<MarkRowDTO> rows = new ArrayList<>();

    /** A heat, as an option in the grid's group filter. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class GroupOption {
        private Long id;
        private Integer groupNumber;
        private String label;
        private Integer athleteCount;
    }
}
