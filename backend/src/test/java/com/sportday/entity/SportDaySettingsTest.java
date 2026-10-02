package com.sportday.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The points rule behind the personal and house championships.
 *
 * <p>Requirement: first = 9, second = 6, third = 3, top 8 = 1, and the relay on
 * its own scale of 30 / 20 / 10 — all editable by an administrator.</p>
 */
class SportDaySettingsTest {

    private SportDaySettings settings() {
        return SportDaySettings.defaults();
    }

    @Test
    @DisplayName("the documented defaults")
    void defaults() {
        SportDaySettings settings = settings();
        assertEquals(2, settings.getTrackMaxEntries());
        assertEquals(1, settings.getFieldMaxEntries());
        assertEquals(9, settings.getPointsFirst());
        assertEquals(6, settings.getPointsSecond());
        assertEquals(3, settings.getPointsThird());
        assertEquals(8, settings.getPointsTopPlace());
        assertEquals(1, settings.getPointsTop());
        assertEquals(30, settings.getRelayPointsFirst());
        assertEquals(20, settings.getRelayPointsSecond());
        assertEquals(10, settings.getRelayPointsThird());
    }

    @Test
    @DisplayName("an individual event scores 9 / 6 / 3 and then 1 down to eighth")
    void individualScale() {
        SportDaySettings settings = settings();
        assertEquals(9, settings.pointsForPlace(1, false));
        assertEquals(6, settings.pointsForPlace(2, false));
        assertEquals(3, settings.pointsForPlace(3, false));
        // "top 8 = 1" — every place from fourth to eighth is worth one point.
        for (int place = 4; place <= 8; place++) {
            assertEquals(1, settings.pointsForPlace(place, false), "place " + place);
        }
        assertEquals(0, settings.pointsForPlace(9, false));
        assertEquals(0, settings.pointsForPlace(41, false));
    }

    @Test
    @DisplayName("a relay scores 30 / 20 / 10 and then 1 down to eighth")
    void relayScale() {
        SportDaySettings settings = settings();
        assertEquals(30, settings.pointsForPlace(1, true));
        assertEquals(20, settings.pointsForPlace(2, true));
        assertEquals(10, settings.pointsForPlace(3, true));
        for (int place = 4; place <= 8; place++) {
            assertEquals(1, settings.pointsForPlace(place, true), "place " + place);
        }
        assertEquals(0, settings.pointsForPlace(9, true));
    }

    @Test
    @DisplayName("an administrator's own scale is used instead of the defaults")
    void customScale() {
        SportDaySettings settings = settings();
        settings.setPointsFirst(12);
        settings.setPointsSecond(8);
        settings.setPointsThird(5);
        settings.setPointsTopPlace(6);
        settings.setPointsTop(2);
        settings.setRelayPointsFirst(50);

        assertEquals(12, settings.pointsForPlace(1, false));
        assertEquals(8, settings.pointsForPlace(2, false));
        assertEquals(5, settings.pointsForPlace(3, false));
        assertEquals(2, settings.pointsForPlace(6, false));
        assertEquals(0, settings.pointsForPlace(7, false), "seventh is past the configured top place");
        assertEquals(50, settings.pointsForPlace(1, true));
    }

    @Test
    @DisplayName("a place below first scores nothing")
    void nonsensePlacesScoreNothing() {
        SportDaySettings settings = settings();
        assertEquals(0, settings.pointsForPlace(0, false));
        assertEquals(0, settings.pointsForPlace(-3, false));
        assertEquals(0, settings.pointsForPlace(0, true));
    }

    @Test
    @DisplayName("blank or nonsensical values fall back to the defaults")
    void fillBlanksRepairsTheRow() {
        SportDaySettings settings = SportDaySettings.builder()
                .id(1L)
                .trackMaxEntries(null)
                .fieldMaxEntries(0)
                .pointsFirst(-5)
                .pointsSecond(null)
                .pointsThird(null)
                .pointsTopPlace(null)
                .pointsTop(null)
                .relayPointsFirst(null)
                .relayPointsSecond(null)
                .relayPointsThird(null)
                .relayPointsTop(null)
                .build();

        settings.fillBlanks();

        assertEquals(2, settings.getTrackMaxEntries(), "a null entry limit falls back to 2");
        assertEquals(1, settings.getFieldMaxEntries(), "an entry limit cannot be zero");
        assertEquals(9, settings.getPointsFirst(), "negative points fall back");
        assertEquals(6, settings.getPointsSecond());
        assertEquals(8, settings.getPointsTopPlace());
        assertEquals(30, settings.getRelayPointsFirst());
    }

    @Test
    @DisplayName("zero is a legitimate setting — a school may not score a place at all")
    void zeroIsAllowed() {
        SportDaySettings settings = settings();
        settings.setPointsTop(0);
        settings.setRelayPointsThird(0);

        assertEquals(0, settings.pointsForPlace(5, false));
        assertEquals(0, settings.pointsForPlace(3, true));
        // The places that do score are unaffected.
        assertEquals(9, settings.pointsForPlace(1, false));
    }
}
