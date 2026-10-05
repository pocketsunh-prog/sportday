package com.sportday.dto;

import com.sportday.entity.Event;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The relay board for one event: what kind of relay it is, how big a team is, and
 * every team with its runners.
 *
 * <p>An event that is not a relay is refused before this is built, and a relay with
 * no kind is <em>undivided</em> — it has no teams, and this reports that rather than
 * inventing any, which is how the relay events already in the programme behave.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RelayEventTeamsDTO {

    private Long eventId;
    private String eventName;

    /** Enum name, e.g. {@code RELAY_4X100M}. */
    private String eventType;
    private String eventTypeLabel;

    /** {@code MALE} or {@code FEMALE} — the division the teams are drawn from. */
    private String sex;

    /** {@code A}, {@code B} or {@code C} — the grade the teams are drawn from. */
    private String grade;

    /**
     * The <strong>form</strong> the teams are drawn from — {@code 1} for a "Form 1
     * 4x100M" of {@code 1A}, {@code 1B}, {@code 1C} and {@code 1D} — or null when the
     * event is scoped by its grade instead.
     *
     * <p>It is the scope of the board: the classes a form relay derives are every class
     * of this form, across grades, so the grade beside it says nothing about who may
     * run. A house relay is always grade-scoped and carries no form.</p>
     */
    private String form;

    /** Printable form, {@code Form 1}; null when the board is graded. */
    private String formLabel;

    /** {@code FORM} or {@code HOUSE}; null when the relay is undivided. */
    private String relayTeamKind;
    private String relayTeamKindLabel;

    /** True when the event is a relay at all. */
    private Boolean relay;

    /** Legs in a team — four for a 4x100M. */
    private Integer legsPerTeam;

    /** True when a team may also name reserves past its legs. */
    private Boolean reservesAllowed;

    /** How many runners one team may hold in total. */
    private Integer memberCap;

    private Integer teamCount;
    private Integer runnerCount;

    private List<RelayTeamDTO> teams;

    /**
     * The students who applied to this event — those with a confirmed entry in it —
     * each of whom may be placed in one of the teams above. Ordered by class in
     * school order, then class number and name, so the page reads the way the
     * register does.
     *
     * <p>A teacher's copy of the board carries only the applicants of their own
     * classes, because those are the students they may place; an administrator's
     * carries all of them. The teams themselves are unchanged on both.</p>
     */
    private List<RelayApplicantDTO> applicants;

    /** How many applicants this board shows. */
    private Integer applicantCount;

    /** How many of them already hold a leg in one of the teams above. */
    private Integer placedCount;

    /** How many are still unplaced — {@code applicantCount - placedCount}. */
    private Integer unplacedCount;

    /** The teams and counts of a board with no applicant list. */
    public static RelayEventTeamsDTO of(Event event, List<RelayTeamDTO> teams) {
        return of(event, teams, List.of());
    }

    public static RelayEventTeamsDTO of(Event event, List<RelayTeamDTO> teams,
                                        List<RelayApplicantDTO> applicants) {
        int runners = teams.stream()
                .mapToInt(team -> team.getMemberCount() == null ? 0 : team.getMemberCount())
                .sum();
        List<RelayApplicantDTO> roster = applicants == null ? List.of() : applicants;
        int placed = (int) roster.stream()
                .filter(applicant -> applicant.getTeamId() != null)
                .count();
        return RelayEventTeamsDTO.builder()
                .eventId(event.getId())
                .eventName(event.getName())
                .eventType(event.getType() == null ? null : event.getType().name())
                .eventTypeLabel(event.getType() == null ? null : event.getType().getDisplayName())
                .sex(event.getSex() == null ? null : event.getSex().name())
                .grade(event.getGrade() == null ? null : event.getGrade().name())
                .form(event.getForm())
                .formLabel(event.getFormLabel())
                .relayTeamKind(event.getRelayTeamKind() == null ? null : event.getRelayTeamKind().name())
                .relayTeamKindLabel(event.getRelayTeamKind() == null
                        ? null : event.getRelayTeamKind().getLabel())
                .relay(event.isRelay())
                .legsPerTeam(event.getEffectiveRelayTeamSize())
                .reservesAllowed(event.isRelayReservesAllowed())
                .memberCap(event.getRelayMemberCap())
                .teamCount(teams.size())
                .runnerCount(runners)
                .teams(teams)
                .applicants(roster)
                .applicantCount(roster.size())
                .placedCount(placed)
                .unplacedCount(roster.size() - placed)
                .build();
    }
}
