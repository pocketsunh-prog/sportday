package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A batch of marks typed into the grid and saved in one go.
 *
 * <p>Each row is independent: a row carrying a mark is inserted or updated, a row
 * flagged {@code clear} has its mark removed, and a row with no mark at all is
 * left alone. One bad row never loses the rest of the batch — it comes back in
 * {@link Result#getErrors()}.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkMarkRequest {

    /**
     * Which stage the grid is for: {@code HEAT} (the default) or {@code FINAL}.
     * Heats and the final keep separate marks, so this is what stops a final time
     * from overwriting the heat time that earned the athlete their place.
     */
    private String stage;

    @Builder.Default
    private List<Entry> rows = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Entry {
        private Long userId;

        /** The recorded mark; null means "nothing to save for this athlete". */
        private BigDecimal mark;

        /**
         * A field athlete's attempts, in order. A missed attempt is left null or
         * absent — the last attempt may simply be omitted rather than padded. The
         * best of them becomes the mark, so a helper can fill in only what was
         * thrown or jumped.
         */
        private List<BigDecimal> attempts;

        /**
         * For a race longer than 400M, the time as a stopwatch reads it — whole
         * minutes and the seconds left over. Sent instead of {@code mark}, which the
         * server works out as the total in seconds.
         */
        private Integer minutes;
        private BigDecimal seconds;

        /** Blank falls back to the event's default unit (s or M). */
        private String unit;

        private String notes;

        /** True to remove this athlete's mark instead of setting one. */
        private Boolean clear;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Result {
        private Long eventId;
        private String eventName;

        /** {@code HEAT} or {@code FINAL} — the stage these marks were written to. */
        private String stage;

        private int saved;
        private int cleared;
        private int skipped;
        private int failed;

        /** Marks recorded for the event after the save, for the leaderboard view. */
        @Builder.Default
        private List<EventResultDTO> results = new ArrayList<>();

        @Builder.Default
        private List<RowError> errors = new ArrayList<>();

        public void addError(Long userId, String message) {
            if (errors == null) {
                errors = new ArrayList<>();
            }
            errors.add(RowError.builder().userId(userId).message(message).build());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RowError {
        private Long userId;
        private String message;
    }
}
