package com.sportday.controller;

import com.sportday.dto.BulkMarkRequest;
import com.sportday.dto.MarkSheetDTO;
import com.sportday.entity.EventStage;
import com.sportday.entity.Grade;
import com.sportday.service.MarkEntryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Grid-based mark entry: read the athletes of an event (optionally narrowed to a
 * heat and a grade band), then save the whole grid back in one request.
 */
@Tag(name = "Mark entry",
        description = "Grid mark entry filtered by event group and grade (ADMIN, MANAGER or HELPER). "
                + "A FINAL grid is refused with 409 until the final has been drawn from the heat marks.")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','HELPER')")
@RequiredArgsConstructor
public class MarkEntryController {

    private final MarkEntryService markEntryService;

    @Operation(summary = "Get the mark-entry grid for an event",
            description = "Every athlete competing in the chosen stage, with their group, lane and any mark "
                    + "already recorded. Narrow it with groupId and/or grade. `stage` is HEAT (the default) "
                    + "or FINAL, and is ignored when a group is named because the group already says which "
                    + "stage it is. The filter options returned always describe the whole stage, so the "
                    + "dropdowns stay stable. `stage=FINAL` is refused with 409 while the event has no final "
                    + "drawn: the final is drawn from the heat marks, so record those and draw it first. The "
                    + "response carries `finalState` so a client can gate its own final button — `NONE` (the "
                    + "event has no final stage), `DIRECT` (it runs straight to one), `NOT_DRAWN` or `DRAWN`.")
    @GetMapping("/events/{eventId}/marks")
    public ResponseEntity<MarkSheetDTO> getMarkSheet(
            @PathVariable Long eventId,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String stage) {
        Grade gradeFilter = null;
        if (grade != null && !grade.isBlank()) {
            gradeFilter = Grade.fromCode(grade);
            if (gradeFilter == null) {
                throw new IllegalArgumentException("Unknown grade: " + grade + " — use A, B or C.");
            }
        }
        EventStage stageFilter = null;
        if (stage != null && !stage.isBlank()) {
            stageFilter = EventStage.fromCode(stage);
            if (stageFilter == null) {
                throw new IllegalArgumentException("Unknown stage: " + stage + " — use HEAT or FINAL.");
            }
        }
        return ResponseEntity.ok(markEntryService.getMarkSheet(eventId, groupId, gradeFilter, stageFilter));
    }

    @Operation(summary = "Save a whole grid of marks",
            description = "Rows carrying a mark are inserted or updated, rows flagged clear have the mark "
                    + "removed, and rows with no mark are left untouched. Set `stage` to FINAL to record the "
                    + "final, which keeps its own marks — refused with 409 while the final has not been "
                    + "drawn, because there is no final field to write against yet. A bad row is reported "
                    + "in errors without losing the rest of the batch.")
    @PostMapping("/events/{eventId}/marks")
    public ResponseEntity<BulkMarkRequest.Result> saveMarks(
            @PathVariable Long eventId,
            @RequestBody BulkMarkRequest request) {
        return ResponseEntity.ok(markEntryService.saveMarks(eventId, request));
    }
}
