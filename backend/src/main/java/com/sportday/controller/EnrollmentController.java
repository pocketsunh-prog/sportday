package com.sportday.controller;

import com.sportday.dto.EnrollmentDTO;
import com.sportday.entity.EventCategory;
import com.sportday.service.CurrentUserService;
import com.sportday.service.EnrollmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Enrollments", description = "Event entry management APIs")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api/enrollments")
@RequiredArgsConstructor
public class EnrollmentController {

    private final EnrollmentService enrollmentService;
    private final CurrentUserService currentUser;

    @Operation(summary = "Enter an event",
            description = "Enters the signed-in student in an event. A student may hold at most "
                    + "2 track entries (徑項) and 1 field entry (田項).")
    @PostMapping("/{eventId}")
    public ResponseEntity<EnrollmentDTO> enroll(@PathVariable Long eventId, Authentication authentication) {
        return ResponseEntity.ok(
                enrollmentService.enrollUserToEvent(currentUser.requireId(authentication), eventId));
    }

    @Operation(summary = "Withdraw from an event",
            description = "Cancels the entry and frees its quota slot")
    @DeleteMapping("/{eventId}")
    public ResponseEntity<Void> cancelEnrollment(@PathVariable Long eventId, Authentication authentication) {
        enrollmentService.cancelEnrollment(currentUser.requireId(authentication), eventId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Re-enter a previously withdrawn event")
    @PostMapping("/{eventId}/re-enroll")
    public ResponseEntity<EnrollmentDTO> reEnroll(@PathVariable Long eventId, Authentication authentication) {
        return ResponseEntity.ok(
                enrollmentService.reEnroll(currentUser.requireId(authentication), eventId));
    }

    @Operation(summary = "My entries", description = "Confirmed entries for the signed-in user")
    @GetMapping("/my")
    public ResponseEntity<List<EnrollmentDTO>> getMyEnrollments(Authentication authentication) {
        return ResponseEntity.ok(enrollmentService.getUserEnrollments(currentUser.requireId(authentication)));
    }

    @Operation(summary = "My entry history", description = "All entries including withdrawn ones")
    @GetMapping("/my/all")
    public ResponseEntity<List<EnrollmentDTO>> getMyEnrollmentHistory(Authentication authentication) {
        return ResponseEntity.ok(enrollmentService.getAllUserEnrollments(currentUser.requireId(authentication)));
    }

    @Operation(summary = "My remaining allowance",
            description = "How many track and field entries the signed-in user may still add")
    @GetMapping("/my/quota")
    public ResponseEntity<Map<String, Object>> getMyQuota(Authentication authentication) {
        EnrollmentService.Quota quota = enrollmentService.getQuota(currentUser.requireId(authentication));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trackUsed", quota.trackUsed());
        body.put("trackMax", quota.trackMax());
        body.put("trackRemaining", quota.trackRemaining());
        body.put("fieldUsed", quota.fieldUsed());
        body.put("fieldMax", quota.fieldMax());
        body.put("fieldRemaining", quota.fieldRemaining());
        body.put("rules", Map.of(
                EventCategory.TRACK.name(), quota.trackMax(),
                EventCategory.FIELD.name(), quota.fieldMax()));
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "List the entries of an event", description = "ADMIN or MANAGER")
    @GetMapping("/event/{eventId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ResponseEntity<List<EnrollmentDTO>> getEventEnrollments(@PathVariable Long eventId) {
        return ResponseEntity.ok(enrollmentService.getEventEnrollments(eventId));
    }

    @Operation(summary = "Am I entered in this event?")
    @GetMapping("/check/{eventId}")
    public ResponseEntity<Boolean> checkEnrollment(@PathVariable Long eventId, Authentication authentication) {
        return ResponseEntity.ok(
                enrollmentService.isUserEnrolled(currentUser.requireId(authentication), eventId));
    }
}
