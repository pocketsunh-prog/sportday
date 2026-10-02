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

    /** Lane / start position in the final, 1-based. */
    @Column(name = "lane", nullable = false)
    private Integer lane;

    /** Position in the qualification ranking, 1-based. */
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
