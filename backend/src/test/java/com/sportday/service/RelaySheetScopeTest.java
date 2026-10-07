package com.sportday.service;

import com.sportday.dto.EventGroupDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * <strong>The printed sheet is headed by the scope that belongs to the relay.</strong>
 *
 * <p>The school hit this on the live programme: the Form 3 relays are stored as
 * {@code Boys 4x100M Relay · B Grade}, so a sheet headed by the stored name told the
 * helper marking it that the race was a B Grade one — while the card on the form page,
 * and the four class teams printed below, all said Form 3. The relay-events card had
 * already been taught to show the form the relay is scoped to; the marking sheet had
 * not.</p>
 *
 * <p>This test wires the two real halves together — {@link EventGroupService} building
 * the group and {@link PdfSheetService} drawing the page — the way
 * {@code FormRelaySheetNamingTest} does, because each half alone looks right. It covers
 * <em>both</em> paths a relay's sheet is drawn from: one with a heat behind it
 * ({@link EventGroupService#getGroup}) and the live shape with no
 * {@code event_groups} row at all ({@link EventGroupService#getGroupsWithAthletes}). It
 * also pins the team counts the second requirement is about: <strong>two, three and
 * four teams are all runnable</strong>, and a relay is never held to four.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelaySheetScopeTest {

    /** Event 6050 on the live programme: the Form 3 relay, stored as "· B Grade". */
    private static final long FORM_RELAY_ID = 6050L;
    private static final long HOUSE_RELAY_ID = 167L;
    private static final long HOUSE_FORM_ID = 9001L;
    private static final long UNDIVIDED_ID = 9002L;
    private static final long SPRINT_ID = 115L;

    private static final long SPRINT_GROUP_ID = 401L;
    private static final long RELAY_GROUP_ID = 402L;

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
    private RelayReadiness readiness;

    // ------------------------------------------------------------ the events

    /** The live shape: a form relay whose stored name carries the grade it is not divided by. */
    private static Event formRelay() {
        return Event.builder()
                .id(FORM_RELAY_ID)
                .name("Boys 4x100M Relay · B Grade")
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.RELAY)
                .sex(Sex.MALE)
                .grade(Grade.B)
                .form("3")
                .relayTeamKind(RelayTeamKind.FORM)
                .groupSize(24)
                .eventDate(LocalDate.of(2026, 10, 4))
                .enabled(true)
                .build();
    }

    /** The live shape: a house relay whose stored name already carries its grade. */
    private static Event houseRelay() {
        return Event.builder()
                .id(HOUSE_RELAY_ID)
                .name("Boys 4x100M Relay - B Grade")
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.RELAY)
                .sex(Sex.MALE)
                .grade(Grade.B)
                .relayTeamKind(RelayTeamKind.HOUSE)
                .groupSize(24)
                .eventDate(LocalDate.of(2026, 10, 4))
                .enabled(true)
                .build();
    }

    /** A house relay that is stored under a form, and is divided by its grade. */
    private static Event houseRelayStoredUnderAForm() {
        return Event.builder()
                .id(HOUSE_FORM_ID)
                .name("Girls 4x100M Relay - Form 3")
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.RELAY)
                .sex(Sex.FEMALE)
                .grade(Grade.C)
                .relayTeamKind(RelayTeamKind.HOUSE)
                .groupSize(24)
                .eventDate(LocalDate.of(2026, 10, 4))
                .enabled(true)
                .build();
    }

    /** An undivided relay: no kind, so no scope to name it by. */
    private static Event undividedRelay() {
        return Event.builder()
                .id(UNDIVIDED_ID)
                .name("Boys 4x400M Relay · A Grade")
                .type(Event.EventType.RELAY_4X400M)
                .category(EventCategory.RELAY)
                .sex(Sex.MALE)
                .grade(Grade.A)
                .relayTeamKind(null)
                .groupSize(24)
                .eventDate(LocalDate.of(2026, 10, 4))
                .enabled(true)
                .build();
    }

    /** An individual event — the sheet that must not change at all. */
    private static Event sprint() {
        return Event.builder()
                .id(SPRINT_ID)
                .name("Boys 100M · B Grade")
                .type(Event.EventType.RUN_100M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .grade(Grade.B)
                .groupSize(8)
                .eventDate(LocalDate.of(2026, 10, 4))
                .enabled(true)
                .build();
    }

    // ------------------------------------------------------------ the teams

    private static User user(long id) {
        return User.builder().id(id).username("S%04d".formatted(id)).password("x")
                .fullName("Runner " + id).role(User.Role.STUDENT).enabled(true).build();
    }

    /** One team of a relay holding {@code runners} legs, as the board builds it. */
    private static List<RelayTeamMember> teamLegs(Event relay, long teamId, String label, int runners) {
        RelayTeam team = RelayTeam.builder()
                .id(teamId)
                .event(relay)
                .kind(relay.getRelayTeamKind())
                .teamKey(label)
                .label(label)
                .build();
        List<RelayTeamMember> legs = new ArrayList<>();
        for (int leg = 1; leg <= runners; leg++) {
            legs.add(RelayTeamMember.builder()
                    .id(teamId * 10 + leg)
                    .team(team)
                    .user(user(teamId * 100 + leg))
                    .leg(leg)
                    .build());
        }
        return legs;
    }

    /** The four (or three, or two) complete teams of a relay, in board order. */
    private static List<RelayTeamMember> legsOf(Event relay, String... labels) {
        List<RelayTeamMember> legs = new ArrayList<>();
        for (int index = 0; index < labels.length; index++) {
            legs.addAll(teamLegs(relay, 600L + index, labels[index], 4));
        }
        return legs;
    }

    /** The team rows behind a leg list — what the readiness rule reads. */
    private static List<RelayTeam> teamsIn(List<RelayTeamMember> legs) {
        List<RelayTeam> teams = new ArrayList<>();
        for (RelayTeamMember member : legs) {
            if (teams.stream().noneMatch(team -> team.getId().equals(member.getTeam().getId()))) {
                teams.add(member.getTeam());
            }
        }
        return teams;
    }

    private static EventGroup heat(Event event, long id) {
        return EventGroup.builder()
                .id(id)
                .event(event)
                .groupNumber(1)
                .stage(EventStage.HEAT)
                .capacity(event.getGroupSize())
                .athleteCount(2)
                .build();
    }

    // ------------------------------------------------------------ the wiring

    @BeforeEach
    void setUp() {
        readiness = new RelayReadiness(relayTeamRepository, relayTeamMemberRepository);
        groups = new EventGroupService(groupRepository, eventRepository, enrollmentRepository,
                studentRepository, finalEntryRepository, recordService, resultRepository,
                relayTeamMemberRepository, readiness);
        SettingsService settings = org.mockito.Mockito.mock(SettingsService.class);
        when(settings.get()).thenReturn(SportDaySettings.defaults());
        sheets = new PdfSheetService(groups, new PdfFontProvider(""), settings);

        when(recordService.record(any(), any(), any())).thenReturn(null);
    }

    /**
     * The relay as the server reads it: its teams, and either a heat or — the live
     * shape — no {@code event_groups} row at all.
     */
    private void stub(Event relay, List<RelayTeamMember> legs, List<EventGroup> heats) {
        when(eventRepository.findById(relay.getId())).thenReturn(Optional.of(relay));
        when(groupRepository.findByEventIdOrderByGroupNumberAsc(relay.getId())).thenReturn(heats);
        when(relayTeamMemberRepository.findForEventWithUser(relay.getId())).thenReturn(legs);
        when(relayTeamRepository.findByEventIdOrderByIdAsc(relay.getId())).thenReturn(teamsIn(legs));
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static List<String> linesOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).lines()
                    .map(String::trim).filter(line -> !line.isBlank()).toList();
        }
    }

    // --------------------------------------------- a FORM relay's paper

    @Test
    @DisplayName("a form relay stored as '· B Grade' prints 'Form 3', on a sheet with no heat behind it")
    void aFormRelayWithNoHeatsIsHeadedByItsForm() throws Exception {
        Event relay = formRelay();
        stub(relay, legsOf(relay, "3A", "3B", "3C", "3D"), List.of());

        EventGroupDTO sheet = groups.getGroupsWithAthletes(FORM_RELAY_ID).get(0);
        assertEquals("Boys 4x100M Relay · Form 3", sheet.sheetHeading(),
                "the heading is the form the teams are drawn from");
        assertEquals("Boys 4x100M Relay · B Grade", sheet.getEventName(),
                "and the stored name is still carried as data, unwritten");

        String text = textOf(sheets.renderEventSheets(FORM_RELAY_ID));
        assertTrue(text.contains("Boys 4x100M Relay"), "the relay still heads the sheet: " + text);
        assertTrue(text.contains("Form 3"),
                "the paper names the form its teams are drawn from: " + text);
        assertFalse(text.contains("B Grade"),
                "and never the grade its stored name carries, which decides nothing: " + text);
    }

    @Test
    @DisplayName("a form relay with a heat behind it prints its form too")
    void aFormRelayWithAHeatIsHeadedByItsForm() throws Exception {
        Event relay = formRelay();
        EventGroup heat = heat(relay, RELAY_GROUP_ID);
        stub(relay, legsOf(relay, "3A", "3B", "3C", "3D"), List.of(heat));
        when(groupRepository.findById(RELAY_GROUP_ID)).thenReturn(Optional.of(heat));
        when(enrollmentRepository.findByGroupWithUserOrdered(RELAY_GROUP_ID))
                .thenReturn(List.of(Enrollment.builder().id(1L).user(user(801L)).event(relay)
                        .status(Enrollment.EnrollmentStatus.CONFIRMED).lane(1).build()));
        when(enrollmentRepository.findWithUserByEventAndUserIds(eq(FORM_RELAY_ID), any()))
                .thenReturn(List.of());
        when(studentRepository.findWithUserByUserIdIn(any())).thenReturn(List.of());

        EventGroupDTO sheet = groups.getGroup(RELAY_GROUP_ID);
        assertEquals("Boys 4x100M Relay · Form 3", sheet.sheetHeading());

        String text = textOf(sheets.renderGroupSheet(RELAY_GROUP_ID));
        assertTrue(text.contains("Form 3"), "the form heads the paper: " + text);
        assertFalse(text.contains("B Grade"),
                "the grade in the stored name does not reach the paper: " + text);
    }

    // -------------------------------------------- a HOUSE relay's paper

    @Test
    @DisplayName("a house relay prints the grade it is run in")
    void aHouseRelayIsHeadedByItsGrade() throws Exception {
        Event relay = houseRelay();
        stub(relay, legsOf(relay, "B Grade Yellow", "B Grade Red"), List.of());

        EventGroupDTO sheet = groups.getGroupsWithAthletes(HOUSE_RELAY_ID).get(0);
        assertEquals("Boys 4x100M Relay - B Grade", sheet.sheetHeading(),
                "a house relay's own grade line is kept, word for word");

        String text = textOf(sheets.renderEventSheets(HOUSE_RELAY_ID));
        assertTrue(text.contains("Boys 4x100M Relay - B Grade"),
                "the grade heads the paper: " + text);
        assertFalse(text.contains("Form"),
                "and no form is invented for a relay that is divided by grade: " + text);
    }

    @Test
    @DisplayName("a house relay stored under a form prints its grade instead")
    void aHouseRelayStoredUnderAFormIsHeadedByItsGrade() throws Exception {
        Event relay = houseRelayStoredUnderAForm();
        stub(relay, legsOf(relay, "C Grade Yellow", "C Grade Red"), List.of());

        EventGroupDTO sheet = groups.getGroupsWithAthletes(HOUSE_FORM_ID).get(0);
        assertEquals("Girls 4x100M Relay · C Grade", sheet.sheetHeading());

        String text = textOf(sheets.renderEventSheets(HOUSE_FORM_ID));
        assertTrue(text.contains("C Grade"), "the grade heads the paper: " + text);
        assertFalse(text.contains("Form 3"),
                "the form in the stored name is not the scope of a house relay: " + text);
    }

    // ------------------------------------------- what must not change

    @Test
    @DisplayName("an individual event's sheet is unchanged, heading and all")
    void anIndividualSheetIsUnchanged() throws Exception {
        Event sprint = sprint();
        EventGroup heat = heat(sprint, SPRINT_GROUP_ID);
        when(eventRepository.findById(SPRINT_ID)).thenReturn(Optional.of(sprint));
        when(groupRepository.findById(SPRINT_GROUP_ID)).thenReturn(Optional.of(heat));
        when(enrollmentRepository.findByGroupWithUserOrdered(SPRINT_GROUP_ID))
                .thenReturn(List.of(Enrollment.builder().id(1L).user(user(901L)).event(sprint)
                        .status(Enrollment.EnrollmentStatus.CONFIRMED).lane(3).build()));
        when(enrollmentRepository.findWithUserByEventAndUserIds(eq(SPRINT_ID), any()))
                .thenReturn(List.of());
        when(studentRepository.findWithUserByUserIdIn(any())).thenReturn(List.of());

        EventGroupDTO group = groups.getGroup(SPRINT_GROUP_ID);
        assertNull(group.getRelayTitle(), "an individual event carries no relay title");
        assertEquals("Boys 100M · B Grade", group.sheetHeading());

        String text = textOf(sheets.renderGroupSheet(SPRINT_GROUP_ID));
        assertTrue(text.contains("Boys 100M · B Grade"),
                "a sprint prints exactly the name it always printed: " + text);
    }

    @Test
    @DisplayName("an undivided relay keeps whatever name it has today")
    void anUndividedRelayKeepsItsName() throws Exception {
        Event relay = undividedRelay();
        List<RelayTeamMember> legs = List.of(
                RelayTeamMember.builder().id(1L)
                        .team(RelayTeam.builder().id(11L).event(relay).kind(null)
                                .teamKey("A").label("A").build())
                        .user(user(701L)).leg(1).build(),
                RelayTeamMember.builder().id(2L)
                        .team(RelayTeam.builder().id(12L).event(relay).kind(null)
                                .teamKey("B").label("B").build())
                        .user(user(702L)).leg(1).build());
        stub(relay, legs, List.of());

        EventGroupDTO sheet = groups.getGroupsWithAthletes(UNDIVIDED_ID).get(0);
        assertEquals("Boys 4x400M Relay · A Grade", sheet.sheetHeading());
    }

    // ------------------------------------------- two, three and four teams

    @Test
    @DisplayName("a two-team relay is ready and prints: two lines, and the count says two")
    void aTwoTeamRelayIsReadyAndPrints() throws Exception {
        Event relay = formRelay();
        List<RelayTeamMember> legs = legsOf(relay, "3A", "3B");
        stub(relay, legs, List.of());

        assertTrue(readiness.isReady(relay), "two complete teams is a race: "
                + readiness.shortfallOf(relay).orElse("ready"));

        byte[] pdf = sheets.renderEventSheets(FORM_RELAY_ID);
        List<String> sheetLines = linesOf(pdf);
        String text = String.join("\n", sheetLines);
        assertTrue(sheetLines.contains("3A") && sheetLines.contains("3B"),
                "one line per team: " + text);
        assertEquals(2, sheetLines.stream().filter(line -> List.of("3A", "3B").contains(line)).count(),
                "two teams, two lines — nothing pads the race out to four: " + text);
        assertTrue(text.contains("隊伍 Teams: 2"),
                "and the header counts the two teams it prints: " + text);
    }

    @Test
    @DisplayName("a three-team relay is ready and prints: three lines, and the count says three")
    void aThreeTeamRelayIsReadyAndPrints() throws Exception {
        Event relay = formRelay();
        List<RelayTeamMember> legs = legsOf(relay, "3A", "3B", "3C");
        stub(relay, legs, List.of());

        assertTrue(readiness.isReady(relay), "three complete teams is a race: "
                + readiness.shortfallOf(relay).orElse("ready"));

        String text = String.join("\n", linesOf(sheets.renderEventSheets(FORM_RELAY_ID)));
        assertTrue(text.contains("隊伍 Teams: 3"),
                "the sheet counts the three teams it prints: " + text);
    }

    @Test
    @DisplayName("four teams is the ceiling only in the sense that four is what a form has — not a demand")
    void aFourTeamRelayIsReadyToo() {
        Event relay = formRelay();
        stub(relay, legsOf(relay, "3A", "3B", "3C", "3D"), List.of());

        assertTrue(readiness.isReady(relay));
        assertEquals(2, RelayReadiness.MINIMUM_TEAMS,
                "the only count the rule states is the floor of two");
    }

    @Test
    @DisplayName("a fourth team short of its runners still holds the relay back — the rule is unchanged")
    void aShortTeamStillHoldsTheRelayBack() {
        Event relay = formRelay();
        List<RelayTeamMember> legs = new ArrayList<>(legsOf(relay, "3A", "3B", "3C"));
        // The fourth team exists but holds three runners: the race is not ready, and
        // the reason names that team rather than counting teams.
        legs.addAll(teamLegs(relay, 700L, "3D", 3));
        stub(relay, legs, List.of());

        assertFalse(readiness.isReady(relay));
        String reason = readiness.shortfallOf(relay).orElse("");
        assertTrue(reason.contains("3D has 3 of the 4 runners it needs"),
                "the short team is named, in the readiness rule's own words: " + reason);
    }
}
