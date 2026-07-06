package com.sportday.controller;

import com.sportday.dto.RegisterRequest;
import com.sportday.dto.UserDTO;
import com.sportday.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin", description = "Administrative APIs (ADMIN role required)")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final UserService userService;

    @Operation(summary = "Create manager", description = "Create a new manager account (ADMIN only)")
    @PostMapping("/managers")
    public ResponseEntity<UserDTO> createManager(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(userService.createManager(request));
    }
}
