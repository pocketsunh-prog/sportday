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
import com.sportday.entity.Student;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A <strong>form relay's printed sheet names the class teams</strong>.
 *
 * <p>Requirement: <em>"form relay should show class team name not student name on
 * print form"</em>. A form relay's teams are one per class its entrants are in — 1A and
 * 1B of 1A, 1B, 1C and 1D here — filled from the student register, and the board may
 * carry further class teams made by hand beside them. The heat's roster, on the other
 * hand, is the students who <em>entered</em> the event, and the two are not the same
 * people: the event was a grade relay before it was made form-scoped, so its entries
 * and its teams do not overlap at all.</p>
 *
 * <p>This test wires the two halves together — the real {@link EventGroupService}
 * building the group and the real {@link PdfSheetService} drawing the page — with
 * exactly that shape of data, because each half alone looks right: the sheet does draw
 * team lines when its lines carry team labels, and the group does carry the teams. It
 * was the join between them that printed the entrants' names.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FormRelaySheetNamingTest {

    private static final long RELAY_ID = 166L;
    private static final long GROUP_ID = 6633L;

    /** Two students who entered the relay. Neither is on any team. */
    private static final long ENTRANT_ONE = 547L;
    private static final long ENTRANT_TWO = 592L;

    @Mock private EventGroupRepository groupRepository;
    @Mock private EventRepository eventRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private FinalEntryRepository finalEntryRepository;
    @Mock private RecordService recordService;
    @Mock private EventResultRepository resultRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;
    @Mock private RelayTeamRepository relayTeamRepository;

    private PdfSheetService sheets;

    private static User user(long id, String name) {
        return User.builder().id(id).username("S%04d".formatted(id)).password("x")
                .fullName(name).role(User.Role.STUDENT).enabled(true).build();
    }

    private static Student student(long id, String name, String className) {
        return Student.builder()
                .id(id)
                .user(user(id, name))
                .studentId("S%04d".formatted(id))
                .name(name)
                .grade(Grade.A)
                .className(className)
                .classNumber(1)
                .house("Red")
                .dob(LocalDate.of(2012, 1, 1))
                .sex(Sex.MALE)
                .enabled(true)
                .build();
    }

    /** The event as the live data has it: a form relay, grade A, form 1. */
    private static Event formRelay() {
        return Event.builder()
                .id(RELAY_ID)
                .name("Boys 4x100M Relay - Form 1")
                .type(Event.EventType.RELAY_4X100M)
                .category(Event.EventType.RELAY_4X100M.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.A)
                .form("1")
                .relayTeamKind(RelayTeamKind.FORM)
                .groupSize(24)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .build();
    }

    private static EventGroup heat(Event event) {
        return EventGroup.builder()
                .id(GROUP_ID)
                .event(event)
                .groupNumber(1)
                .stage(EventStage.HEAT)
                .capacity(24)
                .athleteCount(2)
                .build();
    }

    /** The four class teams of the form, four runners each, in class order. */
    private static List<RelayTeamMember> classTeams(Event relay) {
        List<RelayTeamMember> legs = new ArrayList<>();
        long memberId = 6000L;
        long runnerId = 7000L;
        for (String className : List.of("1A", "1B", "1C", "1D")) {
            RelayTeam team = RelayTeam.builder()
                    .id(memberId)
                    .event(relay)
                    .kind(RelayTeamKind.FORM)
                    .teamKey(className)
                    .label(className)
                    .build();
            for (int leg = 1; leg <= 4; leg++) {
                legs.add(RelayTeamMember.builder()
                        .id(memberId + leg)
                        .team(team)
                        .user(user(runnerId + leg, className + " runner " + leg))
                        .leg(leg)
                        .build());
            }
            memberId += 10;
            runnerId += 10;
        }
        return legs;
    }

    @BeforeEach
    void setUp() {
        Event relay = formRelay();
        EventGroup group = heat(relay);

        EventGroupService groups = new EventGroupService(groupRepository, eventRepository,
                enrollmentRepository, studentRepository, finalEntryRepository, recordService,
                resultRepository, relayTeamMemberRepository,
                new RelayReadiness(relayTeamRepository, relayTeamMemberRepository));
        SettingsService settings = org.mockito.Mockito.mock(SettingsService.class);
        when(settings.get()).thenReturn(SportDaySettings.defaults());
        sheets = new PdfSheetService(groups, new PdfFontProvider(""), settings);

        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(eventRepository.existsById(RELAY_ID)).thenReturn(true);
        when(recordService.record(any(), any(), any())).thenReturn(null);

        // The teams live on the event, built from the register: 1A, 1B, 1C and 1D.
        when(relayTeamMemberRepository.findForEventWithUser(RELAY_ID)).thenReturn(classTeams(relay));

        // The heat's roster is the two students who entered the event, and neither of
        // them is on a team — which is what the live relay looks like.
        List<Enrollment> entries = List.of(
                Enrollment.builder().id(1L).user(user(ENTRANT_ONE, "Chow Tsz Mei")).event(relay)
                        .status(Enrollment.EnrollmentStatus.CONFIRMED).lane(1).build(),
                Enrollment.builder().id(2L).user(user(ENTRANT_TWO, "Lam Wang Long")).event(relay)
                        .status(Enrollment.EnrollmentStatus.CONFIRMED).lane(2).build());
        when(enrollmentRepository.findByGroupWithUserOrdered(GROUP_ID)).thenReturn(entries);
        when(enrollmentRepository.findWithUserByEventAndUserIds(eq(RELAY_ID), any()))
                .thenReturn(entries);
        when(studentRepository.findWithUserByUserIdIn(any())).thenReturn(List.of(
                student(ENTRANT_ONE, "Chow Tsz Mei", "5A"),
                student(ENTRANT_TWO, "Lam Wang Long", "5D")));
    }

    private static List<String> linesOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).lines()
                    .map(String::trim).filter(line -> !line.isBlank()).toList();
        }
    }

    @Test
    @DisplayName("the sheet lists the class teams — 1A, 1B, 1C, 1D — and no entrant's name")
    void theSheetNamesTheClassTeams() throws Exception {
        byte[] pdf = sheets.renderGroupSheet(GROUP_ID);

        List<String> sheetLines = linesOf(pdf);
        String text = String.join("\n", sheetLines);

        for (String team : List.of("1A", "1B", "1C", "1D")) {
            assertTrue(sheetLines.contains(team),
                    team + " is a line of its own on the sheet: " + text);
        }
        // The two who entered the event are not on it: the line is the team's.
        assertFalse(text.contains("Chow Tsz Mei"),
                "an entrant's name must not be printed on a relay sheet: " + text);
        assertFalse(text.contains("Lam Wang Long"),
                "and neither must the other entrant's: " + text);
        assertFalse(text.contains("S0546") || text.contains("S0591"),
                "nor their student ids: " + text);
        // Four teams, four lines — the roster's two entrants add none.
        assertEquals(4, sheetLines.stream()
                        .filter(line -> List.of("1A", "1B", "1C", "1D").contains(line)).count(),
                "one line per team: " + text);
        // And the header counts the lines below it: teams, not the heat's entrants.
        assertTrue(text.contains("隊伍 Teams: 4"),
                "the sheet counts the teams it prints: " + text);
        assertFalse(text.contains("人數 Entries"),
                "and does not count the entrants a relay sheet does not list: " + text);
    }

    @Test
    @DisplayName("the group the sheet is drawn from carries the event's teams")
    void theGroupCarriesTheEventsTeams() {
        EventGroupDTO group = new EventGroupService(groupRepository, eventRepository,
                enrollmentRepository, studentRepository, finalEntryRepository, recordService,
                resultRepository, relayTeamMemberRepository,
                new RelayReadiness(relayTeamRepository, relayTeamMemberRepository))
                .getGroup(GROUP_ID);

        assertEquals(List.of("1A", "1B", "1C", "1D"), group.getRelayTeamLabels(),
                "the teams are the event's, in the order the grid marks them");
        // And the roster is still the entrants: the group is what a heat is, the
        // team list is what the sheet prints.
        assertEquals(2, group.getAthletes().size());
        assertEquals("Chow Tsz Mei", group.getAthletes().get(0).getName());
        assertNull(group.getAthletes().get(0).getRelayTeamLabel(),
                "an entrant is on no team, which is what made the sheet fall back");
    }
}
