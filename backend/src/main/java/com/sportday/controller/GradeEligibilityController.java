package com.sportday.controller;

import com.sportday.dto.GradeEligibilityDTO;
import com.sportday.service.GradeEligibilityService;
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
 * Which grades may enter which events.
 */
@Tag(name = "Grade eligibility",
        description = "Assign which events each grade may enter, and see how many events each grade has")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GradeEligibilityController {

    private final GradeEligibilityService gradeEligibilityService;

    @Operation(summary = "The event-by-grade grid",
            description = "One row per event with A, B and C across it, ticked where that grade may "
                    + "enter, plus how many events each grade ends up with. By default the C grade does "
                    + "not run the 1500M or 5000M and only the A grade runs the 5000M.")
    @GetMapping("/grade-events")
    public ResponseEntity<GradeEligibilityDTO> getMatrix() {
        return ResponseEntity.ok(gradeEligibilityService.matrix());
    }

    @Operation(summary = "Assign which grades may enter which events",
            description = "Send the cells that changed. Each is {eventType, grade, allowed}. Entry is "
                    + "refused for a grade that is not allowed, including when an administrator enters a "
                    + "student on their behalf (ADMIN only).")
    @PutMapping("/admin/grade-events")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<GradeEligibilityDTO> update(
            @RequestBody List<GradeEligibilityDTO.RuleUpdate> updates) {
        return ResponseEntity.ok(gradeEligibilityService.update(updates));
    }

    @Operation(summary = "Reset the grade assignment",
            description = "Back to the school's starting position: everything open except the long "
                    + "distances (ADMIN only).")
    @PostMapping("/admin/grade-events/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> reset() {
        int created = gradeEligibilityService.resetToDefaults();
        return ResponseEntity.ok(Map.of(
                "rulesCreated", created,
                "matrix", gradeEligibilityService.matrix()));
    }
}
