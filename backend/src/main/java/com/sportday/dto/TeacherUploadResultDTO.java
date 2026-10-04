package com.sportday.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of a bulk teacher upload.
 *
 * <p>Row-level problems are reported instead of aborting the whole file, so an
 * administrator can fix a handful of bad rows and re-upload without losing the
 * good ones — exactly as the student register import behaves.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeacherUploadResultDTO {

    /** Identifier for this import run, stamped onto every teacher it touched. */
    private String batch;

    private String fileName;
    private int totalRows;
    private int created;
    private int updated;
    private int failed;

    /** True when this was a rehearsal: nothing was written, the counts say what would happen. */
    private boolean dryRun;

    /** Teachers whose class list was replaced by this upload (created or updated alike). */
    private int classesAssigned;

    /** How the generated passwords are derived, so the rule is not a secret. */
    private String passwordRule;

    /**
     * The credentials this run produced — one row per teacher it created, and per
     * teacher whose password was supplied in the file. For a new teacher nobody
     * knows the password otherwise, so it is handed back the way the student
     * import hands back a failed row: in the response, to be noted down.
     *
     * <p>Always empty on a rehearsal, because a rehearsal generates nothing.</p>
     */
    @Builder.Default
    private List<Credential> credentials = new ArrayList<>();

    @Builder.Default
    private List<RowError> errors = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RowError {
        private int rowNumber;
        /** The username the row carried, when it had one. */
        private String username;
        private String message;
    }

    /** One teacher's login details, for the credentials sheet. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Credential {
        private String username;
        private String name;
        private String email;
        private List<String> classes;
        private String password;
        /** True when the password came from the file rather than being derived. */
        private boolean supplied;
    }

    public void addError(int rowNumber, String username, String message) {
        if (errors == null) {
            errors = new ArrayList<>();
        }
        errors.add(RowError.builder()
                .rowNumber(rowNumber)
                .username(username)
                .message(message)
                .build());
    }
}
