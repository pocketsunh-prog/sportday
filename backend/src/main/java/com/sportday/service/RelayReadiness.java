package com.sportday.service;

import com.sportday.entity.Event;
import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamMember;
import com.sportday.repository.RelayTeamMemberRepository;
import com.sportday.repository.RelayTeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One rule, in one place: <strong>a relay may only be marked once it is ready.</strong>
 *
 * <p>A relay is run by teams, and one time is written for a whole team. Until the
 * school has built the teams the race is not a race: a single team has nobody to race
 * against, and a team of two runners cannot run four legs. Marking a race like that
 * would either write one team's time into an empty field or leave a team's leg unfilled
 * with nothing on the sheet to say so.</p>
 *
 * <p>So a relay is <strong>ready</strong> when both of these hold:</p>
 * <ol>
 *   <li>it has at least {@value #MINIMUM_TEAMS} teams — one team is not a race; and</li>
 *   <li><strong>every</strong> team holds at least
 *       {@link Event#getEffectiveRelayTeamSize()} runners — four for a 4x100M or a
 *       4x400M. <em>One</em> short team holds the whole relay back.</li>
 * </ol>
 *
 * <p>The rule is a property of the <em>event</em>, not of a sheet or a grid: it is
 * stated once here and the wording is stated once here, so mark entry and the marking
 * sheets — which are built by different services and must not own one another — cannot
 * drift apart about when a relay may be marked. The callers are:</p>
 *
 * <ul>
 *   <li>the mark-entry grid and its save — {@link MarkEntryService};</li>
 *   <li>a not-ready relay is left out of the whole-programme print run as the DTOs are
 *       gathered, and a print run that names one relay is refused —
 *       {@link EventGroupService} and {@code EventGroupController}.</li>
 * </ul>
 *
 * <p>Every refusal is an {@link IllegalStateException}, which the application's
 * {@code GlobalExceptionHandler} turns into <strong>409 Conflict</strong> — the status
 * this codebase already uses for "the request is understood, but the programme is not
 * in a state where it can be honoured". The message travels to the client as written,
 * in the plain style the rest of the product uses, and it names what is missing so the
 * person is told why rather than shown an empty page.</p>
 *
 * <h2>What is not this rule's business</h2>
 * <p>A team short of its runners is still <strong>saved</strong>: the runners are
 * collected one at a time, and refusing to store an incomplete team would make the
 * team impossible to finish. {@code RelayTeamService} owns that half. This rule only
 * decides when the race has enough teams to be <em>marked</em>, which is why it is
 * asked where marks are read, written and printed and nowhere on the team builder.</p>
 *
 * <p>An individual event is never gated: the rule is about a relay's teams, and a race
 * of athletes has none. Every method here answers "ready" for anything that is not a
 * relay — including a null event — so no caller has to ask the question first.</p>
 *
 * <h2>Why the rule is static</h2>
 * <p>The rule is about the event and its teams and nothing else, so the method that
 * states it is static: a caller that already holds the teams and their runner counts —
 * as the mark grid does — does not need this component injected to ask. The component
 * exists for what a caller cannot answer on its own: reading the event's teams, and how
 * many runners each holds. That split mirrors {@link FinalStageGuard}, so both gates
 * read the same way.</p>
 */
@Component
@RequiredArgsConstructor
public class RelayReadiness {

    /**
     * The fewest teams a relay needs before any of its marks can be entered —
     * <strong>two</strong>: a race of one team has nobody to race against.
     */
    public static final int MINIMUM_TEAMS = 2;

    /**
     * A relay with too few teams. {@code %d} is how many it has, and the event's own
     * name goes in front of it.
     */
    public static final String NEEDS_TWO_TEAMS =
            " has %d team(s), and a relay needs at least 2 before its marks can be entered. "
                    + "Build another team first.";

    /**
     * A team short of its runners. The first {@code %d} is how many it holds, the second
     * how many the race has, and {@code %s} is the relay's own name.
     */
    public static final String SHORT_TEAM =
            " has %d of the %d runners it needs, so %s cannot be marked yet. Fill that team first.";

    private final RelayTeamRepository relayTeamRepository;
    private final RelayTeamMemberRepository relayTeamMemberRepository;

    /**
     * One team of a relay and how many runners it holds — the facts the rule judges.
     *
     * <p>Read from {@link RelayTeamMemberRepository} rather than from the team's own
     * {@code members} collection, exactly as the relay board reads its runners, so a
     * team is never judged from a collection a write has just left stale.</p>
     */
    public record TeamState(Long teamId, String label, long runners) {

        /** True when this team is short of the legs the race has. */
        public boolean isShortOf(int legs) {
            return runners < legs;
        }
    }

    // ------------------------------------------------------------- the lookups

    /**
     * The event's teams, each with the number of runners it holds.
     *
     * <p>One query for the teams and one for every leg of the event, so a whole relay
     * costs two reads however many teams it has. An event that is not a relay has no
     * teams to describe and costs nothing.</p>
     */
    @Transactional(readOnly = true)
    public List<TeamState> statesOf(Event event) {
        if (event == null || !event.isRelay() || event.getId() == null) {
            return List.of();
        }
        return statesOf(event.getId(), relayTeamRepository.findByEventIdOrderByIdAsc(event.getId()));
    }

    /**
     * The state of teams the caller has already read.
     *
     * <p>For a caller that holds the teams anyway — the mark grid reads them to build
     * its rows — so the event is not read twice. The runner counts still come from the
     * leg rows, which is the one place a team's size is really stored.</p>
     */
    @Transactional(readOnly = true)
    public List<TeamState> statesOf(Long eventId, List<RelayTeam> teams) {
        if (teams == null || teams.isEmpty() || eventId == null) {
            return List.of();
        }
        Map<Long, Long> runnersByTeam = runnersByTeam(eventId);
        List<TeamState> states = new ArrayList<>(teams.size());
        for (RelayTeam team : teams) {
            states.add(new TeamState(team.getId(), team.getLabel(),
                    runnersByTeam.getOrDefault(team.getId(), 0L)));
        }
        return states;
    }

    /** How many runners each team of an event holds, in one query for the event. */
    private Map<Long, Long> runnersByTeam(Long eventId) {
        Map<Long, Long> runners = new HashMap<>();
        for (RelayTeamMember member : relayTeamMemberRepository.findForEventWithUser(eventId)) {
            if (member.getTeam() != null && member.getTeam().getId() != null) {
                runners.merge(member.getTeam().getId(), 1L, Long::sum);
            }
        }
        return runners;
    }

    // --------------------------------------------------------------- the asks

    /**
     * True when the event may be marked and printed. An individual event — and a null
     * event — is always ready, so a caller never has to check what it is holding.
     */
    @Transactional(readOnly = true)
    public boolean isReady(Event event) {
        return shortfallOf(event).isEmpty();
    }

    /**
     * Why the relay cannot be marked yet, or empty when it can.
     *
     * <p>The reason is the wording the client is shown, so it is built once, here.</p>
     */
    @Transactional(readOnly = true)
    public Optional<String> shortfallOf(Event event) {
        if (event == null || !event.isRelay()) {
            return Optional.empty();
        }
        return shortfall(event, statesOf(event));
    }

    /**
     * Refuses the call unless the event's relay is ready.
     *
     * <p>The form for a caller that holds only the event: it reads the teams itself and
     * then applies the one rule. An individual event passes through untouched.</p>
     *
     * @throws IllegalStateException with the reason — the handler turns it into a 409
     */
    public void requireRelayIsReadyToMark(Event event) {
        shortfallOf(event).ifPresent(RelayReadiness::refuse);
    }

    // -------------------------------------------------------------- the rule

    /**
     * The one readiness rule, over the facts a caller already holds.
     *
     * <p>Too few teams is checked first: a relay with one team of four is refused for
     * having <em>one team</em>, not for that team being short, because building another
     * team is the next thing to do either way. Otherwise every team is judged, and the
     * first one short of the race's legs is named — the school fills that team, and the
     * next attempt names the next one.</p>
     *
     * @return the reason the relay cannot be marked yet, or empty when it can — and
     *         always empty for an event that is not a relay
     */
    public static Optional<String> shortfall(Event event, List<TeamState> teams) {
        if (event == null || !event.isRelay()) {
            return Optional.empty();
        }
        List<TeamState> all = teams == null ? List.of() : teams;
        String relay = nameOf(event);
        if (all.size() < MINIMUM_TEAMS) {
            return Optional.of(relay + String.format(NEEDS_TWO_TEAMS, all.size()));
        }
        int legs = event.getEffectiveRelayTeamSize();
        for (TeamState team : all) {
            if (team.isShortOf(legs)) {
                return Optional.of(labelOf(team) + String.format(SHORT_TEAM, team.runners(), legs, relay));
            }
        }
        return Optional.empty();
    }

    /**
     * Refuses the call unless the relay the facts describe is ready — the static half of
     * {@link #requireRelayIsReadyToMark(Event)}, for a caller that has already read the
     * teams.
     *
     * @throws IllegalStateException with the reason — the handler turns it into a 409
     */
    public static void requireRelayIsReadyToMark(Event event, List<TeamState> teams) {
        shortfall(event, teams).ifPresent(RelayReadiness::refuse);
    }

    private static void refuse(String reason) {
        throw new IllegalStateException(reason);
    }

    /** The event as a refusal names it: its own name, or "This relay" when it has none. */
    private static String nameOf(Event event) {
        String name = event.getName();
        return name == null || name.isBlank() ? "This relay" : name;
    }

    /** The team as a refusal names it: its label, or "A team" when it has none. */
    private static String labelOf(TeamState team) {
        String label = team.label();
        return label == null || label.isBlank() ? "A team" : label;
    }
}
