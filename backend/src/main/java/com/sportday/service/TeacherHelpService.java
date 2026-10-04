package com.sportday.service;

import com.sportday.dto.EnrollmentDTO;
import com.sportday.dto.StudentDTO;
import com.sportday.dto.UserDTO;
import com.sportday.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helping a student enter or withdraw from events on their behalf — the
 * administrator's path and the teacher's path, held to the class rule.
 *
 * <h2>Why this is a separate service</h2>
 * The rule is "who may act for whom", and it is not an entry rule: it is decided
 * before the entry is even looked at, and the same decision covers entering,
 * withdrawing and reading the entries. Putting it here means
 * <ul>
 *   <li>there is exactly one implementation of it, so it cannot drift between the
 *       enter path and the withdraw path;</li>
 *   <li>{@link EnrollmentService} keeps doing what it always did — the event's own
 *       grade and division, the student's quota, reviving a withdrawn entry —
 *       unchanged for a student entering themselves;</li>
 *   <li>every staff-facing path goes through these methods rather than calling
 *       {@code EnrollmentService} directly, which is what makes the rule
 *       impossible to bypass by picking a different endpoint.</li>
 * </ul>
 *
 * <p>The refusal is a 403 naming the class — {@code 2C is not one of your
 * classes.} — and a teacher with no classes at all is refused everybody rather
 * than quietly allowed. An administrator bypasses the check entirely and may help
 * any student.</p>
 */
@Service
public class TeacherHelpService {

    private final TeacherClassService teacherClassService;
    private final EnrollmentService enrollmentService;

    public TeacherHelpService(TeacherClassService teacherClassService,
                              EnrollmentService enrollmentService) {
        this.teacherClassService = teacherClassService;
        this.enrollmentService = enrollmentService;
    }

    /** The signed-in caller's own account, for the profile endpoint. */
    @Transactional(readOnly = true)
    public User caller() {
        return teacherClassService.signedInUser();
    }

    /** The classes the caller may help in — every class for an administrator. */
    @Transactional(readOnly = true)
    public List<String> classesMayHelp() {
        return teacherClassService.classesOf(caller());
    }

    /**
     * The students of those classes, optionally narrowed to one of them. A teacher
     * with no assignments gets an empty list, never the whole school.
     */
    @Transactional(readOnly = true)
    public List<StudentDTO> studentsMayHelp(String classNameFilter) {
        return teacherClassService.studentsMayHelp(caller(), classNameFilter);
    }

    /** The signed-in caller, as it is handed to a client. */
    @Transactional(readOnly = true)
    public UserDTO profile() {
        User caller = caller();
        return caller == null ? null : UserDTO.from(caller);
    }

    /**
     * A student's entries, withdrawn ones included, with their remaining quota.
     * Reading them is part of helping, so it is held to the same rule.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> entriesFor(String studentId) {
        Long userId = userIdFor(studentId);
        teacherClassService.requireMayHelpUserId(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("studentId", studentId);
        body.put("userId", userId);
        body.put("quota", enrollmentService.getQuota(userId));
        body.put("enrollments", enrollmentService.getAllUserEnrollments(userId));
        return body;
    }

    /**
     * Enters the student, or revives an entry they had withdrawn from. Every rule
     * of the event and the student's quota still applies afterwards.
     */
    @Transactional
    public EnrollmentDTO enroll(String studentId, Long eventId) {
        Long userId = userIdFor(studentId);
        teacherClassService.requireMayHelpUserId(userId);
        return enrollmentService.enrollOnBehalf(userId, eventId);
    }

    /** Withdraws the student's entry, freeing the place in their quota. */
    @Transactional
    public void withdraw(String studentId, Long eventId) {
        Long userId = userIdFor(studentId);
        teacherClassService.requireMayHelpUserId(userId);
        enrollmentService.cancelEnrollment(userId, eventId);
    }

    /**
     * The login account behind a student id. A student record with no account
     * cannot be entered by anybody, and that is said plainly rather than left as a
     * missing id.
     */
    @Transactional(readOnly = true)
    public Long userIdFor(String studentId) {
        return enrollmentService.requireUserIdFor(studentId);
    }
}
