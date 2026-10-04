package com.sportday.service;

import com.sportday.dto.StudentDTO;
import com.sportday.dto.StudentUploadResultDTO;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.Student;
import com.sportday.entity.User;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.StudentRepository;
import com.sportday.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Imports, lists and maintains student records.
 *
 * <p>Every student record owns a login account ({@link User}) whose username is
 * the student id and whose password is the derived
 * {@code yyyyMMdd + class + class number} value. Importing the same file twice
 * updates the existing records rather than creating duplicates, and resets each
 * password back to the derived value so it can never drift out of sync.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentService {

    private final StudentRepository studentRepository;
    private final UserRepository userRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final EventResultRepository eventResultRepository;
    private final PasswordEncoder passwordEncoder;
    private final GradeCalculator gradeCalculator;
    private final StudentPasswordPolicy passwordPolicy;
    private final StudentImportParser parser;
    private final StudentSampleDataGenerator generator;

    /** Upper bound on a single register upload. */
    public static final int MAX_IMPORT_ROWS = 20000;

    /** How many students about to be locked are named in the response. */
    private static final int LOCK_REPORT_LIMIT = 100;

    // ------------------------------------------------------------- import

    /**
     * Imports a CSV/XLSX register.
     *
     * @param referenceDate date the grades are computed for; defaults to the
     *                      configured sport-day date, or today
     */
    @Transactional
    public StudentUploadResultDTO importStudents(InputStream input, String fileName, LocalDate referenceDate) {
        return importStudents(input, fileName, referenceDate, false, false);
    }

    /**
     * Bulk import, optionally treating the file as the year's complete student list.
     *
     * <p>With {@code lockAbsent}, every student the file does <em>not</em> mention is
     * locked: they can no longer sign in or be entered in an event, though their
     * entries, results and records are kept, and an administrator can unlock them.
     * Because that is destructive, {@code dryRun} reports exactly what would happen
     * without writing anything, so the administrator can check the list of students
     * about to be locked before committing.</p>
     *
     * <p>A student who was locked before and appears in this file becomes active
     * again, so re-uploading restores somebody who has returned.</p>
     */
    @Transactional
    public StudentUploadResultDTO importStudents(InputStream input, String fileName, LocalDate referenceDate,
                                                 boolean lockAbsent, boolean dryRun) {
        LocalDate on = referenceDate != null ? referenceDate : gradeCalculator.referenceDate();
        String batch = "IMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        List<StudentImportParser.RawRow> rows;
        try {
            rows = parser.parse(fileName, input);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read the uploaded file", ex);
        } catch (IllegalStateException ex) {
            // e.g. Excel support is unavailable because Apache POI is missing.
            // A 400 with the explanation is far more useful to the administrator
            // than a 409 carrying the same text under a "conflict" label.
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
        if (rows.size() > MAX_IMPORT_ROWS) {
            throw new IllegalArgumentException(
                    "The file has " + rows.size() + " rows, which exceeds the " + MAX_IMPORT_ROWS + " row limit.");
        }

        StudentUploadResultDTO result = StudentUploadResultDTO.builder()
                .batch(batch)
                .fileName(fileName)
                .totalRows(rows.size())
                .gradeReferenceDate(on)
                .dryRun(dryRun)
                .lockAbsent(lockAbsent)
                .build();

        Set<String> seenInFile = new HashSet<>();
        Set<String> idsInFile = new LinkedHashSet<>();
        for (StudentImportParser.RawRow row : rows) {
            try {
                ValidatedStudent validated = validate(row, on);
                if (!seenInFile.add(validated.studentId())) {
                    result.addError(row.rowNumber(), validated.studentId(),
                            "Duplicate student id — this student appears earlier in the same file.");
                    result.setFailed(result.getFailed() + 1);
                    continue;
                }
                idsInFile.add(validated.studentId());

                Student before = studentRepository.findByStudentId(validated.studentId()).orElse(null);
                if (before != null && !Boolean.TRUE.equals(before.getEnabled())) {
                    result.setUnlocked(result.getUnlocked() + 1);
                }

                if (dryRun) {
                    // Rehearsal: classify the row without touching the database.
                    if (before == null) {
                        result.setCreated(result.getCreated() + 1);
                    } else {
                        result.setUpdated(result.getUpdated() + 1);
                    }
                } else if (upsert(validated, batch)) {
                    result.setCreated(result.getCreated() + 1);
                } else {
                    result.setUpdated(result.getUpdated() + 1);
                }
            } catch (RowValidationException ex) {
                result.addError(row.rowNumber(), ex.studentId, ex.getMessage());
                result.setFailed(result.getFailed() + 1);
            }
        }

        if (lockAbsent) {
            List<Student> toLock = studentsMissingFrom(idsInFile);
            result.setLockedTotal(toLock.size());
            for (Student student : toLock) {
                if (result.getLockedStudents().size() >= LOCK_REPORT_LIMIT) {
                    break;
                }
                result.getLockedStudents().add(StudentUploadResultDTO.LockedStudent.builder()
                        .studentId(student.getStudentId())
                        .name(student.getName())
                        .className(student.getClassName())
                        .classNumber(student.getClassNumber())
                        .build());
            }
            if (!dryRun) {
                applyLock(toLock);
                result.setLocked(toLock.size());
            }
        }

        result.setGradeCounts(gradeCounts());
        log.info("Student import {} from '{}'{}: {} created, {} updated, {} failed"
                        + (lockAbsent ? ", {} of {} absent student(s) locked" : "")
                        + " (grade date {})",
                batch, fileName, dryRun ? " [rehearsal]" : "",
                result.getCreated(), result.getUpdated(), result.getFailed(),
                result.getLocked(), result.getLockedTotal(), on);
        return result;
    }

    /** Active students the upload did not mention — the ones a full roster locks. */
    private List<Student> studentsMissingFrom(Set<String> idsInFile) {
        return studentRepository.findAll().stream()
                .filter(student -> Boolean.TRUE.equals(student.getEnabled()))
                .filter(student -> !idsInFile.contains(student.getStudentId()))
                .sorted(Comparator.comparing(Student::getClassName)
                        .thenComparing(Student::getClassNumber)
                        .thenComparing(Student::getStudentId))
                .toList();
    }

    /** Locks the students, and their login accounts with them. */
    private void applyLock(List<Student> students) {
        if (students.isEmpty()) {
            return;
        }
        for (Student student : students) {
            student.setEnabled(false);
        }
        studentRepository.saveAll(students);
        List<User> accounts = students.stream()
                .map(Student::getUser)
                .filter(Objects::nonNull)
                .peek(user -> user.setEnabled(false))
                .toList();
        if (!accounts.isEmpty()) {
            userRepository.saveAll(accounts);
        }
        log.warn("Locked {} student(s) missing from the uploaded roster", students.size());
    }

    // -------------------------------------------------------------- locking

    /** Locks one student: no sign-in, no new entries, history kept. */
    @Transactional
    public StudentDTO setStudentLocked(String studentId, boolean locked) {
        Student student = studentRepository.findByStudentId(studentId)
                .orElseThrow(() -> new ResourceNotFoundException("Student not found: " + studentId));
        student.setEnabled(!locked);
        studentRepository.save(student);
        if (student.getUser() != null) {
            student.getUser().setEnabled(!locked);
            userRepository.save(student.getUser());
        }
        log.info("Student {} {}", studentId, locked ? "locked" : "unlocked");
        return StudentDTO.from(student, gradeCalculator.referenceDate());
    }

    /** The batch id of the most recent upload, or null when nothing was uploaded. */
    @Transactional(readOnly = true)
    public String latestImportBatch() {
        List<String> batches = studentRepository.findBatchesByRecency();
        return batches.isEmpty() ? null : batches.get(0);
    }

    /** Locks every active student who was not in the most recent upload. */
    @Transactional
    public int lockStudentsMissingFrom(String importBatch) {
        List<Student> toLock = studentRepository.findAll().stream()
                .filter(student -> Boolean.TRUE.equals(student.getEnabled()))
                .filter(student -> importBatch == null || !importBatch.equals(student.getImportBatch()))
                .toList();
        applyLock(toLock);
        return toLock.size();
    }

    // ------------------------------------------------------------- sample

    /**
     * Replaces/creates {@code count} generated students. Existing records with
     * the same student ids are updated, so this is safe to re-run.
     */
    @Transactional
    public StudentUploadResultDTO seedSampleData(int count, LocalDate referenceDate) {
        LocalDate on = referenceDate != null ? referenceDate : gradeCalculator.referenceDate();
        String batch = "SAMPLE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        List<StudentSampleDataGenerator.StudentSpec> specs = generator.generate(count, on);
        StudentUploadResultDTO result = StudentUploadResultDTO.builder()
                .batch(batch)
                .fileName("generated-" + count + "-students")
                .totalRows(specs.size())
                .gradeReferenceDate(on)
                .build();

        for (StudentSampleDataGenerator.StudentSpec spec : specs) {
            String password = passwordPolicy.generate(spec.dob(), spec.className(), spec.classNumber());
            ValidatedStudent validated = new ValidatedStudent(
                    spec.studentId(),
                    spec.name(),
                    spec.dob(),
                    spec.sex(),
                    StudentPasswordPolicy.normalizeClass(spec.className()),
                    spec.classNumber(),
                    spec.house(),
                    gradeCalculator.gradeFor(spec.dob(), on),
                    password);
            boolean created = upsert(validated, batch);
            if (created) {
                result.setCreated(result.getCreated() + 1);
            } else {
                result.setUpdated(result.getUpdated() + 1);
            }
        }

        result.setGradeCounts(gradeCounts());
        log.info("Seeded {} sample students in batch {} (grade date {})", specs.size(), batch, on);
        return result;
    }

    // ------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public List<StudentDTO> listStudents(String className, Sex sex, Grade grade, String house) {
        return listStudents(className, sex, grade, house, null);
    }

    /**
     * The register, filtered. {@code enabled} narrows it to active or to locked
     * students — locked ones are those a yearly roster upload did not include.
     */
    @Transactional(readOnly = true)
    public List<StudentDTO> listStudents(String className, Sex sex, Grade grade, String house,
                                         Boolean enabled) {
        LocalDate on = gradeCalculator.referenceDate();
        List<Student> students;
        if (className != null && !className.isBlank()) {
            students = studentRepository.findWithUserByClassName(StudentPasswordPolicy.normalizeClass(className));
        } else if (sex != null) {
            students = studentRepository.findWithUserBySex(sex);
        } else {
            students = studentRepository.findAllWithUser();
        }
        return students.stream()
                .filter(s -> grade == null || s.getGrade() == grade)
                .filter(s -> house == null || house.isBlank() || house.equalsIgnoreCase(s.getHouse()))
                .filter(s -> enabled == null || enabled.equals(Boolean.TRUE.equals(s.getEnabled())))
                .map(s -> StudentDTO.from(s, on))
                .toList();
    }

    /** How many students are locked, for the register summary. */
    @Transactional(readOnly = true)
    public long countLocked() {
        return studentRepository.findAll().stream()
                .filter(student -> !Boolean.TRUE.equals(student.getEnabled()))
                .count();
    }

    @Transactional(readOnly = true)
    public StudentDTO getByStudentId(String studentId) {
        Student student = studentRepository.findByStudentId(studentId)
                .orElseThrow(() -> new ResourceNotFoundException("Student not found: " + studentId));
        return StudentDTO.from(student, gradeCalculator.referenceDate());
    }

    @Transactional(readOnly = true)
    public Map<String, Long> gradeCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Grade grade : Grade.values()) {
            counts.put(grade.name(), studentRepository.countByGrade(grade));
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public Map<String, Long> sexCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Sex sex : Sex.values()) {
            counts.put(sex.name(), studentRepository.countBySex(sex));
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public long totalStudents() {
        return studentRepository.count();
    }

    /**
     * Recomputes every student's grade against the given reference date. Grades
     * are stored, so this is how an administrator moves the whole school onto a
     * new sport-day date.
     */
    @Transactional
    public int recomputeGrades(LocalDate referenceDate) {
        LocalDate on = referenceDate != null ? referenceDate : gradeCalculator.referenceDate();
        List<Student> students = studentRepository.findAll();
        int changed = 0;
        for (Student student : students) {
            Grade grade = gradeCalculator.gradeFor(student.getDob(), on);
            if (grade != student.getGrade()) {
                student.setGrade(grade);
                changed++;
            }
        }
        studentRepository.saveAll(students);
        log.info("Recomputed grades for {} students against {} ({} changed)", students.size(), on, changed);
        return changed;
    }

    /**
     * Removes a student and the login account behind it, together with any
     * entries and recorded results, so the foreign keys stay valid. Used when a
     * student leaves the school or was imported in error.
     */
    @Transactional
    public void deleteStudent(String studentId) {
        Student student = studentRepository.findByStudentId(studentId)
                .orElseThrow(() -> new ResourceNotFoundException("Student not found: " + studentId));
        User user = student.getUser();

        if (user != null) {
            List<com.sportday.entity.Enrollment> entries = enrollmentRepository.findByUserId(user.getId());
            if (!entries.isEmpty()) {
                enrollmentRepository.deleteAll(entries);
                enrollmentRepository.flush();
            }
            eventResultRepository.deleteByUserId(user.getId());
        }
        studentRepository.delete(student);
        studentRepository.flush();
        if (user != null) {
            userRepository.delete(user);
        }
        log.info("Deleted student {} and their login account", studentId);
    }

    /**
     * A credentials sheet the administrator can hand out: student id, name,
     * class and the derived password.
     */
    @Transactional(readOnly = true)
    public String credentialsCsv(String className) {
        List<Student> students = (className == null || className.isBlank())
                ? studentRepository.findAllWithUser()
                : studentRepository.findWithUserByClassName(StudentPasswordPolicy.normalizeClass(className));

        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF').append("studentId,name,class,classNumber,house,grade,password\r\n");
        DateTimeFormatter dobFormat = StudentPasswordPolicy.DOB_FORMAT;
        for (Student student : students) {
            csv.append(escape(student.getStudentId())).append(',')
                    .append(escape(student.getName())).append(',')
                    .append(escape(student.getClassName())).append(',')
                    .append(student.getClassNumber()).append(',')
                    .append(escape(student.getHouse())).append(',')
                    .append(student.getGrade().name()).append(',')
                    .append(escape(student.getDob().format(dobFormat)
                            + student.getClassName() + student.getClassNumber()))
                    .append("\r\n");
        }
        return csv.toString();
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

    // ------------------------------------------------------------- helpers

    /** A row that passed validation and is ready to be written. */
    private record ValidatedStudent(
            String studentId,
            String name,
            LocalDate dob,
            Sex sex,
            String className,
            Integer classNumber,
            String house,
            Grade grade,
            String password) {
    }

    private static class RowValidationException extends RuntimeException {
        private final String studentId;

        RowValidationException(String studentId, String message) {
            super(message);
            this.studentId = studentId;
        }
    }

    private ValidatedStudent validate(StudentImportParser.RawRow row, LocalDate referenceDate) {
        String studentId = trim(row.get(StudentImportParser.Field.STUDENT_ID));
        if (studentId == null) {
            throw new RowValidationException(null, "Missing student id.");
        }
        String name = trim(row.get(StudentImportParser.Field.NAME));
        if (name == null) {
            throw new RowValidationException(studentId, "Missing student name.");
        }
        String rawDob = trim(row.get(StudentImportParser.Field.DOB));
        if (rawDob == null) {
            throw new RowValidationException(studentId, "Missing date of birth.");
        }
        LocalDate dob = StudentImportParser.parseDate(rawDob);
        if (dob == null) {
            throw new RowValidationException(studentId,
                    "Unrecognised date of birth '" + rawDob + "' — use YYYY-MM-DD.");
        }
        if (dob.isAfter(referenceDate)) {
            throw new RowValidationException(studentId,
                    "Date of birth " + dob + " is after the grade reference date " + referenceDate + ".");
        }
        if (dob.isBefore(referenceDate.minusYears(100))) {
            throw new RowValidationException(studentId, "Date of birth " + dob + " is implausible.");
        }
        Sex sex = Sex.fromCode(row.get(StudentImportParser.Field.SEX));
        if (sex == null) {
            throw new RowValidationException(studentId,
                    "Unrecognised sex '" + trim(row.get(StudentImportParser.Field.SEX)) + "' — use M or F.");
        }
        String className = trim(row.get(StudentImportParser.Field.CLASS_NAME));
        if (className == null) {
            throw new RowValidationException(studentId, "Missing class.");
        }
        className = StudentPasswordPolicy.normalizeClass(className);
        String rawNumber = trim(row.get(StudentImportParser.Field.CLASS_NUMBER));
        if (rawNumber == null) {
            throw new RowValidationException(studentId, "Missing class number.");
        }
        Integer classNumber;
        try {
            // Tolerate "12", "12.0" and "No.12" style cell contents.
            String digits = rawNumber.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) {
                throw new NumberFormatException(rawNumber);
            }
            classNumber = Integer.valueOf(digits);
        } catch (NumberFormatException ex) {
            throw new RowValidationException(studentId,
                    "Unrecognised class number '" + rawNumber + "' — expected a whole number.");
        }
        if (classNumber < 1 || classNumber > 999) {
            throw new RowValidationException(studentId, "Class number " + classNumber + " is out of range.");
        }
        String house = trim(row.get(StudentImportParser.Field.HOUSE));
        if (house == null) {
            house = "Unassigned";
        }

        return new ValidatedStudent(studentId, name, dob, sex, className, classNumber, house,
                gradeCalculator.gradeFor(dob, referenceDate),
                passwordPolicy.generate(dob, className, classNumber));
    }

    /** Creates or updates the student and its login account. Returns true when created. */
    private boolean upsert(ValidatedStudent row, String batch) {
        User user = userRepository.findByUsername(row.studentId()).orElse(null);
        Student student = studentRepository.findByStudentId(row.studentId()).orElse(null);
        boolean created = student == null && user == null;

        int age = gradeCalculator.ageOn(row.dob(), gradeCalculator.referenceDate());
        // The password is always re-derived so a re-upload cannot leave a
        // student with a password that no longer matches their record.
        String encoded = passwordEncoder.encode(row.password());

        if (user == null) {
            user = User.builder()
                    .username(row.studentId())
                    .password(encoded)
                    .fullName(row.name())
                    .age(age)
                    .gender(row.sex().getCode())
                    .role(User.Role.STUDENT)
                    .enabled(true)
                    .build();
        } else {
            user.setPassword(encoded);
            user.setFullName(row.name());
            user.setAge(age);
            user.setGender(row.sex().getCode());
            user.setRole(User.Role.STUDENT);
            // Being on the list is what makes a student active, so a student who
            // was locked for missing a previous upload becomes active again.
            user.setEnabled(true);
        }
        user = userRepository.save(user);

        if (student == null) {
            student = Student.builder()
                    .user(user)
                    .studentId(row.studentId())
                    .name(row.name())
                    .dob(row.dob())
                    .sex(row.sex())
                    .className(row.className())
                    .classNumber(row.classNumber())
                    .house(row.house())
                    .grade(row.grade())
                    .enabled(true)
                    .importBatch(batch)
                    .build();
        } else {
            student.setUser(user);
            student.setName(row.name());
            student.setDob(row.dob());
            student.setSex(row.sex());
            student.setClassName(row.className());
            student.setClassNumber(row.classNumber());
            student.setHouse(row.house());
            student.setGrade(row.grade());
            student.setImportBatch(batch);
            student.setEnabled(true);
        }
        studentRepository.save(student);
        return created;
    }

    /** Generates the sample CSV in the same column order as the upload format. */
    public List<String> sampleCsvLines(int count, LocalDate referenceDate) {
        LocalDate on = referenceDate != null ? referenceDate : gradeCalculator.referenceDate();
        List<StudentSampleDataGenerator.StudentSpec> specs = generator.generate(count, on);
        List<String> lines = new ArrayList<>(specs.size() + 1);
        lines.add("studentId,name,dob,sex,className,classNumber,house");
        for (var spec : specs) {
            lines.add(String.join(",",
                    spec.studentId(),
                    spec.name(),
                    spec.dob().format(DateTimeFormatter.ISO_LOCAL_DATE),
                    spec.sex().getCode(),
                    spec.className(),
                    String.valueOf(spec.classNumber()),
                    spec.house()));
        }
        return lines;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
