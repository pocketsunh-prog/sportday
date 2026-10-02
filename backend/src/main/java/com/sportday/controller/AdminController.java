package com.sportday.controller;

import com.sportday.dto.RegisterRequest;
import com.sportday.dto.UserDTO;
import com.sportday.entity.User;
import com.sportday.service.SeasonResetService;
import com.sportday.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Admin", description = "Administrative APIs (ADMIN role required)")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final UserService userService;
    private final SeasonResetService seasonResetService;

    @Operation(summary = "Create manager", description = "Create a new manager account (ADMIN only)")
    @PostMapping("/managers")
    public ResponseEntity<UserDTO> createManager(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(userService.createManager(request));
    }

    @Operation(summary = "Create a user account",
            description = "The only way an account is created now that public self-registration has been "
                    + "removed. Students are not created here — they arrive through the register import "
                    + "(ADMIN only).")
    @PostMapping("/users")
    public ResponseEntity<UserDTO> createUser(
            @RequestBody RegisterRequest request,
            @RequestParam(required = false, defaultValue = "MANAGER") String role) {
        User.Role parsed;
        try {
            parsed = User.Role.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown role: " + role
                    + " — use ADMIN, MANAGER or USER.");
        }
        if (parsed == User.Role.STUDENT) {
            throw new IllegalArgumentException(
                    "Student accounts are created by the register import, not here.");
        }
        return ResponseEntity.ok(userService.createUser(request, parsed));
    }

    @Operation(summary = "The roles an administrator can hand out")
    @GetMapping("/users/roles")
    public ResponseEntity<List<String>> assignableRoles() {
        return ResponseEntity.ok(userService.assignableRoles());
    }

    @Operation(summary = "Reset the season",
            description = "Deletes every entry, heat, final and recorded result so the sport day can be run "
                    + "again. The student register and the event catalogue are left untouched. School "
                    + "records are kept: a mark an administrator typed in survives, and a record that was "
                    + "set by a result falls back to it (ADMIN only).")
    @PostMapping("/season/reset")
    public ResponseEntity<Map<String, Object>> resetSeason() {
        return ResponseEntity.ok(seasonResetService.resetSeason());
    }
}
