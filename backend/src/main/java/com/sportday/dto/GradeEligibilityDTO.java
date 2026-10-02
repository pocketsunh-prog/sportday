package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which grades may enter which events — the event-by-grade grid an administrator
 * assigns, with a count of how many events each grade ends up with.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GradeEligibilityDTO {

    /** The grades, in order A, B, C — the grid's columns. */
    @Builder.Default
    private List<String> grades = new ArrayList<>();

    /** One row per event type — the grid's rows. */
    @Builder.Default
    private List<EventRow> events = new ArrayList<>();

    /** How many events of the current programme each grade may enter, e.g. {@code {A=38, B=36, C=34}}. */
    @Builder.Default
    private Map<String, Integer> allowedEventCounts = new LinkedHashMap<>();

    /** How many events the programme holds altogether. */
    private Integer totalEvents;

    /** One event type and which grades may enter it. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EventRow {
        private String eventType;
        private String eventTypeLabel;
        private String category;
        private String categoryLabel;

        /** Grade name to whether that grade may enter this event. */
        @Builder.Default
        private Map<String, Boolean> allowed = new LinkedHashMap<>();
    }

    /** One cell change: this grade may, or may no longer, enter this event type. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RuleUpdate {
        private String eventType;
        private String grade;
        private Boolean allowed;
    }
}
