package com.sportday.service;

import com.sportday.dto.MarkRowDTO;
import com.sportday.dto.MarkSheetDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventGroup;
import com.sportday.entity.EventResult;
import com.sportday.entity.EventStage;
import com.sportday.entity.FinalEntry;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.Student;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The heat record on the <strong>input</strong> form: a final's mark-entry grid
 * carries what each finalist ran in their heat, beside the box the final is
 * written in.
 *
 * <p>The final is drawn <em>from</em> the heat marks, so the person keying the
 * final in needs to see them. The heat reads exactly as it reads everywhere else
 * — {@code 11.86s}, through {@link MarkFormatter} — and an athlete whose heat was
 * ABS or DQ shows that rather than a number. A <strong>heat</strong> grid is
 * unchanged: it has no earlier stage, so it carries none of this.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarkEntryFinalHeatRecordTest {

    private static final long EVENT_ID = 2L;
    private static final long FINAL_GROUP_ID = 99L;
    private static final long RAN_THE_HEAT = 61L;
    private static final long DISQUALIFIED = 62L;

    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private EventRepository eventRepository;
    @Mock private EventGroupRepository groupRepository;
    @Mock private EventResultRepository resultRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private UserRepository userRepository;
    @Mock private FinalEntryRepository finalEntryRepository;
    @Mock private EventGroupService eventGroupService;
    @Mock private RecordService recordService;

    @InjectMocks private MarkEntryService service;

    private Event sprint;
    private EventGroup finalGroup;

    // ------------------------------------------------------------- fixtures

    private static User user(long id) {
        return User.builder().id(id).username("S00" + id).fullName("Athlete " + id).build();
    }

    private static Student student(User athlete) {
        return Student.builder()
                .id(athlete.getId())
                .user(athlete)
                .studentId(athlete.getUsername())
                .name("Athlete " + athlete.getId())
                .dob(LocalDate.of(2012, 1, 1))
                .sex(Sex.MALE)
                .className("1A")
                .classNumber(athlete.getId().intValue())
                .house("Red")
                .grade(Grade.C)
                .enabled(true)
                .build();
    }

    /** The mark a finalist ran in their heat: 11.860s for one, DQ for the other. */
    private static EventResult heatResult(Event event, User athlete, BigDecimal mark,
                                          EventResult.Outcome outcome) {
        return EventResult.builder()
                .id(athlete.getId() + 100)
                .user(athlete)
                .event(event)
                .stage(EventStage.HEAT)
                .outcome(outcome)
                .mark(mark)
                .unit(outcome == EventResult.Outcome.RESULT ? "s" : null)
                .build();
    }

    private static EventResult finalResult(Event event, User athlete, String mark) {
        return EventResult.builder()
                .id(athlete.getId() + 200)
                .user(athlete)
                .event(event)
                .stage(EventStage.FINAL)
                .outcome(EventResult.Outcome.RESULT)
                .mark(new BigDecimal(mark))
                .unit("s")
                .build();
    }

    private static EventGroup finalGroup(Event event) {
        return EventGroup.builder()
                .id(FINAL_GROUP_ID)
                .event(event)
                .groupNumber(EventGroup.FINAL_GROUP_NUMBER)
                .stage(EventStage.FINAL)
                .capacity(8)
                .athleteCount(2)
                .build();
    }

    private static MarkRowDTO rowOf(MarkSheetDTO sheet, long userId) {
        return sheet.getRows().stream()
                .filter(row -> row.getUserId() != null && row.getUserId() == userId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for athlete " + userId));
    }

    @BeforeEach
    void setUp() {
        sprint = Event.builder()
                .id(EVENT_ID)
                .name("Boys 100M")
                .type(Event.EventType.RUN_100M)
                .category(Event.EventType.RUN_100M.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.B)
                .groupSize(8)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .directToFinal(false)
                .build();
        finalGroup = finalGroup(sprint);

        User ranIt = user(RAN_THE_HEAT);
        User disqualified = user(DISQUALIFIED);

        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(sprint));
        when(eventGroupService.finalDrawn(EVENT_ID)).thenReturn(true);
        when(groupRepository.findFirstByEventIdAndStage(EVENT_ID, EventStage.FINAL))
                .thenReturn(Optional.of(finalGroup));
        when(finalEntryRepository.findByGroupIdOrderByLaneAsc(FINAL_GROUP_ID)).thenReturn(List.of(
                FinalEntry.builder().id(1L).group(finalGroup).user(ranIt).lane(1).seed(1)
                        .seedMark(new BigDecimal("11.860")).seedUnit("s").build(),
                FinalEntry.builder().id(2L).group(finalGroup).user(disqualified).lane(2).seed(2)
                        .seedMark(null).seedUnit(null).build()));

        // The heat the two of them ran, and the final mark recorded for the one who
        // has run the final already.
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(EVENT_ID, EventStage.HEAT))
                .thenReturn(List.of(
                        heatResult(sprint, ranIt, new BigDecimal("11.860"), EventResult.Outcome.RESULT),
                        heatResult(sprint, disqualified, null, EventResult.Outcome.DQ)));
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(EVENT_ID, EventStage.FINAL))
                .thenReturn(List.of(finalResult(sprint, ranIt, "11.500")));

        // The heat grid's own field: one confirmed entry, as the heats were drawn.
        when(enrollmentRepository.findConfirmedWithUserByEvent(
                eq(EVENT_ID), eq(Enrollment.EnrollmentStatus.CONFIRMED)))
                .thenReturn(List.of(Enrollment.builder().user(ranIt).event(sprint)
                        .status(Enrollment.EnrollmentStatus.CONFIRMED).build()));

        when(studentRepository.findWithUserByUserIdIn(any()))
                .thenReturn(List.of(student(ranIt), student(disqualified)));
        when(recordService.recordResultIds()).thenReturn(Set.of());
    }

    // -------------------------------------------------------- the final grid

    @Test
    @DisplayName("the final grid carries the heat mark beside the final being written")
    void theFinalGridCarriesTheHeatRecord() {
        MarkSheetDTO sheet = service.getMarkSheet(EVENT_ID, null, null, EventStage.FINAL);

        MarkRowDTO row = rowOf(sheet, RAN_THE_HEAT);

        assertEquals(0, new BigDecimal("11.860").compareTo(row.getHeatMark()),
                "the raw heat mark, in the event's own unit");
        assertEquals("RESULT", row.getHeatOutcome(), "the outcome vocabulary a result uses");
        assertEquals("11.86s", row.getHeatDisplayMark(), "and it reads as it does everywhere else");
        assertEquals(0, new BigDecimal("11.500").compareTo(row.getMark()),
                "the final's own mark is untouched by the heat being shown");

        // One lookup for the whole sheet, not one per row.
        verify(resultRepository, times(1))
                .findByEventIdAndStageOrderByMarkAsc(EVENT_ID, EventStage.HEAT);
    }

    @Test
    @DisplayName("a finalist whose heat was disqualified shows DQ, not a number")
    void aDisqualifiedHeatReadsAsDQ() {
        MarkRowDTO row = rowOf(service.getMarkSheet(EVENT_ID, null, null, EventStage.FINAL),
                DISQUALIFIED);

        assertNull(row.getHeatMark(), "a disqualified heat has no mark behind it");
        assertEquals("DQ", row.getHeatOutcome());
        assertEquals("DQ", row.getHeatDisplayMark(), "and the grid can print that as it stands");
        assertNull(row.getMark(), "nothing has been written in the final for them yet");
    }

    @Test
    @DisplayName("the heat record on the final grid is the mark the heat sheet shows")
    void theHeatRecordMatchesTheHeatSheet() {
        MarkSheetDTO heatSheet = service.getMarkSheet(EVENT_ID, null, null, EventStage.HEAT);
        MarkSheetDTO finalSheet = service.getMarkSheet(EVENT_ID, null, null, EventStage.FINAL);

        MarkRowDTO asRun = rowOf(heatSheet, RAN_THE_HEAT);
        MarkRowDTO inTheFinal = rowOf(finalSheet, RAN_THE_HEAT);

        assertEquals(0, asRun.getMark().compareTo(inTheFinal.getHeatMark()),
                "the final grid's heat mark is the mark the heat recorded");
        assertEquals(
                MarkFormatter.formatWithUnit(asRun.getMark(), sprint.getType(), asRun.getUnit()),
                inTheFinal.getHeatDisplayMark(),
                "and it is read through the one formatter, so the two never drift");
    }

    // -------------------------------------------------- the heat grid, again

    @Test
    @DisplayName("a heat grid is unchanged: its own rows carry no heat record")
    void theHeatGridCarriesNoHeatRecord() {
        MarkSheetDTO sheet = service.getMarkSheet(EVENT_ID, null, null, EventStage.HEAT);

        MarkRowDTO row = rowOf(sheet, RAN_THE_HEAT);

        assertEquals(0, new BigDecimal("11.860").compareTo(row.getMark()), "the heat's own mark");
        assertNull(row.getHeatMark(), "a heat has no earlier heat to show");
        assertNull(row.getHeatOutcome());
        assertNull(row.getHeatDisplayMark());
    }

    @Test
    @DisplayName("a group's own grid is a final's only when the group says so")
    void aGroupGridIsReadFromTheGroup() {
        // Reading the final by its group id takes the stage from the group, so the
        // heat record is there without the caller passing a stage at all.
        when(eventGroupService.requireGroup(FINAL_GROUP_ID)).thenReturn(finalGroup);
        when(eventGroupService.membersOf(finalGroup)).thenReturn(List.of(
                new EventGroupService.GroupMember(RAN_THE_HEAT, 1, null, null)));

        MarkSheetDTO sheet = service.getMarkSheet(EVENT_ID, FINAL_GROUP_ID, null, null);

        assertEquals("FINAL", sheet.getStage());
        assertEquals("11.86s", rowOf(sheet, RAN_THE_HEAT).getHeatDisplayMark());
    }

    @Test
    @DisplayName("an event with no heat results still opens its final grid, with no heat to show")
    void aMissingHeatRowLeavesTheHeatFieldsNull() {
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(EVENT_ID, EventStage.HEAT))
                .thenReturn(List.of());

        MarkRowDTO row = rowOf(service.getMarkSheet(EVENT_ID, null, null, EventStage.FINAL),
                RAN_THE_HEAT);

        assertNull(row.getHeatMark());
        assertNull(row.getHeatOutcome(), "no heat record at all, so the client is told nothing rather than zero");
        assertNull(row.getHeatDisplayMark());
    }
}
