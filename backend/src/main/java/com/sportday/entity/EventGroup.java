package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One heat or the final of a single {@link Event}.
 *
 * <p>After entries close the system splits every event's confirmed entries into
 * groups of {@link Event#getGroupSize()} athletes — 8 to a group for
 * 60/100/200/400 and 24 to a group for 800 and above. Each group is what a
 * helper receives on one marking sheet, so the group size also decides whether
 * that sheet is A5 (short sprints) or A4 (everything else).</p>
 *
 * <p>Short sprints add a second stage: the fastest
 * {@value com.sportday.service.FinalQualificationService#DEFAULT_FINAL_SIZE}
 * athletes from the heats go through to the final, which is another group of this
 * same event with {@link EventStage#FINAL}.</p>
 *
 * <h2>Why the final is group number 0</h2>
 * <p>The table's unique key is {@code (event_id, group_number)} and heats are
 * numbered from 1, so the final takes 0. That keeps a final from colliding with
 * Heat 1 without having to change the key, and {@link #getLabel()} prints it as
 * "Final" rather than "Heat 0".</p>
 */
@Entity
@Table(name = "event_groups", uniqueConstraints = {
    @UniqueConstraint(name = "uk_event_group_number", columnNames = {"event_id", "group_number"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventGroup {

    /** Group number reserved for the final. */
    public static final int FINAL_GROUP_NUMBER = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Event event;

    /** 1-based heat number within the event; 0 for the final. */
    @Column(name = "group_number", nullable = false)
    private Integer groupNumber;

    /**
     * Which stage this group is. Nullable only so that groups created before the
     * heat/final split can be adopted: {@link #getStageOrDefault()} and the
     * lifecycle hook below both treat a null as {@link EventStage#HEAT}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 10)
    private EventStage stage;

    /** Group size in force when this group was created. */
    @Column(name = "capacity", nullable = false)
    private Integer capacity;

    /** Number of athletes currently allocated to this group. */
    @Column(name = "athlete_count", nullable = false)
    private Integer athleteCount;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "eventGroup", fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Enrollment> entries = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (athleteCount == null) athleteCount = 0;
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

    /** Display label used on the marking sheet: {@code Heat 3} or {@code Final}. */
    @Transient
    public String getLabel() {
        return isFinal() ? "Final" : "Heat " + groupNumber;
    }
}
