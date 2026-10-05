package com.sportday.service;

import com.sportday.entity.Event;
import com.sportday.entity.Grade;
import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamKind;
import com.sportday.entity.RelayTeamMember;
import com.sportday.entity.Sex;
import com.sportday.entity.User;
import com.sportday.repository.RelayTeamMemberRepository;
import com.sportday.repository.RelayTeamRepository;
import com.sportday.service.RelayReadiness.TeamState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The one readiness rule: <strong>a relay may be marked once it has two teams and every
 * team holds its runners.</strong>
 *
 * <p>It is a property of the event, so it is stated once — here — and mark entry and the
 * marking sheets both refuse a half-built relay in these words. The boundary is exact:
 * <em>two</em> teams of <em>four</em> runners is ready, and one team fewer or one runner
 * fewer is not.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayReadinessTest {

    private static final long EVENT_ID = 7L;

    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;

    private RelayReadiness readiness;
    private Event relay;

    private static TeamState team(long id, String label, long runners) {
        return new TeamState(id, label, runners);
    }

    private static RelayTeam relayTeam(long id, String label) {
        return RelayTeam.builder().id(id).kind(RelayTeamKind.FORM).teamKey(label).label(label).build();
    }

    private static RelayTeamMember leg(long id, RelayTeam team, long userId, int number) {
        return RelayTeamMember.builder().id(id).team(team).leg(number)
                .user(User.builder().id(userId).username("S" + userId).password("x")
                        .fullName("Runner " + userId).role(User.Role.STUDENT).enabled(true).build())
                .build();
    }

    /** Every leg of the two teams, team by team, leg by leg — what the board reads. */
    private static List<RelayTeamMember> legsOf(RelayTeam... teams) {
        List<RelayTeamMember> legs = new ArrayList<>();
        long memberId = 1L;
        long userId = 100L;
        for (RelayTeam team : teams) {
            for (int leg = 1; leg <= 4; leg++) {
                legs.add(leg(memberId++, team, userId++, leg));
            }
        }
        return legs;
    }

    @BeforeEach
    void setUp() {
        readiness = new RelayReadiness(relayTeamRepository, relayTeamMemberRepository);
        relay = Event.builder()
                .id(EVENT_ID)
                .name("Boys 4x100M Relay · A Grade")
                .type(Event.EventType.RELAY_4X100M)
                .category(Event.EventType.RELAY_4X100M.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.A)
                .relayTeamKind(RelayTeamKind.FORM)
                .build();
    }

    // -------------------------------------------------------- the event's teams

    @Test
    @DisplayName("a relay with no teams at all is not ready, and the reason says so")
    void noTeamsIsNotReady() {
        Optional<String> reason = RelayReadiness.shortfall(relay, List.of());

        assertEquals("Boys 4x100M Relay · A Grade has 0 team(s), and a relay needs at least 2 "
                + "before its marks can be entered. Build another team first.", reason.orElse(null));
    }

    @Test
    @DisplayName("one team is not ready, however full it is: one team is not a race")
    void oneTeamIsNotReady() {
        Optional<String> reason = RelayReadiness.shortfall(relay, List.of(team(1L, "5A", 4)));

        assertEquals("Boys 4x100M Relay · A Grade has 1 team(s), and a relay needs at least 2 "
                + "before its marks can be entered. Build another team first.", reason.orElse(null));
    }

    @Test
    @DisplayName("exactly two full teams is ready — the boundary")
    void exactlyTwoFullTeamsIsReady() {
        Optional<String> reason = RelayReadiness.shortfall(relay,
                List.of(team(1L, "5A", 4), team(2L, "5B", 4)));

        assertTrue(reason.isEmpty(), "2 teams of 4 is ready: " + reason.orElse(null));
        assertDoesNotThrow(() -> RelayReadiness.requireRelayIsReadyToMark(relay,
                List.of(team(1L, "5A", 4), team(2L, "5B", 4))));
    }

    @Test
    @DisplayName("two teams where one holds three runners is not ready, and names the short team")
    void aTeamOfThreeHoldsTheRelayBack() {
        Optional<String> reason = RelayReadiness.shortfall(relay,
                List.of(team(1L, "5A", 4), team(2L, "5B", 3)));

        assertEquals("5B has 3 of the 4 runners it needs, so Boys 4x100M Relay · A Grade cannot "
                + "be marked yet. Fill that team first.", reason.orElse(null));
    }

    @Test
    @DisplayName("a team with no runners yet holds the relay back too")
    void aTeamWithNoRunnersHoldsTheRelayBack() {
        Optional<String> reason = RelayReadiness.shortfall(relay,
                List.of(team(1L, "5A", 4), team(2L, "5B", 0)));

        assertEquals("5B has 0 of the 4 runners it needs, so Boys 4x100M Relay · A Grade cannot "
                + "be marked yet. Fill that team first.", reason.orElse(null));
    }

    @Test
    @DisplayName("three or more full teams are ready, and a fifth runner is no hindrance")
    void moreFullTeamsAreReady() {
        assertTrue(RelayReadiness.shortfall(relay,
                List.of(team(1L, "5A", 4), team(2L, "5B", 4), team(3L, "5C", 4))).isEmpty());
        // Four runners and a backup: a reserve makes the team five strong, not short.
        assertTrue(RelayReadiness.shortfall(relay,
                List.of(team(1L, "5A", 5), team(2L, "5B", 4))).isEmpty());
    }

    @Test
    @DisplayName("the race's own team size is the rule, not a hard-coded four")
    void theRacesOwnTeamSizeIsTheRule() {
        relay.setRelayTeamSize(3);

        assertTrue(RelayReadiness.shortfall(relay,
                List.of(team(1L, "5A", 3), team(2L, "5B", 3))).isEmpty(),
                "a race of three legs is ready with three runners a team");
        assertEquals("5B has 2 of the 3 runners it needs, so Boys 4x100M Relay · A Grade cannot "
                + "be marked yet. Fill that team first.",
                RelayReadiness.shortfall(relay,
                        List.of(team(1L, "5A", 3), team(2L, "5B", 2))).orElse(null));
    }

    // ------------------------------------------------ what the rule never gates

    @Test
    @DisplayName("an individual event is never gated, however few athletes it has")
    void anIndividualEventIsNeverGated() {
        Event sprint = Event.builder()
                .id(9L).name("Girls 100M · B Grade").type(Event.EventType.RUN_100M)
                .sex(Sex.FEMALE).grade(Grade.B).build();

        assertTrue(RelayReadiness.shortfall(sprint, List.of()).isEmpty(),
                "a race of athletes has no teams to be short of");
        assertTrue(RelayReadiness.shortfall(sprint, List.of(team(1L, "whatever", 0))).isEmpty());
        assertTrue(readiness.isReady(sprint));
        assertDoesNotThrow(() -> readiness.requireRelayIsReadyToMark(sprint));
        // The rule is the event's own, so nothing is read for a race of athletes.
        verifyNoInteractions(relayTeamRepository, relayTeamMemberRepository);
    }

    @Test
    @DisplayName("no event at all is ready: nothing to hold back")
    void noEventIsReady() {
        assertTrue(RelayReadiness.shortfall(null, List.of()).isEmpty());
        assertTrue(readiness.isReady(null));
        assertDoesNotThrow(() -> readiness.requireRelayIsReadyToMark(null));
    }

    // ---------------------------------------------- the rule over the register

    @Test
    @DisplayName("the lookup counts the runners each team really holds")
    void theLookupCountsTheRunners() {
        RelayTeam full = relayTeam(1L, "5A");
        RelayTeam shortTeam = relayTeam(2L, "5B");
        RelayTeam third = relayTeam(3L, "5C");
        when(relayTeamRepository.findByEventIdOrderByIdAsc(EVENT_ID))
                .thenReturn(List.of(full, shortTeam, third));

        List<RelayTeamMember> legs = new ArrayList<>(legsOf(full, third));
        legs.add(leg(99L, shortTeam, 555L, 1));
        legs.add(leg(100L, shortTeam, 556L, 2));
        when(relayTeamMemberRepository.findForEventWithUser(EVENT_ID)).thenReturn(legs);

        Optional<String> reason = readiness.shortfallOf(relay);

        assertEquals("5B has 2 of the 4 runners it needs, so Boys 4x100M Relay · A Grade cannot "
                + "be marked yet. Fill that team first.", reason.orElse(null));
        assertFalse(readiness.isReady(relay));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> readiness.requireRelayIsReadyToMark(relay));
        assertEquals(reason.orElse(null), error.getMessage(),
                "the refusal is the reason, in the same words");
    }

    @Test
    @DisplayName("a relay whose teams are all full is ready, read from the register")
    void aFullRelayIsReady() {
        RelayTeam first = relayTeam(1L, "5A");
        RelayTeam second = relayTeam(2L, "5B");
        when(relayTeamRepository.findByEventIdOrderByIdAsc(EVENT_ID))
                .thenReturn(List.of(first, second));
        when(relayTeamMemberRepository.findForEventWithUser(EVENT_ID))
                .thenReturn(legsOf(first, second));

        assertTrue(readiness.shortfallOf(relay).isEmpty());
        assertTrue(readiness.isReady(relay));
        assertDoesNotThrow(() -> readiness.requireRelayIsReadyToMark(relay));
    }
}
