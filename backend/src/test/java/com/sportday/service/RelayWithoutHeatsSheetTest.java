package com.sportday.service;

import com.sportday.dto.EventGroupDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventGroup;
import com.sportday.entity.EventStage;
import com.sportday.entity.Grade;
import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamKind;
import com.sportday.entity.RelayTeamMember;
import com.sportday.entity.Sex;
import com.sportday.entity.SportDaySettings;
import com.sportday.entity.User;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.FinalEntryRepository;
import com.sportday.repository.RelayTeamMemberRepository;
import com.sportday.repository.RelayTeamRepository;
import com.sportday.repository.StudentRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * <strong>A relay that has its teams prints, whether or not it has heats.</strong>
 *
 * <p>The school hit this: {@code Boys 4x100M Relay - Form 5} has four class teams and
 * no heats, and printing answered "has no groups yet — run group allocation first".
 * A relay is divided into <em>teams</em>, never into heats, so the demand was for rows
 * the race was never going to have — and the relay board, where the teams are built,
 * no longer offers a heats page at all.</p>
 *
 * <p>The decision belongs where the sheets' contents are gathered
 * ({@link EventGroupService}) and not in the renderer, which is a pure DTO-to-PDF
 * step: a relay with teams yields the group its sheet is drawn from even though
 * {@code event_groups} holds nothing. An <strong>individual</strong> event is the
 * regression that matters and is held to the same byte of wording it had; a relay with
 * no teams at all keeps a refusal, in the readiness rule's own words rather than a
 * sentence about heats.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayWithoutHeatsSheetTest {

    private static final long RELAY_ID = 6047L;
    private static final long SPRINT_ID = 5L;
    private static final long SPRINT_GROUP_ID = 601L;
    private static final long RELAY_GROUP_ID = 701L;

    /** The refusal an individual event with no heats has always given. */
    private static final String NO_GROUPS_REFUSAL =
            "Event " + SPRINT_ID + " has no groups yet — run group allocation first.";

    @Mock private EventGroupRepository groupRepository;
    @Mock private EventRepository eventRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private FinalEntryRepository finalEntryRepository;
    @Mock private RecordService recordService;
    @Mock private EventResultRepository resultRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;
    @Mock private RelayTeamRepository relayTeamRepository;

    private EventGroupService groups;
    private PdfSheetService sheets;

    private static User user(long id, String name) {
        return User.builder().id(id).username("S%04d".formatted(id)).password("x")
                .fullName(name).role(User.Role.STUDENT).enabled(true).build();
    }

    /** The live relay, as the school has it: a form relay with four class teams. */
    private static Event formRelay() {
        return Event.builder()
                .id(RELAY_ID)
                .name("Boys 4x100M Relay - Form 5")
                .type(Event.EventType.RELAY_4X100M)
                .category(Event.EventType.RELAY_4X100M.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.B)
                .form("5")
                .relayTeamKind(RelayTeamKind.FORM)
                .groupSize(24)
                .eventDate(LocalDate.of(2026, 10, 4))
                .enabled(true)
                .build();
    }

    /** An undivided relay: no kind, so no teams. */
    private static Event undividedRelay() {
        return Event.builder()
                .id(RELAY_ID)
                .name("Girls 4x100M Relay - Form 5")
                .type(Event.EventType.RELAY_4X100M)
                .category(Event.EventType.RELAY_4X100M.getCategory())
                .sex(Sex.FEMALE)
                .grade(Grade.B)
                .relayTeamKind(null)
                .groupSize(24)
                .eventDate(LocalDate.of(2026, 10, 4))
                .enabled(true)
                .build();
    }

    private static Event sprint() {
        return Event.builder()
                .id(SPRINT_ID)
                .name("Boys 100M - B Grade")
                .type(Event.EventType.RUN_100M)
                .category(Event.EventType.RUN_100M.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.B)
                .groupSize(8)
                .eventDate(LocalDate.of(2026, 10, 4))
                .enabled(true)
                .directToFinal(true)
                .build();
    }

    /** The four class teams of the form, four runners each, in class order. */
    private static List<RelayTeamMember> classTeams(Event relay, String... labels) {
        List<RelayTeamMember> legs = new ArrayList<>();
        for (int index = 0; index < labels.length; index++) {
            String label = labels[index];
            long teamId = 600L + index;
            long runnerId = 700L + (index * 10L);
            RelayTeam team = RelayTeam.builder()
                    .id(teamId)
                    .event(relay)
                    .kind(RelayTeamKind.FORM)
                    .teamKey(label)
                    .label(label)
                    .build();
            for (int leg = 1; leg <= 4; leg++) {
                legs.add(RelayTeamMember.builder()
                        .id(teamId * 10 + leg)
                        .team(team)
                        .user(user(runnerId + leg, label + " runner " + leg))
                        .leg(leg)
                        .build());
            }
        }
        return legs;
    }

    /** The team rows the readiness rule counts, in the order it reads them. */
    private static List<RelayTeam> teamsOf(List<RelayTeamMember> legs) {
        List<RelayTeam> teams = new ArrayList<>();
        for (RelayTeamMember member : legs) {
            if (teams.stream().noneMatch(team -> team.getId().equals(member.getTeam().getId()))) {
                teams.add(member.getTeam());
            }
        }
        return teams;
    }

    private static EventGroup heat(Event event, long id, int athletes) {
        return EventGroup.builder()
                .id(id)
                .event(event)
                .groupNumber(1)
                .stage(EventStage.HEAT)
                .capacity(event.getGroupSize())
                .athleteCount(athletes)
                .build();
    }

    private static List<String> linesOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).lines()
                    .map(String::trim).filter(line -> !line.isBlank()).toList();
        }
    }

    @BeforeEach
    void setUp() {
        groups = new EventGroupService(groupRepository, eventRepository, enrollmentRepository,
                studentRepository, finalEntryRepository, recordService, resultRepository,
                relayTeamMemberRepository,
                new RelayReadiness(relayTeamRepository, relayTeamMemberRepository));
        SettingsService settings = org.mockito.Mockito.mock(SettingsService.class);
        when(settings.get()).thenReturn(SportDaySettings.defaults());
        sheets = new PdfSheetService(groups, new PdfFontProvider(""), settings);

        when(recordService.record(any(), any(), any())).thenReturn(null);
    }

    // ------------------------------------------- a relay with teams, and no heats

    @Test
    @DisplayName("a relay with teams and no heats prints: one line per team, and no runner's name")
    void aRelayWithTeamsPrintsWithoutHeats() throws Exception {
        Event relay = formRelay();
        List<RelayTeamMember> legs = classTeams(relay, "5A", "5B", "5C", "5D");
        when(eventRepository.findById(RELAY_ID)).thenReturn(Optional.of(relay));
        // The whole point: event_groups holds nothing at all for this race.
        when(groupRepository.findByEventIdOrderByGroupNumberAsc(RELAY_ID)).thenReturn(List.of());
        when(relayTeamMemberRepository.findForEventWithUser(RELAY_ID)).thenReturn(legs);
        when(relayTeamRepository.findByEventIdOrderByIdAsc(RELAY_ID)).thenReturn(teamsOf(legs));

        byte[] pdf = sheets.renderEventSheets(RELAY_ID);
        List<String> sheetLines = linesOf(pdf);
        String text = String.join("\n", sheetLines);

        for (String team : List.of("5A", "5B", "5C", "5D")) {
            assertTrue(sheetLines.contains(team),
                    team + " is a line of its own on the sheet: " + text);
        }
        assertEquals(4, sheetLines.stream()
                        .filter(line -> List.of("5A", "5B", "5C", "5D").contains(line)).count(),
                "one line per team: " + text);
        // The line is the team's, so no runner is named and no student id printed.
        for (String label : List.of("5A", "5B", "5C", "5D")) {
            assertFalse(text.contains(label + " runner"),
                    "a runner's name must not reach the sheet, and " + label
                            + " runner is on it: " + text);
        }
        assertTrue(text.contains("Boys 4x100M Relay - Form 5"), "the event heads the sheet");
        // The header is what it is on every other relay sheet: the team count.
        assertTrue(text.contains("隊伍 Teams: 4"),
                "the sheet counts the four teams it prints: " + text);
        assertFalse(text.contains("人數 Entries"),
                "and prints no Entries count for a race whose lines are teams: " + text);
    }

    @Test
    @DisplayName("the group the sheet is drawn from is the event's, with no row in event_groups behind it")
    void theRelaySheetGroupCarriesTheEventsTeams() {
        Event relay = formRelay();
        List<RelayTeamMember> legs = classTeams(relay, "5A", "5B", "5C", "5D");
        when(eventRepository.findById(RELAY_ID)).thenReturn(Optional.of(relay));
        when(groupRepository.findByEventIdOrderByGroupNumberAsc(RELAY_ID)).thenReturn(List.of());
        when(relayTeamMemberRepository.findForEventWithUser(RELAY_ID)).thenReturn(legs);
        when(relayTeamRepository.findByEventIdOrderByIdAsc(RELAY_ID)).thenReturn(teamsOf(legs));

        List<EventGroupDTO> rendered = groups.getGroupsWithAthletes(RELAY_ID);

        assertEquals(1, rendered.size(), "one sheet, drawn from the event's teams");
        EventGroupDTO sheet = rendered.get(0);
        assertEquals(List.of("5A", "5B", "5C", "5D"), sheet.getRelayTeamLabels());
        assertNull(sheet.getId(), "no heat row stands behind it, so it carries no id");
        assertEquals(RELAY_ID, sheet.getEventId());
        assertEquals("Boys 4x100M Relay - Form 5", sheet.getEventName());
        assertEquals("RELAY_4X100M", sheet.getEventType());
        assertEquals("RELAY", sheet.getCategory(), "a relay's own category, for the unit");
        assertEquals("A4", sheet.getSheetSize(), "a relay is not a short sprint: A4");
        assertEquals("HEAT", sheet.getStage());
    }

    // ------------------------------------------------------- the regression

    @Test
    @DisplayName("an individual event with no heats still refuses, word for word as before")
    void anIndividualEventWithNoHeatsStillRefuses() {
        Event sprint = sprint();
        when(eventRepository.findById(SPRINT_ID)).thenReturn(Optional.of(sprint));
        when(groupRepository.findByEventIdOrderByGroupNumberAsc(SPRINT_ID)).thenReturn(List.of());

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> sheets.renderEventSheets(SPRINT_ID));

        // Byte for byte: the string every caller and test has seen until today.
        assertEquals(NO_GROUPS_REFUSAL, refusal.getMessage());
    }

    // --------------------------------------------------- a relay with no teams

    @Test
    @DisplayName("a relay with no teams is refused for having none, not for want of heats")
    void aRelayWithNoTeamsIsRefusedInTheRelaysOwnWords() {
        Event relay = undividedRelay();
        when(eventRepository.findById(RELAY_ID)).thenReturn(Optional.of(relay));
        when(groupRepository.findByEventIdOrderByGroupNumberAsc(RELAY_ID)).thenReturn(List.of());
        when(relayTeamMemberRepository.findForEventWithUser(RELAY_ID)).thenReturn(List.of());
        when(relayTeamRepository.findByEventIdOrderByIdAsc(RELAY_ID)).thenReturn(List.of());

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> sheets.renderEventSheets(RELAY_ID));

        String message = refusal.getMessage();
        assertTrue(message.startsWith("Girls 4x100M Relay - Form 5 has 0 team(s) in the race"),
                "the refusal names the relay and what it has in the race: " + message);
        assertTrue(message.contains("at least 2 before its marks can be entered"),
                "and the rule it falls short of: " + message);
        assertTrue(message.contains("Build or fill another team first."),
                "and what to do about it: " + message);
        assertFalse(message.contains("group allocation"),
                "a relay reader is never sent to a heats page: " + message);
    }

    @Test
    @DisplayName("teams that carry nobody print no blank sheet: the relay is refused in the same words")
    void teamsWithNoRunnersAreNotAPrintableSheet() {
        // Two team rows exist, but neither holds anybody: no team is in the race, so the
        // readiness rule refuses the relay. A sheet's line is a team's NAME and a team
        // nobody has filled has none to read, so the refusal would stand either way — and
        // it is still the relay's, not one about heats.
        Event relay = formRelay();
        RelayTeam first = RelayTeam.builder().id(601L).event(relay)
                .kind(RelayTeamKind.FORM).teamKey("5A").label("5A").build();
        RelayTeam second = RelayTeam.builder().id(602L).event(relay)
                .kind(RelayTeamKind.FORM).teamKey("5B").label("5B").build();
        when(eventRepository.findById(RELAY_ID)).thenReturn(Optional.of(relay));
        when(groupRepository.findByEventIdOrderByGroupNumberAsc(RELAY_ID)).thenReturn(List.of());
        when(relayTeamMemberRepository.findForEventWithUser(RELAY_ID)).thenReturn(List.of());
        when(relayTeamRepository.findByEventIdOrderByIdAsc(RELAY_ID))
                .thenReturn(List.of(first, second));

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> sheets.renderEventSheets(RELAY_ID));

        assertTrue(refusal.getMessage().contains("cannot be marked yet")
                        || refusal.getMessage().contains("team(s)"),
                "the relay's own reason, not a heats one: " + refusal.getMessage());
        assertFalse(refusal.getMessage().contains("group allocation"),
                "and never the heats page: " + refusal.getMessage());
    }

    // --------------------------------------- a relay with heats, unchanged
    @Test
    @DisplayName("a relay that already has heats renders exactly as it did today")
    void aRelayWithHeatsIsUnchanged() throws Exception {
        Event relay = formRelay();
        EventGroup existing = heat(relay, RELAY_GROUP_ID, 9);
        List<RelayTeamMember> legs = classTeams(relay, "5A", "5B", "5C", "5D");
        when(eventRepository.findById(RELAY_ID)).thenReturn(Optional.of(relay));
        when(groupRepository.findByEventIdOrderByGroupNumberAsc(RELAY_ID))
                .thenReturn(List.of(existing));
        when(relayTeamMemberRepository.findForEventWithUser(RELAY_ID)).thenReturn(legs);
        // The heat's own roster is the nine students who entered; the sheet's lines are
        // the event's teams, as they have been since the class-team fix.
        List<Enrollment> entries = List.of(
                Enrollment.builder().id(1L).user(user(801L, "Chow Tsz Mei")).event(relay)
                        .status(Enrollment.EnrollmentStatus.CONFIRMED).lane(1).build());
        when(enrollmentRepository.findByGroupWithUserOrdered(RELAY_GROUP_ID)).thenReturn(entries);
        when(enrollmentRepository.findWithUserByEventAndUserIds(eq(RELAY_ID), any()))
                .thenReturn(entries);
        when(studentRepository.findWithUserByUserIdIn(any())).thenReturn(List.of());

        List<EventGroupDTO> rendered = groups.getGroupsWithAthletes(RELAY_ID);

        assertEquals(1, rendered.size(), "the heat group is the sheet, as it was");
        assertEquals(RELAY_GROUP_ID, rendered.get(0).getId(), "and it is still the real heat");
        assertEquals(List.of("5A", "5B", "5C", "5D"), rendered.get(0).getRelayTeamLabels());

        byte[] pdf = sheets.renderEventSheets(RELAY_ID);
        List<String> sheetLines = linesOf(pdf);
        String text = String.join("\n", sheetLines);

        for (String team : List.of("5A", "5B", "5C", "5D")) {
            assertTrue(sheetLines.contains(team), team + " is on the sheet: " + text);
        }
        assertTrue(text.contains("隊伍 Teams: 4"), "the header still counts the teams: " + text);
        assertFalse(text.contains("Chow Tsz Mei"),
                "the roster's entrants are not the sheet's lines: " + text);
    }
}
