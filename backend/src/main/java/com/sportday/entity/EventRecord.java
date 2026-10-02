package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The school record for one event, division and grade — for example
 * "Boys 100M, B Grade".
 *
 * <p>A row exists for <em>every</em> combination of event type, division and
 * grade, created as soon as the event is, so the records page is complete from the
 * start rather than filling in as results arrive.</p>
 *
 * <p>Two things decide the mark that stands:</p>
 * <ul>
 *   <li>a <strong>baseline</strong> an administrator types in — last season's best,
 *       or a record held by a student who has since left, which is why
 *       {@link #manualHolderName} is free text rather than a link to an account;</li>
 *   <li>the <strong>best result</strong> recorded in any event of this type and
 *       division, by an athlete in this grade.</li>
 * </ul>
 *
 * <p>{@link #mark} and its companions are the winner of those two, recomputed by
 * {@code RecordService}. The baseline is never overwritten by that recomputation,
 * so clearing results or resetting the season falls back to it rather than losing
 * it.</p>
 *
 * <p>Which direction is better follows the event: a track time is lower-is-better,
 * a field distance or height is higher-is-better.</p>
 */
@Entity
@Table(name = "event_records", uniqueConstraints = {
    @UniqueConstraint(name = "uk_record_type_sex_grade",
            columnNames = {"event_type", "sex", "grade"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventRecord {

    /** Where the standing mark came from. */
    public enum Source {
        /** Typed in by an administrator; no result holds it. */
        BASELINE,
        /** Set by a result recorded at a sport day. */
        RESULT,
        /** Nothing yet — the row exists but is empty. */
        NONE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private Event.EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Sex sex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private Grade grade;

    // ------------------------------------------------------- the administrator's baseline

    /** The mark an administrator entered. Null until one is set. */
    @Column(name = "manual_mark", precision = 10, scale = 3)
    private BigDecimal manualMark;

    @Column(name = "manual_unit", length = 20)
    private String manualUnit;

    /** Free text: a record may be held by a student who has left the school. */
    @Column(name = "manual_holder_name", length = 120)
    private String manualHolderName;

    @Column(name = "manual_achieved_on")
    private LocalDate manualAchievedOn;

    // ------------------------------------------------------- the mark that stands

    /** The best of the baseline and the results. Null when there is neither. */
    @Column(precision = 10, scale = 3)
    private BigDecimal mark;

    private String unit;

    /** The athlete who holds the record, when a result set it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holder_user_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User holder;

    /** The holder's name — the athlete's, or the typed one for a baseline. */
    @Column(name = "holder_name", length = 120)
    private String holderName;

    /** The performance that holds it, when a result set it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private EventResult result;

    /** The event the standing result was set in. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Event event;

    @Column(name = "achieved_on")
    private LocalDate achievedOn;

    // ------------------------------------------------------- what the standing mark beat

    @Column(name = "previous_mark", precision = 10, scale = 3)
    private BigDecimal previousMark;

    @Column(name = "previous_holder_name", length = 120)
    private String previousHolderName;

    @Column(name = "previous_achieved_on")
    private LocalDate previousAchievedOn;

    @Column(name = "has_previous", nullable = false)
    private Boolean hasPrevious;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
        if (hasPrevious == null) hasPrevious = previousMark != null;
    }

    /** Where the standing mark came from. */
    @Transient
    public Source getSource() {
        if (result != null) {
            return Source.RESULT;
        }
        return manualMark == null ? Source.NONE : Source.BASELINE;
    }

    /** True when an administrator has entered a baseline. */
    @Transient
    public boolean hasBaseline() {
        return manualMark != null;
    }

    /** True when {@code candidate} would beat the mark that stands. */
    @Transient
    public boolean isBeatenBy(BigDecimal candidate) {
        return candidate != null && (mark == null || isBetter(candidate, mark));
    }

    /** True when {@code a} is the better of two marks for this event. */
    @Transient
    public boolean isBetter(BigDecimal a, BigDecimal b) {
        if (a == null) return false;
        if (b == null) return true;
        return eventType != null && eventType.isLowerBetter()
                ? a.compareTo(b) < 0
                : a.compareTo(b) > 0;
    }
}
