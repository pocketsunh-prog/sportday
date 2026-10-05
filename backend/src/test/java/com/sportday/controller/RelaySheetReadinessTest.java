package com.sportday.controller;

import com.sportday.dto.EventGroupDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventGroup;
import com.sportday.entity.EventStage;
import com.sportday.entity.Grade;
import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamKind;
import com.sportday.entity.RelayTeamMember;
import com.sportday.entity.Sex;
import com.sportday.entity.User;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.RelayTeamMemberRepository;
import com.sportday.repository.RelayTeamRepository;
import com.sportday.service.EventGroupService;
import com.sportday.service.EventService;
import com.sportday.service.FinalQualificationService;
import com.sportday.service.FinalStageGuard;
import com.sportday.service.PdfSheetService;
import com.sportday.service.RelayReadiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The printing half of relay readiness: <strong>a relay that is not ready has no
 * markable sheet.</strong>
 *
 * <p>Asking for one relay's sheet — its group's, or its event's — is asking on purpose,
 * so it is refused with the same reason the marking grid gives. The
 * <strong>whole-programme</strong> run is the one that skips such a relay instead, as it
 * gathers the groups ({@code EventGroupService.getGroupsWithAthletesFiltered}), so one
 * half-built relay cannot stop the school printing everything else. An individual event
 * is never gated.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelaySheetReadinessTest {

    private static final long RELAY_ID = 21L;
    private static final long RELAY_GROUP_ID = 501L;
    private static final long SPRINT_ID = 5L;
    private static final long SPRINT_GROUP_ID = 601L;

    /** What a one-team relay is refused with, exactly. */
    private static final String ONE_TEAM_REFUSAL =
            "Boys 4x100M Relay · A Grade has 1 team(s), and a relay needs at least 2 before its "
                    + "marks can be entered. Build another team first.";

    @Mock private EventGroupService eventGroupService;
    @Mock private EventService eventService;
    @Mock private PdfSheetService pdfSheetService;
    @Mock private FinalQualificationService finalQualificationService;
    @Mock private EventGroupRepository groupRepository;
    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;

    private EventGroupController controller;

    private Event relay;
    private Event sprint;
    private RelayTeam firstTeam;
    private RelayTeam secondTeam;

    private static User runner(long id) {
        return User.builder().id(id).username("S" + id).password("x").fullName("Runner " + id)
                .role(User.Role.STUDENT).enabled(true).build();
    }

    private static RelayTeam team(long id, Event event, String label) {
        return RelayTeam.builder().id(id).event(event).kind(RelayTeamKind.FORM)
                .teamKey(label).label(label).build();
    }

    /** Four runners, legs 1..4 — a full team of a 4x100M. */
    private static List<RelayTeamMember> fullTeam(long firstMemberId, RelayTeam team) {
        List<RelayTeamMember> squad = new ArrayList<>(4);
        for (int leg = 1; leg <= 4; leg++) {
            squad.add(RelayTeamMember.builder().id(firstMemberId + leg - 1).team(team)
                    .user(runner(firstMemberId * 10 + leg)).leg(leg).build());
        }
        return squad;
    }

    private static EventGroup group(Event event, long id) {
        return EventGroup.builder()
                .id(id)
                .event(event)
                .groupNumber(1)
                .stage(EventStage.HEAT)
                .capacity(event.getGroupSize())
                .athleteCount(0)
                .build();
    }

    @BeforeEach
    void setUp() {
        controller = new EventGroupController(eventGroupService, eventService, pdfSheetService,
                finalQualificationService, new FinalStageGuard(groupRepository),
                new RelayReadiness(relayTeamRepository, relayTeamMemberRepository));

        relay = Event.builder()
                .id(RELAY_ID)
                .name("Boys 4x100M Relay · A Grade")
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .grade(Grade.A)
                .groupSize(24)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .relayTeamKind(RelayTeamKind.FORM)
                .build();
        sprint = Event.builder()
                .id(SPRINT_ID)
                .name("Boys 100M · A Grade")
                .type(Event.EventType.RUN_100M)
                .sex(Sex.MALE)
                .grade(Grade.A)
                .groupSize(8)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .directToFinal(true)
                .build();

        firstTeam = team(11L, relay, "5A");
        secondTeam = team(12L, relay, "5B");
        // The default relay here has one team of four: not ready.
        when(relayTeamRepository.findByEventIdOrderByIdAsc(RELAY_ID)).thenReturn(List.of(firstTeam));
        when(relayTeamMemberRepository.findForEventWithUser(RELAY_ID))
                .thenReturn(fullTeam(1L, firstTeam));

        when(eventService.requireEvent(RELAY_ID)).thenReturn(relay);
        when(eventService.requireEvent(SPRINT_ID)).thenReturn(sprint);
        when(pdfSheetService.renderEventSheets(anyLong())).thenReturn(new byte[]{1, 2, 3});
        when(pdfSheetService.renderGroupSheet(anyLong())).thenReturn(new byte[]{1, 2, 3});
    }

    // ------------------------------------------------- a relay that is not ready

    @Test
    @DisplayName("a not-ready relay's own sheet is refused with the reason, never drawn")
    void aNotReadyRelaysSheetIsRefused() {
        when(eventGroupService.requireGroup(RELAY_GROUP_ID)).thenReturn(group(relay, RELAY_GROUP_ID));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.groupSheet(RELAY_GROUP_ID));

        assertEquals(ONE_TEAM_REFUSAL, error.getMessage());
        verify(pdfSheetService, never()).renderGroupSheet(anyLong());
    }

    @Test
    @DisplayName("a not-ready relay's print run is refused with the same reason")
    void aNotReadyRelaysPrintRunIsRefused() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.eventSheets(RELAY_ID));

        assertEquals(ONE_TEAM_REFUSAL, error.getMessage());
        verify(pdfSheetService, never()).renderEventSheets(anyLong());
    }

    @Test
    @DisplayName("and the same run through the whole-school endpoint, which takes an event id")
    void theWholeSchoolEndpointIsTheSameCall() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.allSheets(RELAY_ID, null, null));

        assertEquals(ONE_TEAM_REFUSAL, error.getMessage());
        verify(pdfSheetService, never()).renderEventSheets(anyLong());
    }

    // ------------------------------------------------------- a relay that is ready

    @Test
    @DisplayName("a relay with two full teams prints: its sheets are live")
    void aReadyRelayPrints() {
        when(relayTeamRepository.findByEventIdOrderByIdAsc(RELAY_ID))
                .thenReturn(List.of(firstTeam, secondTeam));
        List<RelayTeamMember> legs = new ArrayList<>(fullTeam(1L, firstTeam));
        legs.addAll(fullTeam(5L, secondTeam));
        when(relayTeamMemberRepository.findForEventWithUser(RELAY_ID)).thenReturn(legs);
        when(eventGroupService.requireGroup(RELAY_GROUP_ID)).thenReturn(group(relay, RELAY_GROUP_ID));

        var run = controller.eventSheets(RELAY_ID);
        var sheet = controller.groupSheet(RELAY_GROUP_ID);

        assertEquals(200, run.getStatusCode().value());
        assertEquals(200, sheet.getStatusCode().value());
        verify(pdfSheetService).renderEventSheets(RELAY_ID);
        verify(pdfSheetService).renderGroupSheet(RELAY_GROUP_ID);
    }

    // ------------------------------------------------- nothing else is held back

    @Test
    @DisplayName("an individual event's sheets are untouched")
    void anIndividualEventsSheetsAreUntouched() {
        when(eventGroupService.requireGroup(SPRINT_GROUP_ID)).thenReturn(group(sprint, SPRINT_GROUP_ID));

        var run = controller.eventSheets(SPRINT_ID);
        var sheet = controller.groupSheet(SPRINT_GROUP_ID);

        assertEquals(200, run.getStatusCode().value());
        assertEquals(200, sheet.getStatusCode().value());
        verify(pdfSheetService).renderEventSheets(SPRINT_ID);
        verify(pdfSheetService).renderGroupSheet(SPRINT_GROUP_ID);
    }

    @Test
    @DisplayName("the whole-programme run holds nothing back here: it renders what it was given")
    void theProgrammeRunIsNotHeldBackHere() {
        // A not-ready relay never reaches this call: it is skipped as the groups are
        // gathered, which the service test covers. What is handed over is rendered.
        EventGroupDTO heat = EventGroupDTO.builder()
                .id(SPRINT_GROUP_ID).eventId(SPRINT_ID).groupNumber(1).label("Heat 1").stage("HEAT")
                .sheetSize("A5").capacity(8).athleteCount(0)
                .athletes(new ArrayList<>())
                .build();
        when(eventGroupService.getGroupsWithAthletesFiltered(null, null)).thenReturn(List.of(heat));
        when(pdfSheetService.renderSheets(List.of(heat))).thenReturn(new byte[]{1, 2, 3});

        var response = controller.allSheets(null, null, null);

        assertEquals(200, response.getStatusCode().value());
        verify(pdfSheetService).renderSheets(List.of(heat));
    }
}
