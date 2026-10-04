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

    public static RelayEventTeamsDTO of(Event event, List<RelayTeamDTO> teams) {
        int runners = teams.stream()
                .mapToInt(team -> team.getMemberCount() == null ? 0 : team.getMemberCount())
                .sum();
        return RelayEventTeamsDTO.builder()
                .eventId(event.getId())
                .eventName(event.getName())
                .eventType(event.getType() == null ? null : event.getType().name())
                .eventTypeLabel(event.getType() == null ? null : event.getType().getDisplayName())
                .sex(event.getSex() == null ? null : event.getSex().name())
                .grade(event.getGrade() == null ? null : event.getGrade().name())
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
                .build();
    }
}
