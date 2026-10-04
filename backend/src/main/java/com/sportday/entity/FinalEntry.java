package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An athlete's place in the final of an event.
 *
 * <p>This is deliberately separate from {@link Enrollment#getEventGroup()}, which
 * holds the athlete's <em>heat</em>. A finalist is in two places at once: they
 * still belong to the heat they ran, so a reprinted heat sheet is unchanged, and
 * they also have a lane in the final. Moving the enrollment instead would quietly
 * empty the heat sheets once the final was drawn.</p>
 *
 * <p>{@link #seedMark} is the heat performance that earned the place, kept so the
 * final's start list can be printed and audited without re-running the ranking.</p>
 *
 * <p>{@link #seed} is the qualification rank while {@link #lane} is the drawn lane.
 * They are deliberately different numbers: the school's draw sends the fastest
 * qualifier to the middle of the track rather than to lane 1, so an entry can read
 * seed 1, lane 3.</p>
 */
@Entity
@Table(name = "final_entries", uniqueConstraints = {
    @UniqueConstraint(name = "uk_final_entry_group_user", columnNames = {"group_id", "user_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The {@link EventStage#FINAL} group this place belongs to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private EventGroup group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User user;

    /**
     * The lane the athlete runs in, 1-based — the <strong>draw</strong>, not the
     * rank. The school's draw puts the fastest qualifier in the middle of the track
     * (lane 3) and works outwards, leaving the two slowest qualifiers on the outside
     * (lane 2, then lane 1); the rule lives in
     * {@code FinalQualificationService.laneForRank(int)}. So the fastest does not
     * run in lane 1.
     */
    @Column(name = "lane", nullable = false)
    private Integer lane;

    /**
     * Position in the qualification ranking, 1-based — the <strong>entry order</strong>.
     * A sheet can still say who qualified fastest even though that athlete does not
     * run in lane 1, and a re-draw re-seeds rather than leaving a stale number behind.
     */
    @Column(name = "seed", nullable = false)
    private Integer seed;

    /** The heat mark that earned this place. */
    @Column(name = "seed_mark", precision = 10, scale = 3)
    private BigDecimal seedMark;

    @Column(name = "seed_unit")
    private String seedUnit;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
