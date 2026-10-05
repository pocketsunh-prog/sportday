package com.sportday.dto;

import com.sportday.entity.RelayTeamMember;
import com.sportday.entity.Student;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One runner in a relay team: who they are, and which leg they run.
 *
 * <p>The athlete's class, house and grade are read from the register when the team
 * is read rather than stored on the leg, so the board always shows where the runner
 * is <em>now</em> — which is also what the eligibility rules were judged on.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RelayTeamMemberDTO {

    private Long id;
    private Long teamId;

    /** The login account — what entries, results and records are held against. */
    private Long userId;

    private String studentId;
    private String name;
    private String className;
    private String classLabel;

    /** The form the class belongs to — {@code 5} for {@code 5A}; null when it names none. */
    private String form;

    /** The house, in full, as the register stores it — {@code Red}. */
    private String house;

    /** The house's short code — {@code R}, {@code Y}, {@code B}, {@code G}; null for another house. */
    private String houseCode;

    private String grade;

    /** 1-based leg; {@code leg 1} runs first. */
    private Integer leg;

    /** True when this runner is past the race's own legs — a reserve. */
    private Boolean reserve;

    /**
     * @param legCount legs in the team's race, so a runner past them is marked as a
     *                 reserve without the DTO having to reach back through the team
     *                 to the event
     */
    public static RelayTeamMemberDTO from(RelayTeamMember member, Student roster, int legCount) {
        return RelayTeamMemberDTO.builder()
                .id(member.getId())
                .teamId(member.getTeam() == null ? null : member.getTeam().getId())
                .userId(member.getUser() == null ? null : member.getUser().getId())
                .studentId(roster == null ? null : roster.getStudentId())
                .name(roster == null
                        ? (member.getUser() == null ? null : member.getUser().getFullName())
                        : roster.getName())
                .className(roster == null ? null : roster.getClassName())
                .classLabel(roster == null ? null : roster.getClassLabel())
                .form(roster == null ? null : roster.getForm())
                .house(roster == null ? null : roster.getHouse())
                .houseCode(roster == null ? null : roster.getHouseCode())
                .grade(roster == null || roster.getGrade() == null ? null : roster.getGrade().name())
                .leg(member.getLeg())
                .reserve(legCount > 0 && member.getLeg() != null && member.getLeg() > legCount)
                .build();
    }
}
