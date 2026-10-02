package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A single competition event, e.g. "Boys A Grade 100M".
 *
 * <p>Every event is offered in exactly one {@link Sex} division and belongs to
 * one {@link EventCategory} (徑項 track / 田項 field). Events are created
 * <strong>enabled by default</strong>; an administrator can disable one at any
 * time, and disabled events reject new entries.</p>
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
         * The unit a mark is normally recorded in: track events are timed in
         * seconds, field events are measured in metres. Pre-fills the
         * mark-entry grid so a helper does not pick a unit for every athlete.
         */
        public String getDefaultUnit() {
            return category == EventCategory.FIELD ? "metres" : "seconds";
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
         * True for the relay events. A relay is scored on its own, larger scale and
         * its points go to the house rather than to an individual's total.
         */
        public boolean isRelay() {
            return this == RELAY_4X100M || this == RELAY_4X400M;
        }
    }
}
