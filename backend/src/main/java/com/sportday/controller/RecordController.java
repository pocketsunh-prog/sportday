package com.sportday.controller;

import com.sportday.dto.ChampionsDTO;
import com.sportday.dto.EventRecordDTO;
import com.sportday.dto.RecordBaselineDTO;
import com.sportday.dto.SportDaySettingsDTO;
import com.sportday.service.ChampionService;
import com.sportday.service.RecordService;
import com.sportday.service.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The school's rules, the school records, and the championships they feed.
 */
@Tag(name = "Records, settings and championships",
        description = "Editable rules, school records and the personal/house championships")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RecordController {

    private final SettingsService settingsService;
    private final RecordService recordService;
    private final ChampionService championService;

    // ------------------------------------------------------------- settings

    @Operation(summary = "Get the sport day settings",
            description = "How many track and field events a student may enter, and what each placing "
                    + "is worth. Created with the documented defaults on first read.")
    @GetMapping("/settings")
    public ResponseEntity<SportDaySettingsDTO> getSettings() {
        return ResponseEntity.ok(SportDaySettingsDTO.from(settingsService.get()));
    }

    @Operation(summary = "Update the sport day settings",
            description = "Fields left out keep their current value (ADMIN only).")
    @PutMapping("/admin/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SportDaySettingsDTO> updateSettings(@RequestBody SportDaySettingsDTO payload) {
        return ResponseEntity.ok(SportDaySettingsDTO.from(settingsService.update(payload)));
    }

    @Operation(summary = "Restore the default settings", description = "ADMIN only")
    @PostMapping("/admin/settings/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SportDaySettingsDTO> resetSettings() {
        return ResponseEntity.ok(SportDaySettingsDTO.from(settingsService.resetToDefaults()));
    }

    // -------------------------------------------------------------- records

    @Operation(summary = "List the school records",
            description = "One record per event type, division and grade — e.g. Boys 100M, B Grade — "
                    + "with the mark that stands, who holds it and what it beat. A row exists for every "
                    + "combination as soon as the event does, so an event with nothing recorded yet "
                    + "still appears, with source NONE.")
    @GetMapping("/records")
    public ResponseEntity<List<EventRecordDTO>> getRecords() {
        return ResponseEntity.ok(recordService.list());
    }

    @Operation(summary = "Set a record by hand",
            description = "Records the mark an administrator types in — last season's best, or one held "
                    + "by a student who has left. The holder is free text. A result that later beats it "
                    + "takes over, and clearing the results falls back to this mark rather than losing it "
                    + "(ADMIN only).")
    @PutMapping("/admin/records/{recordId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EventRecordDTO> setRecordBaseline(
            @PathVariable Long recordId,
            @RequestBody RecordBaselineDTO payload) {
        return ResponseEntity.ok(recordService.setBaseline(recordId, payload));
    }

    @Operation(summary = "Clear a hand-entered record",
            description = "Removes the typed-in mark and leaves the record to the results (ADMIN only).")
    @DeleteMapping("/admin/records/{recordId}/baseline")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EventRecordDTO> clearRecordBaseline(@PathVariable Long recordId) {
        return ResponseEntity.ok(recordService.clearBaseline(recordId));
    }

    @Operation(summary = "Create any missing record rows",
            description = "Every event should have a record for each grade. This creates the ones that "
                    + "are missing, without touching any that exist (ADMIN only).")
    @PostMapping("/admin/records/seed")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> seedRecords() {
        int created = recordService.seedAll();
        return ResponseEntity.ok(Map.of(
                "recordsCreated", created,
                "records", recordService.list().size()));
    }

    @Operation(summary = "Rebuild every school record",
            description = "Records are derived from the results and the hand-entered marks, so this is "
                    + "safe to run at any time. Baselines are kept (ADMIN only).")
    @PostMapping("/admin/records/recompute")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> recomputeRecords() {
        int rebuilt = recordService.recomputeAll();
        return ResponseEntity.ok(Map.of(
                "recordsRebuilt", rebuilt,
                "records", recordService.list().size()));
    }

    // ---------------------------------------------------------- championships

    @Operation(summary = "The personal and house championships",
            description = "Placings from every event that has results, turned into points by the "
                    + "current settings. Where an event ran a final, the final decides the points. "
                    + "Relay points count for the house only.")
    @GetMapping("/championships")
    public ResponseEntity<ChampionsDTO> getChampionships() {
        return ResponseEntity.ok(championService.calculate());
    }

    @Operation(summary = "The placings of one event",
            description = "Who finished where, and what each place is worth.")
    @GetMapping("/events/{eventId}/standings")
    public ResponseEntity<ChampionsDTO.EventStandingsDTO> getStandings(@PathVariable Long eventId) {
        return ResponseEntity.ok(championService.standingsFor(eventId));
    }
}
