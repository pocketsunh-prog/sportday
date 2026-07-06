package com.sportday.controller;

import com.sportday.dto.EventResultDTO;
import com.sportday.entity.EventResult;
import com.sportday.service.EventResultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Tag(name = "Event Results", description = "Event result recording and retrieval APIs")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api/results")
@RequiredArgsConstructor
public class EventResultController {

    private final EventResultService resultService;

    @Operation(summary = "Get results by event", description = "Retrieve all results for a specific event")
    @GetMapping("/event/{eventId}")
    public ResponseEntity<List<EventResultDTO>> getResultsByEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(resultService.getResultsByEvent(eventId));
    }

    @Operation(summary = "Get results by user", description = "Retrieve all results for a specific user")
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<EventResultDTO>> getResultsByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(resultService.getResultsByUser(userId));
    }

    @Operation(summary = "Record result", description = "Record a new event result (ADMIN or MANAGER)")
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<EventResultDTO> recordResult(
            @RequestParam Long userId,
            @RequestParam Long eventId,
            @RequestParam BigDecimal mark,
            @RequestParam(required = false) String unit,
            @RequestParam(required = false) String notes) {
        return ResponseEntity.ok(resultService.recordResult(userId, eventId, mark, unit, notes));
    }

    @Operation(summary = "Delete result", description = "Delete an event result (ADMIN or MANAGER)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<Void> deleteResult(@PathVariable Long id) {
        resultService.deleteResult(id);
        return ResponseEntity.noContent().build();
    }
}
