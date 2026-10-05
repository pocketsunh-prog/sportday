package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A mark recorded for one athlete in one event, at one {@link EventStage}.
 *
 * <p>Heat and final marks are separate rows: the time an athlete ran in their
 * heat earned them a place in the final, and the final time is a second
 * performance rather than a correction of the first. The unique key is therefore
 * {@code (user_id, event_id, stage)}.</p>
 *
 * <p>{@code stage} is nullable only so that rows written before the heat/final
 * split can be adopted — the lifecycle hook below always writes
 * {@link EventStage#HEAT} for new rows, and readers go through
 * {@link #getStageOrDefault()}.</p>
 *
 * <p>{@code outcome} says whether the athlete produced a mark at all. A helper
 * recording a sheet can write <strong>ABS</strong> (absent) or <strong>DQ</strong>
 * (disqualified) instead of a number: the performance is not a performance, so
 * {@link #mark} and the attempts are left empty and the outcome is the whole
 * story. {@link Outcome#RESULT} means a mark was recorded, which is what an
 * athlete who simply has nothing recorded yet still reads as — hence the column is
 * nullable and readers go through {@link #getOutcomeOrDefault()}, exactly as they
 * do for {@link #stage}.</p>
 */
@Entity
@Table(name = "event_results", uniqueConstraints = {
    @UniqueConstraint(name = "uk_result_user_event_stage",
            columnNames = {"user_id", "event_id", "stage"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    /**
     * The relay team this mark belongs to, or null for an individual event.
     *
     * <p>A relay is scored by <strong>team</strong> — one time for a 4x100M, not four
     * — so a relay result is one row per team rather than one per athlete. This column
     * is what says so: {@code relayTeam != null} means the row is a team's time.</p>
     *
     * <p><strong>A deliberate compromise, not an accident.</strong> The row still
     * carries a {@link #user}, because {@code user_id} is not nullable and every
     * existing query — standings, records, the results PDF, the season backup — joins
     * on it. That user is the team's <em>first runner</em>, an anchor rather than the
     * owner of the time: asking "what did this athlete run?" will return the team's
     * time for whichever of the four happened to be listed first. Modelling a team
     * result properly would be its own table, at the cost of a second path through
     * every one of those queries. This way relay times count for school records and
     * appear in the results like any other mark, which is what the school asked for.
     * See {@code relay-teams-results-migration.sql}.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relay_team_id")
    private RelayTeam relayTeam;

    /** Heat or final. A null is read as {@link EventStage#HEAT}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 10)
    private EventStage stage;

    /**
     * Whether a mark was recorded, or the athlete was absent or disqualified. A
     * null is read as {@link Outcome#RESULT}, so a row written before the outcome
     * existed — and a row that simply has nothing recorded yet — is unchanged.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 10)
    private Outcome outcome;

    /**
     * The performance. Null when the athlete was absent or disqualified, because
     * there is no number to store — {@link #outcome} carries the whole result.
     */
    @Column(precision = 10, scale = 3)
    private BigDecimal mark;

    /**
     * A field athlete's attempts. A track event has one performance, so only
     * {@link #mark} is used; a field event gives three attempts and
     * {@link #mark} is the best of them, which is what the placings, the records
     * and the championships all read.
     */
    @Column(name = "attempt_1", precision = 10, scale = 3)
    private BigDecimal attempt1;

    @Column(name = "attempt_2", precision = 10, scale = 3)
    private BigDecimal attempt2;

    @Column(name = "attempt_3", precision = 10, scale = 3)
    private BigDecimal attempt3;

    private String unit;

    @Column(length = 500)
    private String notes;

    @Column(nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    @PrePersist
    protected void onCreate() {
        recordedAt = LocalDateTime.now();
        if (stage == null) stage = EventStage.HEAT;
    }

    @Transient
    public EventStage getStageOrDefault() {
        return stage == null ? EventStage.HEAT : stage;
    }

    /** A null is read as {@link Outcome#RESULT}, so older rows keep their meaning. */
    @Transient
    public Outcome getOutcomeOrDefault() {
        return outcome == null ? Outcome.RESULT : outcome;
    }

    /** True when the athlete was absent or disqualified: no mark, no placing. */
    @Transient
    public boolean isAbsentOrDisqualified() {
        return getOutcomeOrDefault() != Outcome.RESULT;
    }

    @Transient
    public boolean isFinal() {
        return getStageOrDefault() == EventStage.FINAL;
    }

    /** The three attempts in order, with a not-attempted one left null. */
    @Transient
    public java.util.List<BigDecimal> getAttempts() {
        return java.util.Arrays.asList(attempt1, attempt2, attempt3);
    }

    @Transient
    public void setAttempts(java.util.List<BigDecimal> attempts) {
        attempt1 = attempts != null && attempts.size() > 0 ? attempts.get(0) : null;
        attempt2 = attempts != null && attempts.size() > 1 ? attempts.get(1) : null;
        attempt3 = attempts != null && attempts.size() > 2 ? attempts.get(2) : null;
    }

    /** True when this athlete actually had attempts recorded. */
    @Transient
    public boolean hasAttempts() {
        return attempt1 != null || attempt2 != null || attempt3 != null;
    }

    /**
     * The attempt that counts. A field event is won by the longest or highest
     * attempt, so it is the best of the three; a track event has only one
     * performance and returns it unchanged.
     */
    @Transient
    public BigDecimal bestAttempt() {
        if (!hasAttempts()) {
            return mark;
        }
        boolean lowerBetter = event != null && event.getType() != null
                && event.getType().isLowerBetter();
        return getAttempts().stream()
                .filter(java.util.Objects::nonNull)
                .reduce(null, (best, candidate) -> best == null ? candidate
                        : (lowerBetter
                                ? (candidate.compareTo(best) < 0 ? candidate : best)
                                : (candidate.compareTo(best) > 0 ? candidate : best)));
    }

    /**
     * What the helper recorded instead of a number.
     *
     * <p>A sheet has one box per athlete, and sometimes the right answer is not a
     * mark: the athlete did not turn up, or was disqualified. Neither is a
     * performance, so neither is placed, scores a point, or can be a school
     * record — but both are recorded, and both are shown in place of the mark
     * rather than hidden.</p>
     */
    public enum Outcome {

        /** A mark was recorded; {@link EventResult#mark} carries it. */
        RESULT("Result"),

        /** Absent: the athlete did not compete. */
        ABS("ABS"),

        /** Disqualified: the performance does not stand. */
        DQ("DQ");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        /** How the outcome is written: {@code ABS}, {@code DQ}, {@code Result}. */
        public String getLabel() {
            return label;
        }

        /** True for an outcome that means no mark was produced. */
        public boolean isNoMark() {
            return this != RESULT;
        }

        /**
         * The outcome a client named, case-insensitively — {@code null} when the
         * value is blank or is not one of the three, so the caller can say which.
         */
        public static Outcome fromCode(String raw) {
            if (raw == null) {
                return null;
            }
            String value = raw.trim().toUpperCase();
            if (value.isEmpty()) {
                return null;
            }
            for (Outcome candidate : values()) {
                if (candidate.name().equals(value)) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
