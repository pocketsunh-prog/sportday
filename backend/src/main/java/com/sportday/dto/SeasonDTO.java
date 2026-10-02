package com.sportday.dto;

import com.sportday.entity.Season;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One school year's sport day. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeasonDTO {

    private Long id;

    /** The school year, e.g. 2026. */
    private Integer year;

    private String name;
    private String displayName;

    /** The day itself; grades are frozen against it. */
    private LocalDate sportDayDate;

    /** Whether students may enter events for this year. */
    private Boolean enrollmentOpen;

    private String notes;

    /** How many events belong to this year. */
    private Integer eventCount;

    /** True for the year the school is working on now. */
    private Boolean current;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Copy the catalogue of another year into this one — the usual way to start a
     * new season, so the events do not have to be recreated by hand.
     */
    private Long copyEventsFromSeasonId;

    public static SeasonDTO from(Season season, int eventCount, boolean current) {
        return SeasonDTO.builder()
                .id(season.getId())
                .year(season.getYear())
                .name(season.getName())
                .displayName(season.getDisplayName())
                .sportDayDate(season.getSportDayDate())
                .enrollmentOpen(season.getEnrollmentOpen())
                .notes(season.getNotes())
                .eventCount(eventCount)
                .current(current)
                .createdAt(season.getCreatedAt())
                .updatedAt(season.getUpdatedAt())
                .build();
    }
}
