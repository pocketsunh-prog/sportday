package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One school year's sport day.
 *
 * <p>Every event belongs to a season, so the programme can be worked through a
 * year at a time and previous years can be looked back at without them mixing into
 * this year's entries and results.</p>
 *
 * <p>{@link #enrollmentOpen} is the switch that lets students enter: closed once
 * entries are in and the heats are being drawn, opened again if the school reopens
 * them. Only one season is normally open at a time — see
 * {@code SeasonService.activate}.</p>
 */
@Entity
@Table(name = "seasons", uniqueConstraints = {
    @UniqueConstraint(name = "uk_season_year", columnNames = {"year"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Season {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The school year, e.g. 2026. */
    @Column(name = "year", nullable = false)
    private Integer year;

    /** What the school calls the day, e.g. "2026 Sports Day". */
    @Column(length = 120)
    private String name;

    /** The day itself. Grades are frozen against it. */
    @Column(name = "sport_day_date")
    private LocalDate sportDayDate;

    /** Whether students may enter events for this year. */
    @Column(name = "enrollment_open", nullable = false)
    private Boolean enrollmentOpen;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (enrollmentOpen == null) enrollmentOpen = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** What the school calls the day, falling back to the year. */
    @Transient
    public String getDisplayName() {
        return name == null || name.isBlank() ? year + " Sports Day" : name;
    }
}
