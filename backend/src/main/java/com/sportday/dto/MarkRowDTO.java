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

    /** The form the class belongs to — {@code 5} for {@code 5A}; null when it names none. */
    private String form;

    /** The house, in full, as the register stores it — {@code Red}. */
    private String house;

    /** The house's short code — {@code R}, {@code Y}, {@code B}, {@code G}; null for another house. */
    private String houseCode;

    // ---- where they are running / throwing ----
    private Long groupId;
    private Integer groupNumber;
    private String groupLabel;
    private Integer lane;

    /**
     * The event's <strong>required standard</strong>, in the event's own unit, or
     * null for an event that carries none — every sprint under 400M, and every
     * relay. Which events carry one is {@link Event.EventType#carriesAStandard()},
     * asked by the service that builds the row.
     */
    private java.math.BigDecimal standard;

    /** e.g. {@code 64.123 s}. The standard with its unit, for the row to show. */
    private String standardLabel;

    /**
     * True when this row's result is <strong>below the standard</strong>, worked
     * out from the mark and the standard together.
     *
     * <p>Automatic on purpose: a teacher types nothing, so it cannot be forgotten
     * or set wrongly. A row with no mark, and an event with no standard, are never
     * below — a blank is not a failure.</p>
     */
    private Boolean belowStandard;

    /*
     * The relay team this row is, on a relay grid. Both are null on an individual
     * event's grid — and on a relay whose teams have not been derived, which keeps the
     * athlete-per-row grid. When teamId is present the row is the TEAM: one time for
     * the four runners together, not one mark each.
     */
    private Long teamId;
    /** The team's name — {@code 1A}, {@code C Grade Yellow} — what the school writes. */
    private String teamLabel;

    /**
     * <strong>Deliberately not populated.</strong> The school's requirement is that a
     * relay's line is read by the <em>team's</em> name and not by the students': one
     * line, one record box, the team's one time. So the grid does not carry who is
     * running for a team — the field is left null on every row, relay and individual
     * alike, and is kept here only so the row's shape does not change. Who is on a team
     * is listed, leg by leg, on the relay board
     * ({@code RelayTeamMemberDTO}), and in the applicant list — the two places that
     * legitimately need to know it.
     */
    private java.util.List<String> teamMembers;

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
     * The mark the way the school writes a long race: {@code 1.04.123},
     * {@code 0.48.123}. {@link #mark} still carries the total in seconds, which is
     * what everything downstream uses.
     *
     * <p><strong>This is what the grid's one box shows</strong>, and it is
     * {@link com.sportday.service.StopwatchTime#format(java.math.BigDecimal)}, so
     * what is on screen parses back to exactly this mark — see
     * {@link com.sportday.service.StopwatchTime#parse(String)}. Null on an event
     * that is not timed this way, and on a row with no mark at all.</p>
     */
    private String time;

    /**
     * The whole minutes of {@link #mark}, and the seconds left over. Kept beside
     * {@link #time} for callers that read the parts rather than the text — the
     * shape the box used to be drawn in, and still the way a stopped time splits.
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
