package com.sportday.dto;

import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamMember;
import com.sportday.entity.Student;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * One relay team: which event it belongs to, whether it is a form or a house team,
 * and the runners down for its legs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RelayTeamDTO {

    private Long id;
    private Long eventId;
    private String eventName;

    /** {@code FORM} or {@code HOUSE}. */
    private String kind;
    private String kindLabel;

    /** The form number ({@code "1"}) or the house name ({@code "Red"}). */
    private String teamKey;

    /** What the team is shown as: the class ({@code 1A}) or {@code C Grade Yellow}. */
    private String label;

    /**
     * True when that name was typed by hand rather than derived from the register.
     * A client can show it as the team's own name, and knows a re-derive will not
     * take it away.
     */
    private Boolean nameOverridden;

    /**
     * True when the team was built by hand out of chosen students rather than derived
     * from the register — see {@link RelayTeam#isHandMade()}.
     *
     * <p>Such a team is <strong>not one class's and not one house's</strong>, so
     * {@link #kind} is null whatever kind the event has, and no class or house rule
     * applies to its runners. It is also not the roster's: a later derive leaves it
     * alone — never renamed, never re-keyed, never pruned. A client that draws a team
     * from {@link #kind} must therefore handle a team with none, and should show it as
     * the school's own team rather than as a broken one.</p>
     */
    private Boolean handMade;

    /** Legs in this team's race — four for a 4x100M. */
    private Integer legCount;

    /** How many runners the team may hold in total, reserves included. */
    private Integer memberCap;

    /** True when the event lets this team name reserves past its own legs. */
    private Boolean reservesAllowed;

    private Integer memberCount;

    /** True once every leg has a runner; reserves are not required to complete it. */
    private Boolean complete;

    /** The runners, leg 1 first, any reserves last. */
    private List<RelayTeamMemberDTO> members;

    /** Builds the team from its members, read together with the team itself. */
    public static RelayTeamDTO from(RelayTeam team, List<RelayTeamMember> members,
                                    Map<Long, Student> rosters) {
        int legCount = team.getLegCount();
        List<RelayTeamMember> ordered = new ArrayList<>(members == null ? List.of() : members);
        ordered.sort(Comparator.comparingInt(m -> m.getLeg() == null ? Integer.MAX_VALUE : m.getLeg()));

        List<RelayTeamMemberDTO> rows = new ArrayList<>(ordered.size());
        for (RelayTeamMember member : ordered) {
            Long userId = member.getUser() == null ? null : member.getUser().getId();
            rows.add(RelayTeamMemberDTO.from(member,
                    userId == null || rosters == null ? null : rosters.get(userId), legCount));
        }
        return RelayTeamDTO.builder()
                .id(team.getId())
                .eventId(team.getEvent() == null ? null : team.getEvent().getId())
                .eventName(team.getEvent() == null ? null : team.getEvent().getName())
                .kind(team.getKind() == null ? null : team.getKind().name())
                .kindLabel(team.getKind() == null ? null : team.getKind().getLabel())
                .teamKey(team.getTeamKey())
                .label(team.getLabel())
                .nameOverridden(team.isNameOverridden())
                .handMade(team.isHandMade())
                .legCount(legCount)
                .memberCap(team.getMemberCap())
                .reservesAllowed(team.getEvent() != null && team.getEvent().isRelayReservesAllowed())
                .memberCount(rows.size())
                .complete(legCount > 0 && rows.size() >= legCount)
                .members(rows)
                .build();
    }
}
