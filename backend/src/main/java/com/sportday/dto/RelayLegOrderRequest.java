package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The running order of a relay team: the athletes' accounts, leg 1 first.
 *
 * <p>It must name <strong>exactly</strong> the runners the team already has — a
 * permutation of them — because this request sets the order rather than adding or
 * dropping anybody. A list that misses somebody, names a stranger or repeats an
 * athlete is refused with the reason instead of half-applied, and adding or removing
 * a runner is its own request.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RelayLegOrderRequest {

    /** The team's runners, in the order they are to run. */
    private List<Long> userIds;
}
