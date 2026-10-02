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

    @Transactional
    public Map<String, Object> resetSeason() {
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

        log.warn("Season reset: removed {} entries, {} final places, {} groups and {} results; "
                        + "kept the hand-entered school records ({})",
                enrollments, finalPlaces, groups, results, records);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("enrollmentsRemoved", enrollments);
        summary.put("finalPlacesRemoved", finalPlaces);
        summary.put("groupsRemoved", groups);
        summary.put("resultsRemoved", results);
        summary.put("recordsKept", eventRecordRepository.count());
        summary.put("studentsKept", "unchanged");
        summary.put("eventsKept", "unchanged");
        return summary;
    }
}
