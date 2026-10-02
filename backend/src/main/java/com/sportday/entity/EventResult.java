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

    /** Heat or final. A null is read as {@link EventStage#HEAT}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 10)
    private EventStage stage;

    @Column(nullable = false, precision = 10, scale = 3)
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
}
