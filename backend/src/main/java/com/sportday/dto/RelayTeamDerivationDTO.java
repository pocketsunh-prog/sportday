package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What deriving an event's relay teams did.
 *
 * <p>Deriving is <strong>additive</strong>: it creates the teams the roster calls
 * for and updates their labels, and it never removes a team that a teacher has put
 * runners into, because those selections are not the roster's to throw away. A team
 * that is no longer on the roster and has nobody in it is only removed when the
 * caller explicitly asks to prune.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RelayTeamDerivationDTO {

    private Long eventId;
    private String eventName;

    /** {@code FORM} or {@code HOUSE} — the kind the teams were derived for. */
    private String kind;

    /** Teams created by this call. */
    private Integer created;

    /** Teams that were already there and kept, labels refreshed. */
    private Integer kept;

    /** Teams dropped because they are no longer on the roster and are empty. */
    private Integer pruned;

    /** Teams kept only because somebody runs in them, though the roster no longer calls for them. */
    private Integer keptWithRunners;

    /** How many students the teams were derived from — the event's grade and division. */
    private Integer eligibleStudents;

    private RelayEventTeamsDTO board;
}
