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
 * placings. Belongs to one {@link EventCategory} (徑項 track / 田項 field /
 * 接力 relay — a relay being its own family, run and scored by team).
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

    /**
     * 徑項, 田項 or 接力. Kept in step with {@link #type} by
     * {@link #applyTypeDefaults()}; a relay type declares
     * {@link EventCategory#RELAY}.
     */
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

    /**
     * The <strong>form</strong> a form relay is scoped to - {@code 1} for a
     * "Form 1 4x100M" whose teams are 1A, 1B, 1C and 1D - or null for every event
     * that is scoped by grade, which is all of them today.
     *
     * <p>A form event admits students of <em>any</em> grade so long as they are in
     * that form and the event's division; a grade-scoped event admits only its own
     * grade. Null is therefore the whole of the old behaviour, so nothing already on
     * file changes.</p>
     */
    @Column(name = "form", length = 4)
    private String form;

    /** True when this event is scoped to a form rather than a grade. */
    @Transient
    public boolean isFormScoped() {
        return form != null && !form.isBlank();
    }

    /**
     * The form scope as the school writes it — {@code Form 1} — or {@code null} for
     * every event that is scoped by grade.
     *
     * <p>Written here, beside {@link #isFormScoped()}, rather than in each DTO that
     * carries it, so the event list and the relay board cannot spell the same scope
     * two ways.</p>
     */
    @Transient
    public String getFormLabel() {
        return isFormScoped() ? "Form " + form.trim() : null;
    }

    @Column(nullable = false)
    private LocalDate eventDate;

    /**
     * The <strong>required standard</strong> — the qualifying mark an athlete must
     * reach — for the events that carry one: the track races of 400M and over, and
     * every field event. Null for everything else, and null here is the whole of
     * the old behaviour, so no event on file changes.
     *
     * <p>Measured in the event's own unit: seconds for a race, metres for a field
     * event. A race meets the standard at or <em>under</em> it and a field event at
     * or <em>over</em> it — the same lower-is-better rule that already decides the
     * leaderboards and the records, taken from {@link EventType#isLowerBetter()}
     * rather than decided again here.</p>
     *
     * @see EventType#carriesAStandard()
     */
    @Column(name = "standard", precision = 10, scale = 3)
    private java.math.BigDecimal standard;

    /**
     * Where {@link #standard} came from: <strong>true</strong> when it was inherited
     * from the grade and division's default ({@link StandardDefault}), false when a
     * person typed it on this event.
     *
     * <p>This one flag is what makes updating a default safe. "Apply now" re-points
     * the events that <em>follow</em> the default and leaves the exceptions alone,
     * and without a record of which is which there is no way to tell a number that
     * was inherited from a number that was chosen — they are the same column. A
     * forced apply ({@code mode=ALL}) deliberately overwrites both and says so.</p>
     *
     * <p>Nullable on purpose, exactly like {@link #directToFinal}. Null reads as
     * false through {@link #isStandardInherited()}, so every standard already on file
     * — the column did not exist when it was typed — is treated as <strong>hand-set
     * and therefore never quietly overwritten</strong>. That is the conservative
     * reading: an unattributed number has no proof it came from a default, so it is
     * left alone and reported as kept.</p>
     */
    @Column(name = "standard_is_default")
    private Boolean standardIsDefault;

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
     * How a relay event's teams are divided — one team per class, or one per house
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
     * True when this event is a <strong>draft</strong> — a relay event made to hold
     * teams the school is building by hand, before the race itself is real.
     *
     * <p>The school's requirement: "create relay event base on selected relay team".
     * A relay team cannot exist without an event ({@code RelayTeam.event} is a
     * non-null foreign key, and a mark and a marking sheet both reach a team
     * <em>through</em> its event), so the teams are collected on a draft event and
     * moved onto the real event when it is created
     * ({@code RelayTeamService.moveTeamsToEvent}).</p>
     *
     * <p><strong>A draft must not look like a real event to the school.</strong> It
     * is deliberately not a status enum and carries no extra machinery: one flag, and
     * every place that lists or counts events decides explicitly whether a draft
     * belongs there. The programme, the date picker, the past-events list, the
     * results print run and the year's event count all exclude drafts; the relay
     * board of the draft itself of course includes it, and an administrator can list
     * the drafts ({@code EventService.getDraftEvents}) to find the one they are
     * filling.</p>
     *
     * <p>Nullable on purpose, exactly like {@link #directToFinal}: null reads as
     * false through {@link #isDraft()}, so every event already on file — and every
     * event created without the flag — is a real event and behaves as it always
     * did.</p>
     */
    @Builder.Default
    @Column(name = "is_draft")
    private Boolean draft = Boolean.FALSE;

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
     * How many reserves a team may name when the event allows them: <strong>one</strong>.
     *
     * <p>The school enters four runners and one backup, so a team is four strong or
     * five. A reserve is there to replace somebody who cannot run; a team that could
     * name four of them is a second squad, not the team the school entered.</p>
     */
    public static final int RESERVE_ALLOWANCE = 1;

    /**
     * The largest team a relay may be given. Four is the race and the backup makes
     * five; this is only a sanity bound so a typo — {@code 400} legs — is refused
     * rather than stored.
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
     * True when this event is a <strong>draft</strong>: a relay event made to hold
     * teams the school is building by hand, before the race itself is real. Null — a
     * row from before the flag existed, or an event created without it — reads as
     * false, so every event already on file is a real event.
     *
     * <p>One place decides it, so nothing downstream has to ask the column directly:
     * the programme and everything that offers entry exclude a draft
     * ({@code EventService}), and {@code RelayTeamService.moveTeamsToEvent} is what
     * carries its teams onto the real event.</p>
     */
    @Transient
    public boolean isDraft() {
        return Boolean.TRUE.equals(draft);
    }

    /**
     * True when this event's {@link #standard} came from the grade and division's
     * {@link StandardDefault} rather than from somebody typing it on this event.
     *
     * <p>Null — a standard set before the column existed — reads as <strong>false</strong>,
     * so a number with no record of where it came from is treated as hand-set and is
     * never overwritten by an "apply the default" run. Only an explicit
     * {@code mode=ALL} apply rewrites it, and that is a decision the administrator
     * makes with the count in front of them.</p>
     */
    @Transient
    public boolean isStandardInherited() {
        return Boolean.TRUE.equals(standardIsDefault);
    }

    /**
     * Points this event's standard at the default it inherits, or marks the value it
     * already holds as hand-set when {@code defaultId} is null.
     *
     * @param value     the standard to hold, or null for none
     * @param defaultId the default it came from, or null when it was typed here
     */
    public void setStandardFromDefault(java.math.BigDecimal value, Long defaultId) {
        this.standard = value;
        this.standardIsDefault = defaultId != null;
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
     * How many runners one team of this event may hold: the race's legs, plus
     * <strong>one</strong> reserve when the school has allowed them.
     *
     * <p>The school's rule is four runners and one backup, so a team is four or five
     * strong. One reserve, not a second squad: a reserve is there to replace somebody
     * who cannot run, and a team that could name two or four of them is not the team
     * the school enters.</p>
     */
    @Transient
    public int getRelayMemberCap() {
        int legs = getEffectiveRelayTeamSize();
        if (legs <= 0) {
            return 0;
        }
        return isRelayReservesAllowed() ? legs + RESERVE_ALLOWANCE : legs;
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
        RELAY_4X100M("4x100M Relay", EventCategory.RELAY, false),
        RELAY_4X400M("4x400M Relay", EventCategory.RELAY, false),

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
         *
         * <p>A relay is a race, so it is timed like the track: it takes the
         * seconds here exactly as it does in {@link MarkFormatter} and on the
         * marking sheet. Only {@link EventCategory#FIELD} is measured.</p>
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
            return !category.isMeasuredInDistance();
        }

        /**
         * True for a race long enough to be timed on a stopwatch — <strong>the one
         * place that decides it</strong>, asked by the mark-entry grid, by the DTO
         * the grid is drawn from and by {@code MarkFormatter}, rather than each
         * testing event types for itself.
         *
         * <p>The school's rule: the track races of <strong>400M and over</strong> —
         * the 400M, the 400M hurdles, the 800M, the 1500M and the 5000M — <em>and
         * both relays</em>. A 4x100M and a 4x400M are run and timed exactly like the
         * race they are, and their time is written the same way.</p>
         *
         * <p>Deliberately not the 60M, 100M or 200M or the short hurdles: a sprint is
         * timed in seconds alone, {@code 14.123}, and stays that way. The field
         * events are not races at all.</p>
         *
         * <p>What it decides is only how a mark <em>reads</em> and how it is
         * <em>typed</em> — {@code M.SS.mmm}, see {@code StopwatchTime}. The mark is
         * still stored as a plain number of seconds, so nothing downstream of the
         * entry changes.</p>
         */
        public boolean usesMinutesAndSeconds() {
            return this == RUN_400M || this == HURDLES_400M
                    || this == RUN_800M || this == RUN_1500M || this == RUN_5000M
                    || isRelay();
        }

        /**
         * True for the relay events. A relay is scored on its own, larger scale and
         * its points go to the house rather than to an individual's total.
         */
        public boolean isRelay() {
            return this == RELAY_4X100M || this == RELAY_4X400M;
        }

        /**
         * True for the events a school sets a <strong>required standard</strong> on:
         * the track races of <strong>400M and over</strong>, and <strong>every field
         * event</strong>.
         *
         * <p>Deliberately not "every track event": a 60M, 100M or 200M is not
         * qualifying in this school's programme, and neither are the short hurdles.
         * A <strong>relay</strong> does not carry one either — it is its own category
         * now, and it is run and scored by team. This is the single place that
         * answers the question; the standards page, the sheet and the mark grid all
         * ask it rather than testing categories and distances for themselves.</p>
         */
        public boolean carriesAStandard() {
            if (isRelay()) {
                return false;
            }
            if (category == EventCategory.FIELD) {
                return true;
            }
            return this == RUN_400M || this == HURDLES_400M
                    || this == RUN_800M || this == RUN_1500M || this == RUN_5000M;
        }

        /**
         * True when a mark <strong>meets</strong> the standard, or when there is
         * nothing to meet.
         *
         * <p>The direction is not decided here: it is {@link #isLowerBetter()}, the
         * one rule that already settles the leaderboards, the records and the
         * placings. A time counts at or under the standard, a distance at or over
         * it. A missing mark, or no standard, is never "below" — a blank is not a
         * failure.</p>
         */
        public boolean meetsStandard(java.math.BigDecimal mark,
                                     java.math.BigDecimal standard) {
            if (mark == null || standard == null) {
                return true;
            }
            return isLowerBetter()
                    ? mark.compareTo(standard) <= 0
                    : mark.compareTo(standard) >= 0;
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
