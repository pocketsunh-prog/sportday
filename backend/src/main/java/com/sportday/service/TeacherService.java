package com.sportday.service;

import com.sportday.dto.TeacherUploadResultDTO;
import com.sportday.entity.TeacherClass;
import com.sportday.entity.User;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.TeacherClassRepository;
import com.sportday.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Creates and maintains teacher accounts from the administrator's staff list.
 *
 * <p>A teacher is an account ({@link User} with {@link User.Role#TEACHER}) plus a
 * set of classes they may help in ({@link TeacherClass}). The upload creates both
 * in one step, and re-uploading the same file <strong>updates</strong> the teacher
 * and <strong>replaces</strong> their class list rather than duplicating either,
 * so the staff list can be re-run as often as the office likes.</p>
 *
 * <p>See {@link TeacherPasswordPolicy} for the password rule. In short: a
 * password supplied in the file is used and kept on a re-upload; without one the
 * teacher gets the derived password the credentials sheet prints, and a later
 * re-upload that omits the column leaves an existing password alone.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeacherService {

    /** Upper bound on a single staff-list upload. */
    public static final int MAX_IMPORT_ROWS = 5000;

    private final UserRepository userRepository;
    private final TeacherClassRepository teacherClassRepository;
    private final TeacherImportParser parser;
    private final TeacherPasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;

    /** One teacher as the upload sees them, once validated. */
    private record ValidatedTeacher(String username, String name, String email,
                                    List<String> classes, String suppliedPassword) {
    }

    private static class RowValidationException extends RuntimeException {
        private final String username;

        RowValidationException(String username, String message) {
            super(message);
            this.username = username;
        }
    }

    // ------------------------------------------------------------- import

    @Transactional
    public TeacherUploadResultDTO importTeachers(InputStream input, String fileName) {
        return importTeachers(input, fileName, false);
    }

    /**
     * Bulk upload, mirroring {@link StudentService#importStudents}: a missing
     * required column fails the file, a bad row is reported and skipped, and
     * {@code dryRun} classifies every row without writing anything.
     */
    @Transactional
    public TeacherUploadResultDTO importTeachers(InputStream input, String fileName, boolean dryRun) {
        String batch = "TCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        List<TeacherImportParser.RawRow> rows;
        try {
            rows = parser.parse(fileName, input);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read the uploaded file", ex);
        } catch (IllegalStateException ex) {
            // e.g. Excel support is unavailable because Apache POI is missing.
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
        if (rows.size() > MAX_IMPORT_ROWS) {
            throw new IllegalArgumentException(
                    "The file has " + rows.size() + " rows, which exceeds the " + MAX_IMPORT_ROWS + " row limit.");
        }

        TeacherUploadResultDTO result = TeacherUploadResultDTO.builder()
                .batch(batch)
                .fileName(fileName)
                .totalRows(rows.size())
                .dryRun(dryRun)
                .passwordRule(passwordPolicy.describeRule())
                .build();

        Set<String> seenInFile = new HashSet<>();
        for (TeacherImportParser.RawRow row : rows) {
            try {
                ValidatedTeacher teacher = validate(row);
                if (!seenInFile.add(teacher.username())) {
                    result.addError(row.rowNumber(), teacher.username(),
                            "Duplicate username — this teacher appears earlier in the same file.");
                    result.setFailed(result.getFailed() + 1);
                    continue;
                }

                Optional<User> existing = userRepository.findByUsername(teacher.username());
                boolean created = existing.isEmpty();

                if (dryRun) {
                    // Rehearsal: classify the row without touching the database.
                    if (created) {
                        result.setCreated(result.getCreated() + 1);
                    } else {
                        result.setUpdated(result.getUpdated() + 1);
                    }
                    continue;
                }

                User saved = upsert(teacher, existing.orElse(null));
                replaceClasses(saved, teacher.classes());
                result.setClassesAssigned(result.getClassesAssigned() + teacher.classes().size());
                if (created) {
                    result.setCreated(result.getCreated() + 1);
                } else {
                    result.setUpdated(result.getUpdated() + 1);
                }

                // Hand back any credential this run is the only source of: a new
                // account, or one whose password the file deliberately set.
                if (created || teacher.suppliedPassword() != null) {
                    String password = teacher.suppliedPassword() != null
                            ? teacher.suppliedPassword()
                            : passwordPolicy.generate(teacher.username());
                    result.getCredentials().add(TeacherUploadResultDTO.Credential.builder()
                            .username(teacher.username())
                            .name(teacher.name())
                            .email(teacher.email())
                            .classes(teacher.classes())
                            .password(password)
                            .supplied(teacher.suppliedPassword() != null)
                            .build());
                }
            } catch (RowValidationException ex) {
                result.addError(row.rowNumber(), ex.username, ex.getMessage());
                result.setFailed(result.getFailed() + 1);
            }
        }

        log.info("Teacher import {} from '{}'{}: {} created, {} updated, {} failed, {} class assignment(s)",
                batch, fileName, dryRun ? " [rehearsal]" : "",
                result.getCreated(), result.getUpdated(), result.getFailed(), result.getClassesAssigned());
        return result;
    }

    /**
     * Creates or updates the account itself. A teacher's password is only
     * rewritten when the file supplies one; omitting the column means "leave the
     * login as it is", so re-uploading a staff list cannot silently lock out a
     * teacher who has since changed their password.
     */
    private User upsert(ValidatedTeacher row, User existing) {
        String encoded = row.suppliedPassword() == null
                ? null
                : passwordEncoder.encode(row.suppliedPassword());

        if (existing == null) {
            String password = row.suppliedPassword() != null
                    ? row.suppliedPassword()
                    : passwordPolicy.generate(row.username());
            return userRepository.save(User.builder()
                    .username(row.username())
                    .password(passwordEncoder.encode(password))
                    .email(row.email())
                    .fullName(row.name())
                    .role(User.Role.TEACHER)
                    .enabled(true)
                    .build());
        }

        existing.setFullName(row.name());
        existing.setEmail(row.email());
        existing.setRole(User.Role.TEACHER);
        // A teacher the office still lists is active again, so re-uploading
        // restores somebody who had been disabled.
        existing.setEnabled(true);
        if (encoded != null) {
            existing.setPassword(encoded);
        }
        return userRepository.save(existing);
    }

    /**
     * Replaces the teacher's class list. Re-uploading is idempotent: the old rows
     * are removed first, so a class that is no longer in the file is no longer
     * one the teacher may help in.
     */
    private void replaceClasses(User user, List<String> classes) {
        teacherClassRepository.deleteByUserId(user.getId());
        // The deletes and the inserts share one (user_id, class_name) unique key,
        // so they are flushed apart to keep the order unambiguous.
        teacherClassRepository.flush();
        List<TeacherClass> rows = new ArrayList<>(classes.size());
        for (String className : classes) {
            rows.add(TeacherClass.builder()
                    .user(user)
                    .className(className)
                    .build());
        }
        teacherClassRepository.saveAll(rows);
    }

    private ValidatedTeacher validate(TeacherImportParser.RawRow row) {
        String username = trim(row.get(TeacherImportParser.Field.USERNAME));
        if (username == null) {
            throw new RowValidationException(null, "Missing username.");
        }
        String name = trim(row.get(TeacherImportParser.Field.NAME));
        if (name == null) {
            throw new RowValidationException(username, "Missing teacher name.");
        }
        String rawClasses = trim(row.get(TeacherImportParser.Field.CLASSES));
        List<String> classes = TeacherImportParser.parseClasses(rawClasses);
        if (classes.isEmpty()) {
            throw new RowValidationException(username,
                    "Missing classes — a teacher must be assigned at least one class "
                            + "(e.g. 1A;3B), otherwise they can help nobody.");
        }
        String email = trim(row.get(TeacherImportParser.Field.EMAIL));
        if (email != null && !email.contains("@")) {
            throw new RowValidationException(username,
                    "Unrecognised email address '" + email + "'.");
        }
        if (email != null) {
            // Email is unique across accounts, so one already held by somebody
            // else must be refused here rather than blowing up the whole file.
            Optional<User> holder = userRepository.findByEmail(email);
            if (holder.isPresent() && !holder.get().getUsername().equals(username)) {
                throw new RowValidationException(username,
                        "Email " + email + " is already used by account " + holder.get().getUsername() + ".");
            }
        }

        String password = trim(row.get(TeacherImportParser.Field.PASSWORD));
        if (password != null && password.length() < TeacherPasswordPolicy.MIN_SUPPLIED_LENGTH) {
            throw new RowValidationException(username,
                    "Password is too short — use at least " + TeacherPasswordPolicy.MIN_SUPPLIED_LENGTH
                            + " characters, or leave the column out to use the generated one.");
        }

        return new ValidatedTeacher(username, name, email, classes, password);
    }

    // ------------------------------------------------------------ queries

    /**
     * Creates (or updates) one teacher from form fields rather than a file, by
     * running the same importer over a one-row CSV. That keeps a single
     * implementation of the rules — the classes column format, the username and
     * email uniqueness checks and the password policy — rather than a second,
     * subtly different path for the single-account case.
     */
    @Transactional
    public TeacherUploadResultDTO createTeacher(String username, String name, String email,
                                                String classes, String password) {
        StringBuilder csv = new StringBuilder("username,name,email,classes,password\r\n");
        csv.append(csvCell(username)).append(',')
                .append(csvCell(name)).append(',')
                .append(csvCell(email)).append(',')
                .append(csvCell(classes)).append(',')
                .append(csvCell(password)).append("\r\n");
        return importTeachers(
                new java.io.ByteArrayInputStream(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "single-teacher.csv", false);
    }

    /** Quotes a value for the one-row CSV the single-teacher path feeds the importer. */
    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    /** Every teacher account, in username order. */
    @Transactional(readOnly = true)
    public List<User> listTeachers() {
        return userRepository.findAll().stream()
                .filter(user -> user.getRole() == User.Role.TEACHER)
                .sorted((a, b) -> a.getUsername().compareToIgnoreCase(b.getUsername()))
                .toList();
    }

    /**
     * The credentials sheet for the teachers, mirroring the student one: one row
     * per teacher with the classes they may help in and the password their
     * account was given.
     *
     * <p>A password supplied in the upload is not stored in clear text, so the
     * sheet prints the <em>derived</em> password beside a note saying so whenever
     * one was supplied — a sheet that quietly printed the wrong password would be
     * worse than no sheet.</p>
     */
    @Transactional(readOnly = true)
    public String credentialsCsv() {
        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF').append("username,name,email,classes,password\r\n");
        for (User teacher : listTeachers()) {
            List<String> classes = teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(teacher.getId());
            csv.append(escape(teacher.getUsername())).append(',')
                    .append(escape(teacher.getFullName())).append(',')
                    .append(escape(teacher.getEmail())).append(',')
                    .append(escape(String.join(";", classes))).append(',')
                    .append(escape(passwordPolicy.generate(teacher.getUsername())))
                    .append("\r\n");
        }
        return csv.toString();
    }

    /** An empty upload template, in the column order the importer accepts. */
    @Transactional(readOnly = true)
    public String templateCsv() {
        return "\uFEFF" + String.join(",", "username", "name", "email", "classes", "password") + "\r\n"
                + "tchan,Chan Tai Man,tchan@school.edu.hk,1A;3B,\r\n"
                + "wlee,Lee Siu Ming,,2C,\r\n";
    }

    // ------------------------------------------------------------ helpers

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The classes one teacher may help in, sorted. */
    @Transactional(readOnly = true)
    public List<String> classesOf(Long userId) {
        return teacherClassRepository.findClassNamesByUserIdOrderByClassNameAsc(userId);
    }

    /**
     * Replaces one teacher's class list without re-uploading the whole staff file.
     *
     * <p>For fixing a mistyped class, or a teacher who has changed year group, without
     * disturbing anybody else's assignments. The list comes back sorted, so the staff
     * page never shows the same teacher's classes in a different order twice.</p>
     */
    @Transactional
    public List<String> setClasses(String username, String rawClasses) {
        User teacher = requireTeacher(username);
        Set<String> names = new TreeSet<>(TeacherImportParser.parseClasses(rawClasses));
        if (names.isEmpty()) {
            throw new IllegalArgumentException(
                    "Please give at least one class the teacher looks after.");
        }
        teacherClassRepository.deleteByUserId(teacher.getId());
        teacherClassRepository.flush();
        for (String className : names) {
            teacherClassRepository.save(
                    TeacherClass.builder().user(teacher).className(className).build());
        }
        log.info("Assigned {} class(es) to teacher {}", names.size(), username);
        return new ArrayList<>(names);
    }

    /**
     * Removes a teacher account and their class assignments.
     *
     * <p>Only a TEACHER may be removed this way. An administrator or a manager is not
     * touched: deleting one by mistake through a staff-maintenance path would lock the
     * school out of its own system.</p>
     */
    @Transactional
    public void deleteTeacher(String username) {
        User teacher = requireTeacher(username);
        teacherClassRepository.deleteByUserId(teacher.getId());
        teacherClassRepository.flush();
        userRepository.delete(teacher);
        log.info("Removed the teacher account {}", username);
    }

    /** The teacher with this username, or a refusal saying why not. */
    private User requireTeacher(String username) {
        User teacher = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("No account called " + username));
        if (teacher.getRole() != User.Role.TEACHER) {
            throw new IllegalArgumentException(
                    username + " is not a teacher account, so it is not changed here.");
        }
        return teacher;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
