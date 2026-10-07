package com.sportday.controller;

import com.sportday.dto.RelayEventTeamsDTO;
import com.sportday.dto.RelayLegOrderRequest;
import com.sportday.dto.RelayRenameRequest;
import com.sportday.dto.RelayRunnerRequest;
import com.sportday.dto.RelayTeamCreateRequest;
import com.sportday.dto.RelayTeamDTO;
import com.sportday.dto.RelayTeamDerivationDTO;
import com.sportday.service.RelayTeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Relay teams, as a teacher helps pick them.
 *
 * <p>Requirement 3: the form relay is a team per class and the house relay is a team
 * per house within the event's grade, and a teacher helps select which students run.
 * A teacher may only name a runner from a class assigned to them; an administrator
 * may name anybody. That rule is not enforced here — it is enforced once, in
 * {@link RelayTeamService}, so it cannot be bypassed by calling a different
 * endpoint.</p>
 *
 * <p>The endpoints are a family of their own under {@code /api/teacher/**}, which
 * {@code SecurityConfig} already maps to ADMIN or TEACHER, so a teacher reaches
 * these and nothing of the administrative side. The administrative family lives
 * under {@code /api/admin/**} and is ADMIN only.</p>
 */
@Tag(name = "Teacher — relay teams",
        description = "The form and house relay teams of an event, and the students who run in them "
                + "(ADMIN or TEACHER; a teacher only for their own classes)")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api/teacher")
@PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
@RequiredArgsConstructor
public class RelayTeamController {

    private final RelayTeamService relayTeamService;

    @Operation(summary = "An event's relay teams, and the students who applied to it",
            description = "Every team of a relay event with the runners down for its legs: one team "
                    + "per class a form relay's entrants are in, or one per house of the event's "
                    + "grade for a house "
                    + "relay. A relay with no team kind is undivided and reports no teams. An event "
                    + "that is not a relay at all is refused. Beside the teams, the applicants: "
                    + "every student with a confirmed entry in the event, with form, class, house "
                    + "and house code and the team each is already on — for a teacher, only the "
                    + "applicants of their own classes, because those are the ones they may place. "
                    + "A house team spans classes, so its other runners are shown on the team "
                    + "itself but their names are not offered to a teacher as somebody to place.")
    @GetMapping("/events/{eventId}/relay-teams")
    public ResponseEntity<RelayEventTeamsDTO> teams(@PathVariable Long eventId) {
        return ResponseEntity.ok(relayTeamService.getBoard(eventId));
    }

    @Operation(summary = "Create the event's teams from its entries",
            description = "Creates one team per class the relay's entrants are in — a form relay's "
                    + "teams are the classes its confirmed entrants belong to, not the form's first "
                    + "two classes — or one team per house, among the students of the event's own "
                    + "scope and division. Fewer than two classes with an entrant is not an error: "
                    + "the team or teams are still made and the relay is simply not ready to mark. "
                    + "Additive: teams already there are kept and "
                    + "their labels refreshed, and a team somebody already runs in is never "
                    + "removed. Pass prune=true to also drop teams that are empty and no longer "
                    + "called for by the entries.")
    @PostMapping("/events/{eventId}/relay-teams/derive")
    public ResponseEntity<RelayTeamDerivationDTO> deriveTeams(
            @PathVariable Long eventId,
            @RequestParam(required = false, defaultValue = "false") boolean prune) {
        return ResponseEntity.ok(relayTeamService.deriveTeams(eventId, prune));
    }

    @Operation(summary = "Create a team by hand out of chosen students",
            description = "Requirement: a teacher ticks the students who applied to the relay and "
                    + "creates a team from them under a name they type — 1A, B Grade Yellow, "
                    + "anything the school writes. The team is deliberately not required to be "
                    + "one class or one house: a hand-made team carries a free-text name and "
                    + "the students chosen for it, in the order they are to run, so the first "
                    + "student listed runs leg 1. Every eligibility rule still applies — the "
                    + "event's division and grade, one leg per athlete per event, four runners "
                    + "and at most one reserve — and, for a teacher, every chosen student must "
                    + "be in a class assigned to them. The name is trimmed, must not be blank, "
                    + "is at most 40 characters and must be unique within the event. A team "
                    + "made this way is never matched, renamed or pruned by a later derive.")
    @PostMapping("/relay-events/{eventId}/teams")
    public ResponseEntity<RelayTeamDTO> createTeam(@PathVariable Long eventId,
                                                   @RequestBody RelayTeamCreateRequest request) {
        return ResponseEntity.ok(relayTeamService.createTeam(eventId, request));
    }

    @Operation(summary = "Name a runner for a leg",
            description = "Refused unless the athlete is on this year's list, in the event's own "
                    + "division and grade, in the team's form or house, not already running in "
                    + "another team of the event, and the team still has a leg free — and unless "
                    + "the caller may help that student, which for a teacher means one of their "
                    + "own classes. `leg` is optional: without it the next free leg is taken.")
    @PostMapping("/relay-teams/{teamId}/runners")
    public ResponseEntity<RelayTeamDTO> addRunner(@PathVariable Long teamId,
                                                  @RequestBody RelayRunnerRequest request) {
        RelayRunnerRequest body = request == null ? new RelayRunnerRequest() : request;
        return ResponseEntity.ok(
                relayTeamService.addRunner(teamId, body.getUserId(), body.getLeg()));
    }

    @Operation(summary = "Remove a runner",
            description = "Takes the athlete out of the team and closes the legs up behind them, so "
                    + "the running order never has a hole. Refused for a student the caller may "
                    + "not help.")
    @DeleteMapping("/relay-teams/{teamId}/runners/{userId}")
    public ResponseEntity<RelayTeamDTO> removeRunner(@PathVariable Long teamId,
                                                     @PathVariable Long userId) {
        return ResponseEntity.ok(relayTeamService.removeRunner(teamId, userId));
    }

    @Operation(summary = "Set the running order",
            description = "Leg 1 first. The list must name exactly the runners the team already "
                    + "has — adding or removing a runner is its own request — and, for a teacher, "
                    + "every one of them must be in a class assigned to them.")
    @PutMapping("/relay-teams/{teamId}/legs")
    public ResponseEntity<RelayTeamDTO> reorderLegs(@PathVariable Long teamId,
                                                    @RequestBody RelayLegOrderRequest request) {
        List<Long> order = request == null ? null : request.getUserIds();
        return ResponseEntity.ok(relayTeamService.reorderLegs(teamId, order));
    }

    @Operation(summary = "Rename a relay team",
            description = "The name the school writes on the sheet — 1A, C Grade Yellow. "
                    + "An administrator may rename any team; a teacher only one from their "
                    + "own classes, and a house team only while one of their own athletes is "
                    + "named on it.")
    @PutMapping("/relay-teams/{teamId}/name")
    public ResponseEntity<RelayTeamDTO> renameTeam(@PathVariable Long teamId,
                                                   @RequestBody RelayRenameRequest request) {
        return ResponseEntity.ok(relayTeamService.renameTeam(
                teamId, request == null ? null : request.getName()));
    }
}
