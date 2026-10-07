package com.sportday.service;

import com.sportday.dto.BulkMarkRequest;
import com.sportday.dto.MarkSheetDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventGroup;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Requirement 5, second half: <strong>mark entry waits for the heat result.</strong>
 *
 * <p>An event that runs heats and then a final cannot be worked on in its final
 * stage until the final has been drawn from the heat marks. Until then there is no
 * final field at all, so a grid would show nobody and a save would write a mark
 * against no one. The three no-final cases are distinct and each says so in its
 * own words:</p>
 *
 * <ul>
 *   <li>an event that cannot be split — an 800M — has no final stage at all;</li>
 *   <li>a sprint set to run straight to a final has a final stage it is not using;</li>
 *   <li>a sprint running heats has one that has not been drawn yet.</li>
 * </ul>
 *
 * <p>Every refusal is an {@link IllegalStateException}, which the application's
 * handler reports as <strong>409 Conflict</strong>. The guard's own rule is shared
 * with {@link FinalQualificationService}, so the two cannot drift.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarkEntryFinalStageTest {

    private static final long SPRINT_ID = 2L;
    private static final long DISTANCE_ID = 4L;
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
    @Mock private com.sportday.repository.RelayTeamRepository relayTeamRepository;
    @Mock private com.sportday.repository.RelayTeamMemberRepository relayTeamMemberRepository;

    /** The real rule, over a mocked group table — the rule itself is the subject. */
    private FinalStageGuard guard;

    private MarkEntryService service;

    private Event heatsAndFinal;
    private Event directToFinal;
    private Event eightHundred;

    // ------------------------------------------------------------- fixtures

    private static Event event(long id, Event.EventType type, boolean directToFinal) {
        return Event.builder()
                .id(id)
                .name("Boys " + type.getDisplayName())
                .type(type)
                .category(type.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.B)
                .groupSize(type.getDefaultGroupSize())
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .directToFinal(directToFinal)
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

    /** The final group a draw leaves behind. */
    private static EventGroup finalGroup(Event event) {
        return EventGroup.builder()
                .id(99L)
                .event(event)
                .groupNumber(EventGroup.FINAL_GROUP_NUMBER)
                .stage(EventStage.FINAL)
                .capacity(8)
                .athleteCount(1)
                .build();
    }

    private static BulkMarkRequest finalRow(Long userId, String mark) {
        return BulkMarkRequest.builder()
                .stage(EventStage.FINAL.name())
                .rows(List.of(BulkMarkRequest.Entry.builder()
                        .userId(userId)
                        .mark(mark == null ? null : new BigDecimal(mark))
                        .build()))
                .build();
    }

    @BeforeEach
    void setUp() {
        // A 100M being run as heats and a final is the only one of the three that
        // waits for a draw.
        heatsAndFinal = event(SPRINT_ID, Event.EventType.RUN_100M, false);
        directToFinal = event(SPRINT_ID, Event.EventType.RUN_100M, true);
        eightHundred = event(DISTANCE_ID, Event.EventType.RUN_800M, false);

        when(eventRepository.findById(DISTANCE_ID)).thenReturn(Optional.of(eightHundred));
        when(eventRepository.findById(SPRINT_ID)).thenReturn(Optional.of(heatsAndFinal));

        when(groupRepository.findFirstByEventIdAndStage(DISTANCE_ID, EventStage.FINAL))
                .thenReturn(Optional.empty());
        when(groupRepository.findFirstByEventIdAndStage(SPRINT_ID, EventStage.FINAL))
                .thenReturn(Optional.empty());
        // The grid asks the group service whether the final is drawn, the way it
        // asks it for a group's roster; the rule itself is the guard's.
        when(eventGroupService.finalDrawn(SPRINT_ID)).thenReturn(false);
        when(eventGroupService.finalDrawn(DISTANCE_ID)).thenReturn(false);

        when(enrollmentRepository.findConfirmedWithUserByEvent(
                eq(SPRINT_ID), eq(Enrollment.EnrollmentStatus.CONFIRMED)))
                .thenReturn(List.of(entered(heatsAndFinal, ATHLETE)));
        when(enrollmentRepository.findConfirmedWithUserByEvent(
                eq(DISTANCE_ID), eq(Enrollment.EnrollmentStatus.CONFIRMED)))
                .thenReturn(List.of(entered(eightHundred, ATHLETE)));

        when(recordService.recordResultIds()).thenReturn(Set.of());
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(anyLong(), any())).thenReturn(List.of());

        guard = new FinalStageGuard(groupRepository);
        service = new MarkEntryService(enrollmentRepository, eventRepository, groupRepository,
                resultRepository, studentRepository, userRepository, finalEntryRepository,
                eventGroupService, recordService, relayTeamRepository, relayTeamMemberRepository,
                // An individual event is never gated by the relay rule, so the guard is
                // never asked anything here — these are sprints and a distance race.
                new RelayReadiness(relayTeamRepository, relayTeamMemberRepository));
    }

    // ------------------------------------------------- the final is not drawn

    @Test
    @DisplayName("reading the final grid before the draw is refused, and says what to do first")
    void readingTheFinalGridWaitsForTheDraw() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.getMarkSheet(SPRINT_ID, null, null, EventStage.FINAL));

        assertEquals(FinalStageGuard.NOT_DRAWN, error.getMessage());
        assertTrue(error.getMessage().contains("heat"), error.getMessage());
        assertTrue(error.getMessage().contains("draw the final"), error.getMessage());
    }

    @Test
    @DisplayName("saving the final grid before the draw is refused, and writes nothing")
    void savingTheFinalGridWaitsForTheDraw() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.saveMarks(SPRINT_ID, finalRow(ATHLETE, "11.100")));

        assertEquals(FinalStageGuard.NOT_DRAWN, error.getMessage());
        verify(resultRepository, org.mockito.Mockito.never()).save(any());
        verify(finalEntryRepository, org.mockito.Mockito.never()).saveAll(any());
    }

    @Test
    @DisplayName("the heat grid is unaffected: the draw is not needed to record a heat")
    void theHeatGridIsUnaffected() {
        MarkSheetDTO sheet = service.getMarkSheet(SPRINT_ID, null, null, EventStage.HEAT);

        assertEquals("HEAT", sheet.getStage());
        assertEquals(1, sheet.getTotalAthletes());
        assertEquals("NOT_DRAWN", sheet.getFinalState(), "and the grid can see the final is to come");
        assertFalse(sheet.isFinalDrawn());
    }

    @Test
    @DisplayName("a save with no stage named is a heat save, so it is not held back")
    void savingWithoutAStageIsAHeatSave() {
        var result = service.saveMarks(SPRINT_ID, BulkMarkRequest.builder()
                .rows(List.of(BulkMarkRequest.Entry.builder()
                        .userId(ATHLETE)
                        .mark(new BigDecimal("11.100"))
                        .build()))
                .build());

        assertEquals(1, result.getSaved());
        assertEquals(EventStage.HEAT.name(), result.getStage());
    }

    // ------------------------------------------------ an event with no final

    @Test
    @DisplayName("a distance event has no final stage, so it refuses in its own words")
    void anEventThatCannotBeSplitHasNoFinalStage() {
        when(eventRepository.findById(DISTANCE_ID)).thenReturn(Optional.of(eightHundred));

        IllegalStateException read = assertThrows(IllegalStateException.class,
                () -> service.getMarkSheet(DISTANCE_ID, null, null, EventStage.FINAL));
        assertTrue(read.getMessage().contains("straight to a final"), read.getMessage());
        assertTrue(read.getMessage().contains("60M, 100M, 200M and 400M"), read.getMessage());

        IllegalStateException write = assertThrows(IllegalStateException.class,
                () -> service.saveMarks(DISTANCE_ID, finalRow(ATHLETE, "130.000")));
        assertTrue(write.getMessage().contains("straight to a final"), write.getMessage());

        // And it is not the "not drawn yet" message: there is nothing to wait for.
        assertNotEquals(FinalStageGuard.NOT_DRAWN, read.getMessage());
        verify(resultRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("a sprint set to run straight to a final refuses with its own message too")
    void aDirectToFinalSprintRefusesWithItsOwnMessage() {
        when(eventRepository.findById(SPRINT_ID)).thenReturn(Optional.of(directToFinal));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.getMarkSheet(SPRINT_ID, null, null, EventStage.FINAL));

        assertTrue(error.getMessage().contains("direct to final"), error.getMessage());
        assertTrue(error.getMessage().contains("Untick"), error.getMessage());
        assertNotEquals(FinalStageGuard.NOT_DRAWN, error.getMessage());
        assertFalse(error.getMessage().endsWith(FinalStageGuard.NO_FINAL_STAGE),
                "a sprint that could be split is not an event that cannot be split: " + error.getMessage());
    }

    @Test
    @DisplayName("the two no-final messages are different answers, not one message twice")
    void theTwoNoFinalMessagesDiffer() {
        when(eventRepository.findById(DISTANCE_ID)).thenReturn(Optional.of(eightHundred));
        when(eventRepository.findById(SPRINT_ID)).thenReturn(Optional.of(directToFinal));

        String noStage = assertThrows(IllegalStateException.class,
                () -> service.getMarkSheet(DISTANCE_ID, null, null, EventStage.FINAL)).getMessage();
        String direct = assertThrows(IllegalStateException.class,
                () -> service.getMarkSheet(SPRINT_ID, null, null, EventStage.FINAL)).getMessage();

        assertNotEquals(noStage, direct);
        assertTrue(direct.contains("Boy"), "the refusal names the event: " + direct);
    }

    // ------------------------------------------------- the final has been drawn

    @Test
    @DisplayName("once the final is drawn the final grid reads, and says so")
    void theFinalGridOpensOnceTheFinalIsDrawn() {
        when(eventGroupService.finalDrawn(SPRINT_ID)).thenReturn(true);
        when(groupRepository.findFirstByEventIdAndStage(SPRINT_ID, EventStage.FINAL))
                .thenReturn(Optional.of(finalGroup(heatsAndFinal)));
        when(finalEntryRepository.findByGroupIdOrderByLaneAsc(99L)).thenReturn(List.of());

        MarkSheetDTO sheet = service.getMarkSheet(SPRINT_ID, null, null, EventStage.FINAL);

        assertEquals("FINAL", sheet.getStage());
        assertTrue(sheet.isFinalDrawn(), "the grid can see the final exists");
        assertEquals("DRAWN", sheet.getFinalState());
    }

    @Test
    @DisplayName("and a final mark saves against the final's own field")
    void theFinalGridSavesOnceTheFinalIsDrawn() {
        EventGroup finalGroup = finalGroup(heatsAndFinal);
        when(eventGroupService.finalDrawn(SPRINT_ID)).thenReturn(true);
        when(groupRepository.findFirstByEventIdAndStage(SPRINT_ID, EventStage.FINAL))
                .thenReturn(Optional.of(finalGroup));
        when(finalEntryRepository.findByGroupIdOrderByLaneAsc(99L))
                .thenReturn(List.of(com.sportday.entity.FinalEntry.builder()
                        .id(1L).group(finalGroup).user(user(ATHLETE)).lane(1).seed(1)
                        .seedMark(new BigDecimal("11.000")).seedUnit("s")
                        .build()));
        when(resultRepository.findByUserIdAndEventIdAndStage(ATHLETE, SPRINT_ID, EventStage.FINAL))
                .thenReturn(Optional.empty());

        var result = service.saveMarks(SPRINT_ID, finalRow(ATHLETE, "10.900"));

        assertEquals(1, result.getSaved());
        assertEquals(EventStage.FINAL.name(), result.getStage());
        var saved = org.mockito.ArgumentCaptor.forClass(com.sportday.entity.EventResult.class);
        verify(resultRepository).save(saved.capture());
        assertEquals(EventStage.FINAL, saved.getValue().getStageOrDefault());
        assertEquals(0, new BigDecimal("10.900").compareTo(saved.getValue().getMark()));
    }

    // ---------------------------------------------- the rule the draw applies

    @Test
    @DisplayName("the draw and the grid name the same three cases, so they cannot disagree")
    void theGuardIsTheSameRuleTheDrawUses() {
        // FinalQualificationService wraps exactly these, which is what makes the
        // draw's refusal and the grid's refusal one answer rather than two.
        assertDoesNotThrow(() -> FinalStageGuard.requireAFinalIsPossible(heatsAndFinal));

        // The "not drawn yet" refusal is the one message with no event name in it,
        // because it is the same answer for every event; the other two name the
        // event, so they are asserted as the ending they share.
        assertEquals(FinalStageGuard.NOT_DRAWN, assertThrows(IllegalStateException.class,
                () -> FinalStageGuard.requireDrawnFinal(heatsAndFinal, false)).getMessage());
        assertTrue(assertThrows(IllegalStateException.class,
                () -> FinalStageGuard.requireDrawnFinal(eightHundred, false)).getMessage()
                .endsWith(FinalStageGuard.NO_FINAL_STAGE));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> FinalStageGuard.requireDrawnFinal(directToFinal, false)).getMessage()
                .endsWith(FinalStageGuard.DIRECT_TO_FINAL));

        // The event's own half, with the draw's answer already in hand.
        assertTrue(assertThrows(IllegalStateException.class,
                () -> FinalStageGuard.requireAFinalIsPossible(eightHundred)).getMessage()
                .endsWith(FinalStageGuard.NO_FINAL_STAGE));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> FinalStageGuard.requireAFinalIsPossible(directToFinal)).getMessage()
                .endsWith(FinalStageGuard.DIRECT_TO_FINAL));

        // And the component-level entry point the controller uses answers the same.
        assertEquals(FinalStageGuard.NOT_DRAWN, assertThrows(IllegalStateException.class,
                () -> guard.requireDrawnFinal(heatsAndFinal)).getMessage());
    }

    @Test
    @DisplayName("the four states are told apart for a client to gate on")
    void theFourStatesAreToldApart() {
        assertEquals(FinalStageGuard.FinalState.NOT_DRAWN, guard.state(heatsAndFinal));
        assertEquals(FinalStageGuard.FinalState.DIRECT, guard.state(directToFinal));
        assertEquals(FinalStageGuard.FinalState.NONE, guard.state(eightHundred));

        when(groupRepository.findFirstByEventIdAndStage(SPRINT_ID, EventStage.FINAL))
                .thenReturn(Optional.of(finalGroup(heatsAndFinal)));
        assertEquals(FinalStageGuard.FinalState.DRAWN, guard.state(heatsAndFinal));
        assertTrue(guard.finalDrawn(SPRINT_ID));

        // The same four, decided without a lookup, for a caller that has the answer.
        assertEquals(FinalStageGuard.FinalState.NOT_DRAWN,
                FinalStageGuard.state(heatsAndFinal, false));
        assertEquals(FinalStageGuard.FinalState.DRAWN, FinalStageGuard.state(heatsAndFinal, true));
        assertEquals(FinalStageGuard.FinalState.DIRECT, FinalStageGuard.state(directToFinal, true));
        assertEquals(FinalStageGuard.FinalState.NONE, FinalStageGuard.state(eightHundred, true));
    }

    @Test
    @DisplayName("a field event is decided by its own run, so it has no final stage either")
    void aFieldEventHasNoFinalStage() {
        Event shot = event(9L, Event.EventType.SHOT_PUT, false);
        when(eventRepository.findById(9L)).thenReturn(Optional.of(shot));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.getMarkSheet(9L, null, null, EventStage.FINAL));

        assertTrue(error.getMessage().contains("straight to a final"), error.getMessage());
        assertEquals(FinalStageGuard.FinalState.NONE, guard.state(shot));
    }
}
