package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a bulk student upload. Row level problems are reported instead of
 * aborting the whole file, so an administrator can fix a handful of bad rows
 * and re-upload without losing the good ones.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudentUploadResultDTO {

    /** Identifier for this import run, stamped onto every student it touched. */
    private String batch;

    private String fileName;
    private int totalRows;
    private int created;
    private int updated;
    private int failed;

    /**
     * True when this was a rehearsal: nothing was written, and the counts say what
     * <em>would</em> happen. Used to show the administrator who a full-roster
     * upload would lock before they commit to it.
     */
    private boolean dryRun;

    /** True when the upload was declared to be the complete student list. */
    private boolean lockAbsent;

    /** Students locked because the upload did not include them. */
    private int locked;

    /** Students who were locked before and are in this upload, so are active again. */
    private int unlocked;

    /** Who would be (or was) locked — a sample, for the confirmation screen. */
    @Builder.Default
    private List<LockedStudent> lockedStudents = new ArrayList<>();

    /** How many students would be locked in total, when that is more than the sample. */
    private int lockedTotal;

    /** Reference date used to derive grades for this upload. */
    private LocalDate gradeReferenceDate;

    /** Grade -> number of students after the upload, e.g. {@code {C=240, B=198, A=162}}. */
    @Builder.Default
    private Map<String, Long> gradeCounts = new LinkedHashMap<>();

    @Builder.Default
    private List<RowError> errors = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RowError {
        private int rowNumber;
        private String studentId;
        private String message;
    }

    /** A student the upload left out. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LockedStudent {
        private String studentId;
        private String name;
        private String className;
        private Integer classNumber;
    }

    public void addError(int rowNumber, String studentId, String message) {
        if (errors == null) {
            errors = new ArrayList<>();
        }
        errors.add(RowError.builder()
                .rowNumber(rowNumber)
                .studentId(studentId)
                .message(message)
                .build());
    }
}
