package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A record an administrator types in by hand — last season's best, or a mark held
 * by a student who has since left.
 *
 * <p>The holder is a name rather than an account, because the athlete who set a
 * school record is often no longer in the register.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecordBaselineDTO {

    /** The mark. Send null to clear the baseline and leave the record to the results. */
    private BigDecimal mark;

    private String unit;

    /** Free text — "Chan Tai Man", or the year, whatever the school records. */
    private String holderName;

    private LocalDate achievedOn;
}
