package com.sportday.service;

import com.sportday.dto.BulkMarkRequest;
import com.sportday.dto.EventResultDTO;
import com.sportday.dto.MarkRowDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventResult;
import com.sportday.entity.EventStage;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.User;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.FinalEntryRepository;
import com.sportday.repository.StudentRepository;
import com.sportday.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recording <strong>ABS</strong> and <strong>DQ</strong> on the mark-entry sheet.
 *
 * <p>Requirement: a helper who can only write a number must be able to record an
 * athlete as absent or disqualified instead, in both a track and a field event.
 * Neither is a performance, so neither is placed, scores, or sets a record — but
 * both are stored and read back in place of the mark. An empty box is unchanged:
 * it still means "nothing recorded yet".</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarkEntryServiceTest {

    private static final long SHOT_ID = 9L;
    private static final long EIGHT_HUNDRED_ID = 4L;
    private static final long ATHLETE = 61L;

    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private EventRepository eventRepository;
    @Mock private EventGroupRepository groupRepository;
    @Mock private EventResultRepository resultRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private UserRepository userRepository;
    @Mock private FinalEntryRepository finalEntryRepository;
    @Mock private EventGroupService eventGroupService;
    @Mock private RecordService recordService;
    /** The relay gate: never asked anything here, because every event is an individual one. */
    @Mock private RelayReadiness relayReadiness;

    @InjectMocks private MarkEntryService service;

    private Event shotPut;
    private Event eightHundred;

    // ------------------------------------------------------------- fixtures

    private static Event event(long id, Event.EventType type) {
        return Event.builder()
                .id(id)
                .name(type.getDisplayName())
                .type(type)
                .category(type.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.B)
                .groupSize(type.getDefaultGroupSize())
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .build();
    }

    private static User user(long id) {
        return User.builder().id(id).username("S00" + id).fullName("Athlete " + id).build();
    }

    private static Enrollment entered(Event event, long userId) {
        return Enrollment.builder()
                .user(user(userId))
                .event(event)
                .status(Enrollment.EnrollmentStatus.CONFIRMED)
                .build();
    }

    private static BulkMarkRequest oneRow(BulkMarkRequest.Entry row) {
        return BulkMarkRequest.builder().stage(EventStage.HEAT.name()).rows(List.of(row)).build();
    }

    @BeforeEach
    void setUp() {
        shotPut = event(SHOT_ID, Event.EventType.SHOT_PUT);
        eightHundred = event(EIGHT_HUNDRED_ID, Event.EventType.RUN_800M);
        when(eventRepository.findById(SHOT_ID)).thenReturn(Optional.of(shotPut));
        when(eventRepository.findById(EIGHT_HUNDRED_ID)).thenReturn(Optional.of(eightHundred));

        // One athlete entered in each event.
        when(enrollmentRepository.findConfirmedWithUserByEvent(
                eq(SHOT_ID), eq(Enrollment.EnrollmentStatus.CONFIRMED)))
                .thenReturn(List.of(entered(shotPut, ATHLETE)));
        when(enrollmentRepository.findConfirmedWithUserByEvent(
                eq(EIGHT_HUNDRED_ID), eq(Enrollment.EnrollmentStatus.CONFIRMED)))
                .thenReturn(List.of(entered(eightHundred, ATHLETE)));

        when(recordService.recordResultIds()).thenReturn(Set.of());
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(anyLong(), any()))
                .thenReturn(List.of());
    }

    // ------------------------------------------------------------ storing

    @Test
    @DisplayName("ABS is stored with no mark and no attempts, in place of a performance")
    void absIsStoredWithoutAMark() {
        EventResult existing = EventResult.builder()
                .id(7L).user(user(ATHLETE)).event(shotPut).stage(EventStage.HEAT)
                .mark(new BigDecimal("11.420")).unit("M")
                .attempt1(new BigDecimal("11.420"))
                .build();
        when(resultRepository.findByUserIdAndEventIdAndStage(ATHLETE, SHOT_ID, EventStage.HEAT))
                .thenReturn(Optional.of(existing));

        var result = service.saveMarks(SHOT_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .outcome("ABS")
                .build()));

        assertEquals(1, result.getSaved());
        assertEquals(0, result.getFailed());
        assertEquals(EventResult.Outcome.ABS, existing.getOutcome());
        assertNull(existing.getMark(), "an absent athlete has no mark");
        assertFalse(existing.hasAttempts(), "and no throws or jumps either");
        assertTrue(existing.isAbsentOrDisqualified());
    }

    @Test
    @DisplayName("DQ is stored the same way, in a track event as well as a field one")
    void dqIsStoredTheSameWay() {
        var result = service.saveMarks(EIGHT_HUNDRED_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .outcome("dq")
                .build()));

        assertEquals(1, result.getSaved(), "the value is accepted case-insensitively");
        var saved = org.mockito.ArgumentCaptor.forClass(EventResult.class);
        verify(resultRepository).save(saved.capture());
        assertEquals(EventResult.Outcome.DQ, saved.getValue().getOutcome());
        assertNull(saved.getValue().getMark());
        assertEquals(EventStage.HEAT, saved.getValue().getStageOrDefault());
        verify(recordService, never()).recomputeFor(any(), any(), any());
    }

    @Test
    @DisplayName("a mark replaced by an ABS/DQ takes its school record with it")
    void aReplacedMarkReleasesItsRecord() {
        EventResult existing = EventResult.builder()
                .id(7L).user(user(ATHLETE)).event(shotPut).stage(EventStage.HEAT)
                .outcome(EventResult.Outcome.RESULT)
                .mark(new BigDecimal("11.420")).unit("M")
                .build();
        when(resultRepository.findByUserIdAndEventIdAndStage(ATHLETE, SHOT_ID, EventStage.HEAT))
                .thenReturn(Optional.of(existing));

        service.saveMarks(SHOT_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .outcome("DQ")
                .build()));

        // The record was built on a performance that no longer exists, so it is
        // rebuilt — the outcome itself never has a say in the record.
        verify(recordService).recomputeFor(Event.EventType.SHOT_PUT, Sex.MALE, Grade.B);
    }

    @Test
    @DisplayName("an ABS/DQ row is not put through the mark rules — there is no number to check")
    void absSkipsEveryMarkRule() {
        // A negative throw and 1 minute 75 seconds would each be refused for a real
        // mark; neither is a mark here.
        var field = service.saveMarks(SHOT_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .outcome("ABS")
                .attempts(List.of(new BigDecimal("-5.000")))
                .build()));
        assertEquals(1, field.getSaved());
        assertEquals(0, field.getFailed(), "the abs row has no mark to be implausible");

        var timed = service.saveMarks(EIGHT_HUNDRED_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .outcome("DQ")
                .minutes(1)
                .seconds(new BigDecimal("75"))
                .build()));
        assertEquals(1, timed.getSaved());
        assertEquals(0, timed.getFailed(), "and no stopwatch time to be mistyped");
    }

    @Test
    @DisplayName("a value that is not an outcome is refused, naming the value")
    void anUnknownOutcomeIsRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.saveMarks(SHOT_ID, oneRow(BulkMarkRequest.Entry.builder()
                        .userId(ATHLETE)
                        .outcome("NR")
                        .build())));

        assertTrue(error.getMessage().contains("NR"), error.getMessage());
        verify(resultRepository, never()).save(any());
    }

    @Test
    @DisplayName("ABS still needs the athlete to be entered in the event")
    void absStillRequiresAnEntry() {
        when(enrollmentRepository.findConfirmedWithUserByEvent(
                eq(SHOT_ID), eq(Enrollment.EnrollmentStatus.CONFIRMED)))
                .thenReturn(List.of());

        var result = service.saveMarks(SHOT_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .outcome("ABS")
                .build()));

        assertEquals(1, result.getFailed());
        assertEquals(0, result.getSaved());
        assertTrue(result.getErrors().get(0).getMessage().contains("not entered"),
                result.getErrors().get(0).getMessage());
        verify(resultRepository, never()).save(any());
    }

    // ------------------------------------------------ back and forth again

    @Test
    @DisplayName("a mark saved over an ABS puts the performance back")
    void aMarkOverAbsRestoresTheResult() {
        EventResult existing = EventResult.builder()
                .id(7L).user(user(ATHLETE)).event(shotPut).stage(EventStage.HEAT)
                .outcome(EventResult.Outcome.ABS)
                .build();
        when(resultRepository.findByUserIdAndEventIdAndStage(ATHLETE, SHOT_ID, EventStage.HEAT))
                .thenReturn(Optional.of(existing));

        var result = service.saveMarks(SHOT_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .mark(new BigDecimal("12.500"))
                .build()));

        assertEquals(1, result.getSaved());
        assertEquals(EventResult.Outcome.RESULT, existing.getOutcome());
        assertEquals(0, new BigDecimal("12.500").compareTo(existing.getMark()));
        assertEquals(0, new BigDecimal("12.500").compareTo(existing.getAttempt1()));
    }

    @Test
    @DisplayName("an ABS saved over a mark takes the mark and the attempts away")
    void absOverAMarkClearsIt() {
        EventResult existing = EventResult.builder()
                .id(7L).user(user(ATHLETE)).event(shotPut).stage(EventStage.HEAT)
                .outcome(EventResult.Outcome.RESULT)
                .mark(new BigDecimal("12.500")).unit("M")
                .attempt1(new BigDecimal("12.500"))
                .build();
        when(resultRepository.findByUserIdAndEventIdAndStage(ATHLETE, SHOT_ID, EventStage.HEAT))
                .thenReturn(Optional.of(existing));

        service.saveMarks(SHOT_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .outcome("ABS")
                .build()));

        assertEquals(EventResult.Outcome.ABS, existing.getOutcome());
        assertNull(existing.getMark());
        assertFalse(existing.hasAttempts());
    }

    @Test
    @DisplayName("an empty box is still nothing recorded yet, not a clearing save")
    void anEmptyBoxIsStillLeftAlone() {
        EventResult existing = EventResult.builder()
                .id(7L).user(user(ATHLETE)).event(shotPut).stage(EventStage.HEAT)
                .outcome(EventResult.Outcome.RESULT)
                .mark(new BigDecimal("12.500")).unit("M")
                .build();
        when(resultRepository.findByUserIdAndEventIdAndStage(ATHLETE, SHOT_ID, EventStage.HEAT))
                .thenReturn(Optional.of(existing));

        var result = service.saveMarks(SHOT_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .outcome("RESULT")
                .build()));

        assertEquals(1, result.getSkipped());
        assertEquals(0, result.getCleared());
        assertEquals(0, new BigDecimal("12.500").compareTo(existing.getMark()),
                "an untouched row keeps whatever is stored");
    }

    // ------------------------------------------------- the one time box

    @Test
    @DisplayName("a time typed as M.SS.mmm is stored as the total in seconds")
    void aTypedTimeIsStoredAsSeconds() {
        var result = service.saveMarks(EIGHT_HUNDRED_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .time("2.15.500")
                .build()));

        assertEquals(1, result.getSaved());
        assertEquals(0, result.getFailed());
        var saved = org.mockito.ArgumentCaptor.forClass(EventResult.class);
        verify(resultRepository).save(saved.capture());
        assertEquals(0, new BigDecimal("135.500").compareTo(saved.getValue().getMark()));
        assertEquals("s", saved.getValue().getUnit(), "the event decides the unit");
    }

    @Test
    @DisplayName("a time with no minute part is seconds, exactly as the box says")
    void aTimeWithNoMinutePartIsSeconds() {
        var result = service.saveMarks(EIGHT_HUNDRED_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .time("48.123")
                .build()));

        assertEquals(1, result.getSaved());
        var saved = org.mockito.ArgumentCaptor.forClass(EventResult.class);
        verify(resultRepository).save(saved.capture());
        assertEquals(0, new BigDecimal("48.123").compareTo(saved.getValue().getMark()),
                "48.123 means 48.123 seconds — there is no minute part to read it as");
    }

    @Test
    @DisplayName("a malformed time is refused outright, not read as a number")
    void aMalformedTimeIsRefused() {
        // 1 minute 75 seconds is how somebody mistypes 2:15. Read as a number it
        // would be a plausible 2:15 that nobody wrote, so it is refused instead.
        var result = service.saveMarks(EIGHT_HUNDRED_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .time("1.75.000")
                .build()));

        assertEquals(1, result.getFailed());
        assertEquals(0, result.getSaved());
        assertEquals(Long.valueOf(ATHLETE), result.getErrors().get(0).getUserId());
        assertTrue(result.getErrors().get(0).getMessage().contains("under 60"),
                result.getErrors().get(0).getMessage());
        verify(resultRepository, never()).save(any());
    }

    @Test
    @DisplayName("a time that is not a time at all is refused, naming the shape")
    void nonsenseIsRefusedWithTheShape() {
        var result = service.saveMarks(EIGHT_HUNDRED_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .time("2.15.500s")
                .build()));

        assertEquals(1, result.getFailed());
        assertEquals(0, result.getSaved());
        String message = result.getErrors().get(0).getMessage();
        assertTrue(message.contains("2.15.500s"), message);
        assertTrue(message.contains(StopwatchTime.SHAPE), message);
        verify(resultRepository, never()).save(any());
    }

    @Test
    @DisplayName("a blank time box is still nothing recorded, not a refusal")
    void aBlankTimeBoxIsLeftAlone() {
        var result = service.saveMarks(EIGHT_HUNDRED_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .time("   ")
                .build()));

        assertEquals(1, result.getSkipped());
        assertEquals(0, result.getFailed());
    }

    @Test
    @DisplayName("the grid hands the box the one time the mark is, as M.SS.mmm")
    void theGridShowsTheTimeInTheShape() {
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(EIGHT_HUNDRED_ID, EventStage.HEAT))
                .thenReturn(List.of(EventResult.builder()
                        .id(7L).user(user(ATHLETE)).event(eightHundred).stage(EventStage.HEAT)
                        .mark(new BigDecimal("135.500")).unit("s")
                        .build()));
        when(studentRepository.findWithUserByUserIdIn(any())).thenReturn(List.of());

        MarkRowDTO row = service.getMarkSheet(EIGHT_HUNDRED_ID, null, null, EventStage.HEAT)
                .getRows().get(0);

        assertEquals("2.15.500", row.getTime(),
                "what the box shows is exactly what the server parses back");
        assertEquals(0, StopwatchTime.parse(row.getTime()).compareTo(row.getMark()));
        // The 400M rows read the same way, with the leading zero minute.
        assertEquals("0.48.123", StopwatchTime.format(new BigDecimal("48.123")));
    }

    @Test
    @DisplayName("an event that is not timed on a stopwatch carries no time text")
    void aShotPutHasNoStopwatchText() {
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(SHOT_ID, EventStage.HEAT))
                .thenReturn(List.of(EventResult.builder()
                        .id(7L).user(user(ATHLETE)).event(shotPut).stage(EventStage.HEAT)
                        .mark(new BigDecimal("62.400")).unit("M")
                        .build()));
        when(studentRepository.findWithUserByUserIdIn(any())).thenReturn(List.of());

        MarkRowDTO row = service.getMarkSheet(SHOT_ID, null, null, EventStage.HEAT).getRows().get(0);

        assertNull(row.getTime(), "62.4 metres is not a stopped time");
    }

    @Test
    @DisplayName("the two-box shape an older client still sends is read too")
    void theTwoBoxShapeStillSaves() {
        var result = service.saveMarks(EIGHT_HUNDRED_ID, oneRow(BulkMarkRequest.Entry.builder()
                .userId(ATHLETE)
                .minutes(2)
                .seconds(new BigDecimal("15.5"))
                .build()));

        assertEquals(1, result.getSaved());
        var saved = org.mockito.ArgumentCaptor.forClass(EventResult.class);
        verify(resultRepository).save(saved.capture());
        assertEquals(0, new BigDecimal("135.5").compareTo(saved.getValue().getMark()));
    }

    // ---------------------------------------------------------- read back

    @Test
    @DisplayName("the grid shows the outcome, with no mark, for an ABS or DQ row")
    void theGridShowsTheOutcome() {
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(SHOT_ID, EventStage.HEAT))
                .thenReturn(List.of(EventResult.builder()
                        .id(7L).user(user(ATHLETE)).event(shotPut).stage(EventStage.HEAT)
                        .outcome(EventResult.Outcome.ABS)
                        .build()));
        when(studentRepository.findWithUserByUserIdIn(any())).thenReturn(List.of());

        MarkRowDTO row = service.getMarkSheet(SHOT_ID, null, null, EventStage.HEAT).getRows().get(0);

        assertEquals("ABS", row.getOutcome());
        assertNull(row.getMark());
        assertNull(row.getAttempts());
        assertNull(row.getMinutes());
        assertNull(row.getSeconds());
    }

    @Test
    @DisplayName("a result reads ABS or DQ where the mark would be")
    void aResultReadsTheOutcomeInPlaceOfAMark() {
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(SHOT_ID, EventStage.HEAT))
                .thenReturn(List.of(
                        EventResult.builder()
                                .id(7L).user(user(1L)).event(shotPut).stage(EventStage.HEAT)
                                .outcome(EventResult.Outcome.ABS)
                                .build(),
                        EventResult.builder()
                                .id(8L).user(user(2L)).event(shotPut).stage(EventStage.HEAT)
                                .outcome(EventResult.Outcome.DQ)
                                .build()));

        List<EventResultDTO> results = service.getResultsByEvent(SHOT_ID, EventStage.HEAT);

        assertEquals(2, results.size());
        assertEquals("ABS", results.get(0).getDisplayMark());
        assertEquals("ABS", results.get(0).getOutcome());
        assertNull(results.get(0).getMark());
        assertEquals("DQ", results.get(1).getDisplayMark());
        assertEquals("DQ", results.get(1).getOutcome());
    }

    @Test
    @DisplayName("an athlete who was absent or disqualified is listed after the placed ones")
    void resultsListTheOutcomesLast() {
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(SHOT_ID, EventStage.HEAT))
                .thenReturn(List.of(
                        EventResult.builder()
                                .id(7L).user(user(1L)).event(shotPut).stage(EventStage.HEAT)
                                .outcome(EventResult.Outcome.ABS)
                                .build(),
                        EventResult.builder()
                                .id(8L).user(user(2L)).event(shotPut).stage(EventStage.HEAT)
                                .mark(new BigDecimal("12.500")).unit("M")
                                .build()));

        List<EventResultDTO> results = service.getResultsByEvent(SHOT_ID, EventStage.HEAT);

        assertEquals("12.5M", results.get(0).getDisplayMark(), "the performance comes first");
        assertEquals("ABS", results.get(1).getDisplayMark(), "the absent athlete follows");
    }

    @Test
    @DisplayName("a mark recorded for an athlete never reads as an outcome")
    void aRealMarkReadsAsAMark() {
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(SHOT_ID, EventStage.HEAT))
                .thenReturn(List.of(EventResult.builder()
                        .id(7L).user(user(1L)).event(shotPut).stage(EventStage.HEAT)
                        .mark(new BigDecimal("18.120")).unit("M")
                        .build()));

        EventResultDTO dto = service.getResultsByEvent(SHOT_ID, EventStage.HEAT).get(0);

        assertEquals("RESULT", dto.getOutcome());
        assertEquals("18.12M", dto.getDisplayMark());
        assertEquals(0, new BigDecimal("18.120").compareTo(dto.getMark()));
    }
}
