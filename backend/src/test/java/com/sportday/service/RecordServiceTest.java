package com.sportday.service;

import com.sportday.dto.EventRecordDTO;
import com.sportday.dto.RecordBaselineDTO;
import com.sportday.entity.*;
import com.sportday.repository.EventRecordRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.StudentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * School records: one per event, created by default, with a mark an administrator
 * can type in.
 *
 * <p>Requirement: each event has a record by default, and it can be updated — while
 * still following a result that beats it. Now that an event belongs to exactly one
 * grade, that is <strong>one</strong> record row per event, and the grade it belongs
 * to is the event's rather than the athlete's.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecordServiceTest {

    private static final Sex SEX = Sex.MALE;
    private static final Event.EventType HUNDRED = Event.EventType.RUN_100M;
    private static final Event.EventType LONG_JUMP = Event.EventType.LONG_JUMP;

    @Mock private EventRecordRepository recordRepository;
    @Mock private EventResultRepository resultRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private EventRepository eventRepository;

    @InjectMocks private RecordService service;

    private final List<EventResult> results = new ArrayList<>();
    private final List<Student> rosters = new ArrayList<>();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // A JpaRepository's save() hands back the persisted entity; the mock must
        // too, because recomputeFor uses its return value.
        when(recordRepository.save(any(EventRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ------------------------------------------------------------- fixtures

    /** A grade's event — the unit an event now is, and what a record belongs to. */
    private Event event(Event.EventType type, Grade grade) {
        return Event.builder()
                .id(2L)
                .name(type.getDisplayName() + " · " + grade.getLabel())
                .type(type)
                .category(type.getCategory())
                .sex(SEX)
                .grade(grade)
                .groupSize(8)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .build();
    }

    private void result(long userId, String mark, Grade grade, Event.EventType type) {
        User athlete = User.builder().id(userId).username("S000" + userId)
                .fullName("Athlete " + userId).build();
        results.add(EventResult.builder()
                .id(userId)
                .user(athlete)
                .event(event(type, grade))
                .stage(EventStage.HEAT)
                .mark(new BigDecimal(mark))
                .unit(type.getDefaultUnit())
                .build());
        rosters.add(Student.builder()
                .id(userId)
                .user(athlete)
                .studentId("S000" + userId)
                .name("Athlete " + userId)
                .grade(grade)
                .className("3A")
                .house("Red")
                .dob(LocalDate.of(2011, 5, 5))
                .sex(SEX)
                .enabled(true)
                .build());
    }

    /** The marks recorded in that grade's event, as the repository would return them. */
    private void givenResultsFor(Event.EventType type, Grade grade) {
        givenResultsFor(type, grade, results);
    }

    private void givenResultsFor(Event.EventType type, Grade grade, List<EventResult> forGrade) {
        when(resultRepository.findByEventTypeAndSexAndGrade(type, SEX, grade)).thenReturn(forGrade);
        when(studentRepository.findWithUserByUserIdIn(any())).thenReturn(rosters);
    }

    /** An existing record row, as an event would have created up front. */
    private EventRecord existingRecord(Event.EventType type, Grade grade, String mark, Long resultId) {
        EventRecord record = EventRecord.builder()
                .id(5L)
                .eventType(type)
                .sex(SEX)
                .grade(grade)
                .mark(mark == null ? null : new BigDecimal(mark))
                .unit(mark == null ? null : type.getDefaultUnit())
                .holder(resultId == null ? null : User.builder().id(9L).username("S0009").build())
                .holderName(resultId == null ? null : "Previous Holder")
                .result(resultId == null ? null : EventResult.builder().id(resultId).build())
                .event(event(type, grade))
                .achievedOn(LocalDate.of(2025, 10, 1))
                .hasPrevious(false)
                .build();
        when(recordRepository.findByEventTypeAndSexAndGrade(type, SEX, grade))
                .thenReturn(Optional.of(record));
        return record;
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("a time: the fastest mark takes the record")
    void trackRecordGoesToTheLowestMark() {
        result(1, "12.500", Grade.B, HUNDRED);
        result(2, "11.900", Grade.B, HUNDRED);
        result(3, "13.100", Grade.B, HUNDRED);
        givenResultsFor(HUNDRED, Grade.B);
        when(recordRepository.findByEventTypeAndSexAndGrade(HUNDRED, SEX, Grade.B))
                .thenReturn(Optional.empty());

        service.recomputeFor(HUNDRED, SEX, Grade.B);

        ArgumentCaptor<EventRecord> saved = ArgumentCaptor.forClass(EventRecord.class);
        verify(recordRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertEquals(new BigDecimal("11.900"), saved.getValue().getMark());
        assertEquals(2L, saved.getValue().getHolder().getId());
        assertEquals("Athlete 2", saved.getValue().getHolderName());
        assertEquals(EventRecord.Source.RESULT, saved.getValue().getSource());
        assertFalse(saved.getValue().getHasPrevious(), "the first record beats nothing");
    }

    @Test
    @DisplayName("a distance: the longest mark takes the record")
    void fieldRecordGoesToTheHighestMark() {
        result(1, "5.100", Grade.C, LONG_JUMP);
        result(2, "7.200", Grade.C, LONG_JUMP);
        result(3, "6.050", Grade.C, LONG_JUMP);
        givenResultsFor(LONG_JUMP, Grade.C);
        when(recordRepository.findByEventTypeAndSexAndGrade(LONG_JUMP, SEX, Grade.C))
                .thenReturn(Optional.empty());

        service.recomputeFor(LONG_JUMP, SEX, Grade.C);

        ArgumentCaptor<EventRecord> saved = ArgumentCaptor.forClass(EventRecord.class);
        verify(recordRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertEquals(new BigDecimal("7.200"), saved.getValue().getMark(),
                "a field event is won by the biggest mark");
    }

    @Test
    @DisplayName("each grade has its own record, built from that grade's own event")
    void gradesAreSeparate() {
        result(1, "12.000", Grade.A, HUNDRED);
        result(2, "11.000", Grade.B, HUNDRED);
        result(3, "13.000", Grade.C, HUNDRED);
        // The grade is part of the event, so the B grade's record is rebuilt from the
        // B grade event's marks alone — the A and C grade races are different events
        // and never feed it.
        List<EventResult> bGradeOnly = List.of(results.get(1));
        givenResultsFor(HUNDRED, Grade.B, bGradeOnly);
        when(recordRepository.findByEventTypeAndSexAndGrade(HUNDRED, SEX, Grade.B))
                .thenReturn(Optional.empty());

        service.recomputeFor(HUNDRED, SEX, Grade.B);

        verify(resultRepository).findByEventTypeAndSexAndGrade(HUNDRED, SEX, Grade.B);
        ArgumentCaptor<EventRecord> saved = ArgumentCaptor.forClass(EventRecord.class);
        verify(recordRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertEquals(new BigDecimal("11.000"), saved.getValue().getMark(),
                "the B grade record ignores the A and C grade marks");
        assertEquals(Grade.B, saved.getValue().getGrade());
    }

    @Test
    @DisplayName("beating a record remembers what it beat")
    void beatingARecordKeepsThePrevious() {
        result(1, "11.500", Grade.B, HUNDRED);
        givenResultsFor(HUNDRED, Grade.B);
        EventRecord existing = existingRecord(HUNDRED, Grade.B, "11.800", 9L);

        service.recomputeFor(HUNDRED, SEX, Grade.B);

        assertEquals(new BigDecimal("11.500"), existing.getMark(), "the record improves");
        assertEquals(1L, existing.getHolder().getId());
        assertEquals(new BigDecimal("11.800"), existing.getPreviousMark(),
                "the old mark is kept so the results page can show the improvement");
        assertEquals("Previous Holder", existing.getPreviousHolderName());
        assertTrue(existing.getHasPrevious());
    }

    @Test
    @DisplayName("a mark that does not beat the record leaves it alone")
    void aSlowerMarkDoesNotTakeTheRecord() {
        result(9, "11.800", Grade.B, HUNDRED);
        result(1, "12.400", Grade.B, HUNDRED);
        givenResultsFor(HUNDRED, Grade.B);
        EventRecord existing = existingRecord(HUNDRED, Grade.B, "11.800", 9L);

        service.recomputeFor(HUNDRED, SEX, Grade.B);

        assertEquals(new BigDecimal("11.800"), existing.getMark(), "the record stands");
        assertEquals(9L, existing.getHolder().getId(), "and so does its holder");
        assertFalse(existing.getHasPrevious());
    }

    @Test
    @DisplayName("a correction keeps the record in step without inventing a previous best")
    void aCorrectionKeepsTheRecordInStep() {
        result(1, "12.100", Grade.B, HUNDRED);
        givenResultsFor(HUNDRED, Grade.B);
        EventRecord existing = existingRecord(HUNDRED, Grade.B, "11.900", 1L);

        service.recomputeFor(HUNDRED, SEX, Grade.B);

        assertEquals(new BigDecimal("12.100"), existing.getMark(),
                "the holder mistyped 11.900 and the record follows their corrected time");
        assertFalse(existing.getHasPrevious(),
                "an athlete beating their own mark is not a new record holder");
    }

    @Test
    @DisplayName("a record whose results have gone is emptied, not deleted — the row is the event's")
    void aRecordWithNoResultsIsEmptiedNotDeleted() {
        result(1, "12.000", Grade.A, HUNDRED);
        // Only the A grade event has results; the B grade event has none.
        givenResultsFor(HUNDRED, Grade.B, List.of());
        EventRecord existing = existingRecord(HUNDRED, Grade.B, "11.800", 9L);

        service.recomputeFor(HUNDRED, SEX, Grade.B);

        verify(recordRepository, never()).delete(any(EventRecord.class));
        assertNull(existing.getMark(), "nothing is recorded for that event any more");
        assertEquals(EventRecord.Source.NONE, existing.getSource());
    }

    @Test
    @DisplayName("a saved mark updates the record for its own event, taking the grade from it")
    void considerResultDerivesTheKeyFromTheEvent() {
        result(4, "10.900", Grade.A, Event.EventType.RUN_200M);
        givenResultsFor(Event.EventType.RUN_200M, Grade.A);
        when(recordRepository.findByEventTypeAndSexAndGrade(Event.EventType.RUN_200M, SEX, Grade.A))
                .thenReturn(Optional.empty());

        service.considerResult(results.get(0));

        ArgumentCaptor<EventRecord> record = ArgumentCaptor.forClass(EventRecord.class);
        verify(recordRepository, org.mockito.Mockito.atLeastOnce()).save(record.capture());
        assertEquals(Event.EventType.RUN_200M, record.getValue().getEventType());
        assertEquals(Grade.A, record.getValue().getGrade(),
                "the grade comes from the event the mark was run in");
        assertEquals(new BigDecimal("10.900"), record.getValue().getMark());
    }

    @Test
    @DisplayName("a result in an event with no grade cannot set a record")
    void considerResultIgnoresEventsWithoutAGrade() {
        // A row from before events belonged to a grade: there is no grade to file the
        // mark under, and guessing one would rank it against another grade.
        EventResult unattributed = EventResult.builder()
                .id(4L)
                .user(User.builder().id(4L).username("S0004").build())
                .event(Event.builder().id(2L).type(Event.EventType.RUN_200M).sex(SEX).build())
                .stage(EventStage.HEAT)
                .mark(new BigDecimal("10.900"))
                .build();

        service.considerResult(unattributed);

        verify(recordRepository, never()).save(any());
    }

    // ------------------------------------------------------------- baseline

    @Test
    @DisplayName("a typed-in mark stands when nothing has beaten it")
    void aBaselineStandsOnItsOwn() {
        // No results at all — the record is only what the administrator entered.
        givenResultsFor(HUNDRED, Grade.B, List.of());
        EventRecord blank = EventRecord.builder()
                .id(5L).eventType(HUNDRED).sex(SEX).grade(Grade.B).hasPrevious(false).build();
        when(recordRepository.findByEventTypeAndSexAndGrade(HUNDRED, SEX, Grade.B))
                .thenReturn(Optional.of(blank));
        when(recordRepository.findById(5L)).thenReturn(Optional.of(blank));

        EventRecordDTO result = service.setBaseline(5L, RecordBaselineDTO.builder()
                .mark(new BigDecimal("11.200"))
                .unit("seconds")
                .holderName("Chan Tai Man (2019)")
                .achievedOn(LocalDate.of(2019, 10, 4))
                .build());

        assertEquals(new BigDecimal("11.200"), blank.getMark());
        assertEquals("seconds", blank.getUnit());
        assertEquals("Chan Tai Man (2019)", blank.getHolderName(),
                "a record can be held by a student who has left, so the name is free text");
        assertNull(blank.getHolder(), "and there is no account to point at");
        assertEquals(LocalDate.of(2019, 10, 4), blank.getAchievedOn());
        assertEquals(EventRecord.Source.BASELINE, blank.getSource());
        assertFalse(blank.getHasPrevious(), "nothing has beaten the baseline");
        assertEquals("BASELINE", result.getSource());
    }

    @Test
    @DisplayName("a result that beats the typed-in mark takes over and remembers it")
    void aResultBeatsTheBaseline() {
        result(1, "11.000", Grade.B, HUNDRED);
        givenResultsFor(HUNDRED, Grade.B);

        EventRecord record = EventRecord.builder()
                .id(5L).eventType(HUNDRED).sex(SEX).grade(Grade.B)
                .manualMark(new BigDecimal("11.200")).manualUnit("seconds")
                .manualHolderName("Chan Tai Man (2019)")
                .manualAchievedOn(LocalDate.of(2019, 10, 4))
                .hasPrevious(false)
                .build();
        when(recordRepository.findByEventTypeAndSexAndGrade(HUNDRED, SEX, Grade.B))
                .thenReturn(Optional.of(record));

        service.recomputeFor(HUNDRED, SEX, Grade.B);

        assertEquals(new BigDecimal("11.000"), record.getMark(), "the faster run takes the record");
        assertEquals(EventRecord.Source.RESULT, record.getSource());
        assertEquals(1L, record.getHolder().getId());
        assertEquals(new BigDecimal("11.200"), record.getPreviousMark(),
                "what it beat is the typed-in mark");
        assertEquals("Chan Tai Man (2019)", record.getPreviousHolderName());
        assertTrue(record.getHasPrevious());
        assertEquals(new BigDecimal("11.200"), record.getManualMark(),
                "the baseline is kept, so it survives the results being cleared");
    }

    @Test
    @DisplayName("a typed-in mark that is better than every result keeps the record")
    void aBetterBaselineBeatsTheResults() {
        result(1, "11.500", Grade.B, HUNDRED);
        givenResultsFor(HUNDRED, Grade.B);

        EventRecord record = EventRecord.builder()
                .id(5L).eventType(HUNDRED).sex(SEX).grade(Grade.B)
                .manualMark(new BigDecimal("11.000")).manualUnit("seconds")
                .manualHolderName("Chan Tai Man (2019)")
                .manualAchievedOn(LocalDate.of(2019, 10, 4))
                .hasPrevious(false)
                .build();
        when(recordRepository.findByEventTypeAndSexAndGrade(HUNDRED, SEX, Grade.B))
                .thenReturn(Optional.of(record));

        service.recomputeFor(HUNDRED, SEX, Grade.B);

        assertEquals(new BigDecimal("11.000"), record.getMark(), "the school best still stands");
        assertEquals(EventRecord.Source.BASELINE, record.getSource());
        assertEquals("Chan Tai Man (2019)", record.getHolderName());
        assertNull(record.getHolder());
    }

    @Test
    @DisplayName("clearing the typed-in mark leaves the record to the results")
    void clearingABaselineFallsBackToTheResults() {
        result(1, "11.500", Grade.B, HUNDRED);
        givenResultsFor(HUNDRED, Grade.B);

        EventRecord record = EventRecord.builder()
                .id(5L).eventType(HUNDRED).sex(SEX).grade(Grade.B)
                .manualMark(new BigDecimal("11.000")).manualUnit("seconds")
                .manualHolderName("Chan Tai Man (2019)")
                .hasPrevious(false)
                .build();
        when(recordRepository.findByEventTypeAndSexAndGrade(HUNDRED, SEX, Grade.B))
                .thenReturn(Optional.of(record));
        when(recordRepository.findById(5L)).thenReturn(Optional.of(record));

        service.clearBaseline(5L);

        assertNull(record.getManualMark(), "the typed-in mark is gone");
        assertEquals(new BigDecimal("11.500"), record.getMark(),
                "and the best recorded run takes the record");
        assertEquals(EventRecord.Source.RESULT, record.getSource());
    }

    @Test
    @DisplayName("rebuilding keeps every typed-in mark")
    void recomputeAllKeepsBaselines() {
        result(1, "12.000", Grade.B, HUNDRED);
        givenResultsFor(HUNDRED, Grade.B);

        EventRecord bGrade = EventRecord.builder()
                .id(5L).eventType(HUNDRED).sex(SEX).grade(Grade.B)
                .manualMark(new BigDecimal("11.000")).manualUnit("seconds")
                .manualHolderName("Chan Tai Man (2019)")
                .hasPrevious(false)
                .build();
        when(recordRepository.findAll()).thenReturn(List.of(bGrade));
        when(recordRepository.findByEventTypeAndSexAndGrade(HUNDRED, SEX, Grade.B))
                .thenReturn(Optional.of(bGrade));

        int rebuilt = service.recomputeAll();

        assertEquals(1, rebuilt);
        verify(recordRepository, never()).deleteAllInBatch();
        assertEquals(new BigDecimal("11.000"), bGrade.getMark(),
                "the hand-entered record is untouched by a rebuild");
        assertEquals(new BigDecimal("11.000"), bGrade.getManualMark());
    }

    @Test
    @DisplayName("an event gets one record, for its own grade, created once")
    void seedingCreatesOneRecordPerEvent() {
        when(recordRepository.findByEventTypeAndSexAndGrade(eq(HUNDRED), eq(SEX), any()))
                .thenReturn(Optional.empty());

        int created = service.seedFor(HUNDRED, SEX, Grade.B);

        assertEquals(1, created, "an event is one grade, so it has one record");
        ArgumentCaptor<EventRecord> saved = ArgumentCaptor.forClass(EventRecord.class);
        verify(recordRepository).save(saved.capture());
        assertEquals(Grade.B, saved.getValue().getGrade());
        assertNull(saved.getValue().getMark(), "an event's record starts empty, ready for a mark");
    }

    @Test
    @DisplayName("seeding from an event takes the event's own grade")
    void seedingFromAnEventUsesItsGrade() {
        when(recordRepository.findByEventTypeAndSexAndGrade(HUNDRED, SEX, Grade.C))
                .thenReturn(Optional.empty());

        int created = service.seedForEvent(event(HUNDRED, Grade.C));

        assertEquals(1, created);
        ArgumentCaptor<EventRecord> saved = ArgumentCaptor.forClass(EventRecord.class);
        verify(recordRepository).save(saved.capture());
        assertEquals(Grade.C, saved.getValue().getGrade());
    }

    @Test
    @DisplayName("seeding does not disturb a record that already exists")
    void seedingIsIdempotent() {
        EventRecord existing = EventRecord.builder()
                .id(5L).eventType(HUNDRED).sex(SEX).grade(Grade.B)
                .manualMark(new BigDecimal("11.000"))
                .hasPrevious(false)
                .build();
        when(recordRepository.findByEventTypeAndSexAndGrade(HUNDRED, SEX, Grade.B))
                .thenReturn(Optional.of(existing));

        int created = service.seedFor(HUNDRED, SEX, Grade.B);

        assertEquals(0, created, "the record is already there");
        verify(recordRepository, never()).save(any(EventRecord.class));
    }

    @Test
    @DisplayName("seeding the whole catalogue covers every event, grade included")
    void seedAllCoversTheCatalogue() {
        Event boysA = event(HUNDRED, Grade.A);
        Event boysB = Event.builder().id(3L).name("Boys 100M · B Grade").type(HUNDRED)
                .category(EventCategory.TRACK).sex(Sex.MALE).grade(Grade.B)
                .eventDate(LocalDate.of(2026, 11, 6)).enabled(true).build();
        Event girlsA = Event.builder().id(4L).name("Girls 100M · A Grade").type(HUNDRED)
                .category(EventCategory.TRACK).sex(Sex.FEMALE).grade(Grade.A)
                .eventDate(LocalDate.of(2026, 11, 6)).enabled(true).build();
        when(eventRepository.findAll()).thenReturn(List.of(boysA, boysB, girlsA));
        when(recordRepository.findByEventTypeAndSexAndGrade(any(), any(), any()))
                .thenReturn(Optional.empty());

        int created = service.seedAll();

        assertEquals(3, created, "one record per event — three events, three grades");
    }

    @Test
    @DisplayName("detaching lets a record go of an event without losing it")
    void detachingKeepsTheRecord() {
        when(recordRepository.detachResultsForEvent(2L)).thenReturn(1);

        service.detachForEvent(2L);

        verify(recordRepository).detachResultsForEvent(2L);
        verify(recordRepository, never()).delete(any(EventRecord.class));
    }
}
