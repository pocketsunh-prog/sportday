package com.sportday.controller;

import com.sportday.dto.EnrollmentDTO;
import com.sportday.dto.StudentDTO;
import com.sportday.dto.StudentUploadResultDTO;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.service.StudentService;
import com.sportday.service.TeacherHelpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bulk student register management (ADMIN only).
 *
 * <p>Uploading a CSV or XLSX creates or updates every student and their login
 * account: username = student id, password = {@code yyyyMMdd + class + class
 * number}, grade derived from the date of birth.</p>
 */
@Slf4j
@Tag(name = "Admin — students", description = "Bulk student register upload and maintenance (ADMIN only)")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api/admin/students")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminStudentController {

    private final StudentService studentService;
    private final TeacherHelpService teacherHelpService;

    @Operation(summary = "Upload a student register",
            description = "Accepts .csv or .xlsx with columns: studentId, name, dob, sex, className, "
                    + "classNumber, house. English and Chinese headings are both understood. "
                    + "Existing student ids are updated; row-level problems are reported without "
                    + "failing the rest of the file.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StudentUploadResultDTO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceDate,
            @RequestParam(required = false, defaultValue = "false") boolean lockAbsent,
            @RequestParam(required = false, defaultValue = "false") boolean dryRun) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please choose a file to upload.");
        }
        try (InputStream in = file.getInputStream()) {
            return ResponseEntity.ok(studentService.importStudents(
                    in, file.getOriginalFilename(), referenceDate, lockAbsent, dryRun));
        }
    }

    @Operation(summary = "Upload the year's full student list",
            description = "The same upload, declared to be the COMPLETE student list for the year. Every "
                    + "student the file does not mention is locked — they can no longer sign in or be "
                    + "entered in an event, though their entries, results and records are kept. A student "
                    + "who was locked before and appears again becomes active. Pass dryRun=true first: it "
                    + "reports exactly who would be locked without writing anything, so a partial file "
                    + "cannot quietly disable the school.")
    @PostMapping(value = "/upload/roster", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StudentUploadResultDTO> uploadFullRoster(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceDate,
            @RequestParam(required = false, defaultValue = "true") boolean lockAbsent,
            @RequestParam(required = false, defaultValue = "true") boolean dryRun) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please choose a file to upload.");
        }
        try (InputStream in = file.getInputStream()) {
            return ResponseEntity.ok(studentService.importStudents(
                    in, file.getOriginalFilename(), referenceDate, lockAbsent, dryRun));
        }
    }

    @Operation(summary = "Lock or unlock a student",
            description = "A locked student cannot sign in or be entered in an event, but keeps their "
                    + "entries, results and records — so a student who has left is hidden without losing "
                    + "the school's history. Unlocking restores their access.")
    @PatchMapping("/{studentId}/lock")
    public ResponseEntity<StudentDTO> setLocked(@PathVariable String studentId,
                                               @RequestParam boolean locked) {
        return ResponseEntity.ok(studentService.setStudentLocked(studentId, locked));
    }

    @Operation(summary = "Lock every student missing from the latest upload",
            description = "For a roster the administrator uploaded without ticking \"complete list\": "
                    + "locks everyone who was not in the most recent upload.")
    @PostMapping("/lock-missing")
    public ResponseEntity<Map<String, Object>> lockMissing() {
        String latestBatch = studentService.latestImportBatch();
        int locked = studentService.lockStudentsMissingFrom(latestBatch);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latestBatch", latestBatch);
        body.put("locked", locked);
        body.put("lockedNow", studentService.countLocked());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Generate sample students",
            description = "Creates N realistic Hong Kong students (600 by default) with Chinese names, "
                    + "date of birth spread across grades C/B/A, class registers and houses. "
                    + "Re-running updates the same student ids.")
    @PostMapping("/sample")
    public ResponseEntity<StudentUploadResultDTO> seedSample(
            @RequestParam(required = false, defaultValue = "600") int count,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceDate) {
        if (count < 1 || count > 5000) {
            throw new IllegalArgumentException("count must be between 1 and 5000.");
        }
        return ResponseEntity.ok(studentService.seedSampleData(count, referenceDate));
    }

    @Operation(summary = "Download the generated sample register as CSV",
            description = "The same data as /sample, in the upload format, for testing the import path")
    @GetMapping(value = "/sample.csv", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> sampleCsv(
            @RequestParam(required = false, defaultValue = "600") int count,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceDate) {
        List<String> lines = studentService.sampleCsvLines(count, referenceDate);
        // Leading BOM so Excel opens the Chinese names correctly.
        byte[] body = ("\uFEFF" + String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_8);
        return csvResponse(body, "students-" + count + ".csv");
    }

    @Operation(summary = "Download an empty upload template")
    @GetMapping(value = "/template.csv", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> template() {
        byte[] body = ("\uFEFF" + String.join(",",
                "studentId", "name", "dob", "sex", "className", "classNumber", "house") + "\r\n"
                + "S0001,陳大文,2010-03-15,M,5A,12,Red\r\n"
                + "S0002,李小明,2009-07-02,F,5B,7,Blue\r\n")
                .getBytes(StandardCharsets.UTF_8);
        return csvResponse(body, "student-upload-template.csv");
    }

    @Operation(summary = "Download the login credentials sheet",
            description = "studentId, name, class and the derived password, for handing out to students")
    @GetMapping(value = "/credentials.csv", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> credentials(@RequestParam(required = false) String className) {
        byte[] body = studentService.credentialsCsv(className).getBytes(StandardCharsets.UTF_8);
        return csvResponse(body, "student-credentials.csv");
    }

    @Operation(summary = "List students",
            description = "Optionally filter by class, sex, grade, house, or whether the student is "
                    + "active (enabled) or locked.")
    @GetMapping
    public ResponseEntity<List<StudentDTO>> list(
            @RequestParam(required = false) String className,
            @RequestParam(required = false) String sex,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String house,
            @RequestParam(required = false) Boolean enabled) {
        Sex sexFilter = sex == null || sex.isBlank() ? null : Sex.fromCode(sex);
        if (sex != null && !sex.isBlank() && sexFilter == null) {
            throw new IllegalArgumentException("Unknown sex: " + sex);
        }
        Grade gradeFilter = grade == null || grade.isBlank() ? null : Grade.fromCode(grade);
        if (grade != null && !grade.isBlank() && gradeFilter == null) {
            throw new IllegalArgumentException("Unknown grade: " + grade);
        }
        return ResponseEntity.ok(
                studentService.listStudents(className, sexFilter, gradeFilter, house, enabled));
    }

    @Operation(summary = "Get one student by student id")
    @GetMapping("/{studentId}")
    public ResponseEntity<StudentDTO> getOne(@PathVariable String studentId) {
        return ResponseEntity.ok(studentService.getByStudentId(studentId));
    }

    @Operation(summary = "Remove a student",
            description = "Deletes the student, their login account, their entries and their results. "
                    + "Use this when a student leaves the school or was imported in error.")
    @DeleteMapping("/{studentId}")
    public ResponseEntity<Void> delete(@PathVariable String studentId) {
        studentService.deleteStudent(studentId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Student register summary",
            description = "Totals by grade, sex and house, plus the classes on roll")
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", studentService.totalStudents());
        body.put("byGrade", studentService.gradeCounts());
        body.put("bySex", studentService.sexCounts());
        body.put("sexCodes", Map.of("MALE", "M", "FEMALE", "F"));
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Recompute every grade",
            description = "Recalculates each student's grade from their date of birth against the given "
                    + "reference date (the sport day). Use this when the sport day moves.")
    @PostMapping("/recompute-grades")
    public ResponseEntity<Map<String, Object>> recomputeGrades(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceDate) {
        int changed = studentService.recomputeGrades(referenceDate);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("changed", changed);
        body.put("referenceDate", (referenceDate != null ? referenceDate : LocalDate.now()).toString());
        body.put("byGrade", studentService.gradeCounts());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "The grade rule", description = "Documents how grades are derived from date of birth")
    @GetMapping("/grade-rule")
    public ResponseEntity<Map<String, Object>> gradeRule() {
        Map<String, Object> body = new LinkedHashMap<>();
        for (Grade grade : Grade.values()) {
            body.put(grade.name(), grade.getAgeRange());
        }
        body.put("note", "Age is measured on the sport day (app.grade.reference-date), not the upload date.");
        body.put("passwordRule", "yyyyMMdd(dob) + class + class number, e.g. 201003155A12");
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------ entries on a student's behalf

    @Operation(summary = "A student's event entries",
            description = "The entries an administrator manages on the student's behalf, with the "
                    + "track/field quota that still applies to the student. A teacher has the same "
                    + "view on /api/teacher/students/{studentId}/enrollments, limited to their own "
                    + "classes.")
    @GetMapping("/{studentId}/enrollments")
    public ResponseEntity<Map<String, Object>> getStudentEnrollments(@PathVariable String studentId) {
        return ResponseEntity.ok(teacherHelpService.entriesFor(studentId));
    }

    @Operation(summary = "Enter a student into an event",
            description = "Enters the student on their behalf, for a student who cannot do it themselves. "
                    + "An entry they had withdrawn from is revived rather than refused, and entering "
                    + "somebody who is already in is a no-op. The quota is enforced against the student, so "
                    + "an administrator cannot exceed it either; the refusal explains which category is "
                    + "full. An administrator is never refused by the teacher class rule, which the same "
                    + "service applies for a teacher.")
    @PostMapping("/{studentId}/enrollments/{eventId}")
    public ResponseEntity<EnrollmentDTO> enrollForStudent(@PathVariable String studentId,
                                                          @PathVariable Long eventId) {
        return ResponseEntity.ok(teacherHelpService.enroll(studentId, eventId));
    }

    @Operation(summary = "Remove a student's entry to an event",
            description = "Cancels the student's entry, freeing the place in their quota. The heats are "
                    + "left alone; re-allocate them if the event has already been drawn.")
    @DeleteMapping("/{studentId}/enrollments/{eventId}")
    public ResponseEntity<Void> cancelForStudent(@PathVariable String studentId,
                                                 @PathVariable Long eventId) {
        teacherHelpService.withdraw(studentId, eventId);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<byte[]> csvResponse(byte[] body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .contentLength(body.length)
                .body(body);
    }
}
