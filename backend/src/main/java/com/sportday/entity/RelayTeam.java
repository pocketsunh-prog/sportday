package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One relay team of one {@link Event} — a form's team or a house's team.
 *
 * <p>A relay event that has been given a {@link Event#getRelayTeamKind() kind} has
 * one team per form or per house of its own grade and division; the teams are
 * derived from the student register rather than typed in one at a time
 * ({@code RelayTeamService}). An event with no kind has no teams at all, which is
 * how the relay events already in the programme behave.</p>
 *
 * <h2>The key and the label</h2>
 * <p>{@link #teamKey} is what the team is <em>matched on</em>: the form number as a
 * string ({@code "1"}, {@code "10"}) for a form team, the house name as the register
 * writes it ({@code "Red"}) for a house team. {@link #label} is what it is
 * <em>shown as</em> — {@code Form 1}, or {@code Red} — and is stored rather than
 * derived so a marking sheet or a results board can print one string that is known
 * to be right.</p>
 *
 * <p>{@code (event_id, kind, team_key)} is unique, so an event cannot end up with
 * two Form 1 teams however often the teams are derived.</p>
 */
@Entity
@Table(name = "relay_teams",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_relay_team_event_kind_key",
                columnNames = {"event_id", "kind", "team_key"}),
        indexes = @Index(name = "idx_relay_team_event", columnList = "event_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RelayTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Event event;

    /** Form or house — copied from the event's own kind when the team is derived. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private RelayTeamKind kind;

    /** The form number ({@code "1"}) or the house name ({@code "Red"}). */
    @Column(name = "team_key", nullable = false, length = 40)
    private String teamKey;

    /** What the team is shown as: {@code Form 1} or {@code Red}. */
    @Column(name = "label", nullable = false, length = 80)
    private String label;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "team", fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<RelayTeamMember> members = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (members == null) members = new ArrayList<>();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** The runners in leg order — leg 1 first, any reserves after them. */
    @Transient
    public List<RelayTeamMember> orderedMembers() {
        List<RelayTeamMember> ordered = new ArrayList<>(members == null ? List.of() : members);
        ordered.sort(Comparator.comparingInt(m -> m.getLeg() == null ? Integer.MAX_VALUE : m.getLeg()));
        return ordered;
    }

    /**
     * How many legs this team's race has — the event's own relay team size, which
     * is four for a 4x100M and a 4x400M unless the school has said otherwise.
     */
    @Transient
    public int getLegCount() {
        return event == null ? 0 : event.getEffectiveRelayTeamSize();
    }

    /**
     * How many runners the team may hold in total: its legs, plus the same number
     * again of reserves when the event allows them.
     */
    @Transient
    public int getMemberCap() {
        return event == null ? 0 : event.getRelayMemberCap();
    }
}
