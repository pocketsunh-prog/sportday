package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One class a teacher is allowed to help.
 *
 * <p>A teacher may only act for students in the classes assigned to them, so the
 * assignment is a row per class rather than a comma-separated string on the
 * account: it can be queried (which classes would this teacher see?), it is
 * unique per teacher and class by construction, and re-uploading a teacher
 * replaces the set instead of appending to a string that nobody can validate.</p>
 *
 * <p>A teacher with <strong>no</strong> rows here can help nobody — the refusal
 * says so rather than quietly allowing everything. An administrator is not
 * covered by this table at all and may help any student.</p>
 */
@Entity
@Table(name = "teacher_classes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_teacher_class", columnNames = {"user_id", "class_name"}),
        indexes = @Index(name = "idx_teacher_classes_class", columnList = "class_name"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeacherClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The teacher's login account. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User user;

    /**
     * The class name, stored the same way as {@link Student#getClassName()} —
     * upper-cased with whitespace removed — so a comparison is exact and a
     * teacher assigned {@code " 1a "} still matches class {@code 1A}.
     */
    @Column(name = "class_name", nullable = false, length = 20)
    private String className;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
