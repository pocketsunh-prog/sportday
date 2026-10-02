package com.sportday.dto;

import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventGroup;
import com.sportday.entity.Student;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An entry in an event, flattened so it can be serialised without touching
 * lazy JPA associations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnrollmentDTO {

    private Long id;

    // ---- event ----
    private Long eventId;
    private String eventName;
    private String eventType;
    private String eventTypeLabel;
    private String category;
    private String categoryLabel;

    /**
     * The unit this event's marks are recorded in — {@code s} for a track event,
     * {@code M} for a field one. Lets an entry page show the unit without looking
     * the event up again.
     */
    private String defaultUnit;
    private String sex;
    private String sexLabel;
    private LocalDate eventDate;
    private String location;

    // ---- group / heat ----
    private Long groupId;
    private Integer groupNumber;
    private String groupLabel;
    private Integer lane;

    /** Paper size of this event's marking sheet: {@code A5} or {@code A4}. */
    private String sheetSize;

    // ---- athlete ----
    private Long studentId;
    private Long userId;
    private String studentRef;
    private String name;
    private String grade;
    private String className;
    private Integer classNumber;
    private String house;

    // ---- entry ----
    private String status;
    private LocalDateTime enrolledAt;

    public static EnrollmentDTO from(Enrollment enrollment) {
        return from(enrollment, null);
    }

    /**
     * @param roster the student record behind the login account, may be null
     *               for legacy staff accounts that entered an event directly
     */
    public static EnrollmentDTO from(Enrollment enrollment, Student roster) {
        Event event = enrollment.getEvent();
        EventGroup group = enrollment.getEventGroup();

        EnrollmentDTOBuilder builder = EnrollmentDTO.builder()
                .id(enrollment.getId())
                .status(enrollment.getStatus() == null ? null : enrollment.getStatus().name())
                .enrolledAt(enrollment.getEnrolledAt())
                .groupId(group == null ? null : group.getId())
                .groupNumber(group == null ? null : group.getGroupNumber())
                .groupLabel(group == null ? null : group.getLabel())
                .lane(enrollment.getLane());

        if (event != null) {
            builder.eventId(event.getId())
                    .eventName(event.getName())
                    .eventType(event.getType() == null ? null : event.getType().name())
                    .eventTypeLabel(event.getType() == null ? null : event.getType().getDisplayName())
                    .category(event.getCategoryOrDefault().name())
                    .categoryLabel(event.getCategoryOrDefault().getLabel())
                    .sex(event.getSex() == null ? null : event.getSex().name())
                    .sexLabel(event.getSex() == null ? null : event.getSex().getLabel())
                    .eventDate(event.getEventDate())
                    .location(event.getLocation())
                    .sheetSize(event.isShortSprint() ? "A5" : "A4")
                    .defaultUnit(event.getType() == null ? null : event.getType().getDefaultUnit());
        }

        var user = enrollment.getUser();
        if (user != null) {
            builder.userId(user.getId());
        }
        if (roster != null) {
            builder.studentId(roster.getId())
                    .studentRef(roster.getStudentId())
                    .name(roster.getName())
                    .grade(roster.getGrade() == null ? null : roster.getGrade().name())
                    .className(roster.getClassName())
                    .classNumber(roster.getClassNumber())
                    .house(roster.getHouse());
        } else if (user != null) {
            builder.studentRef(user.getUsername()).name(user.getFullName());
        }
        return builder.build();
    }
}
