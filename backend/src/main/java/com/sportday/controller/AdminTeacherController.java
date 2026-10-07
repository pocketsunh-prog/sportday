package com.sportday.controller;

import com.sportday.dto.TeacherUploadResultDTO;
import com.sportday.service.TeacherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bulk teacher account management (ADMIN only), mirroring
 * {@link AdminStudentController} for the student register.
 *
 * <p>The upload creates a TEACHER account per row and assigns their classes.
 * Re-uploading updates the accounts and replaces each teacher's class list, so the
 * staff list can be run as often as the office likes.</p>
 */
@Slf4j
@Tag(name = "Admin — teachers",
        description = "Bulk teacher account upload and maintenance (ADMIN only)")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api/admin/teachers")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminTeacherController {

    private final TeacherService teacherService;

    @Operation(summary = "Upload a teacher list",
            description = "Accepts .csv or .xlsx with columns: username (login, required), name "
                    + "(required), email (optional), classes (required — one or more class names, "
                    + "e.g. \"1A;3B\" or \"1A,3B\"), password (optional; when absent the account's "
                    + "generated password is used and returned in `credentials`). Existing usernames "
                    + "are updated and their class list replaced, so re-uploading is idempotent. Pass "
                    + "dryRun=true to rehearse the whole file without writing anything.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TeacherUploadResultDTO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "false") boolean dryRun) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please choose a file to upload.");
        }
        try (InputStream in = file.getInputStream()) {
            return ResponseEntity.ok(
                    teacherService.importTeachers(in, file.getOriginalFilename(), dryRun));
        }
    }

    @Operation(summary = "List teacher accounts",
            description = "Every TEACHER account with the classes assigned to them.")
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<Map<String, Object>> body = teacherService.listTeachers().stream()
                .map(teacher -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("username", teacher.getUsername());
                    row.put("name", teacher.getFullName());
                    row.put("email", teacher.getEmail());
                    row.put("enabled", teacher.getEnabled());
                    row.put("role", teacher.getRole().name());
                    // The classes are the whole point of the account, so they belong on
                    // the list rather than needing a second call per teacher.
                    row.put("classes", teacherService.classesOf(teacher.getId()));
                    return row;
                })
                .toList();
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Replace one teacher's classes",
            description = "For fixing a mistyped class or a teacher who has changed year group, "
                    + "without re-uploading the whole staff file. The list is semicolon-separated, "
                    + "e.g. 1A;3B, and replaces whatever was there.")
    @PutMapping("/{username}/classes")
    public ResponseEntity<Map<String, Object>> setClasses(@PathVariable String username,
                                                          @RequestParam String classes) {
        List<String> assigned = teacherService.setClasses(username, classes);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("classes", assigned);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Remove a teacher account",
            description = "Deletes the account and its class assignments. Only a TEACHER may be "
                    + "removed here — an administrator or a manager is refused, so a staff "
                    + "maintenance call cannot lock the school out of its own system.")
    @DeleteMapping("/{username}")
    public ResponseEntity<Void> delete(@PathVariable String username) {
        teacherService.deleteTeacher(username);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Download the teacher login credentials sheet",
            description = "username, name, email, the classes the teacher may help in, and their "
                    + "password. A password supplied in an upload is not stored in clear text, so the "
                    + "sheet prints the derived one for those accounts.")
    @GetMapping(value = "/credentials.csv", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> credentials() {
        byte[] body = teacherService.credentialsCsv().getBytes(StandardCharsets.UTF_8);
        return csvResponse(body, "teacher-credentials.csv");
    }

    @Operation(summary = "Download an empty teacher upload template")
    @GetMapping(value = "/template.csv", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> template() {
        byte[] body = teacherService.templateCsv().getBytes(StandardCharsets.UTF_8);
        return csvResponse(body, "teacher-upload-template.csv");
    }

    @Operation(summary = "Create a teacher account by hand",
            description = "For a single teacher who is not in the uploaded list. The classes are given "
                    + "as a semicolon-separated list, e.g. 1A;3B.")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestParam String username,
            @RequestParam String name,
            @RequestParam(required = false) String email,
            @RequestParam String classes,
            @RequestParam(required = false) String password) {
        TeacherUploadResultDTO result = teacherService.createTeacher(username, name, email, classes, password);
        if (!result.getErrors().isEmpty()) {
            throw new IllegalArgumentException(result.getErrors().get(0).getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("name", name);
        body.put("email", email);
        body.put("classes", com.sportday.service.TeacherImportParser.parseClasses(classes));
        body.put("password", result.getCredentials().isEmpty() ? null : result.getCredentials().get(0).getPassword());
        body.put("passwordRule", result.getPasswordRule());
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<byte[]> csvResponse(byte[] body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .contentLength(body.length)
                .body(body);
    }
}
