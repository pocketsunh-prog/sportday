package com.sportday.service;

import com.sportday.dto.EventResultDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventResult;
import com.sportday.entity.EventStage;
import com.sportday.entity.Grade;
import com.sportday.entity.RelayTeam;
import com.sportday.entity.Sex;
import com.sportday.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A relay result names the <strong>team</strong>.
 *
 * <p>Requirement: <em>"mark result on relay should be display team name not student
 * name"</em>. A 4x100M is run and scored by team, so the row of a results API is the
 * team's time and must carry the team's own name — {@code 1A}, {@code B Grade Green}.
 * The row still carries the athlete it is anchored to, because
 * {@code event_results.user_id} is not nullable and every reader joins on it, and
 * because "what did this athlete run?" is a question the app still answers.</p>
 *
 * <p>An <strong>individual</strong> result must be completely unchanged — that is the
 * regression that matters, so it is asserted beside the relay case.</p>
 */
class RelayResultTeamNameTest {

    private static Event relay() {
        Event event = Event.builder()
                .id(2L)
                .name("Boys 4x100M Relay · B Grade")
                .type(Event.EventType.RELAY_4X100M)
                .category(Event.EventType.RELAY_4X100M.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.B)
                .groupSize(24)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .build();
        event.applyTypeDefaults();
        return event;
    }

    private static Event sprint() {
        Event event = Event.builder()
                .id(1L)
                .name("Boys 100M · B Grade")
                .type(Event.EventType.RUN_100M)
                .category(Event.EventType.RUN_100M.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.B)
                .groupSize(8)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .build();
        event.applyTypeDefaults();
        return event;
    }

    private static User athlete(long id, String name) {
        return User.builder().id(id).username("S%04d".formatted(id)).fullName(name).build();
    }

    @Test
    @DisplayName("a relay row carries the team's own name, and keeps the athlete it hangs off")
    void aRelayRowNamesTheTeam() {
        RelayTeam team = RelayTeam.builder()
                .id(70L).event(relay()).teamKey("Green").label("B Grade Green").build();
        EventResult result = EventResult.builder()
                .id(5L)
                .user(athlete(21, "Chan Tai Man"))
                .event(relay())
                .relayTeam(team)
                .stage(EventStage.HEAT)
                .mark(new BigDecimal("44.500"))
                .unit("s")
                .build();

        EventResultDTO dto = EventResultDTO.from(result);

        assertEquals(70L, dto.getTeamId());
        assertEquals("B Grade Green", dto.getTeamLabel(), "the school's own name for the team");
        assertEquals("0.44.500s", dto.getDisplayMark(),
                "and the team's time reads in the school's own shape");
        // The anchor is still named: the athlete's own page legitimately answers "what
        // did this athlete run?" with the team beside it.
        assertEquals(21L, dto.getUserId());
        assertEquals("S0021", dto.getUsername());
        assertEquals("Chan Tai Man", dto.getFullName());
    }

    @Test
    @DisplayName("a class team is named the way the school names it — 1A")
    void aClassTeamIsNamedByItsClass() {
        RelayTeam team = RelayTeam.builder()
                .id(71L).event(relay()).teamKey("1A").label("1A").build();
        EventResult result = EventResult.builder()
                .id(6L)
                .user(athlete(22, "Lee Siu Ming"))
                .event(relay())
                .relayTeam(team)
                .stage(EventStage.HEAT)
                .mark(new BigDecimal("48.123"))
                .unit("s")
                .build();

        EventResultDTO dto = EventResultDTO.from(result);

        assertEquals("1A", dto.getTeamLabel());
        assertEquals("0.48.123s", dto.getDisplayMark());
    }

    @Test
    @DisplayName("an individual result is unchanged: no team, and the athlete's own mark")
    void anIndividualRowIsUnchanged() {
        EventResult result = EventResult.builder()
                .id(7L)
                .user(athlete(21, "Chan Tai Man"))
                .event(sprint())
                .stage(EventStage.HEAT)
                .mark(new BigDecimal("11.860"))
                .unit("s")
                .build();

        EventResultDTO dto = EventResultDTO.from(result);

        assertNull(dto.getTeamId(), "an individual event has no team");
        assertNull(dto.getTeamLabel());
        assertEquals(21L, dto.getUserId());
        assertEquals("Chan Tai Man", dto.getFullName());
        assertEquals("11.86s", dto.getDisplayMark(),
                "a sprint is still the seconds it is, trimmed of trailing zeros");
    }

    @Test
    @DisplayName("a relay row with no team on file falls back to the athlete, it does not invent one")
    void aRelayRowWithNoTeamFallsBackToTheAthlete() {
        // A row written before the teams existed, or one whose team was deleted (the
        // foreign key is ON DELETE SET NULL). There is no team to name, so the honest
        // answer is the athlete the row hangs off — the data is the parent's to clean
        // up, not something to guess at here.
        EventResult result = EventResult.builder()
                .id(8L)
                .user(athlete(23, "Wong Ka Ming"))
                .event(relay())
                .stage(EventStage.HEAT)
                .mark(new BigDecimal("45.000"))
                .unit("s")
                .build();

        EventResultDTO dto = EventResultDTO.from(result);

        assertNull(dto.getTeamId());
        assertNull(dto.getTeamLabel());
        assertEquals("Wong Ka Ming", dto.getFullName(), "nothing is invented");
    }
}
