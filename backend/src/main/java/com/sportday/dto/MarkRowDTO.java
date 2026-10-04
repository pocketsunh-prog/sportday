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
     * What is recorded for this athlete: {@code RESULT} when a mark was produced,
     * {@code ABS} or {@code DQ} when they were absent or disqualified, and null
     * when nothing has been recorded yet. An ABS/DQ row has no {@link #mark}, so
     * this is what the grid shows in place of one.
     */
    private String outcome;

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

    // ---- what they did in the heat, on a final's grid ----

    /**
     * The athlete's <strong>heat</strong> performance, in the event's own unit,
     * so a final grid can show what they ran to get there. Null when the heat
     * produced no number — {@link #heatOutcome} says what happened instead — and
     * null with {@link #heatOutcome} on a heat sheet, which has no earlier stage
     * to show.
     */
    private BigDecimal heatMark;

    /**
     * What the athlete's heat came to: {@code RESULT}, {@code ABS} or {@code DQ}
     * — the same vocabulary as {@link #outcome}, so a client needs no second one.
     * Null when the athlete has no heat record at all.
     */
    private String heatOutcome;

    /**
     * The heat performance as it reads — {@code 11.86s}, {@code 1.04.123s},
     * {@code 18.12M} — or {@code ABS}/{@code DQ} when the heat produced no mark.
     * Never null while there is a heat record, so a final grid can print it
     * without consulting {@link #heatMark} and {@link #heatOutcome} first; both
     * are null together when there is no heat record.
     */
    private String heatDisplayMark;
}
