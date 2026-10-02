package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * The school's rules for the day, editable by an administrator.
 *
 * <p>Two things live here. First, how many events one student may enter — at most
 * {@link #trackMaxEntries} track events (徑項) and {@link #fieldMaxEntries} field
 * events (田項), which used to be hard-coded. Second, the points awarded for each
 * placing, which decide the personal and house championships.</p>
 *
 * <p>There is exactly one row, {@link #SINGLETON_ID}. {@link #pointsForPlace}
 * keeps the scoring rule in one place so the champions page, the event placings
 * and any future report cannot disagree about it.</p>
 */
@Entity
@Table(name = "sport_day_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SportDaySettings {

    /** The settings are a single row. */
    public static final long SINGLETON_ID = 1L;

    public static final int DEFAULT_TRACK_MAX = 2;
    public static final int DEFAULT_FIELD_MAX = 1;
    public static final int DEFAULT_POINTS_FIRST = 9;
    public static final int DEFAULT_POINTS_SECOND = 6;
    public static final int DEFAULT_POINTS_THIRD = 3;
    public static final int DEFAULT_POINTS_TOP_PLACE = 8;
    public static final int DEFAULT_POINTS_TOP = 1;
    public static final int DEFAULT_RELAY_POINTS_FIRST = 30;
    public static final int DEFAULT_RELAY_POINTS_SECOND = 20;
    public static final int DEFAULT_RELAY_POINTS_THIRD = 10;
    public static final int DEFAULT_RELAY_POINTS_TOP = 1;

    @Id
    private Long id;

    // ------------------------------------------------------- the school itself

    @Column(name = "school_name", length = 160)
    private String schoolName;

    @Column(name = "school_name_zh", length = 160)
    private String schoolNameZh;

    @Column(length = 255)
    private String address;

    @Column(length = 120)
    private String principal;

    /** Printed at the head of every marking sheet. */
    @Column(name = "sport_day_title", length = 160)
    private String sportDayTitle;

    // ------------------------------------------------------- entry limits

    /** Maximum track (徑項) entries one student may hold. */
    @Column(name = "track_max_entries", nullable = false)
    private Integer trackMaxEntries;

    /** Maximum field (田項) entries one student may hold. */
    @Column(name = "field_max_entries", nullable = false)
    private Integer fieldMaxEntries;

    @Column(name = "points_first", nullable = false)
    private Integer pointsFirst;

    @Column(name = "points_second", nullable = false)
    private Integer pointsSecond;

    @Column(name = "points_third", nullable = false)
    private Integer pointsThird;

    /** The lowest place that still scores, 8 by default — so 4th to 8th score. */
    @Column(name = "points_top_place", nullable = false)
    private Integer pointsTopPlace;

    /** Points for places 4 up to {@link #pointsTopPlace}. */
    @Column(name = "points_top", nullable = false)
    private Integer pointsTop;

    @Column(name = "relay_points_first", nullable = false)
    private Integer relayPointsFirst;

    @Column(name = "relay_points_second", nullable = false)
    private Integer relayPointsSecond;

    @Column(name = "relay_points_third", nullable = false)
    private Integer relayPointsThird;

    /** Points for relay places 4 up to {@link #pointsTopPlace}. */
    @Column(name = "relay_points_top", nullable = false)
    private Integer relayPointsTop;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** A settings row with every default, used when the table is empty. */
    public static SportDaySettings defaults() {
        SportDaySettings settings = SportDaySettings.builder()
                .id(SINGLETON_ID)
                .sportDayTitle("田徑運動會記錄表 / Sport Day Marking Sheet")
                .trackMaxEntries(DEFAULT_TRACK_MAX)
                .fieldMaxEntries(DEFAULT_FIELD_MAX)
                .pointsFirst(DEFAULT_POINTS_FIRST)
                .pointsSecond(DEFAULT_POINTS_SECOND)
                .pointsThird(DEFAULT_POINTS_THIRD)
                .pointsTopPlace(DEFAULT_POINTS_TOP_PLACE)
                .pointsTop(DEFAULT_POINTS_TOP)
                .relayPointsFirst(DEFAULT_RELAY_POINTS_FIRST)
                .relayPointsSecond(DEFAULT_RELAY_POINTS_SECOND)
                .relayPointsThird(DEFAULT_RELAY_POINTS_THIRD)
                .relayPointsTop(DEFAULT_RELAY_POINTS_TOP)
                .build();
        settings.fillBlanks();
        return settings;
    }

    /** Fills in any null or nonsensical value with its default. */
    public void fillBlanks() {
        if (id == null) id = SINGLETON_ID;
        if (sportDayTitle == null || sportDayTitle.isBlank()) {
            sportDayTitle = "田徑運動會記錄表 / Sport Day Marking Sheet";
        }
        trackMaxEntries = positiveOr(trackMaxEntries, DEFAULT_TRACK_MAX);
        fieldMaxEntries = positiveOr(fieldMaxEntries, DEFAULT_FIELD_MAX);
        pointsFirst = nonNegativeOr(pointsFirst, DEFAULT_POINTS_FIRST);
        pointsSecond = nonNegativeOr(pointsSecond, DEFAULT_POINTS_SECOND);
        pointsThird = nonNegativeOr(pointsThird, DEFAULT_POINTS_THIRD);
        pointsTopPlace = positiveOr(pointsTopPlace, DEFAULT_POINTS_TOP_PLACE);
        pointsTop = nonNegativeOr(pointsTop, DEFAULT_POINTS_TOP);
        relayPointsFirst = nonNegativeOr(relayPointsFirst, DEFAULT_RELAY_POINTS_FIRST);
        relayPointsSecond = nonNegativeOr(relayPointsSecond, DEFAULT_RELAY_POINTS_SECOND);
        relayPointsThird = nonNegativeOr(relayPointsThird, DEFAULT_RELAY_POINTS_THIRD);
        relayPointsTop = nonNegativeOr(relayPointsTop, DEFAULT_RELAY_POINTS_TOP);
    }

    private static Integer positiveOr(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }

    private static Integer nonNegativeOr(Integer value, int fallback) {
        return value == null || value < 0 ? fallback : value;
    }

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
        fillBlanks();
    }

    /**
     * Points for finishing in {@code place}. Places beyond
     * {@link #pointsTopPlace} score nothing, and a relay scores its own, larger,
     * scale.
     */
    @Transient
    public int pointsForPlace(int place, boolean relay) {
        if (place < 1) {
            return 0;
        }
        int first = relay ? relayPointsFirst : pointsFirst;
        int second = relay ? relayPointsSecond : pointsSecond;
        int third = relay ? relayPointsThird : pointsThird;
        int rest = relay ? relayPointsTop : pointsTop;

        return switch (place) {
            case 1 -> first;
            case 2 -> second;
            case 3 -> third;
            default -> place <= pointsTopPlace ? rest : 0;
        };
    }
}
