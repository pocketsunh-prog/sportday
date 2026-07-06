package com.sportday.controller;

import com.sportday.dto.EventDTO;
import com.sportday.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Events", description = "Event management APIs")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @Operation(summary = "Get all events", description = "Retrieve all events (optionally filter by enabled status)")
    @GetMapping
    public ResponseEntity<List<EventDTO>> getAllEvents(
            @RequestParam(required = false, defaultValue = "false") boolean onlyEnabled) {
        if (onlyEnabled) {
            return ResponseEntity.ok(eventService.getEnabledEvents());
        }
        return ResponseEntity.ok(eventService.getAllEvents());
    }

    @Operation(summary = "Get event by ID", description = "Retrieve a specific event by its ID")
    @GetMapping("/{id}")
    public ResponseEntity<EventDTO> getEventById(@PathVariable Long id) {
        return ResponseEntity.ok(eventService.getEventById(id));
    }

    @Operation(summary = "Create event", description = "Create a new event (ADMIN or MANAGER)")
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<EventDTO> createEvent(@RequestBody EventDTO eventDTO) {
        return ResponseEntity.ok(eventService.createEvent(eventDTO));
    }

    @Operation(summary = "Update event", description = "Update an existing event (ADMIN or MANAGER)")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<EventDTO> updateEvent(@PathVariable Long id, @RequestBody EventDTO eventDTO) {
        return ResponseEntity.ok(eventService.updateEvent(id, eventDTO));
    }

    @Operation(summary = "Set event enabled status", description = "Enable or disable an event (ADMIN or MANAGER)")
    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<EventDTO> setEventEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        return ResponseEntity.ok(eventService.setEventEnabled(id, enabled));
    }

    @Operation(summary = "Delete event", description = "Delete an event (ADMIN only)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteEvent(@PathVariable Long id) {
        eventService.deleteEvent(id);
        return ResponseEntity.noContent().build();
    }
}
