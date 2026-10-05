package com.sportday.dto;

import com.sportday.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDTO {
    private Long id;
    private String username;
    private String email;
    private String fullName;
    private Integer age;
    private String gender;
    private String role;
    private Boolean enabled;
    private LocalDateTime createdAt;

    // ---- the roster record, for a student account ----

    /** The student id, which is also their username. Null for staff accounts. */
    private String studentRef;

    /**
     * The grade the student competes in — A, B or C. Null for staff, and for a
     * student who has no roster record. The entry pages need it to leave out the
     * events a grade may not enter, so it is read from the register rather than
     * inferred from whatever they have already entered.
     */
    private String grade;

    private String gradeLabel;

    private String className;
    private Integer classNumber;

    /** The form the class belongs to — {@code 5} for {@code 5A}; null when it names none. */
    private String form;

    /** The house, in full, as the register stores it — {@code Red}. */
    private String house;

    /** The house's short code — {@code R}, {@code Y}, {@code B}, {@code G}; null for another house. */
    private String houseCode;

    public static UserDTO from(User user) {
        return UserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .age(user.getAge())
                .gender(user.getGender())
                .role(user.getRole().name())
                .enabled(user.getEnabled())
                .createdAt(user.getCreatedAt())
                .build();
    }

    /** As {@link #from(User)}, plus the roster record when there is one. */
    public static UserDTO from(User user, com.sportday.entity.Student student) {
        UserDTO dto = from(user);
        applyRoster(dto, student);
        return dto;
    }

    /** Fills in the roster fields on an already-built DTO. */
    public static void applyRoster(UserDTO dto, com.sportday.entity.Student student) {
        if (dto == null || student == null) {
            return;
        }
        dto.setStudentRef(student.getStudentId());
        if (student.getGrade() != null) {
            dto.setGrade(student.getGrade().name());
            dto.setGradeLabel(student.getGrade().getLabel());
        }
        dto.setClassName(student.getClassName());
        dto.setClassNumber(student.getClassNumber());
        dto.setForm(student.getForm());
        dto.setHouse(student.getHouse());
        dto.setHouseCode(student.getHouseCode());
    }
}
