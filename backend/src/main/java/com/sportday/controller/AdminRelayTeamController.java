package com.sportday.controller;

import com.sportday.dto.DraftRelayTeamRequest;
import com.sportday.dto.EventDTO;
import com.sportday.dto.RelayEventTeamsDTO;
import com.sportday.dto.RelayLegOrderRequest;
import com.sportday.dto.RelayRenameRequest;
import com.sportday.dto.RelayRunnerRequest;
import com.sportday.dto.RelayTeamCreateRequest;
import com.sportday.dto.RelayTeamDTO;
import com.sportday.dto.RelayTeamDerivationDTO;
import com.sportday.dto.RelayTeamMoveDTO;
import com.sportday.dto.RelayTeamMoveRequest;
import com.sportday.service.EventService;
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
    private final EventService eventService;

    // ------------------------------------------------- a relay event round the teams

    @Operation(summary = "The draft relay events — events built around their teams",
            description = "The school's requirement: create a relay event based on the teams it has "
                    + "already chosen by hand. A relay team cannot exist without an event, so the "
                    + "teams are collected on a draft — a relay event that is deliberately not on "
                    + "the programme, offers no entry and is counted nowhere. This lists the drafts "
                    + "an administrator is filling, in programme order, each event described the way "
                    + "every other event endpoint describes one and carrying `draft: true`. It is the "
                    + "one listing a draft belongs in: the programme excludes them all. A draft's "
                    + "teams and runners are on its own relay board "
                    + "(GET /api/admin/events/{eventId}/relay-teams).")
    @GetMapping("/relay-events/drafts")
    public ResponseEntity<List<EventDTO>> draftRelayEvents() {
        return ResponseEntity.ok(eventService.getDraftEvents());
    }

    @Operation(summary = "Create a draft relay event out of chosen teams",
            description = "Creates the relay event the school's hand-made teams are built around, "
                    + "with those teams in the same request — name, and `userIds` in leg order so "
                    + "the first student listed runs leg 1. The event is a 4x100M or a 4x400M and "
                    + "must be given a kind (FORM for a form's class teams, HOUSE for one per house), "
                    + "because that is what the register judges a team and its runners against. "
                    + "Every team goes through the ordinary hand-made-team rule book: the name "
                    + "rules, four runners and at most one reserve, the event's division and "
                    + "grade, one leg per athlete per event, and an administrator may name a "
                    + "student of any class. Every team is judged before any is written. The "
                    + "resulting event does not appear in the programme.")
    @PostMapping("/relay-events/drafts")
    public ResponseEntity<EventDTO> createDraftRelayEvent(
            @RequestBody DraftRelayTeamRequest request) {
        return ResponseEntity.ok(eventService.createDraftEvent(request));
    }

    @Operation(summary = "Move a draft relay event's teams onto the real event",
            description = "The second half of the requirement: the teams were built on a draft and "
                    + "this carries them onto the relay event the school actually runs, all of them "
                    + "or none. The target must be a relay event, and every team is re-judged "
                    + "against it before anything moves: a name already used in the target refuses "
                    + "the whole move (the same name rule a create and a rename share), a squad "
                    + "bigger than the target's own team refuses it, and a runner no longer in the "
                    + "target's division and grade refuses it. Nothing is renamed, re-keyed or "
                    + "dropped to make a move fit. The draft event is kept, emptied of its teams. "
                    + "Returns how many teams and runners moved, and the target event.")
    @PutMapping("/relay-events/{draftEventId}/teams/move")
    public ResponseEntity<RelayTeamMoveDTO> moveTeams(@PathVariable Long draftEventId,
                                                      @RequestBody RelayTeamMoveRequest request) {
        return ResponseEntity.ok(relayTeamService.moveTeamsToEvent(draftEventId,
                request == null ? null : request.getTargetEventId()));
    }

    @Operation(summary = "Discard a draft relay event's teams",
            description = "Throws away the teams a draft is holding — the one deliberate way to "
                    + "empty a draft, on its own request. A draft's teams are never cleared as a "
                    + "side effect of anything else, and deleting a draft that still holds teams is "
                    + "refused, because those teams are the whole reason the draft exists. After "
                    + "this the draft is empty and can be deleted.")
    @DeleteMapping("/relay-events/{draftEventId}/teams")
    public ResponseEntity<Map<String, Object>> discardDraftTeams(@PathVariable Long draftEventId) {
        int removed = relayTeamService.discardDraftTeams(draftEventId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("draftEventId", draftEventId);
        body.put("teamsDiscarded", removed);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "An event's relay teams, and the students who applied to it",
            description = "Every team of a relay event with its runners, for an administrator, who "
                    + "may see and fill any of them — and, beside them, every student with a "
                    + "confirmed entry in the event (form, class, house and house code), with the "
                    + "team each is already on when they are on one, so the page can show who is "
                    + "still unplaced and group them into teams.")
    @GetMapping("/events/{eventId}/relay-teams")
    public ResponseEntity<RelayEventTeamsDTO> teams(@PathVariable Long eventId) {
        return ResponseEntity.ok(relayTeamService.getBoard(eventId));
    }

    @Operation(summary = "Create the event's teams from its entries",
            description = "The same derivation a teacher can run: one team per class the relay's "
                    + "entrants are in — a form relay's teams come from its confirmed entries, not "
                    + "from the form's first two classes — or one team per house of the event's "
                    + "grade and division. Additive, and pass "
                    + "prune=true to also drop teams that are empty and no longer called for by the "
                    + "entries.")
    @PostMapping("/events/{eventId}/relay-teams/derive")
    public ResponseEntity<RelayTeamDerivationDTO> deriveTeams(
            @PathVariable Long eventId,
            @RequestParam(required = false, defaultValue = "false") boolean prune) {
        return ResponseEntity.ok(relayTeamService.deriveTeams(eventId, prune));
    }

    @Operation(summary = "Create a team by hand out of chosen students",
            description = "The school's own team: a free-text name and the students who run "
                    + "for it, in the order they are to run. The team is deliberately not "
                    + "required to be one class or one house. Every eligibility rule still "
                    + "applies — the event's division and grade, one leg per athlete per event, "
                    + "four runners and at most one reserve — and with `userIds` in leg order, "
                    + "so the first student listed runs leg 1. A name is trimmed, must not be "
                    + "blank, is at most 40 characters and must be unique within the event. "
                    + "A team made this way is never matched, renamed or pruned by a later "
                    + "derive.")
    @PostMapping("/relay-events/{eventId}/teams")
    public ResponseEntity<RelayTeamDTO> createTeam(@PathVariable Long eventId,
                                                   @RequestBody RelayTeamCreateRequest request) {
        return ResponseEntity.ok(relayTeamService.createTeam(eventId, request));
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

    @Operation(summary = "Rename a relay team",
            description = "The name the school writes on the sheet. An administrator may "
                    + "rename any team, whichever class or house it belongs to.")
    @PutMapping("/relay-teams/{teamId}/name")
    public ResponseEntity<RelayTeamDTO> renameTeam(@PathVariable Long teamId,
                                                   @RequestBody RelayRenameRequest request) {
        return ResponseEntity.ok(relayTeamService.renameTeam(
                teamId, request == null ? null : request.getName()));
    }
}
