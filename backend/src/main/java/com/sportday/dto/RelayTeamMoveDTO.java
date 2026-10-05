package com.sportday.dto;

import com.sportday.entity.Event;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * What a move of a draft relay event's teams onto the real event did.
 *
 * <p>Stated as counts rather than a bare success, because the school asked for the
 * teams to be carried and the answer should say how much was carried: how many teams,
 * how many runners, and which event they are now on. {@code draftEventId} and
 * {@code draftEventName} name where they came from, so one response reads on its own.
 * {@code targetEvent} is the event as the rest of the API describes it.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RelayTeamMoveDTO {

    private Long draftEventId;
    private String draftEventName;

    /** The event the teams are now on. */
    private Long targetEventId;
    private String targetEventName;

    /** How many teams were re-pointed. */
    private Integer teamsMoved;

    /** How many runners those teams hold between them — the legs and reserves. */
    private Integer runnersMoved;

    /** What each team is called, in board order, so the move is readable. */
    private List<String> teamLabels;

    /** The target event, described the way every other event endpoint describes it. */
    private EventDTO targetEvent;

    public static RelayTeamMoveDTO of(Event draft, Event target, List<String> teamLabels,
                                      int runners) {
        return RelayTeamMoveDTO.builder()
                .draftEventId(draft.getId())
                .draftEventName(draft.getName())
                .targetEventId(target.getId())
                .targetEventName(target.getName())
                .teamsMoved(teamLabels.size())
                .runnersMoved(runners)
                .teamLabels(teamLabels)
                .build();
    }
}
