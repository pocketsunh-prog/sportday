package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A single competition event, e.g. "Boys 100M · A Grade".
 *
 * <p>Every event is offered in exactly one {@link Sex} division <em>and</em> one
 * {@link Grade}, so no grade is ever ranked against another: {@code Boys 100M}
 * is three separate events — one for the A grade, one for the B grade and one
 * for the C grade — each with its own heats, marking sheets, results and
 * placings. Belongs to one {@link EventCategory} (徑項 track / 田項 field).
 * Events are created <strong>enabled by default</strong>; an administrator can
 * disable one at any time, and disabled events reject new entries.</p>
 *
 * <p>Whether a grade runs an event is simply whether that event exists, so the
 * old per-type grade-eligibility rules are gone. The school's starting position
 * — no C grade in the 1500M or the senior hurdles, only the A grade in the
 * 5000M — lives on {@link EventType#allowedGrades()} and decides which events
 * {@code EventService.createDefaults} creates.</p>
 */
@Entity
@Table(name = "events", indexes = {
    @Index(name = "idx_events_enabled", columnList = "enabled"),
    @Index(name = "idx_events_category_sex", columnList = "category,sex")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType type;

    /** 徑項 or 田項. Kept in step with {@link #type} by {@link #applyTypeDefaults()}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventCategory category;

    /** The sex division this event is contested in. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sex sex;

    /**
     * The one grade that competes in this event. An event with no grade would rank
     * grades against each other, which is exactly what the school does not want, so
     * this is required: a create or an update that would leave it unset is refused.
     *
     * <p>It is part of the event's identity alongside type and division, and it is
     * written into {@link #name} — {@code Boys 100M · A Grade} — so every existing
     * consumer (marking sheets, results, the entry list) shows the grade without
     * having to be taught about it.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private Grade grade;

    @Column(nullable = false)
    private LocalDate eventDate;

    private String location;

    /** Hard cap on confirmed entries. Generous by default so a whole year group can enter. */
    @Column(nullable = false)
    private Integer maxParticipants;

    /**
     * Number of athletes per heat/group. Defaults from the event type —
     * 8 for 60/100/200/400 and 24 for everything else — but may be overridden
     * per event by an administrator.
     */
    @Column(nullable = false)
    private Integer groupSize;

    @Column(nullable = false)
    private Boolean enabled;

    /**
     * True when the event is decided by its own run and no final is drawn.
     *
     * <p>This is the default: a school day mostly consists of events where
     * everybody competes once and that is the result. Unticking it — which only
     * 60M, 100M, 200M and 400M allow — turns the event into heats and then a final.
     * Null reads as true, so rows written before the flag existed behave as a
     * direct final rather than silently acquiring one.</p>
     */
    @Column(name = "direct_to_final")
    private Boolean directToFinal;

    /**
     * True when the system switched {@link #directToFinal} on rather than the school.
     *
     * <p>A sprint with only a group's worth of entries runs straight to a final,
     * because a final would be the same athletes as the heat. The system sets this
     * whenever entries change and the field is that small, so a school that untickes
     * the box is overruled on the next entry change — deliberately, since the final
     * would be pointless.</p>
     *
     * <p>What the flag protects is the other direction: once entries rise above a
     * final's worth, the system puts the final back only if it was the one that took
     * it away. An event the school chose to run straight to a final stays that way
     * however large the field becomes.</p>
     */
    @Column(name = "direct_to_final_auto")
    private Boolean directToFinalAuto;

    /**
     * How a relay event's teams are divided — one team per form, or one per house
     * within the event's grade. <strong>Nullable and optional</strong>: a relay with
     * no kind is simply undivided, which is how the relay events already in the
     * programme behave, so this feature adds a choice without taking one away. A
     * non-relay event must not have one at all, and {@code EventService} refuses a
     * create or an update that would give it one.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "relay_team_kind", length = 10)
    private RelayTeamKind relayTeamKind;

    /**
     * How many legs a team in this relay has — four for a 4x100M or a 4x400M.
     *
     * <p>Only meaningful for a relay, and nullable so an existing row (or a relay
     * created without a kind) reads as the type's own default of four legs through
     * {@link #getEffectiveRelayTeamSize()}.
     * A school that runs the relay as a longer squad raises it here rather than in
     * the code, because the size of the race is the event's business.</p>
     */
    @Column(name = "relay_team_size")
    private Integer relayTeamSize;

    /**
     * True when a team in this relay may also name <strong>reserves</strong> — the
     * option to put more athletes down than the race has legs. Off unless the school
     * explicitly asks for it, so going past the four legs of a 4x100M is refused
     * with a reason rather than quietly accepted.
     *
     * <p>Nullable on purpose, exactly like {@link #directToFinal}: a null reads as
     * false through {@link #isRelayReservesAllowed()}, so a row written before the
     * column existed cannot hand out reserves nobody asked for.</p>
     */
    @Column(name = "relay_reserves_allowed")
    private Boolean relayReservesAllowed;

    /**
     * The school year this event belongs to. Null on events created before
     * seasons existed; the bootstrap assigns them to the year their date falls in.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Season season;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** Default cap applied when the client does not supply {@code maxParticipants}. */
    public static final int DEFAULT_MAX_PARTICIPANTS = 512;

    /**
     * How many reserves a team may name when the event allows them, as a multiple of
     * the race's own legs: a 4x100M that allows reserves may put down eight runners
     * — four legs and four reserves — and no more. Generous on purpose, because it
     * is a ceiling rather than a requirement: a school that wants two reserves names
     * six and simply stops there.
     */
    public static final int RESERVE_ALLOWANCE_MULTIPLIER = 2;

    /**
     * The largest team a relay may be given. Four is the race, and the reserve
     * allowance doubles it; this is only a sanity bound so a typo — {@code 400} legs —
     * is refused rather than stored.
     */
    public static final int MAX_RELAY_LEGS = 16;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (enabled == null) enabled = true;
        if (maxParticipants == null) maxParticipants = DEFAULT_MAX_PARTICIPANTS;
        applyTypeDefaults();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (enabled == null) enabled = true;
        applyTypeDefaults();
    }

    /**
     * Fills in the fields that are derived from {@link #type}: category and,
     * when it has not been overridden, the group size. Called before every
     * insert/update so an event can never drift out of sync with its type.
     */
    public void applyTypeDefaults() {
        if (type == null) {
            return;
        }
        category = type.getCategory();
        if (groupSize == null || groupSize <= 0) {
            groupSize = type.getDefaultGroupSize();
        }
    }

    /** True for 60/100/200/400 — the events marked up 8 to an A5 sheet. */
    @Transient
    public boolean isShortSprint() {
        return type != null && type.isShortSprint();
    }

    /**
     * True when this event is decided by its own run and no final is drawn. Null —
     * a row from before the flag existed — reads as true, which is the default.
     */
    @Transient
    public boolean isDirectToFinal() {
        return !Boolean.FALSE.equals(directToFinal);
    }

    /**
     * True when this event <em>may</em> be run as heats and a final. Only
     * 60/100/200/400 can: everything else, including every field event, is decided
     * by its own run.
     */
    @Transient
    public boolean mayHaveFinal() {
        return isShortSprint();
    }

    /**
     * True when a final is actually in play — the event allows one and the school
     * has asked for one.
     */
    @Transient
    public boolean runsAFinal() {
        return mayHaveFinal() && !isDirectToFinal();
    }

    /** True when this race is timed in minutes and seconds rather than in seconds alone. */
    @Transient
    public boolean usesMinutesAndSeconds() {
        return type != null && type.usesMinutesAndSeconds();
    }

    /** True when this event is one of the relays — 4x100M or 4x400M. */
    @Transient
    public boolean isRelay() {
        return type != null && type.isRelay();
    }

    /**
     * How many legs a team of this event has, falling back to the type's own size
     * when nothing has been set on the event. Zero for anything that is not a relay,
     * so a caller can use it without asking about the type first.
     */
    @Transient
    public int getEffectiveRelayTeamSize() {
        if (type == null || !type.isRelay()) {
            return 0;
        }
        return relayTeamSize != null && relayTeamSize > 0
                ? relayTeamSize
                : type.getDefaultRelayLegs();
    }

    /**
     * How many runners one team of this event may hold: the race's legs, plus the
     * same number again of reserves when the school has allowed them.
     */
    @Transient
    public int getRelayMemberCap() {
        int legs = getEffectiveRelayTeamSize();
        if (legs <= 0) {
            return 0;
        }
        return isRelayReservesAllowed() ? legs * RESERVE_ALLOWANCE_MULTIPLIER : legs;
    }

    /**
     * True when this relay may also name reserves. Null — a row from before the flag
     * existed — reads as false, so reserves are opt-in rather than inherited.
     */
    @Transient
    public boolean isRelayReservesAllowed() {
        return Boolean.TRUE.equals(relayReservesAllowed);
    }

    @Transient
    public EventCategory getCategoryOrDefault() {
        if (category != null) {
            return category;
        }
        return type == null ? EventCategory.TRACK : type.getCategory();
    }

    public enum EventType {

        RUN_60M("60M", EventCategory.TRACK, true),
        RUN_100M("100M", EventCategory.TRACK, true),
        RUN_200M("200M", EventCategory.TRACK, true),
        RUN_400M("400M", EventCategory.TRACK, true),
        RUN_800M("800M", EventCategory.TRACK, false),
        RUN_1500M("1500M", EventCategory.TRACK, false),
        RUN_5000M("5000M", EventCategory.TRACK, false),
        HURDLES_100M("100M Hurdles", EventCategory.TRACK, false),
        HURDLES_110M("110M Hurdles", EventCategory.TRACK, false),
        HURDLES_400M("400M Hurdles", EventCategory.TRACK, false),
        RELAY_4X100M("4x100M Relay", EventCategory.TRACK, false),
        RELAY_4X400M("4x400M Relay", EventCategory.TRACK, false),

        SHOT_PUT("Shot Put", EventCategory.FIELD, false),
        DISCUSSION_THROW("Discus", EventCategory.FIELD, false),
        JAVELIN_THROW("Javelin", EventCategory.FIELD, false),
        HAMMER_THROW("Hammer", EventCategory.FIELD, false),
        LONG_JUMP("Long Jump", EventCategory.FIELD, false),
        HIGH_JUMP("High Jump", EventCategory.FIELD, false),
        TRIPLE_JUMP("Triple Jump", EventCategory.FIELD, false),
        POLE_VAULT("Pole Vault", EventCategory.FIELD, false),

        OTHER("Other", EventCategory.TRACK, false);

        /** Athletes per group for 60/100/200/400. */
        public static final int SHORT_SPRINT_GROUP_SIZE = 8;

        /** Athletes per group for 800 and above, and for all field events. */
        public static final int DISTANCE_GROUP_SIZE = 24;

        /** The unit a track mark is recorded in — seconds, as a programme writes it. */
        public static final String UNIT_TRACK = "s";

        /** The unit a field mark is recorded in — metres, as a programme writes it. */
        public static final String UNIT_FIELD = "M";

        /** How many attempts a field athlete gets; the best one is their result. */
        public static final int FIELD_ATTEMPTS = 3;

        /**
         * Legs in a relay team. Both relays the programme runs are 4x — the 4x100M
         * and the 4x400M — so four is the sensible default; an event that runs a
         * longer squad sets its own {@link Event#getRelayTeamSize()}.
         */
        public static final int RELAY_LEGS = 4;

        /**
         * Every spelling of a length or a time a caller might send, so it can be
         * snapped to the event's own unit rather than stored as typed.
         */
        private static final java.util.List<String> LENGTH_OR_TIME_UNITS = java.util.List.of(
                "m", "metre", "metres", "meter", "meters",
                "s", "sec", "secs", "second", "seconds");

        private final String displayName;
        private final EventCategory category;
        private final boolean shortSprint;

        EventType(String displayName, EventCategory category, boolean shortSprint) {
            this.displayName = displayName;
            this.category = category;
            this.shortSprint = shortSprint;
        }

        public String getDisplayName() {
            return displayName;
        }

        public EventCategory getCategory() {
            return category;
        }

        /** True for 60M, 100M, 200M and 400M. */
        public boolean isShortSprint() {
            return shortSprint;
        }

        /** 8 athletes per group for 60/100/200/400, otherwise 24. */
        public int getDefaultGroupSize() {
            return shortSprint ? SHORT_SPRINT_GROUP_SIZE : DISTANCE_GROUP_SIZE;
        }

        /**
         * The unit a mark is recorded in: a track event is timed in seconds, a
         * field event is measured in metres. Written the way an athletics
         * programme writes it — {@code s} and {@code M} — because that is what
         * fits a marking sheet column and what a timekeeper expects to see.
         */
        public String getDefaultUnit() {
            return category == EventCategory.FIELD ? UNIT_FIELD : UNIT_TRACK;
        }

        /**
         * The unit a mark is stored in, whatever the caller sent.
         *
         * <p>The unit follows from the event — a track event is a time and a field
         * event is a distance — so a client sending the spelled-out "seconds" or
         * "metres", or the wrong one entirely, still ends up recorded the same way
         * as everything else. Anything unrecognised is kept, so a school that
         * measures something unusual is not overruled.</p>
         */
        public String normaliseUnit(String requested) {
            if (requested == null || requested.isBlank()) {
                return getDefaultUnit();
            }
            String lower = requested.trim().toLowerCase();
            for (String known : LENGTH_OR_TIME_UNITS) {
                if (known.equals(lower)) {
                    return getDefaultUnit();
                }
            }
            return requested.trim();
        }

        /**
         * True when a smaller mark is the better one. A track event is a time, so
         * the fastest is the smallest number; a field event is a distance or a
         * height, so the biggest wins. This is the single place that decides which
         * way round the leaderboards, the records and the placings go.
         */
        public boolean isLowerBetter() {
            return category == EventCategory.TRACK;
        }

        /**
         * True for a race longer than 400M, where a time reads better as minutes and
         * seconds than as a bare count of them — a helper writes 2:15, not 135. The
         * mark is still stored in seconds, so nothing downstream changes.
         */
        public boolean usesMinutesAndSeconds() {
            return this == RUN_800M || this == RUN_1500M || this == RUN_5000M;
        }

        /**
         * True for the relay events. A relay is scored on its own, larger scale and
         * its points go to the house rather than to an individual's total.
         */
        public boolean isRelay() {
            return this == RELAY_4X100M || this == RELAY_4X400M;
        }

        /**
         * Legs in a team of this event type: four ({@link #RELAY_LEGS}) for a relay,
         * zero for everything else — a sprint has no legs to fill.
         */
        public int getDefaultRelayLegs() {
            return isRelay() ? RELAY_LEGS : 0;
        }

        /**
         * The grades that run this event type — which events the catalogue offers.
         *
         * <p>This used to live in {@code EventGradeRule.defaultAllowedGrades}. Now
         * that an event belongs to exactly one grade, whether a grade runs an event
         * is simply whether that event exists, so the school's starting position is
         * decided once here and {@code createDefaults} creates exactly these events:</p>
         *
         * <ul>
         *   <li>the 1500M — no C grade;</li>
         *   <li>the 5000M — the A grade only;</li>
         *   <li>the 110M hurdles — no C grade, which runs the 100M hurdles instead;</li>
         *   <li>everything else — all three grades.</li>
         * </ul>
         *
         * <p>Returned in programme order (A, B, C), which is what makes the seeded
         * events list that way.</p>
         */
        public java.util.Set<Grade> allowedGrades() {
            return switch (this) {
                case RUN_5000M -> java.util.EnumSet.of(Grade.A);
                case RUN_1500M, HURDLES_110M -> java.util.EnumSet.of(Grade.A, Grade.B);
                default -> java.util.EnumSet.allOf(Grade.class);
            };
        }

        /** True when this event type is run by that grade. */
        public boolean runsGrade(Grade grade) {
            return grade != null && allowedGrades().contains(grade);
        }
    }
}
