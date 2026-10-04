package com.sportday.service;

import com.sportday.dto.SeasonBackupFile;
import com.sportday.dto.SeasonBackupFile.BackupFile;
import com.sportday.dto.SeasonBackupFile.Counts;
import com.sportday.dto.SeasonBackupFile.EnrollmentLine;
import com.sportday.dto.SeasonBackupFile.EventGroupLine;
import com.sportday.dto.SeasonBackupFile.EventRecordLine;
import com.sportday.dto.SeasonBackupFile.EventResultLine;
import com.sportday.dto.SeasonBackupFile.FinalEntryLine;
import com.sportday.dto.SeasonBackupFile.Header;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventGroup;
import com.sportday.entity.EventRecord;
import com.sportday.entity.EventResult;
import com.sportday.entity.EventStage;
import com.sportday.entity.FinalEntry;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.User;
import com.sportday.exception.BackupFailedException;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRecordRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.FinalEntryRepository;
import com.sportday.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Backs a season up before it is reset, and puts one back afterwards.
 *
 * <h2>Why the backup is written first</h2>
 * <p>A season reset is one-way: it deletes every entry, heat, final place and mark
 * the school has recorded. The school asked for a way back, so
 * {@link #writeSeasonBackup()} runs <em>before</em> the first delete and a failure
 * to write the file is allowed to stop the reset — see
 * {@link SeasonResetService#resetSeason()}. There is no catch-and-continue anywhere
 * on that path: if the file is not on disk, nothing is deleted.</p>
 *
 * <h2>What a restore does</h2>
 * <p>It replaces the current season state with the file's: the entries, heats,
 * final places, marks and the records' baselines. Students, events, seasons and
 * settings are <em>not</em> restored — a backup is of one season's competition
 * data, and the register and programme are the things a reset deliberately keeps.
 * A row that names a student or event which no longer exists is skipped and
 * counted, rather than invented.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeasonBackupService {

    private final EnrollmentRepository enrollmentRepository;
    private final EventGroupRepository eventGroupRepository;
    private final EventResultRepository eventResultRepository;
    private final FinalEntryRepository finalEntryRepository;
    private final EventRecordRepository eventRecordRepository;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final RecordService recordService;
    private final EventService eventService;
    private final BackupStore backupStore;
    private final EntityManager entityManager;

    // ------------------------------------------------------------- backing up

    /**
     * Serialises the season — entries, heats, the final, the marks and the school
     * records — into one JSON file in the backup directory.
     *
     * <p>Every row the reset is about to delete is in here, and so is the records
     * table, which the reset keeps: a reset that empties a record because the mark
     * that set it has gone is still a change the school may want undone.</p>
     *
     * @return the file that was written, with its size
     * @throws BackupFailedException if the file could not be written; the caller must
     *                               then abandon whatever it was about to do
     */
    @Transactional(readOnly = true)
    public BackupStore.Written writeSeasonBackup() {
        List<EventGroupLine> groups = eventGroupRepository.findAll().stream()
                .map(SeasonBackupService::toLine)
                .toList();
        List<EnrollmentLine> enrollments = enrollmentRepository.findAll().stream()
                .map(SeasonBackupService::toLine)
                .toList();
        List<FinalEntryLine> finalEntries = finalEntryRepository.findAll().stream()
                .map(SeasonBackupService::toLine)
                .toList();
        List<EventResultLine> results = eventResultRepository.findAll().stream()
                .map(SeasonBackupService::toLine)
                .toList();
        List<EventRecordLine> records = eventRecordRepository.findAll().stream()
                .map(SeasonBackupService::toLine)
                .toList();

        Counts counts = new Counts(enrollments.size(), finalEntries.size(), groups.size(),
                results.size(), records.size());
        LocalDateTime now = LocalDateTime.now();
        Header header = new Header(SeasonBackupFile.APP_NAME, SeasonBackupFile.FORMAT_VERSION,
                now, counts,
                "Entries, heats, final places, marks and school records as they stood before a "
                        + "season reset. Students, events and settings are not included.");
        BackupFile backup = new BackupFile(SeasonBackupFile.APP_NAME,
                SeasonBackupFile.FORMAT_VERSION, header,
                groups, enrollments, finalEntries, results, records);

        BackupStore.Written written = backupStore.write(backup);
        log.info("Season backup {} taken: {} entries, {} groups, {} final places, {} results, "
                        + "{} records", written.name(), counts.enrollments(), counts.groups(),
                counts.finalEntries(), counts.results(), counts.records());
        return written;
    }

    // -------------------------------------------------------------- restoring

    /**
     * Replaces the season with the contents of one backup file.
     *
     * <p><strong>Destructive.</strong> The current entries, heats, final places,
     * marks and the marks the school records stand on are removed first, then the
     * file's are written back.</p>
     *
     * <h2>The order, and why</h2>
     * <ol>
     *   <li>the records let go of their results and events, because those are about
     *       to be deleted — the same detach a reset does;</li>
     *   <li>the final places and the entries go, because both point at a group;</li>
     *   <li>the groups go;</li>
     *   <li>only then the results, which nothing else points at;</li>
     *   <li>inserts then run the other way round: groups first so their generated
     *       ids are known, then entries and final places pointing at those ids, then
     *       the results;</li>
     *   <li>the records' baselines are put back, and every record is recomputed from
     *       the restored results — which is also what re-points a record at the
     *       result that now holds it, since a restored result has a new id.</li>
     * </ol>
     *
     * @param name the file to restore, relative to the backup directory
     * @return what was written back, and what had to be skipped
     * @throws com.sportday.exception.ResourceNotFoundException if there is no such backup
     * @throws IllegalArgumentException                          if the file is not a usable backup
     */
    @Transactional
    public Map<String, Object> restore(String name) {
        BackupFile backup = backupStore.read(name);
        requireOurFile(backup, name);

        // 1. Records let go of the results and events that are about to go.
        recordService.detachAll();

        // 2-4. Delete children before parents: entries and final places both
        // reference a group, and a record referenced a result.
        finalEntryRepository.deleteAllInBatch();
        enrollmentRepository.deleteAllInBatch();
        eventGroupRepository.deleteAllInBatch();
        eventResultRepository.deleteAllInBatch();
        flush();

        // 5. Insert parents before children. A group's id is generated on insert, so
        // the old id in the file is mapped to the new one for the rows that point at it.
        Map<Long, EventGroup> groupsByOldId = restoreGroups(backup);
        Skipped skipped = new Skipped();
        Map<Long, EventResult> restoredResults = restoreResults(backup, skipped);
        restoreEnrollments(backup, groupsByOldId, skipped);
        restoreFinalEntries(backup, groupsByOldId, skipped);

        // 6. The baselines go back as they were, then every record is rebuilt from
        // the results that have just been restored — which is what re-points a record
        // at the result holding it now that the ids are new.
        int records = restoreRecordBaselines(backup);
        recordService.recomputeAll();
        int reformatted = eventService.reapplyFinalFormat();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("restoredFrom", name);
        // Counts are all long, so a client never has to wonder which kind of number
        // it got back for "how many".
        summary.put("groupsRestored", (long) groupsByOldId.size());
        summary.put("enrollmentsRestored",
                (long) orEmpty(backup.enrollments()).size() - skipped.enrollments);
        summary.put("finalEntriesRestored",
                (long) orEmpty(backup.finalEntries()).size() - skipped.finalEntries);
        summary.put("resultsRestored", (long) restoredResults.size());
        summary.put("recordsRestored", (long) records);
        summary.put("skipped", skipped.asMap());
        summary.put("recordsRecomputed", (long) records);
        summary.put("eventsReformatted", (long) reformatted);
        summary.put("restoredAt", LocalDateTime.now().toString());

        log.warn("Season restored from {}: {} groups, {} results, {} records; skipped {} row(s) "
                        + "whose student, event or group is gone",
                name, groupsByOldId.size(), restoredResults.size(), records, skipped.total());
        return summary;
    }

    // --------------------------------------------------------------- deletes

    /** The file has to be one of ours, of a version we understand. */
    private void requireOurFile(BackupFile backup, String name) {
        if (backup == null) {
            throw new IllegalArgumentException("Backup " + name + " is empty.");
        }
        if (backup.app() != null && !SeasonBackupFile.APP_NAME.equalsIgnoreCase(backup.app())) {
            throw new IllegalArgumentException("Backup " + name + " was written by "
                    + backup.app() + ", not " + SeasonBackupFile.APP_NAME + ".");
        }
        if (backup.version() > SeasonBackupFile.FORMAT_VERSION) {
            throw new IllegalArgumentException("Backup " + name + " is version " + backup.version()
                    + ", and this build understands version " + SeasonBackupFile.FORMAT_VERSION
                    + " and earlier.");
        }
    }

    // -------------------------------------------------------------- inserts

    /** Groups first, so the rows that reference one can be pointed at the new id. */
    private Map<Long, EventGroup> restoreGroups(BackupFile backup) {
        List<EventGroupLine> lines = orEmpty(backup.groups());
        Map<Long, Event> events = eventsFor(lines.stream().map(EventGroupLine::eventId).toList());
        Map<Long, EventGroup> byOldId = new HashMap<>();
        for (EventGroupLine line : lines) {
            Event event = events.get(line.eventId());
            if (event == null) {
                log.warn("Skipping heat {} of event {}: the event is gone", line.id(), line.eventId());
                continue;
            }
            EventGroup group = EventGroup.builder()
                    .event(event)
                    .groupNumber(line.groupNumber() == null
                            ? EventGroup.FINAL_GROUP_NUMBER : line.groupNumber())
                    .stage(parseStage(line.stage()))
                    .capacity(line.capacity() == null ? event.getGroupSize() : line.capacity())
                    .athleteCount(line.athleteCount() == null ? 0 : line.athleteCount())
                    .createdAt(line.createdAt() == null ? LocalDateTime.now() : line.createdAt())
                    .build();
            group = eventGroupRepository.save(group);
            byOldId.put(line.id(), group);
        }
        flush();
        return byOldId;
    }

    /** The marks, keeping the old id so the records can be pointed at the new one. */
    private Map<Long, EventResult> restoreResults(BackupFile backup, Skipped skipped) {
        List<EventResultLine> lines = orEmpty(backup.results());
        Map<Long, Event> events = eventsFor(lines.stream().map(EventResultLine::eventId).toList());
        Map<Long, User> users = usersFor(lines.stream().map(EventResultLine::userId).toList());
        Map<Long, EventResult> byOldId = new HashMap<>();
        for (EventResultLine line : lines) {
            Event event = events.get(line.eventId());
            User user = users.get(line.userId());
            if (event == null || user == null) {
                skipped.results++;
                log.warn("Skipping mark {}: {}", line.id(),
                        event == null ? "event " + line.eventId() + " is gone" : "student is gone");
                continue;
            }
            EventResult result = EventResult.builder()
                    .user(user)
                    .event(event)
                    .stage(parseStage(line.stage()))
                    .outcome(parseOutcome(line.outcome()))
                    .mark(line.mark())
                    .attempt1(line.attempt1())
                    .attempt2(line.attempt2())
                    .attempt3(line.attempt3())
                    .unit(line.unit() == null ? event.getType().getDefaultUnit() : line.unit())
                    .notes(line.notes())
                    .recordedAt(line.recordedAt() == null ? LocalDateTime.now() : line.recordedAt())
                    .build();
            result = eventResultRepository.save(result);
            byOldId.put(line.id(), result);
        }
        flush();
        return byOldId;
    }

    /** The entries, pointed at the heat they were drawn into. */
    private void restoreEnrollments(BackupFile backup, Map<Long, EventGroup> groups, Skipped skipped) {
        List<EnrollmentLine> lines = orEmpty(backup.enrollments());
        Map<Long, Event> events = eventsFor(lines.stream().map(EnrollmentLine::eventId).toList());
        Map<Long, User> users = usersFor(lines.stream().map(EnrollmentLine::userId).toList());
        for (EnrollmentLine line : lines) {
            Event event = events.get(line.eventId());
            User user = users.get(line.userId());
            if (event == null || user == null) {
                skipped.enrollments++;
                log.warn("Skipping entry {}: {}", line.id(),
                        event == null ? "event " + line.eventId() + " is gone" : "student is gone");
                continue;
            }
            enrollmentRepository.save(Enrollment.builder()
                    .user(user)
                    .event(event)
                    .eventGroup(line.groupId() == null ? null : groups.get(line.groupId()))
                    .lane(line.lane())
                    .status(parseStatus(line.status()))
                    .enrolledAt(line.enrolledAt() == null ? LocalDateTime.now() : line.enrolledAt())
                    .build());
        }
        flush();
    }

    /**
     * The final's field. A final place points at a group — the {@code FINAL} one — so
     * a place whose group is missing is skipped rather than entered blind: without
     * the group there is no final to be in.
     */
    private void restoreFinalEntries(BackupFile backup, Map<Long, EventGroup> groups, Skipped skipped) {
        List<FinalEntryLine> lines = orEmpty(backup.finalEntries());
        Map<Long, User> users = usersFor(lines.stream().map(FinalEntryLine::userId).toList());
        for (FinalEntryLine line : lines) {
            EventGroup group = groups.get(line.groupId());
            User user = users.get(line.userId());
            if (group == null || user == null) {
                skipped.finalEntries++;
                log.warn("Skipping final place {}: {}", line.id(),
                        group == null ? "group " + line.groupId() + " is gone" : "student is gone");
                continue;
            }
            finalEntryRepository.save(FinalEntry.builder()
                    .group(group)
                    .user(user)
                    .lane(line.lane() == null ? 0 : line.lane())
                    .seed(line.seed() == null ? 0 : line.seed())
                    .seedMark(line.seedMark())
                    .seedUnit(line.seedUnit())
                    .createdAt(line.createdAt() == null ? LocalDateTime.now() : line.createdAt())
                    .build());
        }
        flush();
    }

    /**
     * The school records' typed-in baselines, the part a reset deliberately keeps
     * and which cannot be rebuilt from anything else.
     *
     * <p>The mark that <em>stands</em> is not written from the file: it is recomputed
     * from the results that were just restored, which is also what makes a record
     * point at the result that holds it again. A record the file has and the database
     * does not is created; an existing row is updated in place, so its id — and
     * anything referring to it — survives.</p>
     */
    private int restoreRecordBaselines(BackupFile backup) {
        List<EventRecordLine> lines = orEmpty(backup.records());
        int restored = 0;
        for (EventRecordLine line : lines) {
            Event.EventType type = line.eventType() == null ? null
                    : parseEnum(Event.EventType.class, line.eventType(), "eventType");
            Sex sex = line.sex() == null ? null : parseEnum(Sex.class, line.sex(), "sex");
            Grade grade = line.grade() == null ? null : parseEnum(Grade.class, line.grade(), "grade");
            if (type == null || sex == null || grade == null) {
                log.warn("Skipping record {}: it names no event type, division or grade", line.id());
                continue;
            }
            EventRecord record = eventRecordRepository
                    .findByEventTypeAndSexAndGrade(type, sex, grade)
                    .orElseGet(() -> EventRecord.builder()
                            .eventType(type)
                            .sex(sex)
                            .grade(grade)
                            .hasPrevious(false)
                            .build());
            record.setManualMark(line.manualMark());
            record.setManualUnit(line.manualUnit());
            record.setManualHolderName(line.manualHolderName());
            record.setManualAchievedOn(line.manualAchievedOn());
            eventRecordRepository.save(record);
            restored++;
        }
        flush();
        return restored;
    }

    // -------------------------------------------------------------- lookups

    /**
     * The events a set of ids names, in one query.
     *
     * <p>A student or an event that is not there is handled by the caller rather
     * than thrown: the register may legitimately have moved on since the backup.</p>
     */
    private Map<Long, Event> eventsFor(List<Long> eventIds) {
        return byId(eventIds, eventRepository::findAllById, Event::getId);
    }

    private Map<Long, User> usersFor(List<Long> userIds) {
        return byId(userIds, userRepository::findAllById, User::getId);
    }

    /** The rows that still exist, keyed by id — one query, not one per row. */
    private <T> Map<Long, T> byId(List<Long> ids,
                                  Function<List<Long>, List<T>> lookup,
                                  Function<T, Long> idOf) {
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<Long, T> found = new HashMap<>();
        for (T entity : lookup.apply(wanted)) {
            if (entity != null && idOf.apply(entity) != null) {
                found.put(idOf.apply(entity), entity);
            }
        }
        return found;
    }

    /** Rows the file holds that could not be written back, because a parent is gone. */
    private static final class Skipped {
        private long enrollments;
        private long finalEntries;
        private long results;

        private long total() {
            return enrollments + finalEntries + results;
        }

        private Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("enrollments", enrollments);
            map.put("finalEntries", finalEntries);
            map.put("results", results);
            return map;
        }    }

    // -------------------------------------------------------------- helpers

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** Flushes pending SQL so a delete and a later insert cannot be reordered. */
    private void flush() {
        if (entityManager != null) {
            entityManager.flush();
        }
    }

    private static Enrollment.EnrollmentStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return Enrollment.EnrollmentStatus.CONFIRMED;
        }
        return parseEnum(Enrollment.EnrollmentStatus.class, raw, "status");
    }

    private static EventStage parseStage(String raw) {
        if (raw == null || raw.isBlank()) {
            return EventStage.HEAT;
        }
        return parseEnum(EventStage.class, raw, "stage");
    }

    /**
     * A null outcome reads as "no outcome recorded", which the entity treats as a
     * result — so a row written before outcomes existed restores unchanged, and a
     * file that omits the field (an older backup, or a hand-written one) is not
     * silently turned into an absence.
     */
    private static EventResult.Outcome parseOutcome(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parseEnum(EventResult.Outcome.class, raw, "outcome");
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String what) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("A backup row has an unknown " + what
                    + " '" + raw + "' — the file is not a " + SeasonBackupFile.APP_NAME
                    + " season backup this build can read.");
        }
    }

    // ------------------------------------------------- serialising the rows

    private static EnrollmentLine toLine(Enrollment enrollment) {
        return new EnrollmentLine(
                enrollment.getId(),
                enrollment.getUser() == null ? null : enrollment.getUser().getId(),
                enrollment.getEvent() == null ? null : enrollment.getEvent().getId(),
                enrollment.getStatus() == null ? null : enrollment.getStatus().name(),
                enrollment.getEventGroup() == null ? null : enrollment.getEventGroup().getId(),
                enrollment.getLane(),
                enrollment.getEnrolledAt());
    }

    private static FinalEntryLine toLine(FinalEntry entry) {
        return new FinalEntryLine(
                entry.getId(),
                entry.getGroup() == null ? null : entry.getGroup().getId(),
                entry.getUser() == null ? null : entry.getUser().getId(),
                entry.getLane(),
                entry.getSeed(),
                entry.getSeedMark(),
                entry.getSeedUnit(),
                entry.getCreatedAt());
    }

    private static EventGroupLine toLine(EventGroup group) {
        return new EventGroupLine(
                group.getId(),
                group.getEvent() == null ? null : group.getEvent().getId(),
                group.getGroupNumber(),
                group.getStageOrDefault().name(),
                group.getCapacity(),
                group.getAthleteCount(),
                group.getCreatedAt());
    }

    private static EventResultLine toLine(EventResult result) {
        return new EventResultLine(
                result.getId(),
                result.getUser() == null ? null : result.getUser().getId(),
                result.getEvent() == null ? null : result.getEvent().getId(),
                result.getStageOrDefault().name(),
                result.getMark(),
                result.getUnit(),
                result.getAttempt1(),
                result.getAttempt2(),
                result.getAttempt3(),
                result.getNotes(),
                result.getOutcomeOrDefault().name(),
                result.getRecordedAt());
    }

    private static EventRecordLine toLine(EventRecord record) {
        return new EventRecordLine(
                record.getId(),
                record.getEventType() == null ? null : record.getEventType().name(),
                record.getSex() == null ? null : record.getSex().name(),
                record.getGrade() == null ? null : record.getGrade().name(),
                record.getManualMark(),
                record.getManualUnit(),
                record.getManualHolderName(),
                record.getManualAchievedOn(),
                record.getMark(),
                record.getUnit(),
                record.getSource().name(),
                record.getHolderName(),
                record.getHolder() == null ? null : record.getHolder().getId(),
                record.getAchievedOn(),
                record.getResult() == null ? null : record.getResult().getId(),
                record.getEvent() == null ? null : record.getEvent().getId(),
                record.getPreviousMark(),
                record.getPreviousHolderName(),
                record.getPreviousAchievedOn());
    }

    /** Kept so a test can compare a file's header with the lists beside it. */
    static Counts countsOf(BackupFile backup) {
        return backup.header() == null ? null : backup.header().counts();
    }
}
