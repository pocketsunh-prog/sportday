package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The championship tables, and the event standings they were calculated from.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChampionsDTO {

    /** The day the tables were calculated for. */
    private LocalDate referenceDate;

    /** How many events contributed points. */
    private int eventsScored;

    /** The stage that decided the points — {@code FINAL} where one was run, else {@code HEAT}. */
    private String scoringStageNote;

    /** Points each placing is worth, so the page can show the scale it used. */
    private SportDaySettingsDTO settings;

    @Builder.Default
    private List<PersonalChampionDTO> personal = new ArrayList<>();

    @Builder.Default
    private List<HouseChampionDTO> houses = new ArrayList<>();

    /** Per-event placings behind the totals. */
    @Builder.Default
    private List<EventStandingsDTO> events = new ArrayList<>();

    /** One athlete's line in the personal championship. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PersonalChampionDTO {
        private int rank;
        private Long userId;
        private String studentRef;
        private String name;
        private String grade;
        private String className;

        /** The form the class belongs to — {@code 5} for {@code 5A}; null when it names none. */
        private String form;

        private String house;

        /** The house's short code — {@code R}, {@code Y}, {@code B}, {@code G}; null for another house. */
        private String houseCode;

        private int points;
        private int golds;
        private int silvers;
        private int bronzes;
        /** Individual events that scored, relays excluded. */
        private int eventsScored;
    }

    /** One house's line in the house championship. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class HouseChampionDTO {
        private int rank;
        private String house;
        private int points;
        private int golds;
        private int silvers;
        private int bronzes;
        /** Athletes from this house who scored. */
        private int athletes;
    }

    /** The placings of one event. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EventStandingsDTO {
        private Long eventId;
        private String eventName;
        private String eventType;
        private String eventTypeLabel;
        private String category;
        private String categoryLabel;
        private String sex;
        private String sexLabel;
        private LocalDate eventDate;

        /** {@code FINAL} or {@code HEAT} — whichever decided the points. */
        private String scoringStage;
        private boolean relay;
        private boolean hasFinal;
        private String sheetSize;

        @Builder.Default
        private List<PlacingDTO> placings = new ArrayList<>();
    }

    /** One athlete's finishing position in one event. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlacingDTO {
        private int place;
        private Long userId;
        private String studentRef;
        private String name;

        /**
         * The relay team this placing is <em>for</em>, and the team's own name as the
         * school writes it — {@code 1A}, {@code B Grade Green}. Both null on an
         * individual event's placing.
         *
         * <p><strong>A relay is run and scored by team</strong>, so a relay's placings
         * read by the team and not by the runner the row happens to hang off. On such
         * a row {@link #name} is the team's name too — every existing consumer of a
         * placings table therefore names the team without being taught about it — and
         * {@link #studentRef}, {@link #grade}, {@link #className} and {@link #form} are
         * left empty, exactly as the marking sheet and the mark grid leave a team
         * line's identity cells blank. {@link #house} is kept, because that is what a
         * relay's points count for.</p>
         */
        private Long teamId;
        private String teamLabel;
        private String grade;
        private String className;

        /** The form the class belongs to — {@code 5} for {@code 5A}; null when it names none. */
        private String form;

        private String house;

        /** The house's short code — {@code R}, {@code Y}, {@code B}, {@code G}; null for another house. */
        private String houseCode;

        private BigDecimal mark;
        private String unit;

        /** The mark as it reads, with its unit: {@code 14.123s}, {@code 1.04.123s}. */
        private String displayMark;
        private int points;

        /** True when this placing also holds the school record. */
        private boolean schoolRecord;
    }
}
