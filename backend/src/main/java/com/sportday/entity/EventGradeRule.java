package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Whether one grade may enter one event type.
 *
 * <p>Some events are not for everybody: a C grade student — fourteen or under — does
 * not run the 1500M, and only the oldest grade runs the 5000M. Everything else is
 * open to all three grades.</p>
 *
 * <p>Keyed by <strong>event type</strong> rather than by event, because
 * {@code Boys 1500M} and {@code Girls 1500M} are the same race in two divisions and a
 * school sets the rule once.</p>
 *
 * <p>A missing row means <em>allowed</em>: the table is seeded with the school's
 * default rules, and an event type added later should not silently become closed to
 * everyone.</p>
 */
@Entity
@Table(name = "event_grade_rules", uniqueConstraints = {
    @UniqueConstraint(name = "uk_grade_rule_type_grade", columnNames = {"event_type", "grade"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventGradeRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private Event.EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private Grade grade;

    /** True when this grade may enter this event. */
    @Column(nullable = false)
    private Boolean allowed;

    /**
     * The grades a school starts with for an event type, before any administrator
     * changes them. Only the long distances are restricted: everything else — the
     * sprints, the 800M, the hurdles, the relays and every field event — is open to
     * all three grades.
     */
    public static java.util.Set<Grade> defaultAllowedGrades(Event.EventType type) {
        if (type == null) {
            return java.util.EnumSet.allOf(Grade.class);
        }
        return switch (type) {
            // Only the oldest grade runs the 5000M.
            case RUN_5000M -> java.util.EnumSet.of(Grade.A);
            // The C grade — fourteen or under — does not run the 1500M either.
            case RUN_1500M -> java.util.EnumSet.of(Grade.A, Grade.B);
            default -> java.util.EnumSet.allOf(Grade.class);
        };
    }
}
