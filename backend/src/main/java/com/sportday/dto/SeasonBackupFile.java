package com.sportday.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The shape of a season backup file.
 *
 * <p>One JSON document, written by {@code SeasonBackupService} immediately before a
 * season reset clears the database, so a reset that was not wanted can be undone.
 * It holds exactly what a reset destroys — the entries, the heats and the final,
 * the recorded marks and the final's field — plus the school records, which the
 * reset keeps but which belong in the picture anyway.</p>
 *
 * <p>Every value is a plain id or a plain field off an entity, so the document can
 * be read without the application: an administrator can open it and see what was
 * there. {@link Header} is written first and carries the counts, so a file can be
 * identified and believed before the body is read.</p>
 *
 * <p>{@code app: jackson.default-property-inclusion: non_null} drops absent values,
 * which keeps a file small and readable without losing the distinction between
 * "nothing recorded" and "recorded as null".</p>
 */
public final class SeasonBackupFile {

    /** The application that wrote the file, checked before a restore. */
    public static final String APP_NAME = "SportDay";

    /** Bumped when the body's shape changes in a way an older reader cannot follow. */
    public static final int FORMAT_VERSION = 1;

    private SeasonBackupFile() {
    }

    // ---------------------------------------------------------------- document

    /**
     * A whole backup file: a header a human can believe, then the state itself.
     *
     * <p>The lists are the rows the file holds, written after the header; the header's
     * counts are taken from those same lists, so the two can be compared rather than
     * trusted. The order is pinned here because "the header comes first" is part of
     * how a file is meant to be read.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"app", "version", "header", "groups", "enrollments",
            "finalEntries", "results", "records"})
    public record BackupFile(
            /** The application that wrote it, so the file identifies itself. */
            String app,
            /** The body's shape, so a future reader can refuse what it cannot read. */
            int version,
            Header header,
            List<EventGroupLine> groups,
            List<EnrollmentLine> enrollments,
            List<FinalEntryLine> finalEntries,
            List<EventResultLine> results,
            List<EventRecordLine> records) {
    }

    /**
     * What the file is and what it holds — first in the file, so a listing can read a
     * few hundred bytes of a large backup and know the answer, and so a person opening
     * it sees what it is before what is in it.
     */
    @JsonPropertyOrder({"app", "version", "writtenAt", "counts", "note"})
    public record Header(
            String app,
            int version,
            LocalDateTime writtenAt,
            /** How many of each row the body carries, so the two can be compared. */
            Counts counts,
            String note) {
    }

    /** The row counts, in the order the reset's own summary reports them. */
    public record Counts(
            long enrollments,
            long finalEntries,
            long groups,
            long results,
            long records) {
    }

    // -------------------------------------------------------------- the rows

    /**
     * An entry, as it stood: who, in which event, in what state, and which heat and
     * lane they had been drawn into.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnrollmentLine(
            Long id,
            Long userId,
            Long eventId,
            String status,
            /** The heat or final the entry was allocated to; null before the draw. */
            Long groupId,
            /** Lane inside that heat; null before the draw. */
            Integer lane,
            LocalDateTime enrolledAt) {
    }

    /**
     * A finalist's place. Separate from the entry, because a finalist stays in their
     * heat: the group here is the {@code FINAL} group, identified by its own id.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FinalEntryLine(
            Long id,
            /** The {@code FINAL} group this place belongs to. */
            Long groupId,
            Long userId,
            Integer lane,
            Integer seed,
            /** The heat mark that earned the place. */
            BigDecimal seedMark,
            String seedUnit,
            LocalDateTime createdAt) {
    }

    /** One heat, or the final — group number 0 is the final. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventGroupLine(
            Long id,
            Long eventId,
            Integer groupNumber,
            String stage,
            Integer capacity,
            Integer athleteCount,
            LocalDateTime createdAt) {
    }

    /** One recorded mark, at one stage, with a field athlete's three attempts. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventResultLine(
            Long id,
            Long userId,
            Long eventId,
            String stage,
            BigDecimal mark,
            String unit,
            BigDecimal attempt1,
            BigDecimal attempt2,
            BigDecimal attempt3,
            String notes,
            String outcome,
            LocalDateTime recordedAt) {
    }

    /**
     * One school record — "Boys 100M · B Grade".
     *
     * <p>The administrator's typed-in baseline is carried in full, because that is
     * the part a reset deliberately keeps and it cannot be rebuilt from anything
     * else. The mark that stands is carried too so the file reads as a record sheet,
     * and it is also rebuilt from the restored results when the file is put back.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventRecordLine(
            Long id,
            String eventType,
            String sex,
            String grade,
            /** The mark an administrator typed in — the school's own history. */
            BigDecimal manualMark,
            String manualUnit,
            String manualHolderName,
            LocalDate manualAchievedOn,
            /** The mark that stood, and where it came from. */
            BigDecimal mark,
            String unit,
            String source,
            String holderName,
            /** Who held it, as an account id. */
            Long holderUserId,
            LocalDate achievedOn,
            /** The result that held it, by id — the foreign key a restore has to re-point. */
            Long resultId,
            /** The event the standing mark was set in, by id. */
            Long eventId,
            BigDecimal previousMark,
            String previousHolderName,
            LocalDate previousAchievedOn) {
    }

    // ------------------------------------------------------------ describing a file

    /**
     * A backup file on disk, described for a listing: what it is called, how big it
     * is, when it was written, and the counts out of its header.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BackupSummary(
            String name,
            long bytes,
            LocalDateTime writtenAt,
            Map<String, Object> counts,
            /** Set when the file could not be read, so a listing can say which file is suspect. */
            String problem) {
    }
}
