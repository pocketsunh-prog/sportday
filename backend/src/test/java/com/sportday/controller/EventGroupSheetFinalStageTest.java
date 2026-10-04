package com.sportday.controller;

import com.sportday.dto.EventGroupDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventGroup;
import com.sportday.entity.EventStage;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.repository.EventGroupRepository;
import com.sportday.service.EventGroupService;
import com.sportday.service.EventService;
import com.sportday.service.FinalQualificationService;
import com.sportday.service.FinalStageGuard;
import com.sportday.service.PdfSheetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Requirement 5, the printing half: <strong>a final's marking sheet waits for the
 * heat result too.</strong>
 *
 * <p>A final sheet is the final's field written out — a lane and a line per
 * finalist — so before the draw it would be a sheet of blank lines for a race
 * nobody is in. The print run for an event that will run a final is held back for
 * the same reason, rather than handing over the heats and looking as though the
 * last sheet had gone missing.</p>
 *
 * <p>An event that runs straight to a final, and one that cannot be split at all,
 * are refused nothing: every sheet they have is already in the run.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventGroupSheetFinalStageTest {

    private static final long SPRINT_ID = 2L;
    private static final long DISTANCE_ID = 4L;

    @Mock private EventGroupService eventGroupService;
    @Mock private EventService eventService;
    @Mock private PdfSheetService pdfSheetService;
    @Mock private FinalQualificationService finalQualificationService;
    @Mock private EventGroupRepository groupRepository;

    private EventGroupController controller;

    private Event heatsAndFinal;
    private Event directToFinal;
    private Event eightHundred;

    // ------------------------------------------------------------- fixtures

    private static Event event(long id, Event.EventType type, boolean directToFinal) {
        return Event.builder()
                .id(id)
                .name("Boys " + type.getDisplayName())
                .type(type)
                .sex(Sex.MALE)
                .grade(Grade.B)
                .groupSize(type.getDefaultGroupSize())
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .directToFinal(directToFinal)
                .build();
    }

    private static EventGroup group(Event event, EventStage stage, int number) {
        return EventGroup.builder()
                .id(stage == EventStage.FINAL ? 99L : (long) number)
                .event(event)
                .groupNumber(number)
                .stage(stage)
                .capacity(event.getGroupSize())
                .athleteCount(0)
                .build();
    }

    @BeforeEach
    void setUp() {
        heatsAndFinal = event(SPRINT_ID, Event.EventType.RUN_100M, false);
        directToFinal = event(SPRINT_ID, Event.EventType.RUN_100M, true);
        eightHundred = event(DISTANCE_ID, Event.EventType.RUN_800M, false);

        when(eventService.requireEvent(SPRINT_ID)).thenReturn(heatsAndFinal);
        when(eventService.requireEvent(DISTANCE_ID)).thenReturn(eightHundred);
        when(groupRepository.findFirstByEventIdAndStage(anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        when(pdfSheetService.renderEventSheets(anyLong())).thenReturn(new byte[]{1, 2, 3});
        when(pdfSheetService.renderGroupSheet(anyLong())).thenReturn(new byte[]{1, 2, 3});

        controller = new EventGroupController(eventGroupService, eventService, pdfSheetService,
                finalQualificationService, new FinalStageGuard(groupRepository));
    }

    // --------------------------------------------------------- a final sheet

    @Test
    @DisplayName("a final sheet cannot be printed before the final has been drawn")
    void aFinalSheetWaitsForTheDraw() {
        EventGroup finalGroup = group(heatsAndFinal, EventStage.FINAL, EventGroup.FINAL_GROUP_NUMBER);
        when(eventGroupService.requireGroup(99L)).thenReturn(finalGroup);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.groupSheet(99L));

        assertEquals(FinalStageGuard.NOT_DRAWN, error.getMessage());
        verify(pdfSheetService, never()).renderGroupSheet(anyLong());
    }

    @Test
    @DisplayName("a heat sheet prints as it always has: no draw is needed to write a heat down")
    void aHeatSheetStillPrints() {
        when(eventGroupService.requireGroup(1L)).thenReturn(group(heatsAndFinal, EventStage.HEAT, 1));

        var response = controller.groupSheet(1L);

        assertEquals(200, response.getStatusCode().value());
        verify(pdfSheetService).renderGroupSheet(1L);
    }

    @Test
    @DisplayName("the final's own sheet prints once the final has been drawn")
    void theFinalSheetPrintsOnceDrawn() {
        EventGroup finalGroup = group(heatsAndFinal, EventStage.FINAL, EventGroup.FINAL_GROUP_NUMBER);
        when(eventGroupService.requireGroup(99L)).thenReturn(finalGroup);
        when(groupRepository.findFirstByEventIdAndStage(SPRINT_ID, EventStage.FINAL))
                .thenReturn(Optional.of(finalGroup));

        var response = controller.groupSheet(99L);

        assertEquals(200, response.getStatusCode().value());
        verify(pdfSheetService).renderGroupSheet(99L);
    }

    // ------------------------------------------------------- the print run

    @Test
    @DisplayName("an event's print run is refused while its final is still to be drawn")
    void anEventPrintRunWaitsForTheDraw() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.eventSheets(SPRINT_ID));

        assertEquals(FinalStageGuard.NOT_DRAWN, error.getMessage());
        verify(pdfSheetService, never()).renderEventSheets(anyLong());
    }

    @Test
    @DisplayName("and the same run through the whole-school endpoint, which takes an event id")
    void theEventPrintRunIsTheSameCall() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.allSheets(SPRINT_ID, null, null));

        assertEquals(FinalStageGuard.NOT_DRAWN, error.getMessage());
        verify(pdfSheetService, never()).renderEventSheets(anyLong());
    }

    @Test
    @DisplayName("the print run opens once the final has been drawn")
    void thePrintRunOpensOnceDrawn() {
        when(groupRepository.findFirstByEventIdAndStage(SPRINT_ID, EventStage.FINAL))
                .thenReturn(Optional.of(group(heatsAndFinal, EventStage.FINAL, EventGroup.FINAL_GROUP_NUMBER)));

        var response = controller.eventSheets(SPRINT_ID);

        assertEquals(200, response.getStatusCode().value());
        verify(pdfSheetService).renderEventSheets(SPRINT_ID);
    }

    // ------------------------------------ events that never wait for anything

    @Test
    @DisplayName("an event that cannot be split has nothing to wait for, so it prints")
    void anEventWithoutAFinalPrintsStraightAway() {
        var response = controller.eventSheets(DISTANCE_ID);

        assertEquals(200, response.getStatusCode().value());
        verify(pdfSheetService).renderEventSheets(DISTANCE_ID);
    }

    @Test
    @DisplayName("a sprint set to run straight to a final prints too — its final is its run")
    void aDirectToFinalSprintPrintsStraightAway() {
        when(eventService.requireEvent(SPRINT_ID)).thenReturn(directToFinal);

        var response = controller.eventSheets(SPRINT_ID);

        assertEquals(200, response.getStatusCode().value());
        verify(pdfSheetService).renderEventSheets(SPRINT_ID);
    }

    @Test
    @DisplayName("the whole-school run is untouched: it holds only the groups that exist")
    void theWholeSchoolRunIsNotHeldBack() {
        EventGroupDTO heat = EventGroupDTO.builder()
                .id(1L).eventId(SPRINT_ID).groupNumber(1).label("Heat 1").stage("HEAT")
                .sheetSize("A5").capacity(8).athleteCount(0)
                .athletes(new java.util.ArrayList<>())
                .build();
        when(eventGroupService.getGroupsWithAthletesFiltered(null, null)).thenReturn(java.util.List.of(heat));
        when(pdfSheetService.renderSheets(java.util.List.of(heat))).thenReturn(new byte[]{1, 2, 3});

        var response = controller.allSheets(null, null, null);

        assertEquals(200, response.getStatusCode().value(),
                "a final that has not been drawn has no group, so it cannot be in the run");
        verify(pdfSheetService).renderSheets(java.util.List.of(heat));
    }
}
