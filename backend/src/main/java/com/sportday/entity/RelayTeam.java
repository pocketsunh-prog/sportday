package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One relay team of one {@link Event} — a class's team or a house's team.
 *
 * <p>A relay event that has been given a {@link Event#getRelayTeamKind() kind} has
 * its teams derived rather than typed in one at a time ({@code RelayTeamService}): an
 * event scoped to a form is made of <strong>one team per class its entrants are
 * in</strong> ({@code 3A} and {@code 3B} when those are the classes that entered the
 * Form 3 relay), an event with no form of one team per class of its own grade, and a
 * house relay of one team per house of its grade and division. An event with no kind
 * has no teams at all, which is how the relay events already in the programme
 * behave.</p>
 *
 * <h2>The key and the label</h2>
 * <p>{@link #teamKey} is what the team is <em>matched on</em>: the class as the
 * register writes it ({@code "1A"}) for a class team, the house name
 * ({@code "Yellow"}) for a house team. {@link #label} is what it is
 * <em>shown as</em> — {@code 1A}, or {@code C Grade Yellow} — and is stored rather than
 * derived so a marking sheet or a results board can print one string that is known
 * to be right. A name typed by hand sets {@link #nameOverridden}, so a derive
 * refreshes the names the roster gives and leaves that one alone.</p>
 *
 * <p>{@code (event_id, kind, team_key)} is unique, so an event cannot end up with
 * two 1A teams however often the teams are derived.</p>
 *
 * <h2>A team made by hand</h2>
 * <p>A team may also be built by hand out of students a teacher chose, with a name the
 * teacher typed ({@code RelayTeamService.createTeam}). Such a team carries no
 * {@link #kind} and is marked {@link #handMade} instead: it is not one class's and not
 * one house's, so no derive may touch it. See {@link #handMade}.</p>
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

    /**
     * Form or house — copied from the event's own kind when the team is derived.
     *
     * <p><strong>Null for a team built by hand</strong> ({@link #handMade}), whatever
     * kind its event has. The column is therefore nullable, and
     * {@code relay-teams-migration.sql} originally created it {@code NOT NULL}:
     * {@code db/migration/relay-teams-hand-made-migration.sql} relaxes it, and
     * Hibernate's {@code ddl-auto=update} carries the same change where the
     * application owns the schema. Without that change a hand-made team cannot be
     * written at all — the insert is refused with "Column 'kind' cannot be null".</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 10)
    private RelayTeamKind kind;

    /** The form number ({@code "1"}) or the house name ({@code "Red"}). */
    @Column(name = "team_key", nullable = false, length = 40)
    private String teamKey;

    /** What the team is shown as: the class ({@code 1A}) or {@code C Grade Yellow}. */
    @Column(name = "label", nullable = false, length = 80)
    private String label;

    /**
     * True when the team's name was typed by hand rather than derived from the
     * register.
     *
     * <p>A derive refreshes the label it would give a team — the class name, or
     * {@code C Grade Yellow} — but it must not overwrite a name somebody chose, "1A
     * Boys" or a name a teacher corrected. A rename sets this, and
     * {@code RelayTeamService.deriveTeams} leaves such a team's name alone.</p>
     *
     * <p>Nullable on purpose: null reads as false through {@link #isNameOverridden()},
     * so a team nobody has renamed is still the roster's to label and a row written
     * before the column existed behaves exactly as it always did.</p>
     */
    @Column(name = "name_overridden")
    private Boolean nameOverridden;

    /**
     * True when the team was built by hand out of chosen students rather than
     * derived from the register.
     *
     * <p>A hand-made team is <strong>not the roster's</strong>: its name is the
     * school's own free text — {@code 1A}, {@code B Grade Yellow}, anything — and it
     * may span classes and houses, so no rule the register could apply to it exists.
     * A later {@code derive} therefore leaves it completely alone: it is never
     * matched on {@link #teamKey}, never renamed, never re-keyed and never pruned,
     * however often the derived teams are rebuilt.</p>
     *
     * <p>Such a team carries <strong>no</strong> {@link #kind}, whatever kind its
     * event has. That is the structural half of the guarantee: a derive's key set is
     * built from {@link RelayTeamKind#FORM} class names or
     * {@link RelayTeamKind#HOUSE} house names, so a team with no kind can never be a
     * member of it, and {@code (event_id, kind, team_key)} can never collide between
     * a hand-made team and a derived one.</p>
     *
     * <p>Nullable on purpose, like {@link #nameOverridden}: null reads as false
     * through {@link #isHandMade()}, so every team already on file was derived and
     * behaves exactly as it did before the column existed.</p>
     */
    @Column(name = "hand_made")
    private Boolean handMade;

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

    /** True when the name was typed by hand, so a derive must leave it alone. */
    @Transient
    public boolean isNameOverridden() {
        return Boolean.TRUE.equals(nameOverridden);
    }

    /**
     * True when the team was built by hand from chosen students, so it belongs to no
     * class and no house and a derive must not match, rename, re-key or prune it.
     */
    @Transient
    public boolean isHandMade() {
        return Boolean.TRUE.equals(handMade);
    }
}
