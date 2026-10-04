package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One line of the mark-entry grid: an athlete entered in an event, the heat and
 * lane they were drawn into, and whatever mark has been recorded so far.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarkRowDTO {

    // ---- athlete ----
    private Long userId;
    private String studentRef;
    private String name;
    private String grade;
    private String className;
    private Integer classNumber;
    private String house;

    // ---- where they are running / throwing ----
    private Long groupId;
    private Integer groupNumber;
    private String groupLabel;
    private Integer lane;

    // ---- the mark, if one has been recorded ----
    private Long resultId;
    private BigDecimal mark;
    private String unit;
    private String notes;

    /**
     * A field athlete's attempts, in order, with a missed one left absent. A
     * track event has a single performance, so this stays null.
     */
    private java.util.List<BigDecimal> attempts;

    /** True when this performance is the current school record for its event. */
    private Boolean newRecord;

    /**
     * The mark the way a stopwatch reads it, for a race longer than 400M: the whole
     * minutes and the seconds left over. {@link #mark} still carries the total in
     * seconds, which is what everything downstream uses.
     */
    private Integer minutes;
    private BigDecimal seconds;
}
