package com.sportday.controller;

import com.sportday.dto.EventGroupDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventGroup;
import com.sportday.entity.Sex;
import com.sportday.service.EventGroupService;
import com.sportday.service.EventService;
import com.sportday.service.FinalQualificationService;
import com.sportday.service.FinalStageGuard;
import com.sportday.service.PdfSheetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Groups (heats) and the marking sheets printed from them.
 */
@Tag(name = "Groups & marking sheets",
        description = "Allocate entries into heats and print helper marking sheets")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class EventGroupController {

    private final EventGroupService eventGroupService;
    private final EventService eventService;
    private final PdfSheetService pdfSheetService;
    private final FinalQualificationService finalQualificationService;
    private final FinalStageGuard finalStageGuard;

    // --------------------------------------------------------------- groups

    @Operation(summary = "List the groups of an event",
            description = "Groups are ordered by heat number. Rosters are omitted by default for speed; "
                    + "pass includeRosters=true to get every heat with its athletes in one request.")
    @GetMapping("/events/{eventId}/groups")
    public ResponseEntity<List<EventGroupDTO>> getGroups(
            @PathVariable Long eventId,
            @RequestParam(required = false, defaultValue = "false") boolean includeRosters) {
        return ResponseEntity.ok(includeRosters
                ? eventGroupService.getGroupsWithAthletes(eventId)
                : eventGroupService.getGroups(eventId));
    }

    @Operation(summary = "Get one group with its roster")
    @GetMapping("/groups/{groupId}")
    public ResponseEntity<EventGroupDTO> getGroup(@PathVariable Long groupId) {
        return ResponseEntity.ok(eventGroupService.getGroup(groupId));
    }

    @Operation(summary = "Allocate groups for an event",
            description = "Splits the confirmed entries into groups of the event's group size — "
                    + "8 for 60/100/200/400, 24 for 800 and above. Re-running replaces the previous heats. "
                    + "Use shuffle=true to draw lanes at random instead of seeding by class (ADMIN or MANAGER).")
    @PostMapping("/events/{eventId}/groups/allocate")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<Map<String, Object>> allocate(
            @PathVariable Long eventId,
            @RequestParam(required = false, defaultValue = "false") boolean shuffle) {
        List<EventGroupDTO> groups = eventGroupService.allocateGroups(eventId, shuffle);
        return ResponseEntity.ok(Map.of(
                "eventId", eventId,
                "groupCount", groups.size(),
                "shuffle", shuffle,
                "groups", groups));
    }

    @Operation(summary = "Remove all groups from an event",
            description = "Removes the heats and the final, and clears any marks recorded in the final. "
                    + "Heat marks are kept (ADMIN or MANAGER).")
    @DeleteMapping("/events/{eventId}/groups")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<Void> clearGroups(@PathVariable Long eventId) {
        eventGroupService.clearGroups(eventId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------- heat / final

    @Operation(summary = "Preview the final",
            description = "Who would go through to the final on the heat results recorded so far. Changes "
                    + "nothing. Short sprints (60/100/200/400) take the top 8 by default (ADMIN or MANAGER).")
    @GetMapping("/events/{eventId}/final")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<FinalQualificationService.FinalSummary> previewFinal(
            @PathVariable Long eventId,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(finalQualificationService.preview(eventId, limit));
    }

    @Operation(summary = "Draw the final from the heat results",
            description = "Ranks the heat marks — fastest first for a track event, longest or highest first "
                    + "for a field event — and puts the best 8 (or `limit`) into a final. Re-drawing discards "
                    + "the previous final and any marks recorded in it, because they belong to a field that no "
                    + "longer exists; heat marks are never touched (ADMIN or MANAGER).")
    @PostMapping("/events/{eventId}/final")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<FinalQualificationService.FinalSummary> drawFinal(
            @PathVariable Long eventId,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(finalQualificationService.generate(eventId, limit));
    }

    @Operation(summary = "Remove the final",
            description = "Deletes the final and any marks recorded in it. Heats and heat marks are kept "
                    + "(ADMIN or MANAGER).")
    @DeleteMapping("/events/{eventId}/final")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<Map<String, Object>> clearFinal(@PathVariable Long eventId) {
        int cleared = finalQualificationService.clearFinal(eventId);
        return ResponseEntity.ok(Map.of("eventId", eventId, "finalMarksCleared", cleared));
    }

    // ------------------------------------------------------------- PDF sheets

    @Operation(summary = "Download one group's marking sheet",
            description = "A5 for 60/100/200/400 (8 athletes), A4 otherwise (24 athletes). "
                    + "Columns: student id, name, grade, record, remark. A final's sheet is refused "
                    + "until the final has been drawn from the heat results (ADMIN, MANAGER or HELPER).")
    @GetMapping("/groups/{groupId}/sheet.pdf")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HELPER')")
    public ResponseEntity<byte[]> groupSheet(@PathVariable Long groupId) {
        EventGroup group = eventGroupService.requireGroup(groupId);
        if (group.isFinal()) {
            // The final's sheet is the final's field, so it cannot exist until the
            // draw has run. The group not being found at all is a 404; being asked
            // for before it is drawn is this, which says when to come back.
            finalStageGuard.requireDrawnFinal(group.getEvent());
        }
        byte[] pdf = pdfSheetService.renderGroupSheet(groupId);
        return pdfResponse(pdf, sheetFileName(group));
    }

    @Operation(summary = "Download every marking sheet of an event",
            description = "One page per group, all at the event's paper size. An event that runs "
                    + "heats and a final refuses the whole run until the final has been drawn, so a "
                    + "print run can never be missing its last sheet (ADMIN, MANAGER or HELPER).")
    @GetMapping("/events/{eventId}/sheets.pdf")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HELPER')")
    public ResponseEntity<byte[]> eventSheets(@PathVariable Long eventId) {
        // Checked with the same rule the marking grid uses, and not only inside
        // PdfSheetService: this endpoint is the whole print run, and handing back
        // just the heats would look like the final's sheet had simply gone missing.
        requireSheetsArePrintable(eventId);
        byte[] pdf = pdfSheetService.renderEventSheets(eventId);
        return pdfResponse(pdf, "event-" + eventId + "-marking-sheets.pdf");
    }

    @Operation(summary = "Download one marking sheet per group across many events",
            description = "Convenience endpoint for the print run (ADMIN, MANAGER or HELPER)")
    @GetMapping("/sheets.pdf")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','HELPER')")
    public ResponseEntity<byte[]> allSheets(
            @RequestParam(required = false) Long eventId,
            @RequestParam(required = false) String sex,
            @RequestParam(required = false) String category) {
        if (eventId != null) {
            requireSheetsArePrintable(eventId);
            return pdfResponse(pdfSheetService.renderEventSheets(eventId),
                    "event-" + eventId + "-marking-sheets.pdf");
        }
        Sex division = sex == null || sex.isBlank() ? null : Sex.fromCode(sex);
        EventCategory cat = category == null || category.isBlank() ? null : EventCategory.fromCode(category);
        List<EventGroupDTO> groups = eventGroupService.getGroupsWithAthletesFiltered(division, cat);
        if (groups.isEmpty()) {
            throw new IllegalStateException("No groups match that filter — allocate groups first.");
        }
        // Nothing to check per event here: the groups handed over are the ones that
        // exist, and a final that has not been drawn has no group to be among them.
        return pdfResponse(pdfSheetService.renderSheets(groups), "sportday-marking-sheets.pdf");
    }

    /**
     * Refuses an event's print run while a final it will run has not been drawn.
     *
     * <p>An event that runs straight to a final, and one that cannot be split at
     * all, are refused nothing: every sheet they have is already in the run. Only an
     * event whose final is still to come is held back.</p>
     */
    private void requireSheetsArePrintable(Long eventId) {
        Event event = eventService.requireEvent(eventId);
        if (event.runsAFinal()) {
            finalStageGuard.requireDrawnFinal(event);
        }
    }

    private ResponseEntity<byte[]> pdfResponse(byte[] pdf, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }

    private static String sheetFileName(EventGroup group) {
        String event = group.getEvent() == null || group.getEvent().getType() == null
                ? "event" : group.getEvent().getType().name().toLowerCase();
        return "sheet-" + event + "-heat-" + group.getGroupNumber() + ".pdf";
    }
}
