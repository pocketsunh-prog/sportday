package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * An imported student record. Created by the administrator's bulk upload
 * (CSV or XLSX) and paired with a {@link User} login account whose
 * {@code username} is the {@link #studentId}.
 *
 * <p>The student's password is never stored here: it is derived from the
 * record ({@code yyyyMMdd + class + class number}) and only its BCrypt hash
 * lives on the linked {@link User}.</p>
 */
@Entity
@Table(name = "students", indexes = {
    @Index(name = "idx_students_class", columnList = "class_name"),
    @Index(name = "idx_students_grade", columnList = "grade"),
    @Index(name = "idx_students_house", columnList = "house")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Login account backing this student. Username == {@link #studentId}. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User user;

    @Column(name = "student_id", nullable = false, unique = true, length = 40)
    private String studentId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private LocalDate dob;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Sex sex;

    /** Class code as printed on the register, e.g. {@code 5A}. */
    @Column(name = "class_name", nullable = false, length = 20)
    private String className;

    @Column(name = "class_number", nullable = false)
    private Integer classNumber;

    @Column(nullable = false, length = 40)
    private String house;

    /** Derived from {@link #dob}, never entered by hand. See {@code GradeCalculator}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private Grade grade;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** Import batch this record came from, for auditing/reverting an upload. */
    @Column(name = "import_batch", length = 60)
    private String importBatch;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (enabled == null) enabled = true;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (enabled == null) enabled = true;
    }

    /** Convenience for views: {@code 5A 12}. */
    @Transient
    public String getClassLabel() {
        return className + " " + (classNumber == null ? "" : classNumber);
    }

    // ==================================================== form and house code
    //
    // These two derivations live HERE, on the record they are read from, and
    // nowhere else. The register, the mark grid, the relay rosters, the marking
    // sheets and anything else that shows a student's form or house code all ask
    // this class, so none of them can drift from another: `5A` is Form 5 and
    // ` Red ` is R wherever it is shown, because there is only one answer.

    /**
     * The form a class belongs to: the leading run of digits of the class name, so
     * {@code 1A}, {@code 1B} and {@code 1C} are all Form 1, and {@code 10B} is
     * Form 10 rather than Form 1. Leading zeros are dropped, so {@code 01A} is
     * Form 1 as well.
     *
     * @return the form as a string, or {@code null} when the class name does not
     *         start with a digit and so names no form
     */
    public static String formOf(String className) {
        if (className == null) {
            return null;
        }
        String trimmed = className.trim();
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        String digits = trimmed.substring(0, end);
        int firstSignificant = 0;
        while (firstSignificant < digits.length() - 1 && digits.charAt(firstSignificant) == '0') {
            firstSignificant++;
        }
        return digits.substring(firstSignificant);
    }

    /**
     * The short code of a house: {@code Red} is {@code R}, {@code Yellow} {@code Y},
     * {@code Blue} {@code B} and {@code Green} {@code G}.
     *
     * <p>Matched on the trimmed, case-insensitive name, because the register holds
     * {@code red}, {@code  Red } and {@code RED} as readily as {@code Red} — the same
     * tolerance the rest of the house handling has.</p>
     *
     * <p><strong>A house that is not one of the four yields {@code null}</strong>,
     * not a first letter. Inventing one would put a letter on a sheet that no house
     * owns, and a first-letter fallback can collide with a real code — {@code Black}
     * would read {@code B}, which is Blue's — so a school with a fifth house gets an
     * honest blank instead of a wrong letter. The house keeps its full name
     * everywhere; only this code is derived from it.</p>
     *
     * @return {@code R}, {@code Y}, {@code B} or {@code G}, or {@code null} for an
     *         unknown, blank or missing house
     */
    public static String houseCodeOf(String house) {
        if (house == null) {
            return null;
        }
        return switch (house.trim().toLowerCase(Locale.ROOT)) {
            case "red" -> "R";
            case "yellow" -> "Y";
            case "blue" -> "B";
            case "green" -> "G";
            default -> null;
        };
    }

    /** {@link #formOf(String)} of this student's own class. */
    @Transient
    public String getForm() {
        return formOf(className);
    }

    /** {@link #houseCodeOf(String)} of this student's own house. */
    @Transient
    public String getHouseCode() {
        return houseCodeOf(house);
    }
}
