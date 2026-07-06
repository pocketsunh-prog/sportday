package com.sportday.controller;

import com.sportday.entity.Enrollment;
import com.sportday.entity.User;
import com.sportday.repository.UserRepository;
import com.sportday.service.EnrollmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Enrollments", description = "Event enrollment management APIs")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api/enrollments")
@RequiredArgsConstructor
public class EnrollmentController {

    private final EnrollmentService enrollmentService;
    private final UserRepository userRepository;

    @Operation(summary = "Enroll in event", description = "Enroll the authenticated user in an event")
    @PostMapping("/{eventId}")
    public ResponseEntity<Enrollment> enroll(@PathVariable Long eventId, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        return ResponseEntity.ok(enrollmentService.enrollUserToEvent(user.getId(), eventId));
    }

    @Operation(summary = "Cancel enrollment", description = "Cancel the authenticated user's enrollment in an event")
    @DeleteMapping("/{eventId}")
    public ResponseEntity<Void> cancelEnrollment(@PathVariable Long eventId, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        enrollmentService.cancelEnrollment(user.getId(), eventId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get my enrollments", description = "Retrieve all enrollments for the authenticated user")
    @GetMapping("/my")
    public ResponseEntity<List<Enrollment>> getMyEnrollments(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        return ResponseEntity.ok(enrollmentService.getUserEnrollments(user.getId()));
    }

    @GetMapping("/event/{eventId}")
    public ResponseEntity<List<Enrollment>> getEventEnrollments(@PathVariable Long eventId) {
        return ResponseEntity.ok(enrollmentService.getEventEnrollments(eventId));
    }

    @GetMapping("/check/{eventId}")
    public ResponseEntity<Boolean> checkEnrollment(@PathVariable Long eventId, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        return ResponseEntity.ok(enrollmentService.isUserEnrolled(user.getId(), eventId));
    }
}
