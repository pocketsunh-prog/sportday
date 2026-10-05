package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The event a draft relay event's teams are to be moved onto.
 *
 * <p>{@code POST /api/admin/relay-events/{draftEventId}/teams/move} with
 * {@code { "targetEventId": 7 }} re-points every team of the draft at the real relay
 * event, all of them or none — see {@code RelayTeamService.moveTeamsToEvent}.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RelayTeamMoveRequest {

    /** The real relay event the draft's teams now run in. */
    private Long targetEventId;
}
