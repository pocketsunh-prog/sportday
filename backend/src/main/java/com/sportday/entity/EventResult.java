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
}
