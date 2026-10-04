package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Naming a runner for a relay leg.
 *
 * <p>{@code leg} is optional: left out, the runner takes the next free leg, so a
 * teacher filling a team from scratch never has to count. Given, it must be a leg
 * the team has not already filled — reordering an existing team is a separate
 * request, {@link RelayLegOrderRequest}.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RelayRunnerRequest {

    /** The athlete's login account. */
    private Long userId;

    /** Which leg they run, 1-based. Null means "the next free one". */
    private Integer leg;
}
