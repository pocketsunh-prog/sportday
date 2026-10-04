package com.sportday.controller;

import com.sportday.dto.RelayEventTeamsDTO;
import com.sportday.dto.RelayLegOrderRequest;
import com.sportday.dto.RelayRunnerRequest;
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
 * <p>Requirement 3: the form relay is team per form and the house relay is a team
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

    @Operation(summary = "An event's relay teams",
            description = "Every team of a relay event with the runners down for its legs: one team "
                    + "per form for a form relay, or one per house of the event's grade for a house "
                    + "relay. A relay with no team kind is undivided and reports no teams. An event "
                    + "that is not a relay at all is refused.")
    @GetMapping("/events/{eventId}/relay-teams")
    public ResponseEntity<RelayEventTeamsDTO> teams(@PathVariable Long eventId) {
        return ResponseEntity.ok(relayTeamService.getBoard(eventId));
    }

    @Operation(summary = "Create the event's teams from the roster",
            description = "Creates one team per form, or per house, among the students of the "
                    + "event's own grade and division — the same students an entry is judged "
                    + "eligible by. Additive: teams already there are kept and their labels "
                    + "refreshed, and a team somebody already runs in is never removed. Pass "
                    + "prune=true to also drop teams that are empty and no longer on the roster.")
    @PostMapping("/events/{eventId}/relay-teams/derive")
    public ResponseEntity<RelayTeamDerivationDTO> deriveTeams(
            @PathVariable Long eventId,
            @RequestParam(required = false, defaultValue = "false") boolean prune) {
        return ResponseEntity.ok(relayTeamService.deriveTeams(eventId, prune));
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
}
