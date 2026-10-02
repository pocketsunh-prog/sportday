package com.sportday.dto;

import com.sportday.entity.SportDaySettings;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * The school's editable rules: how many events a student may enter, and what each
 * placing is worth.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SportDaySettingsDTO {

    // ---- the school itself ----

    private String schoolName;
    private String schoolNameZh;
    private String address;
    private String principal;
    private String sportDayTitle;

    // ---- entry limits ----

    private Integer trackMaxEntries;
    private Integer fieldMaxEntries;

    private Integer pointsFirst;
    private Integer pointsSecond;
    private Integer pointsThird;

    /** The lowest place that still scores — 8, so 4th to 8th score. */
    private Integer pointsTopPlace;
    private Integer pointsTop;

    private Integer relayPointsFirst;
    private Integer relayPointsSecond;
    private Integer relayPointsThird;
    private Integer relayPointsTop;

    private LocalDateTime updatedAt;

    public static SportDaySettingsDTO from(SportDaySettings settings) {
        return SportDaySettingsDTO.builder()
                .schoolName(settings.getSchoolName())
                .schoolNameZh(settings.getSchoolNameZh())
                .address(settings.getAddress())
                .principal(settings.getPrincipal())
                .sportDayTitle(settings.getSportDayTitle())
                .trackMaxEntries(settings.getTrackMaxEntries())
                .fieldMaxEntries(settings.getFieldMaxEntries())
                .pointsFirst(settings.getPointsFirst())
                .pointsSecond(settings.getPointsSecond())
                .pointsThird(settings.getPointsThird())
                .pointsTopPlace(settings.getPointsTopPlace())
                .pointsTop(settings.getPointsTop())
                .relayPointsFirst(settings.getRelayPointsFirst())
                .relayPointsSecond(settings.getRelayPointsSecond())
                .relayPointsThird(settings.getRelayPointsThird())
                .relayPointsTop(settings.getRelayPointsTop())
                .updatedAt(settings.getUpdatedAt())
                .build();
    }

    /**
     * Applies this payload onto a settings row. Fields left null keep their
     * current value, so a partial update is safe.
     */
    public void applyTo(SportDaySettings settings) {
        if (schoolName != null) settings.setSchoolName(schoolName);
        if (schoolNameZh != null) settings.setSchoolNameZh(schoolNameZh);
        if (address != null) settings.setAddress(address);
        if (principal != null) settings.setPrincipal(principal);
        if (sportDayTitle != null) settings.setSportDayTitle(sportDayTitle);
        if (trackMaxEntries != null) settings.setTrackMaxEntries(trackMaxEntries);
        if (fieldMaxEntries != null) settings.setFieldMaxEntries(fieldMaxEntries);
        if (pointsFirst != null) settings.setPointsFirst(pointsFirst);
        if (pointsSecond != null) settings.setPointsSecond(pointsSecond);
        if (pointsThird != null) settings.setPointsThird(pointsThird);
        if (pointsTopPlace != null) settings.setPointsTopPlace(pointsTopPlace);
        if (pointsTop != null) settings.setPointsTop(pointsTop);
        if (relayPointsFirst != null) settings.setRelayPointsFirst(relayPointsFirst);
        if (relayPointsSecond != null) settings.setRelayPointsSecond(relayPointsSecond);
        if (relayPointsThird != null) settings.setRelayPointsThird(relayPointsThird);
        if (relayPointsTop != null) settings.setRelayPointsTop(relayPointsTop);
        settings.fillBlanks();
    }
}
