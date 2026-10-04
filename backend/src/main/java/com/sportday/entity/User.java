package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    /**
     * Optional: staff accounts have one, imported student accounts do not.
     * MySQL permits repeated NULLs in a unique index, so this stays unique.
     */
    @Column(unique = true)
    private String email;

    private String fullName;

    private Integer age;

    private String gender;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (enabled == null) enabled = true;
        if (role == null) role = Role.USER;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum Role {
        /** Staff/legacy account that may browse and enter events manually. */
        USER,
        ADMIN,
        MANAGER,
        /** Imported student account: username is the student id. */
        STUDENT,
        /**
         * A teacher. A teacher may help a student in one of the classes assigned
         * to them ({@link TeacherClass}) enter or withdraw from events, and read
         * what that needs — the register of their own classes, the programme and
         * their own profile. Nothing else: no mark entry, no settings, no uploads,
         * no user management.
         */
        TEACHER
    }
}
