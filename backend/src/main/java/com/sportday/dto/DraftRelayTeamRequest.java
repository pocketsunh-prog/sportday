package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * A <strong>draft</strong> relay event, with the teams the school has already chosen.
 *
 * <p>The school's requirement: "create relay event base on selected relay team". The
 * teams come first, and a relay team cannot exist without an event, so the draft is
 * the event they are collected on — see
 * {@code EventService.createDraftEvent}. The fields are the ones an ordinary event
 * create takes, plus the teams.</p>
 *
 * <p>{@code teams} is the same shape as a single team made by hand
 * ({@link RelayTeamCreateRequest}): a name the school typed and the students who run
 * for it, {@code userIds} in leg order so the first student listed runs leg 1. Each
 * one is created through the ordinary call, so every rule is the ordinary rule and
 * there is no second copy of any of them.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DraftRelayTeamRequest {

    /** {@code RELAY_4X100M} or {@code RELAY_4X400M} — a draft is always a relay. */
    private String type;

    /** {@code MALE} or {@code FEMALE}. */
    private String sex;

    /** {@code A}, {@code B} or {@code C}. */
    private String grade;

    /** {@code FORM} or {@code HOUSE} — required: the teams are judged against it. */
    private String relayTeamKind;

    /**
     * The <strong>form</strong> the draft is scoped to — {@code 1} for a Form 1 relay
     * whose teams are {@code 1A}, {@code 1B}, {@code 1C} and {@code 1D} — or null for a
     * draft scoped by its grade, as every draft was before the form existed.
     *
     * <p>Only a {@code FORM} draft may carry one: a house draft is divided by grade and
     * house, so a form on it is refused rather than stored and ignored. The teams given
     * with the draft are created through {@code RelayTeamService.createTeam}, which
     * judges each runner by the same rule, so a Form 1 draft admits a runner of any
     * grade who is in Form 1 and the draft's division.</p>
     */
    private String form;

    /** Legs in a team; four unless the school runs a longer squad. */
    private Integer relayTeamSize;

    /** True when a team may also name one reserve past the race's legs. */
    private Boolean relayReservesAllowed;

    /** The school's own title, or the programme's default when it is left out. */
    private String name;
    private String description;
    private LocalDate eventDate;
    private String location;
    private Integer maxParticipants;
    private Integer groupSize;
    private Long seasonId;

    /** The teams already chosen, each with the name and the runners in leg order. */
    private List<RelayTeamCreateRequest> teams;
}
