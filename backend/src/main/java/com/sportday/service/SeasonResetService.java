package com.sportday.service;

import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRecordRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.FinalEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Clears the results of a sport day so the season can be run again — entries,
 * heats, the final and recorded marks — while leaving the student register and
 * the event catalogue untouched.
 *
 * <p>It does not do that until it has a way back: a backup of everything it is
 * about to destroy, plus the school records, is written to a file first. If that
 * file cannot be written the reset refuses to run and says why, because an
 * unwanted reset with no backup is not something the school can undo.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeasonResetService {

    private final EnrollmentRepository enrollmentRepository;
    private final EventGroupRepository eventGroupRepository;
    private final EventResultRepository eventResultRepository;
    private final FinalEntryRepository finalEntryRepository;
    private final EventRecordRepository eventRecordRepository;
    private final RecordService recordService;
    private final EventService eventService;
    private final SeasonBackupService seasonBackupService;

    @Transactional
    public Map<String, Object> resetSeason() {
        // Before anything is touched: a restorable backup, or no reset at all.
        // SeasonBackupService.writeSeasonBackup() throws if the file cannot be
        // written, and nothing here catches it — so a failed backup means the
        // transaction rolls back with every entry, heat, final place and mark still
        // in place, and the caller is told why.
        BackupStore.Written backup = seasonBackupService.writeSeasonBackup();

        long enrollments = enrollmentRepository.count();
        long finalPlaces = finalEntryRepository.count();
        long groups = eventGroupRepository.count();
        long results = eventResultRepository.count();

        // Records point at the results, so let go of them first. The marks an
        // administrator typed in are NOT deleted — they are the school's history and
        // have nothing to do with this season's results.
        recordService.detachAll();
        // Both entries and the final's field reference a group, so they have to go
        // before the groups themselves or the foreign keys block the delete.
        enrollmentRepository.deleteAllInBatch();
        finalEntryRepository.deleteAllInBatch();
        eventGroupRepository.deleteAllInBatch();
        eventResultRepository.deleteAllInBatch();

        // With no results left, every record falls back to its typed-in baseline, or
        // becomes empty if it never had one.
        int records = recordService.recomputeAll();

        // Nobody is entered any more, so a sprint cannot be running heats and a final.
        // Without this the programme would still claim a final for an event with an
        // empty field, and the next entry would have to work it out again.
        int reformatted = eventService.reapplyFinalFormat();

        log.warn("Season reset: backup {} ({} bytes); removed {} entries, {} final places, {} groups "
                        + "and {} results; kept the hand-entered school records ({}); {} event(s) back "
                        + "to a straight final",
                backup.name(), backup.bytes(), enrollments, finalPlaces, groups, results, records,
                reformatted);

        Map<String, Object> summary = new LinkedHashMap<>();
        // Named and sized in the answer, so the administrator can find the file the
        // reset left behind without going looking for it.
        summary.putAll(backup.asMap());
        summary.put("enrollmentsRemoved", enrollments);
        summary.put("finalPlacesRemoved", finalPlaces);
        summary.put("groupsRemoved", groups);
        summary.put("resultsRemoved", results);
        summary.put("recordsKept", eventRecordRepository.count());
        summary.put("eventsReformatted", reformatted);
        summary.put("studentsKept", "unchanged");
        summary.put("eventsKept", "unchanged");
        return summary;
    }
}
