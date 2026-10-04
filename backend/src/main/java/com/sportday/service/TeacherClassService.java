package com.sportday.service;

import com.sportday.dto.StudentDTO;
import com.sportday.entity.Student;
import com.sportday.entity.TeacherClass;
import com.sportday.entity.User;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.StudentRepository;
import com.sportday.repository.TeacherClassRepository;
import com.sportday.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Who a staff account is allowed to help.
 *
 * <h2>The rule</h2>
 * <ul>
 *   <li>an <strong>administrator</strong> may help any student, always;</li>
 *   <li>a <strong>teacher</strong> may help only a student whose class is one of
 *       the classes assigned to them ({@link TeacherClass});</li>
 *   <li>a teacher with <strong>no</strong> assignments may help nobody — that is a
 *       refusal, never a silent allow;</li>
 *   <li>anybody else — a student, or a manager who is not a teacher — is refused
 *       as well, so widening an endpoint to teachers cannot quietly widen it to
 *       everyone.</li>
 * </ul>
 *
 * <p>The check lives here, in one place, and every path that enters, withdraws or
 * reads a student's entries on their behalf goes through it, so it cannot be
 * bypassed by calling a different endpoint.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeacherClassService {

    private final TeacherClassRepository teacherClassRepository;
    private final StudentRepository studentRepository;
    private final UserRepository userRepository;
    private final GradeCalculator gradeCalculator;

    // ---------------------------------------------------------- the rule

    /**
     * Refuses unless the caller may act for this student.
     *
     * @param student the student being helped, whose class is matched against the
     *                caller's assignments
     * @throws AccessDeniedException the caller is not an administrator and the
     *                               student is outside their classes (or they have
     *                               none)
     */
    public void requireMayHelp(Student student) {
        if (isAdmin()) {
            // An administrator may help any student, which is the existing rule
            // and stays exactly as it was.
            return;
        }
        User caller = signedInUser();
        // Resolved by student id rather than by the roster instance, so the check
        // reads the class the register holds now.
        String className = student == null || student.getStudentId() == null
                ? null
                : studentRepository.findByStudentId(student.getStudentId())
                        .map(Student::getClassName)
                        .orElse(student.getClassName());
        requireClassOf(caller, className);
    }

    /**
     * Refuses unless the caller may act for the student behind this login account.
     * Used by the entry paths, which address a student by their account.
     */
    public void requireMayHelpUserId(Long userId) {
        if (isAdmin()) {
            return;
        }
        Student roster = studentRepository.findWithUserByUserId(userId).orElse(null);
        if (roster == null) {
            // No roster record: there is nothing to match a class against, so a
            // teacher may not act. An administrator was already let through.
            throw new AccessDeniedException(
                    "That account is not on the student register, so you cannot act for it.");
        }
        requireClassOf(signedInUser(), roster.getClassName());
    }

    /** The single class check both entry points above funnel into. */
    private void requireClassOf(User caller, String className) {
        if (!isATeacherAccount(caller)) {
            throw new AccessDeniedException(
                    "Only a teacher or an administrator can act for a student.");
        }
        List<String> assigned = assignedClasses(caller);
        if (assigned.isEmpty()) {
            throw new AccessDeniedException(
                    "You have no classes assigned, so you cannot help any student. "
                            + "Ask the school office to assign you the classes you take.");
        }
        String normalized = StudentPasswordPolicy.normalizeClass(className);
        if (normalized == null || !assigned.contains(normalized)) {
            throw new AccessDeniedException(
                    normalized == null
                            ? "That student has no class on the register, so you cannot help them."
                            : normalized + " is not one of your classes.");
        }
    }

    // ------------------------------------------------------- the caller

    /** The signed-in account, or null when there is none (an internal call). */
    @Transactional(readOnly = true)
    public User signedInUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            return null;
        }
        return userRepository.findByUsername(authentication.getName()).orElse(null);
    }

    /** True when the signed-in account holds the ADMIN role. */
    public boolean isAdmin() {
        return hasRole("ADMIN");
    }

    /** True when the signed-in account holds the TEACHER role. */
    public boolean isTeacher() {
        return hasRole("TEACHER");
    }

    private static boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (("ROLE_" + role).equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** True when this account is a teacher's, whatever the security context says. */
    private static boolean isATeacherAccount(User user) {
        return user != null && user.getRole() == User.Role.TEACHER;
    }

    // -------------------------------------------------- class assignments

    /** The classes assigned to one teacher, in a stable order. */
    @Transactional(readOnly = true)
    public List<String> assignedClasses(User teacher) {
        if (teacher == null || teacher.getId() == null) {
            return List.of();
        }
        return assignedClasses(teacher.getId());
    }

    /**
     * The classes assigned to one teacher, sorted by name.
     *
     * <p>The service sorts as well as asking the repository for sorted rows: the
     * order is part of what these lists promise (they are printed and compared),
     * so it is held here rather than assumed of whichever store answered.</p>
     */
    @Transactional(readOnly = true)
    public List<String> assignedClasses(Long userId) {
        return sorted(teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(userId));
    }

    /** One list's order, in one place, so every read and every write agrees. */
    private static List<String> sorted(List<String> classNames) {
        if (classNames == null || classNames.isEmpty()) {
            return List.of();
        }
        return classNames.stream().sorted().toList();
    }

    /** The classes assigned to the signed-in teacher, or every class for an administrator. */
    @Transactional(readOnly = true)
    public List<String> classesOf(User caller) {
        if (caller != null && caller.getRole() == User.Role.ADMIN) {
            return studentRepository.findDistinctClassNames();
        }
        return assignedClasses(caller);
    }

    /**
     * Replaces a teacher's class list. Kept here beside the rule so an assignment
     * and the check that reads it can never drift apart.
     *
     * <p>The names are normalised and then sorted, and the rows are written and
     * returned in that same sorted order — a caller is handed the list it will
     * read back, not an arbitrary ordering of whatever {@code Set} it passed
     * in.</p>
     */
    @Transactional
    public List<String> assignClasses(User teacher, Set<String> classNames) {
        if (teacher == null) {
            throw new ResourceNotFoundException("Teacher not found");
        }
        Set<String> normalized = new LinkedHashSet<>();
        if (classNames != null) {
            for (String className : classNames) {
                String value = StudentPasswordPolicy.normalizeClass(className);
                if (value != null && !value.isEmpty()) {
                    normalized.add(value);
                }
            }
        }
        List<String> ordered = sorted(new ArrayList<>(normalized));
        teacherClassRepository.deleteByUserId(teacher.getId());
        teacherClassRepository.flush();
        List<TeacherClass> rows = new ArrayList<>(ordered.size());
        for (String className : ordered) {
            rows.add(TeacherClass.builder().user(teacher).className(className).build());
        }
        teacherClassRepository.saveAll(rows);
        log.info("Assigned {} class(es) to teacher {}", ordered.size(), teacher.getUsername());
        return ordered;
    }

    /**
     * The students a caller may help: every class's students for an
     * administrator, only their own classes for a teacher. A teacher with no
     * assignments gets an empty list rather than the whole school.
     */
    @Transactional(readOnly = true)
    public List<StudentDTO> studentsMayHelp(User caller, String classNameFilter) {
        List<String> allowed = classesOf(caller);
        if (allowed.isEmpty()) {
            return List.of();
        }
        String filter = StudentPasswordPolicy.normalizeClass(classNameFilter);
        if (filter != null && !allowed.contains(filter)) {
            throw new AccessDeniedException(filter + " is not one of your classes.");
        }
        LocalDate on = gradeCalculator.referenceDate();
        List<StudentDTO> result = new ArrayList<>();
        for (String className : filter == null ? allowed : List.of(filter)) {
            for (Student student : studentRepository.findWithUserByClassName(className)) {
                result.add(StudentDTO.from(student, on));
            }
        }
        return result;
    }
}
