package com.sportday.dto;

import com.sportday.entity.Student;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One student who <strong>applied</strong> to a relay event — a confirmed entry in
 * that event — as the page that builds relay teams needs to show them.
 *
 * <p>This is the list beside the teams: the teacher ticks the applicants and groups
 * them into the teams the event already has. It is deliberately not the roster of a
 * team, which is {@link RelayTeamMemberDTO}; the difference between the two is that
 * an applicant need not be on a team yet, and {@link #teamId} says whether they
 * are.</p>
 *
 * <p>Form, class and house are read from the register when the board is read, so
 * they are what the register holds <em>now</em> — and they are derived in one place,
 * {@link Student#formOf(String)} and {@link Student#houseCodeOf(String)}, so the
 * register, this list, the mark grid and the marking sheets cannot disagree about
 * which form {@code 5A} is or which letter {@code Red} has.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RelayApplicantDTO {

    /** The login account — what entries, team legs and results are held against. */
    private Long userId;

    /** The student id on the register, e.g. {@code S0001}. */
    private String studentRef;

    private String name;

    /** The form the class belongs to — {@code 5} for {@code 5A}; null when it names none. */
    private String form;

    private String className;
    private Integer classNumber;
    private String classLabel;

    /** The house, in full, as the register stores it — {@code Red}. */
    private String house;

    /** The house's short code — {@code R}, {@code Y}, {@code B}, {@code G}; null for another house. */
    private String houseCode;

    /**
     * The team this applicant already runs for in this event, or null when they are
     * still unplaced. An athlete holds at most one leg of an event, so this is one
     * team or none rather than a list.
     */
    private Long teamId;

    /** That team's name as the school writes it — {@code 1A}, {@code C Grade Yellow}. */
    private String teamLabel;

    /** True when {@link #teamId} is set, so a page can count or filter the unplaced. */
    private Boolean placed;
}
