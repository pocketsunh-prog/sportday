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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relay teams, on the administrative side (ADMIN only).
 *
 * <p>The same operations a teacher has under {@code /api/teacher/**}, plus the one
 * thing that is not a teacher's to do: removing every team of an event, which is what
 * frees the event to change what kind of relay it is, or to have its selections
 * started again.</p>
 *
 * <p>Both families call the same service, so an administrator is let through the
 * class rule by the rule itself rather than by a second, looser copy of it.</p>
 */
@Tag(name = "Admin — relay teams",
        description = "Form and house relay teams for every student, and the administrative "
                + "removal of an event's teams (ADMIN only)")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminRelayTeamController {

    private final RelayTeamService relayTeamService;

    @Operation(summary = "An event's relay teams",
            description = "Every team of a relay event with its runners, for an administrator, who "
                    + "may see and fill any of them.")
    @GetMapping("/events/{eventId}/relay-teams")
    public ResponseEntity<RelayEventTeamsDTO> teams(@PathVariable Long eventId) {
        return ResponseEntity.ok(relayTeamService.getBoard(eventId));
    }

    @Operation(summary = "Create the event's teams from the roster",
            description = "The same derivation a teacher can run: one team per form, or per house of "
                    + "the event's grade and division. Additive, and pass prune=true to also drop "
                    + "teams that are empty and no longer on the roster.")
    @PostMapping("/events/{eventId}/relay-teams/derive")
    public ResponseEntity<RelayTeamDerivationDTO> deriveTeams(
            @PathVariable Long eventId,
            @RequestParam(required = false, defaultValue = "false") boolean prune) {
        return ResponseEntity.ok(relayTeamService.deriveTeams(eventId, prune));
    }

    @Operation(summary = "Remove every team of an event",
            description = "Deletes the event's relay teams and their runners, leaving the event "
                    + "otherwise untouched. This is what frees a relay to change kind, or to have "
                    + "its selections started again from the roster.")
    @DeleteMapping("/events/{eventId}/relay-teams")
    public ResponseEntity<Map<String, Object>> removeTeams(@PathVariable Long eventId) {
        int removed = relayTeamService.removeTeamsForEvent(eventId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("teamsRemoved", removed);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Name a runner for a leg",
            description = "Every eligibility rule still applies — division, grade, house or form, "
                    + "one leg per athlete per event and the team's own size — but an administrator "
                    + "may name a student of any class.")
    @PostMapping("/relay-teams/{teamId}/runners")
    public ResponseEntity<RelayTeamDTO> addRunner(@PathVariable Long teamId,
                                                  @RequestBody RelayRunnerRequest request) {
        RelayRunnerRequest body = request == null ? new RelayRunnerRequest() : request;
        return ResponseEntity.ok(
                relayTeamService.addRunner(teamId, body.getUserId(), body.getLeg()));
    }

    @Operation(summary = "Remove a runner",
            description = "Takes the athlete out of the team and closes the legs up behind them.")
    @DeleteMapping("/relay-teams/{teamId}/runners/{userId}")
    public ResponseEntity<RelayTeamDTO> removeRunner(@PathVariable Long teamId,
                                                     @PathVariable Long userId) {
        return ResponseEntity.ok(relayTeamService.removeRunner(teamId, userId));
    }

    @Operation(summary = "Set the running order",
            description = "Leg 1 first; the list must name exactly the runners the team already has.")
    @PutMapping("/relay-teams/{teamId}/legs")
    public ResponseEntity<RelayTeamDTO> reorderLegs(@PathVariable Long teamId,
                                                    @RequestBody RelayLegOrderRequest request) {
        List<Long> order = request == null ? null : request.getUserIds();
        return ResponseEntity.ok(relayTeamService.reorderLegs(teamId, order));
    }
}
