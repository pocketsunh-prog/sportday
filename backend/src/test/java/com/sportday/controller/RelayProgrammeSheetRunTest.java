package com.sportday.controller;

import com.sportday.dto.EventGroupDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The <strong>relay programme's own print run</strong>: every relay's marking sheets in
 * one file, in one press.
 *
 * <p>The request names the relays — {@code GET /api/relay-events/sheets.pdf?eventIds=…}
 * — because the page that presses it is showing exactly those relays and the file has to
 * be the list on screen rather than a programme that quietly differs from it. The
 * renderer is the same one the per-event run uses
 * ({@link PdfSheetService#renderSheets(java.util.List)}), so a combined file is composed
 * the way a single event's run always was: one sheet per group, in the order given.</p>
 *
 * <p><strong>A relay that cannot print is never dropped in silence.</strong> Every relay
 * on the run is judged before anything is rendered, and the run is refused with
 * <em>all</em> of their reasons — not the first — so one press tells the office
 * everything it still has to finish. Nothing is rendered on a refusal, so there is no
 * half-file either.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayProgrammeSheetRunTest {

    private static final long FORM_RELAY_ID = 21L;
    private static final long HOUSE_RELAY_ID = 22L;
    private static final long THIRD_RELAY_ID = 23L;
    private static final long EMPTY_RELAY_ID = 24L;
    private static final long SPRINT_ID = 5L;

    /** A one-team relay is refused with exactly this, name and all. */
    private static final String FORM_RELAY_SHORTFALL =
            "Boys 4x100M Relay · Form 3 has 1 team(s) in the race, and a relay needs at least 2 "
                    + "before its marks can be entered. Build or fill another team first.";
    private static final String HOUSE_RELAY_SHORTFALL =
            "Girls 4x100M Relay · C Grade has 0 team(s) in the race, and a relay needs at least 2 "
                    + "before its marks can be entered. Build or fill another team first.";

    @Mock private EventGroupService eventGroupService;
    @Mock private EventService eventService;
    @Mock private PdfSheetService pdfSheetService;
    @Mock private FinalQualificationService finalQualificationService;
    @Mock private EventGroupRepository groupRepository;
    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;

    private EventGroupController controller;

    private Event formRelay;
    private Event houseRelay;
    private Event thirdRelay;
    private Event emptyRelay;
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

    private static Event relay(long id, String name, Sex sex, Grade grade) {
        return Event.builder()
                .id(id)
                .name(name)
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.TRACK)
                .sex(sex)
                .grade(grade)
                .groupSize(24)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .relayTeamKind(RelayTeamKind.FORM)
                .build();
    }

    /** One sheet of a relay, as the service hands groups over. */
    private static EventGroupDTO sheet(long eventId, String heading) {
        return EventGroupDTO.builder()
                .eventId(eventId)
                .eventName(heading)
                .groupNumber(1)
                .label("Heat 1")
                .stage("HEAT")
                .sheetSize("A4")
                .capacity(0)
                .athleteCount(0)
                .relayTeamLabels(List.of("3A", "3B"))
                .athletes(new ArrayList<>())
                .build();
    }

    @BeforeEach
    void setUp() {
        controller = new EventGroupController(eventGroupService, eventService, pdfSheetService,
                finalQualificationService, new FinalStageGuard(groupRepository),
                new RelayReadiness(relayTeamRepository, relayTeamMemberRepository));

        formRelay = relay(FORM_RELAY_ID, "Boys 4x100M Relay · Form 3", Sex.MALE, Grade.A);
        houseRelay = relay(HOUSE_RELAY_ID, "Girls 4x100M Relay · C Grade", Sex.FEMALE, Grade.C);
        houseRelay.setRelayTeamKind(RelayTeamKind.HOUSE);
        thirdRelay = relay(THIRD_RELAY_ID, "Boys 4x100M Relay · Form 4", Sex.MALE, Grade.B);
        emptyRelay = relay(EMPTY_RELAY_ID, "Girls 4x100M Relay · Form 5", Sex.FEMALE, Grade.B);
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

        when(eventService.requireEvent(FORM_RELAY_ID)).thenReturn(formRelay);
        when(eventService.requireEvent(HOUSE_RELAY_ID)).thenReturn(houseRelay);
        when(eventService.requireEvent(THIRD_RELAY_ID)).thenReturn(thirdRelay);
        when(eventService.requireEvent(EMPTY_RELAY_ID)).thenReturn(emptyRelay);
        when(eventService.requireEvent(SPRINT_ID)).thenReturn(sprint);

        // The relays' teams, read in two queries for the whole run: the form relay holds
        // one team of four (not ready), the house relay holds none at all, the third relay
        // holds two full ones, and the empty relay holds none.
        firstTeam = team(11L, formRelay, "3A");
        secondTeam = team(12L, thirdRelay, "4A");
        RelayTeam third = team(13L, thirdRelay, "4B");
        when(relayTeamRepository.findForEvents(anyList()))
                .thenReturn(List.of(firstTeam, secondTeam, third));
        List<RelayTeamMember> legs = new ArrayList<>(fullTeam(1L, firstTeam));
        legs.addAll(fullTeam(5L, secondTeam));
        legs.addAll(fullTeam(9L, third));
        when(relayTeamMemberRepository.findTeamIdsForEvents(anyList()))
                .thenReturn(legs.stream().map(member -> member.getTeam().getId()).toList());

        when(pdfSheetService.renderSheets(anyList())).thenReturn(new byte[]{1, 2, 3});
    }

    // ------------------------------------------------------------- the run itself

    /** Makes exactly these relays ready: two full teams each. */
    private void readyRelays(List<Event> relays) {
        List<RelayTeam> all = new ArrayList<>();
        List<RelayTeamMember> legs = new ArrayList<>();
        long teamId = 100;
        long memberId = 1000;
        for (Event event : relays) {
            for (int squad = 0; squad < 2; squad++) {
                RelayTeam team = team(teamId++, event, event.getName() + " " + squad);
                all.add(team);
                legs.addAll(fullTeam(memberId, team));
                memberId += 10;
            }
        }
        when(relayTeamRepository.findForEvents(anyList())).thenReturn(all);
        when(relayTeamMemberRepository.findTeamIdsForEvents(anyList()))
                .thenReturn(legs.stream().map(member -> member.getTeam().getId()).toList());
    }

    @Test
    @DisplayName("every relay named is rendered into one file, in the order asked for")
    void everyRelayNamedIsRenderedInOrder() {
        readyRelays(List.of(formRelay, houseRelay, thirdRelay));
        EventGroupDTO formSheet = sheet(FORM_RELAY_ID, "Form 3");
        EventGroupDTO houseSheet = sheet(HOUSE_RELAY_ID, "C Grade");
        EventGroupDTO thirdSheet = sheet(THIRD_RELAY_ID, "Form 4");
        when(eventGroupService.getGroupsWithAthletes(FORM_RELAY_ID)).thenReturn(List.of(formSheet));
        when(eventGroupService.getGroupsWithAthletes(HOUSE_RELAY_ID)).thenReturn(List.of(houseSheet));
        when(eventGroupService.getGroupsWithAthletes(THIRD_RELAY_ID)).thenReturn(List.of(thirdSheet));

        var response = controller.relaySheets(List.of(THIRD_RELAY_ID, FORM_RELAY_ID, HOUSE_RELAY_ID));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/pdf", response.getHeaders().getContentType().toString());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EventGroupDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(pdfSheetService).renderSheets(captor.capture());
        assertEquals(List.of(thirdSheet, formSheet, houseSheet), captor.getValue(),
                "the sheets are composed in the order the caller named the relays");
    }

    @Test
    @DisplayName("a relay named twice is printed once")
    void aRelayNamedTwiceIsPrintedOnce() {
        when(eventGroupService.getGroupsWithAthletes(THIRD_RELAY_ID))
                .thenReturn(List.of(sheet(THIRD_RELAY_ID, "Form 4")));

        controller.relaySheets(List.of(THIRD_RELAY_ID, THIRD_RELAY_ID));

        verify(eventGroupService).getGroupsWithAthletes(THIRD_RELAY_ID);
    }

    // ------------------------------------------------- a relay that cannot print

    @Test
    @DisplayName("a relay that cannot print refuses the run with its own reason, and nothing is rendered")
    void aRelayThatCannotPrintRefusesTheRun() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.relaySheets(List.of(FORM_RELAY_ID, THIRD_RELAY_ID)));

        assertTrue(error.getMessage().contains(FORM_RELAY_SHORTFALL), error.getMessage());
        verify(pdfSheetService, never()).renderSheets(anyList());
        verify(eventGroupService, never()).getGroupsWithAthletes(FORM_RELAY_ID);
    }

    @Test
    @DisplayName("every relay that cannot print is named, not only the first")
    void everyRelayThatCannotPrintIsNamed() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.relaySheets(
                        List.of(FORM_RELAY_ID, HOUSE_RELAY_ID, EMPTY_RELAY_ID, THIRD_RELAY_ID)));

        assertTrue(error.getMessage().contains(FORM_RELAY_SHORTFALL), error.getMessage());
        assertTrue(error.getMessage().contains(HOUSE_RELAY_SHORTFALL), error.getMessage());
        assertTrue(error.getMessage().contains("Girls 4x100M Relay · Form 5"), error.getMessage());
        verify(pdfSheetService, never()).renderSheets(anyList());
    }

    // ------------------------------------------------ what the run refuses outright

    @Test
    @DisplayName("an event that is not a relay is refused by name")
    void anEventThatIsNotARelayIsRefusedByName() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.relaySheets(List.of(SPRINT_ID)));

        assertTrue(error.getMessage().contains("Boys 100M"), error.getMessage());
        assertTrue(error.getMessage().contains("not a relay event"), error.getMessage());
        verify(pdfSheetService, never()).renderSheets(anyList());
    }

    @Test
    @DisplayName("a run that names no relay is refused, so nothing is guessed")
    void aRunThatNamesNoRelayIsRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.relaySheets(List.of()));

        assertTrue(error.getMessage().contains("Name the relays to print"), error.getMessage());

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> controller.relaySheets(null));

        assertEquals(error.getMessage(), missing.getMessage());
        verify(pdfSheetService, never()).renderSheets(anyList());
    }

    @Test
    @DisplayName("a run whose relays hand over no sheet at all is refused, not handed an empty file")
    void anEmptyRunIsRefused() {
        // A guard rather than a state the app can reach: a ready relay always has its
        // teams to print, so the only way to render nothing is a caller whose groups came
        // back empty. It still refuses in words that name the run, rather than letting the
        // renderer's own "No groups to render" surface to the office.
        RelayTeam houseFirst = team(31L, houseRelay, "C Grade Red");
        RelayTeam houseSecond = team(32L, houseRelay, "C Grade Blue");
        RelayTeam emptyFirst = team(33L, emptyRelay, "5A");
        RelayTeam emptySecond = team(34L, emptyRelay, "5B");
        List<RelayTeam> all = List.of(firstTeam, secondTeam, houseFirst, houseSecond,
                emptyFirst, emptySecond);
        when(relayTeamRepository.findForEvents(anyList())).thenReturn(all);
        List<RelayTeamMember> legs = new ArrayList<>();
        for (RelayTeam squad : all) {
            legs.addAll(fullTeam(squad.getId() * 10L, squad));
        }
        when(relayTeamMemberRepository.findTeamIdsForEvents(anyList()))
                .thenReturn(legs.stream().map(member -> member.getTeam().getId()).toList());
        when(eventGroupService.getGroupsWithAthletes(HOUSE_RELAY_ID)).thenReturn(List.of());
        when(eventGroupService.getGroupsWithAthletes(EMPTY_RELAY_ID)).thenReturn(List.of());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.relaySheets(List.of(HOUSE_RELAY_ID, EMPTY_RELAY_ID)));

        assertTrue(error.getMessage().contains("None of the relays on this run"),
                error.getMessage());
        verify(pdfSheetService, never()).renderSheets(anyList());
    }
}
