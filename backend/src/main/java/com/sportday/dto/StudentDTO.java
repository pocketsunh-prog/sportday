package com.sportday.dto;

import com.sportday.entity.Student;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudentDTO {
    private Long id;
    private Long userId;
    private String studentId;
    private String name;
    private LocalDate dob;

    /** Age in whole years on the reference date used to derive the grade. */
    private Integer age;

    private String sex;
    private String sexLabel;
    private String className;
    private Integer classNumber;
    private String classLabel;

    /**
     * The form the class belongs to — {@code 5} for class {@code 5A}. Null when the
     * class name does not begin with a digit and so names no form. Read from the
     * class by {@link Student#formOf(String)}, the one place either is derived.
     */
    private String form;

    /** The house, in full, as the register stores it — {@code Red}. */
    private String house;

    /**
     * The house's short code — {@code R}, {@code Y}, {@code B}, {@code G} — for a
     * sheet or a grid that has room for one letter. Null for a house that is not one
     * of those four. See {@link Student#houseCodeOf(String)}.
     */
    private String houseCode;

    private String grade;
    private String gradeLabel;
    private String gradeAgeRange;
    private Boolean enabled;
    private String importBatch;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static StudentDTO from(Student student, LocalDate referenceDate) {
        Integer age = null;
        if (student.getDob() != null && referenceDate != null) {
            age = java.time.Period.between(student.getDob(), referenceDate).getYears();
        }
        return StudentDTO.builder()
                .id(student.getId())
                .userId(student.getUser() == null ? null : student.getUser().getId())
                .studentId(student.getStudentId())
                .name(student.getName())
                .dob(student.getDob())
                .age(age)
                .sex(student.getSex() == null ? null : student.getSex().name())
                .sexLabel(student.getSex() == null ? null : student.getSex().getLabel())
                .className(student.getClassName())
                .classNumber(student.getClassNumber())
                .classLabel(student.getClassLabel())
                .form(Student.formOf(student.getClassName()))
                .house(student.getHouse())
                .houseCode(Student.houseCodeOf(student.getHouse()))
                .grade(student.getGrade() == null ? null : student.getGrade().name())
                .gradeLabel(student.getGrade() == null ? null : student.getGrade().getLabel())
                .gradeAgeRange(student.getGrade() == null ? null : student.getGrade().getAgeRange())
                .enabled(student.getEnabled())
                .importBatch(student.getImportBatch())
                .createdAt(student.getCreatedAt())
                .updatedAt(student.getUpdatedAt())
                .build();
    }
}
