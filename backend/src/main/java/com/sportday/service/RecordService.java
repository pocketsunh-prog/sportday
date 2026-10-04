package com.sportday.service;

import com.sportday.dto.EventRecordDTO;
import com.sportday.dto.RecordBaselineDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventRecord;
import com.sportday.entity.EventResult;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.Student;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.EventRecordRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The school records.
 *
 * <p>A record belongs to one event — "Boys 100M · B Grade" — and, because an event
 * now belongs to exactly one grade, that is one record row per event rather than
 * one per grade of a grade-mixed race. A row exists for every event from the
 * moment the event does, so the records page is complete before anybody has
 * competed.</p>
 *
 * <p>The mark that stands is the better of two things: a <strong>baseline</strong>
 * an administrator typed in (last season's best, or a record held by a student who
 * has since left) and the <strong>best result</strong> recorded in any edition of
 * that event. Recomputing never touches the baseline, so a record entered by hand
 * survives results being cleared, an event being deleted, or a season reset.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordService {

    private final EventRecordRepository recordRepository;
    private final EventResultRepository resultRepository;
    private final StudentRepository studentRepository;
    private final EventRepository eventRepository;

    // ------------------------------------------------------------- creating

    /**
     * Creates the record an event should have. Called when an event is created so
     * "each event has a record" is true from the start.
     *
     * <p>The event is one grade, so this is one row: its grade is the event's.</p>
     */
    @Transactional
    public int seedForEvent(Event event) {
        if (event == null || event.getType() == null || event.getSex() == null
                || event.getGrade() == null) {
            return 0;
        }
        return seedFor(event.getType(), event.getSex(), event.getGrade());
    }

    /** Creates the missing record row for one event — its type, division and grade. */
    @Transactional
    public int seedFor(Event.EventType type, Sex sex, Grade grade) {
        if (type == null || sex == null || grade == null) {
            return 0;
        }
        if (recordRepository.findByEventTypeAndSexAndGrade(type, sex, grade).isPresent()) {
            return 0;
        }
        recordRepository.save(EventRecord.builder()
                .eventType(type)
                .sex(sex)
                .grade(grade)
                .hasPrevious(false)
                .build());
        return 1;
    }

    /** Creates every missing record row for the whole catalogue — one per event. */
    @Transactional
    public int seedAll() {
        Set<String> seen = new LinkedHashSet<>();
        int created = 0;
        for (Event event : eventRepository.findAll()) {
            if (event.getType() == null || event.getSex() == null || event.getGrade() == null) {
                continue;
            }
            if (seen.add(event.getType().name() + '|' + event.getSex().name() + '|' + event.getGrade().name())) {
                created += seedFor(event.getType(), event.getSex(), event.getGrade());
            }
        }
        if (created > 0) {
            log.info("Created {} school record row(s) so every event has one", created);
        }
        return created;
    }

    // ------------------------------------------------------------- updating

    /**
     * Rebuilds the record that a single result belongs to. Called after a mark is
     * saved so a record-breaking performance takes the record immediately.
     *
     * <p>The grade is the <em>event's</em>: an event is run by exactly one grade, so
     * the mark an athlete sets belongs to that grade's record whatever the athlete's
     * own grade field says.</p>
     */
    @Transactional
    public void considerResult(EventResult result) {
        if (result == null || result.getEvent() == null || result.getUser() == null) {
            return;
        }
        Event event = result.getEvent();
        if (event.getType() == null || event.getSex() == null || event.getGrade() == null) {
            return;
        }
        recomputeFor(event.getType(), event.getSex(), event.getGrade());
    }

    /**
     * Works out which mark stands for one event — its type, division and grade — and
     * records who holds it and what it beat.
     */
    @Transactional
    public void recomputeFor(Event.EventType type, Sex sex, Grade grade) {
        if (type == null || sex == null || grade == null) {
            return;
        }
        EventRecord record = recordRepository.findByEventTypeAndSexAndGrade(type, sex, grade)
                .orElseGet(() -> recordRepository.save(EventRecord.builder()
                        .eventType(type)
                        .sex(sex)
                        .grade(grade)
                        .hasPrevious(false)
                        .build()));

        List<EventResult> all = resultRepository.findByEventTypeAndSexAndGrade(type, sex, grade);
        Map<Long, Student> rosters = rostersFor(all);
        List<EventResult> matching = all.stream()
                .filter(result -> result.getUser() != null && result.getMark() != null)
                .toList();

        boolean lowerBetter = type.isLowerBetter();
        EventResult winner = matching.stream()
                .sorted(comparatorFor(lowerBetter).thenComparing(result -> result.getUser().getId()))
                .findFirst()
                .orElse(null);

        // Snapshot the state before it is overwritten, so we can say what was beaten.
        Long oldResultId = record.getResult() == null ? null : record.getResult().getId();
        BigDecimal oldMark = record.getMark();
        String oldHolderName = record.getHolderName();
        LocalDate oldAchievedOn = record.getAchievedOn();
        EventRecord.Source oldSource = record.getSource();

        BigDecimal baseline = record.getManualMark();
        boolean baselineWins = baseline != null
                && (winner == null || better(baseline, winner.getMark(), lowerBetter));

        boolean newHolder;
        if (baselineWins) {
            record.setMark(baseline);
            record.setUnit(record.getManualUnit());
            record.setHolder(null);
            record.setHolderName(record.getManualHolderName());
            record.setResult(null);
            record.setEvent(null);
            record.setAchievedOn(record.getManualAchievedOn());
            // Nothing has beaten the administrator's mark, so there is no story yet.
            record.setPreviousMark(null);
            record.setPreviousHolderName(null);
            record.setPreviousAchievedOn(null);
            record.setHasPrevious(false);
            newHolder = false;
        } else if (winner != null) {
            newHolder = !winner.getId().equals(oldResultId);
            record.setMark(winner.getMark());
            record.setUnit(winner.getUnit());
            record.setHolder(winner.getUser());
            record.setHolderName(displayName(winner.getUser(), rosters));
            record.setResult(winner);
            record.setEvent(winner.getEvent());
            record.setAchievedOn(winner.getEvent().getEventDate());

            if (newHolder) {
                if (oldSource == EventRecord.Source.BASELINE) {
                    record.setPreviousMark(baseline);
                    record.setPreviousHolderName(record.getManualHolderName());
                    record.setPreviousAchievedOn(record.getManualAchievedOn());
                } else {
                    record.setPreviousMark(oldMark);
                    record.setPreviousHolderName(oldHolderName);
                    record.setPreviousAchievedOn(oldAchievedOn);
                }
                record.setHasPrevious(record.getPreviousMark() != null);
            }
        } else {
            // Nothing has been recorded and there is no baseline: the record is empty.
            record.setMark(null);
            record.setUnit(null);
            record.setHolder(null);
            record.setHolderName(null);
            record.setResult(null);
            record.setEvent(null);
            record.setAchievedOn(null);
            newHolder = false;
        }

        recordRepository.save(record);

        if (newHolder) {
            log.info("New school record: {} {} {} — {} {}",
                    sex, grade, type.getDisplayName(), record.getMark(), record.getUnit());
        }
    }

    /** Rebuilds every record from the results, keeping every baseline. */
    @Transactional
    public int recomputeAll() {
        List<EventRecord> records = recordRepository.findAll();
        for (EventRecord record : records) {
            recomputeFor(record.getEventType(), record.getSex(), record.getGrade());
        }
        log.info("Recomputed {} school record(s)", records.size());
        return records.size();
    }

    // --------------------------------------------------------- the baseline

    /** Sets or replaces the mark an administrator typed in. */
    @Transactional
    public EventRecordDTO setBaseline(Long recordId, RecordBaselineDTO payload) {
        EventRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new ResourceNotFoundException("Record not found with id: " + recordId));
        record.setManualMark(payload == null ? null : payload.getMark());
        record.setManualUnit(payload == null ? null : payload.getUnit());
        record.setManualHolderName(payload == null ? null : payload.getHolderName());
        record.setManualAchievedOn(payload == null ? null : payload.getAchievedOn());
        recordRepository.save(record);
        recomputeFor(record.getEventType(), record.getSex(), record.getGrade());
        log.info("Record {} baseline set to {} {}", recordId, record.getManualMark(), record.getManualUnit());
        return byId(recordId);
    }

    /** Removes the typed-in baseline, leaving the record to the results again. */
    @Transactional
    public EventRecordDTO clearBaseline(Long recordId) {
        return setBaseline(recordId, null);
    }

    @Transactional(readOnly = true)
    public EventRecordDTO byId(Long recordId) {
        EventRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new ResourceNotFoundException("Record not found with id: " + recordId));
        return describe(record, holderRefs(List.of(record)));
    }

    // ------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<EventRecordDTO> list() {
        List<EventRecord> records = recordRepository.findAllWithHolder();
        Map<Long, Student> rosters = holderRefs(records);
        return records.stream().map(record -> describe(record, rosters)).toList();
    }

    /** The result ids that currently hold a record, so they can be badged. */
    @Transactional(readOnly = true)
    public Set<Long> recordResultIds() {
        return new LinkedHashSet<>(recordRepository.findResultIds());
    }

    // ----------------------------------------------------------- detaching

    /**
     * Clears the record's reference to the results and event that are about to be
     * deleted, so the foreign keys do not block the delete. Rebuild afterwards with
     * {@link #recomputeAll()}.
     */
    @Transactional
    public void detachForEvent(Long eventId) {
        int detached = recordRepository.detachResultsForEvent(eventId);
        if (detached > 0) {
            log.debug("Detached {} record(s) from the results of event {}", detached, eventId);
        }
    }

    /** As {@link #detachForEvent}, for one result. */
    @Transactional
    public void detachForResult(Long resultId) {
        recordRepository.detachResult(resultId);
    }

    /** Lets go of every result, ready for a season reset. Baselines are kept. */
    @Transactional
    public void detachAll() {
        int detached = recordRepository.detachAllResults();
        if (detached > 0) {
            log.debug("Detached {} record(s) from their results for a season reset", detached);
        }
    }

    // ------------------------------------------------------------ helpers

    private EventRecordDTO describe(EventRecord record, Map<Long, Student> rosters) {
        Student roster = record.getHolder() == null ? null : rosters.get(record.getHolder().getId());
        String ref = roster != null ? roster.getStudentId()
                : record.getHolder() == null ? null : record.getHolder().getUsername();
        return EventRecordDTO.from(record, ref);
    }

    private Map<Long, Student> holderRefs(List<EventRecord> records) {
        List<Long> holderIds = records.stream()
                .filter(record -> record.getHolder() != null)
                .map(record -> record.getHolder().getId())
                .distinct()
                .toList();
        if (holderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Student> rosters = new HashMap<>();
        for (Student student : studentRepository.findWithUserByUserIdIn(holderIds)) {
            if (student.getUser() != null) {
                rosters.put(student.getUser().getId(), student);
            }
        }
        return rosters;
    }

    private Map<Long, Student> rostersFor(List<EventResult> results) {
        List<Long> userIds = results.stream()
                .filter(result -> result.getUser() != null)
                .map(result -> result.getUser().getId())
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Student> rosters = new HashMap<>();
        for (Student student : studentRepository.findWithUserByUserIdIn(userIds)) {
            if (student.getUser() != null) {
                rosters.put(student.getUser().getId(), student);
            }
        }
        return rosters;
    }

    private static Comparator<EventResult> comparatorFor(boolean lowerBetter) {
        return lowerBetter
                ? Comparator.comparing(EventResult::getMark)
                : Comparator.<EventResult, BigDecimal>comparing(EventResult::getMark).reversed();
    }

    /** True when {@code a} is the better of two marks. */
    private static boolean better(BigDecimal a, BigDecimal b, boolean lowerBetter) {
        if (a == null) {
            return false;
        }
        if (b == null) {
            return true;
        }
        return lowerBetter ? a.compareTo(b) < 0 : a.compareTo(b) > 0;
    }

    private String displayName(com.sportday.entity.User user, Map<Long, Student> rosters) {
        if (user == null) {
            return null;
        }
        Student roster = rosters.get(user.getId());
        if (roster != null) {
            return roster.getName();
        }
        return user.getFullName() == null ? user.getUsername() : user.getFullName();
    }
}
