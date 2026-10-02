package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

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
}
