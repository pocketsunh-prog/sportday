package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A relay team a teacher builds by hand out of students they chose.
 *
 * <p>The school's requirement, as confirmed: a teacher <strong>ticks any students
 * who applied</strong> to the relay and <strong>creates a team from them</strong>,
 * typing the team's own name as free text — {@code 1A}, {@code B Grade Yellow}, or
 * anything the school writes. The team is deliberately <strong>not</strong> required
 * to be one class or one house, which is exactly what the derived
 * {@code FORM} and {@code HOUSE} teams cannot express.</p>
 *
 * <p>Unlike {@link RelayRenameRequest}, a name here is not just a new label on a team
 * that already exists: it is the whole identity the team is created with, so a blank,
 * over-long or already-used name is refused rather than corrected. The name is
 * trimmed first.</p>
 *
 * <p>{@link #userIds} is the runners, <strong>in the order they are to run</strong>:
 * leg 1 is the first student listed. Fewer than four is allowed and the team is
 * reported incomplete, because a teacher collects a squad a name at a time; a sixth
 * runner is refused unless the event allows a reserve, and even then the ceiling is
 * the event's own {@code memberCap}.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RelayTeamCreateRequest {

    /** What the school writes on the sheet. Free text, trimmed, at most 40 characters. */
    private String name;

    /** The chosen students' accounts, in leg order — the first runs leg 1. */
    private List<Long> userIds;
}
