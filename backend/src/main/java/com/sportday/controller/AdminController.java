package com.sportday.controller;

import com.sportday.dto.RegisterRequest;
import com.sportday.dto.SeasonBackupFile.BackupSummary;
import com.sportday.dto.StandardDefaultDTO;
import com.sportday.dto.UserDTO;
import com.sportday.entity.Event;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.User;
import com.sportday.service.BackupStore;
import com.sportday.service.SeasonBackupService;
import com.sportday.service.SeasonResetService;
import com.sportday.service.StandardDefaultService;
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
import java.math.BigDecimal;

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
    private final StandardDefaultService standardDefaultService;

    /**
     * The <strong>default required standards</strong> — "400M, A grade, boys = 64.0 s"
     * — that every event of that type, grade and division inherits.
     *
     * <h2>The key, stated plainly</h2>
     * <p>The key is <strong>type &times; grade &times; sex</strong>. The sex division is
     * part of it on purpose: a default keyed on grade alone would give the boys' 400M
     * and the girls' 400M the same qualifying time, and the live programme holds both
     * at every grade. If the school wants one time per grade, the sex is dropped from
     * the key and nothing else in this design moves.</p>
     *
     * <h2>Only the events that carry a standard</h2>
     * <p>Only types where {@link Event.EventType#carriesAStandard()} is true — the
     * track races of 400M and over, and the field events. Relays and the short sprints
     * are refused rather than quietly ignored, so a page cannot offer a box that would
     * never be inherited.</p>
     *
     * <h2>A default is saved and then applied, in two steps</h2>
     * <p>{@code PUT} records the default and touches <strong>no event</strong>; it is
     * what a <em>new</em> event of that key will inherit from then on. The events that
     * already exist are re-pointed by the {@code POST}, which can be run as a dry run
     * first so the administrator sees how many events change and how many hand-set
     * numbers are left alone <em>before</em> writing anything.</p>
     */
    @Operation(summary = "List the default standards for every grade and division",
            description = "Every default the school has configured, keyed by event type, grade and "
                    + "sex — the number an event of that key inherits. A key with no row has no "
                    + "default. ADMIN only.")
    @GetMapping("/standard-defaults")
    public ResponseEntity<List<StandardDefaultDTO>> listStandardDefaults() {
        return ResponseEntity.ok(standardDefaultService.list());
    }

    @Operation(summary = "Set or clear one default standard",
            description = "Records the qualifying mark for one event type, grade and sex division — "
                    + "the number every event of that key inherits from now on. Send standard as null "
                    + "to clear it. Refused for an event type that does not carry a standard (a relay, "
                    + "or a race under 400M) and for a number that is not greater than zero. "
                    + "NO EXISTING EVENT IS CHANGED by this call: use POST "
                    + "/api/admin/standard-defaults/apply to re-point the events that follow it. "
                    + "ADMIN only.")
    @PutMapping("/standard-defaults/{type}/{grade}/{sex}")
    public ResponseEntity<StandardDefaultDTO> setStandardDefault(
            @PathVariable String type,
            @PathVariable String grade,
            @PathVariable String sex,
            @RequestParam(required = false) BigDecimal standard) {
        return ResponseEntity.ok(standardDefaultService.set(
                parseStandardType(type), parseStandardGrade(grade), parseStandardSex(sex), standard));
    }

    @Operation(summary = "Apply the defaults to the events that inherit them",
            description = "Re-points existing events at their grade and division's default. "
                    + "mode=INHERITED (the default) changes only the events that FOLLOW a default "
                    + "or hold no standard at all, and leaves every hand-set number exactly as it "
                    + "is, reporting how many were kept; mode=ALL overwrites those too and is the "
                    + "only way a hand-set number is ever replaced. dryRun=true works out and "
                    + "reports what would change and writes nothing. ADMIN only.")
    @PostMapping("/standard-defaults/apply")
    public ResponseEntity<StandardDefaultService.ApplyResult> applyStandardDefaults(
            @RequestParam(required = false, defaultValue = "INHERITED") String mode,
            @RequestParam(required = false, defaultValue = "false") boolean dryRun) {
        StandardDefaultService.ApplyMode parsed;
        try {
            parsed = StandardDefaultService.ApplyMode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown mode: " + mode
                    + " — use INHERITED (leave hand-set standards alone) or ALL (overwrite them).");
        }
        return ResponseEntity.ok(standardDefaultService.apply(parsed, dryRun));
    }

    /** An event type that may carry a standard, or a refusal naming the rule. */
    private static Event.EventType parseStandardType(String raw) {
        Event.EventType type;
        try {
            type = Event.EventType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown event type: " + raw);
        }
        // Refused here rather than stored and never inherited, so the answer is
        // immediate: a relay has no qualifying time to set.
        if (!type.carriesAStandard()) {
            throw new IllegalArgumentException(type.getDisplayName() + " does not carry a required "
                    + "standard, so it cannot have a default one. Only the track races of 400M and "
                    + "over, and the field events, carry a standard — a relay is run and scored by "
                    + "team.");
        }
        return type;
    }

    private static Grade parseStandardGrade(String raw) {
        Grade grade = Grade.fromCode(raw);
        if (grade == null) {
            throw new IllegalArgumentException("Unknown grade: " + raw + " — use A, B or C.");
        }
        return grade;
    }

    private static Sex parseStandardSex(String raw) {
        Sex sex = Sex.fromCode(raw);
        if (sex == null) {
            throw new IllegalArgumentException("Unknown division: " + raw + " — use M (MALE) "
                    + "or F (FEMALE). The division is part of the key: the boys' race and the "
                    + "girls' race have their own qualifying times.");
        }
        return sex;
    }

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
