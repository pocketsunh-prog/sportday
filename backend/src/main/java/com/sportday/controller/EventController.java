package com.sportday.controller;

import com.sportday.dto.EventDTO;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Sex;
import com.sportday.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "Events", description = "Event management APIs")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @Operation(summary = "Get past events",
            description = "Events that have already been held — their date is today or earlier — most "
                    + "recent first. Use this to look back at a previous sport day's results.")
    @GetMapping("/past")
    public ResponseEntity<List<EventDTO>> getPastEvents() {
        return ResponseEntity.ok(eventService.getPastEvents());
    }

    @Operation(summary = "Get all events",
            description = "Retrieve events. The filters are optional and compose: onlyEnabled, "
                    + "sex (M/F), category (TRACK/FIELD), date (yyyy-MM-dd, for a day of a "
                    + "multi-day meeting) and seasonId (a school year).")
    @GetMapping
    public ResponseEntity<List<EventDTO>> getAllEvents(
            @RequestParam(required = false, defaultValue = "false") boolean onlyEnabled,
            @RequestParam(required = false) String sex,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long seasonId) {
        EventCategory categoryFilter = null;
        if (category != null && !category.isBlank()) {
            categoryFilter = EventCategory.fromCode(category);
            if (categoryFilter == null) {
                throw new IllegalArgumentException("Unknown category: " + category
                        + " — use TRACK or FIELD.");
            }
        }
        Sex sexFilter = null;
        if (sex != null && !sex.isBlank()) {
            sexFilter = Sex.fromCode(sex);
            if (sexFilter == null) {
                throw new IllegalArgumentException("Unknown sex division: " + sex
                        + " — use M or F.");
            }
        }
        return ResponseEntity.ok(eventService.searchEvents(onlyEnabled, sexFilter, categoryFilter, date, seasonId));
    }

    @Operation(summary = "The dates the programme runs on",
            description = "Each date that has events, with the number on it and whether it is today or "
                    + "already past — what the date picker offers.")
    @GetMapping("/dates")
    public ResponseEntity<List<Map<String, Object>>> getEventDates() {
        return ResponseEntity.ok(eventService.getEventDates());
    }

    @Operation(summary = "Get event by ID", description = "Retrieve a specific event by its ID")
    @GetMapping("/{id}")
    public ResponseEntity<EventDTO> getEventById(@PathVariable Long id) {
        return ResponseEntity.ok(eventService.getEventById(id));
    }

    @Operation(summary = "Create event",
            description = "Create a new event (ADMIN or MANAGER). New events are enabled by default; "
                    + "the category, group size and marking-sheet size follow the event type.")
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

    @Operation(summary = "Set event enabled status",
            description = "Enable or disable an event (ADMIN or MANAGER). Disabling closes it to new entries.")
    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<EventDTO> setEventEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        return ResponseEntity.ok(eventService.setEventEnabled(id, enabled));
    }

    @Operation(summary = "Delete event", description = "Delete an event, its entries and its results (ADMIN only)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteEvent(@PathVariable Long id) {
        eventService.deleteEvent(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Create the default event catalogue",
            description = "Creates the standard sport-day event list for both divisions. "
                    + "Events that already exist for that type and division are left alone (ADMIN only).")
    @PostMapping("/defaults")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> createDefaults(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate eventDate,
            @RequestParam(required = false, defaultValue = "true") boolean includeField) {
        LocalDate on = eventDate != null ? eventDate : LocalDate.now();
        int created = eventService.createDefaults(on, includeField);
        return ResponseEntity.ok(Map.of(
                "created", created,
                "eventDate", on.toString(),
                "includeField", includeField,
                "totalEvents", eventService.getAllEvents().size()));
    }
}
