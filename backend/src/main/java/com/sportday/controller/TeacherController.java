package com.sportday.controller;

import com.sportday.dto.EnrollmentDTO;
import com.sportday.dto.StudentDTO;
import com.sportday.dto.UserDTO;
import com.sportday.entity.User;
import com.sportday.service.TeacherHelpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A teacher helping a student.
 *
 * <p>A teacher carries a set of classes. They may enter or withdraw a student in
 * an event — and read what that takes — for a student in one of those classes, and
 * for nobody else. An administrator uses the same endpoints and is never refused,
 * because the rule lives in one place ({@link TeacherHelpService}, over
 * {@code TeacherClassService}) rather than in each controller method.</p>
 *
 * <p>The endpoints are a family of their own rather than a widening of
 * {@code /api/admin/students/**}: a teacher is granted the entry paths and nothing
 * else, so they cannot reach the register upload, the locking endpoints or the
 * credentials sheet by holding a teacher account.</p>
 */
@Tag(name = "Teacher — helping students",
        description = "Enter or withdraw a student's events within the classes assigned to the teacher "
                + "(ADMIN or TEACHER)")
@SecurityRequirement(name = "Bearer Authentication")
@RestController
@RequestMapping("/api/teacher")
@PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
@RequiredArgsConstructor
public class TeacherController {

    private final TeacherHelpService teacherHelpService;

    @Operation(summary = "My teaching profile",
            description = "The signed-in teacher's account and the classes they may help in. An "
                    + "administrator gets every class on the register, because they may help anybody.")
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        User caller = teacherHelpService.caller();
        UserDTO profile = teacherHelpService.profile();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profile", profile);
        body.put("role", profile == null ? null : profile.getRole());
        body.put("classes", teacherHelpService.classesMayHelp());
        body.put("note", "A teacher may only help students in these classes. A teacher with no classes "
                + "can help nobody.");
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "The students I may help",
            description = "The students of the classes assigned to the signed-in teacher. An "
                    + "administrator sees the whole register. Optionally narrowed to one class the "
                    + "caller is allowed to help in; anything else is refused.")
    @GetMapping("/students")
    public ResponseEntity<List<StudentDTO>> students(@RequestParam(required = false) String className) {
        return ResponseEntity.ok(teacherHelpService.studentsMayHelp(className));
    }

    @Operation(summary = "A student's event entries",
            description = "The entries, withdrawn ones included, with the track/field quota that still "
                    + "applies to the student. Refused with 403 when the student is not in one of the "
                    + "teacher's classes.")
    @GetMapping("/students/{studentId}/enrollments")
    public ResponseEntity<Map<String, Object>> enrollments(@PathVariable String studentId) {
        return ResponseEntity.ok(teacherHelpService.entriesFor(studentId));
    }

    @Operation(summary = "Enter a student into an event",
            description = "Enters the student on their behalf. An entry they had withdrawn from is "
                    + "revived rather than refused, the event's own grade and division and the "
                    + "student's entry quota all still apply, and a student outside the teacher's "
                    + "classes is refused with 403.")
    @PostMapping("/students/{studentId}/enrollments/{eventId}")
    public ResponseEntity<EnrollmentDTO> enroll(@PathVariable String studentId,
                                                @PathVariable Long eventId) {
        return ResponseEntity.ok(teacherHelpService.enroll(studentId, eventId));
    }

    @Operation(summary = "Withdraw a student from an event",
            description = "Cancels the student's entry, freeing the place in their quota. Refused with "
                    + "403 for a student outside the teacher's classes.")
    @DeleteMapping("/students/{studentId}/enrollments/{eventId}")
    public ResponseEntity<Void> cancel(@PathVariable String studentId,
                                       @PathVariable Long eventId) {
        teacherHelpService.withdraw(studentId, eventId);
        return ResponseEntity.noContent().build();
    }
}
