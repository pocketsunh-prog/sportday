package com.sportday.service;

import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Drawing the final of a short sprint from the heat results.
 *
 * <p>Requirement: 60/100/200/400 may be run as heats and then a final, with the
 * top 8 going through.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FinalQualificationServiceTest {

    private static final long EVENT_ID = 2L;

    @Mock private EventRepository eventRepository;
    @Mock private EventGroupRepository groupRepository;
    @Mock private EventResultRepository resultRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private FinalEntryRepository finalEntryRepository;
    @Mock private RecordService recordService;

    /**
     * The rule the draw refuses on is {@link FinalStageGuard}'s, so the real guard
     * over the mocked group table is wired here rather than a mock of it. If the
     * guard were mocked, these tests would prove that this service calls something,
     * not that the two ends of the rule agree — which is what they are for.
     */
    private FinalStageGuard finalStageGuard;

    private FinalQualificationService service;

    private Event event;

    @BeforeEach
    void setUp() {
        finalStageGuard = new FinalStageGuard(groupRepository);
        service = new FinalQualificationService(eventRepository, groupRepository, resultRepository,
                enrollmentRepository, studentRepository, finalEntryRepository, recordService,
                finalStageGuard);

        event = Event.builder()
                .id(EVENT_ID)
                .name("Boys 60M")
                .type(Event.EventType.RUN_60M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .groupSize(8)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                // This event is being run as heats and a final; a new event would
                // default to running straight to a final instead.
                .directToFinal(false)
                .build();
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(groupRepository.findFirstByEventIdAndStage(EVENT_ID, EventStage.FINAL))
                .thenReturn(Optional.empty());
        when(resultRepository.deleteByEventIdAndStage(EVENT_ID, EventStage.FINAL)).thenReturn(0);
    }

    // ------------------------------------------------------------- fixtures

    private User user(long id) {
        return User.builder().id(id).username(String.format("S%04d", id)).fullName("Athlete " + id).build();
    }

    /** A recorded heat mark, and the confirmed entry needed to be considered. */
    private void record(long userId, String mark) {
        User athlete = user(userId);
        recorded.add(EventResult.builder()
                .id(userId).user(athlete).event(event).stage(EventStage.HEAT)
                .mark(new BigDecimal(mark)).unit("seconds").build());
    }

    private final List<EventResult> recorded = new ArrayList<>();

    // ------------------------------------- a field no bigger than a final

    @Test
    @DisplayName("a sprint with a group's worth or fewer runs straight to a final")
    void aSmallFieldRunsStraightToAFinal() {
        event.setDirectToFinal(false);
        when(enrollmentRepository.countByEventIdAndStatus(
                EVENT_ID, Enrollment.EnrollmentStatus.CONFIRMED)).thenReturn(8L);

        assertTrue(service.syncFinalFormat(EVENT_ID), "the format changed");
        assertTrue(event.isDirectToFinal(), "eight entrants fit a final exactly");
        assertTrue(event.getDirectToFinalAuto(), "and the school can see the system did it");
    }

    @Test
    @DisplayName("one more than a final's worth keeps the heats and final")
    void aFullFieldKeepsItsHeats() {
        event.setDirectToFinal(false);
        when(enrollmentRepository.countByEventIdAndStatus(
                EVENT_ID, Enrollment.EnrollmentStatus.CONFIRMED)).thenReturn(9L);

        assertFalse(service.syncFinalFormat(EVENT_ID), "nothing to change");
        assertFalse(event.isDirectToFinal());
    }

    @Test
    @DisplayName("entries rising again bring the final back, when the system removed it")
    void theFinalComesBackWhenEntriesRise() {
        event.setDirectToFinal(true);
        event.setDirectToFinalAuto(true);   // the system closed it
        when(enrollmentRepository.countByEventIdAndStatus(
                EVENT_ID, Enrollment.EnrollmentStatus.CONFIRMED)).thenReturn(20L);

        assertTrue(service.syncFinalFormat(EVENT_ID));
        assertFalse(event.isDirectToFinal(), "heats and a final are back on");
        assertFalse(event.getDirectToFinalAuto());
    }

    @Test
    @DisplayName("a format the school chose is never undone by the entry count")
    void theSchoolsOwnChoiceStands() {
        event.setDirectToFinal(true);
        event.setDirectToFinalAuto(false);  // the school closed it, deliberately
        when(enrollmentRepository.countByEventIdAndStatus(
                EVENT_ID, Enrollment.EnrollmentStatus.CONFIRMED)).thenReturn(40L);

        assertFalse(service.syncFinalFormat(EVENT_ID), "left alone");
        assertTrue(event.isDirectToFinal(), "the school wanted a straight final");
    }

    @Test
    @DisplayName("an event that cannot have a final is not touched")
    void anEventWithoutAFinalIsNotTouched() {
        event.setType(Event.EventType.RUN_800M);
        event.setDirectToFinal(true);

        assertFalse(service.syncFinalFormat(EVENT_ID));
        verify(enrollmentRepository, never()).countByEventIdAndStatus(any(), any());
    }

    /** Wires up the mocks from everything handed to {@link #record}. */
    private void givenHeatMarks() {
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(EVENT_ID, EventStage.HEAT))
                .thenReturn(recorded);

        List<Enrollment> entries = new ArrayList<>();
        List<Student> students = new ArrayList<>();
        for (EventResult result : recorded) {
            entries.add(Enrollment.builder().user(result.getUser()).event(event)
                    .status(Enrollment.EnrollmentStatus.CONFIRMED).build());
            students.add(Student.builder()
                    .id(result.getUser().getId())
                    .user(result.getUser())
                    .studentId(result.getUser().getUsername())
                    .name("Athlete " + result.getUser().getId())
                    .dob(LocalDate.of(2012, 1, 1))
                    .sex(Sex.MALE)
                    .className("1A")
                    .classNumber(result.getUser().getId().intValue())
                    .house("Red")
                    .grade(Grade.C)
                    .enabled(true)
                    .build());
        }
        when(enrollmentRepository.findConfirmedWithUserByEvent(EVENT_ID, Enrollment.EnrollmentStatus.CONFIRMED))
                .thenReturn(entries);
        when(studentRepository.findWithUserByUserIdIn(any())).thenReturn(students);
        when(groupRepository.save(any(EventGroup.class))).thenAnswer(invocation -> {
            EventGroup saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
        for (EventResult result : recorded) {
            when(enrollmentRepository.findByUserIdAndEventId(result.getUser().getId(), EVENT_ID))
                    .thenReturn(Optional.of(Enrollment.builder().user(result.getUser()).event(event)
                            .status(Enrollment.EnrollmentStatus.CONFIRMED).build()));
        }
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("a track event puts the fastest first")
    void trackRanksFastestFirst() {
        record(1, "12.500");
        record(2, "11.900");
        record(3, "13.100");
        givenHeatMarks();

        var summary = service.preview(EVENT_ID, null);

        assertEquals(3, summary.rankedAthletes());
        assertEquals(2L, summary.qualifiers().get(0).userId(), "11.900 is the fastest");
        assertEquals(1L, summary.qualifiers().get(1).userId());
        assertEquals(3L, summary.qualifiers().get(2).userId());
        assertEquals(1, summary.qualifiers().get(0).rank());
        assertEquals(new BigDecimal("11.900"), summary.qualifiers().get(0).heatMark());
    }

    @Test
    @DisplayName("a field event is decided by its own run, so it has no final to draw")
    void aFieldEventCannotHaveAFinal() {
        // Every field event goes straight to a final: one attempt at each athlete's
        // best throw or jump, and that is the result. Ranking a field event is the
        // championship's job, not this one's.
        event.setType(Event.EventType.LONG_JUMP);
        event.setCategory(EventCategory.FIELD);
        event.setDirectToFinal(false);
        record(1, "5.100");
        record(2, "7.200");
        givenHeatMarks();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.preview(EVENT_ID, null));
        assertTrue(error.getMessage().contains("straight to a final"), error.getMessage());
        assertThrows(IllegalStateException.class, () -> service.generate(EVENT_ID, null));
    }

    @Test
    @DisplayName("a distance event is decided by its own run too")
    void aDistanceEventCannotHaveAFinal() {
        event.setType(Event.EventType.RUN_800M);
        event.setDirectToFinal(false);
        record(1, "150.000");
        givenHeatMarks();

        assertThrows(IllegalStateException.class, () -> service.generate(EVENT_ID, null));
    }

    @Test
    @DisplayName("the top 8 go through out of a full field")
    void onlyTheTopEightQualify() {
        for (int i = 1; i <= 20; i++) {
            record(i, String.format("%.3f", 10.0 + i * 0.1));
        }
        givenHeatMarks();

        var summary = service.preview(EVENT_ID, null);

        assertEquals(8, summary.finalSize(), "a short sprint's group size is the final size");
        assertEquals(20, summary.rankedAthletes());
        assertEquals(8, summary.qualified());
        assertEquals(1L, summary.qualifiers().get(0).userId(), "10.100 is the fastest");
        assertEquals(8L, summary.qualifiers().get(7).userId(), "10.800 is the last qualifier");
    }

    @Test
    @DisplayName("a limit overrides the default final size")
    void limitOverridesTheSize() {
        for (int i = 1; i <= 10; i++) {
            record(i, String.format("%.3f", 10.0 + i * 0.1));
        }
        givenHeatMarks();

        assertEquals(6, service.preview(EVENT_ID, 6).qualified());
        assertEquals(6, service.preview(EVENT_ID, 6).finalSize());
    }

    @Test
    @DisplayName("equal marks are separated by student id, so the draw is repeatable")
    void tiesAreBrokenDeterministically() {
        record(7, "12.000");
        record(3, "12.000");
        record(5, "12.000");
        givenHeatMarks();

        var summary = service.preview(EVENT_ID, null);

        assertEquals(List.of(3L, 5L, 7L), summary.qualifiers().stream().map(q -> q.userId()).toList());
        // And again, to prove it does not depend on iteration order.
        assertEquals(List.of(3L, 5L, 7L), service.preview(EVENT_ID, null).qualifiers().stream()
                .map(q -> q.userId()).toList());
    }

    @Test
    @DisplayName("an athlete who withdrew cannot qualify")
    void withdrawnAthletesAreExcluded() {
        record(1, "11.000");
        record(2, "12.000");
        givenHeatMarks();
        // Athlete 1 is no longer on the confirmed entry list.
        when(enrollmentRepository.findConfirmedWithUserByEvent(EVENT_ID, Enrollment.EnrollmentStatus.CONFIRMED))
                .thenReturn(List.of(Enrollment.builder().user(user(2)).event(event)
                        .status(Enrollment.EnrollmentStatus.CONFIRMED).build()));

        var summary = service.preview(EVENT_ID, null);

        assertEquals(1, summary.qualified());
        assertEquals(2L, summary.qualifiers().get(0).userId());
    }

    @Test
    @DisplayName("drawing a final with no heat results is refused with an explanation")
    void drawingWithoutHeatMarksIsRejected() {
        givenHeatMarks();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.generate(EVENT_ID, null));
        assertTrue(error.getMessage().contains("heat"), error.getMessage());
    }
    @Test
    @DisplayName("an event set to run straight to a final has no final to draw")
    void drawingIsRefusedWhenTheEventIsDirectToFinal() {
        event.setDirectToFinal(true);
        givenHeatMarks();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.generate(EVENT_ID, null));
        assertTrue(error.getMessage().contains("direct"), error.getMessage());
        // The wording is the shared guard's, not this service's own, so the draw and
        // the marking grid answer with one voice about the same event.
        assertTrue(error.getMessage().endsWith(FinalStageGuard.DIRECT_TO_FINAL), error.getMessage());
        assertThrows(IllegalStateException.class, () -> service.preview(EVENT_ID, null),
                "a preview would suggest a final that can never be drawn");
        verify(groupRepository, never()).save(any());
    }

    @Test
    @DisplayName("an event that cannot have a final is refused outright")
    void drawingIsRefusedForAnEventThatCannotHaveAFinal() {
        // An 800M is decided by its own run, whatever the flag says.
        event.setType(Event.EventType.RUN_800M);
        event.setDirectToFinal(false);
        givenHeatMarks();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.generate(EVENT_ID, null));
        assertTrue(error.getMessage().contains("straight to a final"), error.getMessage());
        assertTrue(error.getMessage().endsWith(FinalStageGuard.NO_FINAL_STAGE), error.getMessage());
    }

    @Test
    @DisplayName("unticking the box allows a final for a short sprint")
    void untickingAllowsAFinal() {
        event.setDirectToFinal(false);
        for (int i = 1; i <= 10; i++) {
            record(i, String.format("%.3f", 10.0 + i * 0.1));
        }
        givenHeatMarks();

        var summary = service.generate(EVENT_ID, null);

        assertTrue(summary.drawn());
        assertEquals(8, summary.qualified());
    }

    @Test
    @DisplayName("drawing the final creates the group, its field and the seeding")
    void generateCreatesTheFinal() {
        for (int i = 1; i <= 10; i++) {
            record(i, String.format("%.3f", 10.0 + i * 0.1));
        }
        givenHeatMarks();

        var summary = service.generate(EVENT_ID, null);

        assertTrue(summary.drawn());
        assertEquals(8, summary.qualified());

        ArgumentCaptor<EventGroup> group = ArgumentCaptor.forClass(EventGroup.class);
        verify(groupRepository).save(group.capture());
        assertEquals(EventStage.FINAL, group.getValue().getStage());
        assertEquals(EventGroup.FINAL_GROUP_NUMBER, group.getValue().getGroupNumber(),
                "the final is group 0 so it cannot collide with Heat 1");
        assertEquals(8, group.getValue().getCapacity());
        assertEquals(8, group.getValue().getAthleteCount());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FinalEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(finalEntryRepository).saveAll(entries.capture());
        assertEquals(8, entries.getValue().size());
        // The lane is the draw, the seed is the entry order — and the school's draw
        // puts the fastest qualifier in the middle of the track, not in lane 1.
        assertEquals(3, entries.getValue().get(0).getLane(),
                "the fastest heat rank runs in lane 3");
        assertEquals(1, entries.getValue().get(0).getSeed(),
                "while the seed is still the rank they qualified at");
        assertEquals(new BigDecimal("10.100"), entries.getValue().get(0).getSeedMark(),
                "the seeding keeps the heat mark that earned the place");
        assertEquals(2, entries.getValue().get(6).getLane(),
                "the seventh qualifier is on the outside, in lane 2");
        assertEquals(1, entries.getValue().get(7).getLane(),
                "and the slowest qualifier has lane 1");
        // The whole table the school wrote, rank 1..8 against lane 3,4,5,6,7,8,2,1,
        // read back off the rows that were actually written.
        assertEquals(List.of(3, 4, 5, 6, 7, 8, 2, 1), entries.getValue().stream()
                        .map(FinalEntry::getLane).toList(),
                "the entries carry the school's lanes, not the ranks");
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), entries.getValue().stream()
                        .map(FinalEntry::getSeed).toList(),
                "while the seed stays the qualification rank");
    }

    @Test
    @DisplayName("a preview never writes anything")
    void previewIsReadOnly() {
        record(1, "11.000");
        givenHeatMarks();

        service.preview(EVENT_ID, null);

        verify(groupRepository, never()).save(any());
        verify(finalEntryRepository, never()).saveAll(any());
        verify(resultRepository, never()).deleteByEventIdAndStage(anyLong(), any());
    }

    @Test
    @DisplayName("re-drawing clears the previous final and the marks recorded in it")
    void redrawingClearsThePreviousFinal() {
        record(1, "11.000");
        record(2, "12.000");
        givenHeatMarks();

        EventGroup previous = EventGroup.builder()
                .id(55L).event(event).groupNumber(EventGroup.FINAL_GROUP_NUMBER)
                .stage(EventStage.FINAL).capacity(8).athleteCount(2).build();
        when(groupRepository.findFirstByEventIdAndStage(EVENT_ID, EventStage.FINAL))
                .thenReturn(Optional.of(previous));
        when(resultRepository.deleteByEventIdAndStage(EVENT_ID, EventStage.FINAL)).thenReturn(2);

        var summary = service.generate(EVENT_ID, null);

        assertEquals(2, summary.clearedFinalMarks(), "the old final's marks are gone with the field");
        verify(finalEntryRepository).deleteByGroupId(55L);
        verify(groupRepository).delete(previous);
        // The delete must be flushed before the replacement is inserted: Hibernate
        // orders inserts ahead of deletes in a flush, so without this the new final
        // (event_id, group_number 0) collides with the old one on the unique key.
        InOrder ordered = inOrder(groupRepository);
        ordered.verify(groupRepository).delete(previous);
        ordered.verify(groupRepository).flush();
        // Heat marks and heats are untouched.
        verify(resultRepository, never()).deleteByEventId(anyLong());
    }

    @Test
    @DisplayName("the summary describes the event, so the UI can label the sheet")
    void summaryDescribesTheEvent() {
        record(1, "11.000");
        givenHeatMarks();

        var summary = service.preview(EVENT_ID, null);

        assertEquals("Boys 60M", summary.eventName());
        assertEquals("60M", summary.eventTypeLabel());
        assertEquals("TRACK", summary.category());
        assertEquals("A5", summary.sheetSize(), "short sprints print on A5");
        assertTrue(summary.shortSprint());
    }

    // --------------------------------------------- absent and disqualified

    /** A heat row for an athlete who produced no mark: ABS or DQ. */
    private void recordOutcome(long userId, EventResult.Outcome value) {
        User athlete = user(userId);
        recorded.add(EventResult.builder()
                .id(userId).user(athlete).event(event).stage(EventStage.HEAT)
                .outcome(value).build());
    }

    /** A heat row for an athlete still entered, but with nothing written down. */
    private void recordNoMark(long userId) {
        User athlete = user(userId);
        recorded.add(EventResult.builder()
                .id(userId).user(athlete).event(event).stage(EventStage.HEAT)
                .outcome(EventResult.Outcome.RESULT)
                .build());
    }

    @Test
    @DisplayName("an athlete who was absent or disqualified cannot qualify for the final")
    void absentAthletesCannotQualify() {
        record(1, "12.500");
        record(2, "11.900");
        record(3, "13.100");
        recordOutcome(4, EventResult.Outcome.ABS);
        recordOutcome(5, EventResult.Outcome.DQ);
        givenHeatMarks();

        var summary = service.preview(EVENT_ID, null);

        assertEquals(3, summary.rankedAthletes(), "only the three who ran are ranked");
        assertEquals(3, summary.qualified());
        assertTrue(summary.qualifiers().stream().noneMatch(q -> q.userId() == 4L),
                "an absent athlete is not in the final");
        assertTrue(summary.qualifiers().stream().noneMatch(q -> q.userId() == 5L),
                "and neither is a disqualified one");
        assertEquals(List.of(2L, 1L, 3L), summary.qualifiers().stream()
                .map(FinalQualificationService.Qualifier::userId).toList());
    }

    @Test
    @DisplayName("an absent athlete does not take a qualifying place from somebody who ran")
    void absentAthletesDoNotDisplaceTheQualifiers() {
        // Eight real performances and one absent athlete: the eight go through.
        for (int i = 1; i <= 8; i++) {
            record(i, String.format("%.3f", 10.0 + i * 0.1));
        }
        recordOutcome(99, EventResult.Outcome.ABS);
        givenHeatMarks();

        var summary = service.preview(EVENT_ID, null);

        assertEquals(8, summary.rankedAthletes());
        assertEquals(8, summary.qualified());
        assertTrue(summary.qualifiers().stream().noneMatch(q -> q.userId() == 99L));
        assertEquals(8L, summary.qualifiers().get(7).userId(), "the last real time still qualifies");
    }

    @Test
    @DisplayName("drawing the final leaves the absent athlete out of the field entirely")
    void absentAthletesAreNotDrawnIntoTheFinal() {
        for (int i = 1; i <= 9; i++) {
            record(i, String.format("%.3f", 10.0 + i * 0.1));
        }
        recordOutcome(40, EventResult.Outcome.DQ);
        givenHeatMarks();

        var summary = service.generate(EVENT_ID, null);

        assertEquals(9, summary.rankedAthletes());
        assertEquals(8, summary.qualified());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FinalEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(finalEntryRepository).saveAll(entries.capture());
        assertTrue(entries.getValue().stream()
                        .noneMatch(entry -> entry.getUser().getId() == 40L),
                "a disqualified athlete is not in the final's field");
    }

    @Test
    @DisplayName("a heat entry with no mark cannot qualify either — nothing was run")
    void aHeatEntryWithoutAMarkCannotQualify() {
        // The three ways a heat row can carry no performance: an empty box, ABS and
        // DQ. Only a mark gets an athlete to the final, so all three stay out — and
        // the two who ran are not pushed out of the field by them.
        record(1, "11.900");
        record(2, "12.400");
        recordNoMark(6);
        recordOutcome(7, EventResult.Outcome.ABS);
        recordOutcome(8, EventResult.Outcome.DQ);
        givenHeatMarks();

        var preview = service.preview(EVENT_ID, null);

        assertEquals(2, preview.rankedAthletes(), "only the two with a mark are ranked");
        assertEquals(2, preview.qualified());
        assertEquals(List.of(1L, 2L), preview.qualifiers().stream()
                .map(FinalQualificationService.Qualifier::userId).toList());
        for (long excluded : List.of(6L, 7L, 8L)) {
            assertTrue(preview.qualifiers().stream().noneMatch(q -> q.userId() == excluded),
                    "athlete " + excluded + " produced no heat mark, so has no final");
        }

        // And the draw itself leaves the same three out of the final's field.
        var drawn = service.generate(EVENT_ID, null);
        assertEquals(2, drawn.qualified());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FinalEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(finalEntryRepository).saveAll(entries.capture());
        assertEquals(List.of(1L, 2L), entries.getValue().stream()
                .map(entry -> entry.getUser().getId()).toList());
    }

    // ------------------------------------------------------------- the lane draw

    @Test
    @DisplayName("the preview shows the lane each qualifier would be drawn into")
    void previewShowsTheDrawnLanes() {
        for (int i = 1; i <= 8; i++) {
            record(i, String.format("%.3f", 10.0 + i * 0.1));
        }
        givenHeatMarks();

        var summary = service.preview(EVENT_ID, null);

        // A client drawing the final is shown the lanes it will actually use, so the
        // fastest qualifier is not offered lane 1.
        assertEquals(List.of(3, 4, 5, 6, 7, 8, 2, 1), summary.qualifiers().stream()
                        .map(FinalQualificationService.Qualifier::lane).toList(),
                "rank 1..8 is drawn into lanes 3,4,5,6,7,8,2,1");
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), summary.qualifiers().stream()
                        .map(FinalQualificationService.Qualifier::rank).toList(),
                "while the ranking a client shows stays in performance order");
        // And the preview writes nothing.
        verify(finalEntryRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("a limit of five is drawn into lanes 3 to 7, one lane each")
    void fiveQualifiersTakeLanesThreeToSeven() {
        for (int i = 1; i <= 9; i++) {
            record(i, String.format("%.3f", 10.0 + i * 0.1));
        }
        givenHeatMarks();

        var summary = service.generate(EVENT_ID, 5);

        assertEquals(5, summary.qualified(), "the limit still decides how many go through");
        assertEquals(List.of(3, 4, 5, 6, 7), summary.qualifiers().stream()
                        .map(FinalQualificationService.Qualifier::lane).toList(),
                "five qualifiers take the middle lanes outwards, 3 to 7");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FinalEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(finalEntryRepository).saveAll(entries.capture());
        List<Integer> lanes = entries.getValue().stream().map(FinalEntry::getLane).toList();
        assertEquals(List.of(3, 4, 5, 6, 7), lanes, "the drawn final's rows carry those lanes");
        assertEquals(5, lanes.stream().distinct().count(), "and no two of them share a lane");
    }

    @Test
    @DisplayName("re-drawing a final moves an athlete to the lane their new rank earns")
    void redrawingMovesAnAthleteToTheirNewLane() {
        record(1, "11.000");
        record(2, "12.000");
        givenHeatMarks();

        service.generate(EVENT_ID, null);

        // Athlete 2 runs a faster heat before the final is drawn again, so the two
        // swap places in the ranking — and therefore lanes.
        recorded.clear();
        record(1, "12.500");
        record(2, "11.500");
        givenHeatMarks();
        EventGroup previous = EventGroup.builder()
                .id(55L).event(event).groupNumber(EventGroup.FINAL_GROUP_NUMBER)
                .stage(EventStage.FINAL).capacity(8).athleteCount(2).build();
        when(groupRepository.findFirstByEventIdAndStage(EVENT_ID, EventStage.FINAL))
                .thenReturn(Optional.of(previous));

        var second = service.generate(EVENT_ID, null);

        assertEquals(List.of(2L, 1L), second.qualifiers().stream()
                .map(FinalQualificationService.Qualifier::userId).toList());
        assertEquals(List.of(3, 4), second.qualifiers().stream()
                .map(FinalQualificationService.Qualifier::lane).toList());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FinalEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(finalEntryRepository, times(2)).saveAll(entries.capture());
        List<List<FinalEntry>> drawn = entries.getAllValues();
        assertEquals(3, laneOf(drawn.get(0), 1L), "athlete 1 qualified fastest first time");
        assertEquals(4, laneOf(drawn.get(1), 1L), "and the re-draw moved them to lane 4");
        assertEquals(4, laneOf(drawn.get(0), 2L), "athlete 2 had lane 4");
        assertEquals(3, laneOf(drawn.get(1), 2L), "and took the middle lane of the new draw");
        // The old field is deleted before the replacement is written, so nothing of
        // the previous draw — lane included — is left behind.
        verify(finalEntryRepository).deleteByGroupId(55L);
    }

    /** The lane a final's saved rows give an athlete; null when they are not in it. */
    private static Integer laneOf(List<FinalEntry> entries, long userId) {
        return entries.stream()
                .filter(entry -> entry.getUser().getId() == userId)
                .map(FinalEntry::getLane)
                .findFirst()
                .orElse(null);
    }
}
