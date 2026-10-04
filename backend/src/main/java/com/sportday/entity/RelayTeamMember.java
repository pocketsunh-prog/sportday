package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One runner in a {@link RelayTeam} — the athlete and the leg they run.
 *
 * <p>{@link #leg} is 1-based: leg 1 runs first, leg 4 is the anchor of a 4x100M.
 * A leg greater than the event's own relay team size is a <strong>reserve</strong>,
 * which a team may only hold when the event allows reserves.</p>
 *
 * <p>Two unique keys keep the team honest, so the rule cannot be broken by a second
 * request arriving at the same moment as the first:</p>
 * <ul>
 *   <li>{@code (team_id, user_id)} — the same athlete cannot hold two legs of one
 *       team;</li>
 *   <li>{@code (team_id, leg)} — two athletes cannot both be down for leg 3.</li>
 * </ul>
 *
 * <p>An athlete <em>may</em> hold a leg in both a form team and a house team, because
 * those are different events: the division is on the event, not on the team. What
 * they may not do is run twice in the <em>same</em> event — {@code RelayTeamService}
 * enforces that in one place, on top of the keys above.</p>
 *
 * <p>Only the user is held, not the roster row: entries, results and records all
 * address an athlete by their login account, and the class, house and grade the
 * eligibility rules read are looked up from the register when the runner is
 * selected, so a student who changes class cannot be left on a team they no longer
 * qualify for without anyone noticing.</p>
 */
@Entity
@Table(name = "relay_team_members",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_relay_member_team_user",
                    columnNames = {"team_id", "user_id"}),
            @UniqueConstraint(name = "uk_relay_member_team_leg",
                    columnNames = {"team_id", "leg"})
        },
        indexes = @Index(name = "idx_relay_member_user", columnList = "user_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RelayTeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private RelayTeam team;

    /** The athlete's login account — what entries and results are held against. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User user;

    /** 1-based leg; anything past the race's own size is a reserve. */
    @Column(name = "leg", nullable = false)
    private Integer leg;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
