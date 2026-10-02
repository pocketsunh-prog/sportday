package com.sportday.controller;

import com.sportday.dto.SeasonDTO;
import com.sportday.service.SeasonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The school years — one sport day each.
 */
@Tag(name = "School years", description = "One sport day per school year, with the year's enrolment switch")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SeasonController {

    private final SeasonService seasonService;

    @Operation(summary = "List the school years",
            description = "Every sport day, most recent first, with how many events each has and which "
                    + "one is current.")
    @GetMapping("/seasons")
    public ResponseEntity<List<SeasonDTO>> list() {
        return ResponseEntity.ok(seasonService.list());
    }

    @Operation(summary = "The current school year",
            description = "The year students may enter — normally the most recent, but the school can "
                    + "reopen an earlier one. Null when no year has been set up yet.")
    @GetMapping("/seasons/current")
    public ResponseEntity<SeasonDTO> current() {
        return ResponseEntity.ok(seasonService.current());
    }

    @Operation(summary = "One school year")
    @GetMapping("/seasons/{id}")
    public ResponseEntity<SeasonDTO> byId(@PathVariable Long id) {
        return ResponseEntity.ok(seasonService.byId(id));
    }

    @Operation(summary = "Create a school year",
            description = "Sets up this year's sport day. Pass copyEventsFromSeasonId to copy another "
                    + "year's event catalogue into it, which is the usual way to start a new season "
                    + "(ADMIN only).")
    @PostMapping("/admin/seasons")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SeasonDTO> create(@RequestBody SeasonDTO payload) {
        return ResponseEntity.ok(seasonService.create(payload));
    }

    @Operation(summary = "Update a school year",
            description = "The school's year, its sport day date, its name and whether entries are open "
                    + "(ADMIN only).")
    @PutMapping("/admin/seasons/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SeasonDTO> update(@PathVariable Long id, @RequestBody SeasonDTO payload) {
        return ResponseEntity.ok(seasonService.update(id, payload));
    }

    @Operation(summary = "Open entries for a year",
            description = "Makes this the year students may enter and closes the others, so nobody can "
                    + "enter events for the wrong sport day (ADMIN only).")
    @PostMapping("/admin/seasons/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SeasonDTO> activate(@PathVariable Long id) {
        return ResponseEntity.ok(seasonService.activate(id));
    }

    @Operation(summary = "Delete a school year",
            description = "Only allowed while the year has no events, so a programme is never orphaned "
                    + "(ADMIN only).")
    @DeleteMapping("/admin/seasons/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        seasonService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
