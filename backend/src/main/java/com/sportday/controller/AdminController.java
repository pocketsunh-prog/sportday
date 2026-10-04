package com.sportday.controller;

import com.sportday.dto.RegisterRequest;
import com.sportday.dto.SeasonBackupFile.BackupSummary;
import com.sportday.dto.UserDTO;
import com.sportday.entity.User;
import com.sportday.service.BackupStore;
import com.sportday.service.SeasonBackupService;
import com.sportday.service.SeasonResetService;
import com.sportday.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
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
    private final SeasonBackupService seasonBackupService;
    private final BackupStore backupStore;

    @Operation(summary = "Create manager", description = "Create a new manager account (ADMIN only)")
    @PostMapping("/managers")
    public ResponseEntity<UserDTO> createManager(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(userService.createManager(request));
    }

    @Operation(summary = "Create a user account",
            description = "The only way an account is created now that public self-registration has been "
                    + "removed. Students are not created here — they arrive through the register import "
                    + "(ADMIN only). A TEACHER created here may sign in straight away but can help nobody "
                    + "until the teacher upload assigns them their classes. A HELPER created here may key "
                    + "in marks and print marking sheets as soon as they sign in.")
    @PostMapping("/users")
    public ResponseEntity<UserDTO> createUser(
            @RequestBody RegisterRequest request,
            @RequestParam(required = false, defaultValue = "MANAGER") String role) {
        User.Role parsed;
        try {
            parsed = User.Role.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown role: " + role
                    + " — use ADMIN, MANAGER, TEACHER, HELPER or USER.");
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
                    + "set by a result falls back to it (ADMIN only). "
                    + "A restorable backup of everything the reset destroys — entries, heats, final "
                    + "places, marks and the school records — is written to a file FIRST, and its name "
                    + "and size come back in the response. If that file cannot be written the reset "
                    + "refuses to run and nothing is deleted.")
    @PostMapping("/season/reset")
    public ResponseEntity<Map<String, Object>> resetSeason() {
        return ResponseEntity.ok(seasonResetService.resetSeason());
    }

    // ---------------------------------------------------------------- backups

    @Operation(summary = "List season backups",
            description = "Every backup file in the backup directory, newest first, with its size, the "
                    + "moment it was taken and the counts out of its header — so the right one can be "
                    + "picked without downloading it (ADMIN only).")
    @GetMapping("/backups")
    public ResponseEntity<List<BackupSummary>> listBackups() {
        return ResponseEntity.ok(backupStore.list());
    }

    @Operation(summary = "Download a season backup",
            description = "One backup file, as it was written — the JSON a restore reads. The name must "
                    + "be a backup in the backup directory; a name that would resolve outside it is "
                    + "refused with 400 (ADMIN only).")
    @GetMapping("/backups/{name}")
    public ResponseEntity<FileSystemResource> downloadBackup(@PathVariable String name) {
        Path file = backupStore.resolve(name);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FileSystemResource(file));
    }

    @Operation(summary = "Restore a season backup",
            description = "DESTRUCTIVE — this OVERWRITES CURRENT DATA. Every entry, heat, final place, "
                    + "recorded mark and school-record baseline now in the system is deleted and "
                    + "replaced with the contents of the named backup file. Students, events, school "
                    + "years and settings are not touched, and a row in the file whose student or event "
                    + "no longer exists is skipped and counted rather than invented. Take a backup of "
                    + "the current state first if it may be wanted: use POST /api/admin/season/reset, or "
                    + "the file this same endpoint restored from earlier (ADMIN only).")
    @PostMapping("/backups/{name}/restore")
    public ResponseEntity<Map<String, Object>> restoreBackup(@PathVariable String name) {
        return ResponseEntity.ok(seasonBackupService.restore(name));
    }
}
