package com.sportday.service;

import com.sportday.dto.ChampionsDTO;
import com.sportday.dto.EnrollmentDTO;
import com.sportday.dto.EventGroupDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventResult;
import com.sportday.entity.EventStage;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.SportDaySettings;
import com.sportday.entity.User;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The results sheet.
 *
 * <p>Where an event ran heats and then a final, the sheet carries both: the heats
 * under {@code 初賽 Heats}, one sub-table per heat and each ranked within itself,
 * and the final's placings — points and school-record star included — under
 * {@code 決賽 Final}. An event that ran one straight group prints one table,
 * exactly as it did before, and a heat athlete who recorded no mark still gets a
 * line.</p>
 *
 * <p>No database is involved: the standings, the groups and the marks are the three
 * things the sheet is built from, so they are the three things stubbed here, and the
 * rendered PDF is read back with PDFBox.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PdfResultServiceTest {

    /** A 60M — the kind of event that runs heats and then a final. */
    private static final Long SPRINT_ID = 114L;

    /** An 800M — one straight group, no final. */
    private static final Long DISTANCE_ID = 138L;

    @Mock private ChampionService championService;
    @Mock private EventRepository eventRepository;
    @Mock private EventResultRepository resultRepository;
    @Mock private EventGroupService eventGroupService;
    @Mock private SettingsService settingsService;

    private PdfResultService service;

    @BeforeEach
    void setUp() {
        when(settingsService.get()).thenReturn(SportDaySettings.defaults());
        service = new PdfResultService(championService, eventRepository, resultRepository,
                eventGroupService, new PdfFontProvider(""), settingsService);
    }

    // ------------------------------------------------------------- fixtures

    private static EnrollmentDTO athlete(long userId, String ref, String name, String grade,
                                         String className, String house, Integer lane) {
        return EnrollmentDTO.builder()
                .userId(userId)
                .studentRef(ref)
                .name(name)
                .grade(grade)
                .className(className)
                .classNumber(lane)
                .house(house)
                .lane(lane)
                .build();
    }

    private static EventGroupDTO heat(int groupNumber, List<EnrollmentDTO> athletes) {
        return EventGroupDTO.builder()
                .id((long) groupNumber)
                .eventId(SPRINT_ID)
                .stage(EventStage.HEAT.name())
                .groupNumber(groupNumber)
                .label("Heat " + groupNumber)
                .athletes(new ArrayList<>(athletes))
                .build();
    }

    private static EventGroupDTO finalGroup() {
        return EventGroupDTO.builder()
                .id(99L)
                .eventId(SPRINT_ID)
                .stage(EventStage.FINAL.name())
                .groupNumber(0)
                .label("Final")
                .athletes(new ArrayList<>())
                .build();
    }

    private static EventResult heatResult(long userId, String mark, String unit) {
        return EventResult.builder()
                .id(userId * 10 + 1)
                .user(User.builder().id(userId).username("U" + userId).fullName("Athlete " + userId).build())
                .stage(EventStage.HEAT)
                .mark(new BigDecimal(mark))
                .unit(unit)
                .build();
    }

    /** A heat row for an athlete who produced no mark: ABS or DQ. */
    private static EventResult heatOutcome(long userId, EventResult.Outcome outcome) {
        return EventResult.builder()
                .id(userId * 10 + 2)
                .user(User.builder().id(userId).username("U" + userId).fullName("Athlete " + userId).build())
                .stage(EventStage.HEAT)
                .outcome(outcome)
                .build();
    }

    private static ChampionsDTO.EventStandingsDTO standings(String eventName,
                                                            Event.EventType type,
                                                            String scoringStage, boolean hasFinal,
                                                            List<ChampionsDTO.PlacingDTO> placings) {
        EventCategory category = type == null ? EventCategory.TRACK : type.getCategory();
        return ChampionsDTO.EventStandingsDTO.builder()
                .eventId(SPRINT_ID)
                .eventName(eventName)
                .eventType(type == null ? null : type.name())
                .eventTypeLabel(type == null ? null : type.getDisplayName())
                .category(category.name())
                .categoryLabel(category.getLabel())
                .sex(Sex.MALE.name())
                .sexLabel(Sex.MALE.getLabel())
                .eventDate(LocalDate.of(2026, 10, 4))
                .scoringStage(scoringStage)
                .hasFinal(hasFinal)
                .placings(placings)
                .build();
    }

    private static ChampionsDTO.PlacingDTO placing(int place, long userId, String display, int points) {
        return ChampionsDTO.PlacingDTO.builder()
                .place(place)
                .userId(userId)
                .studentRef("F%04d".formatted(userId))
                .name("Finalist " + userId)
                .grade("A")
                .className("5A")
                .house("Red")
                .mark(new BigDecimal("7.000"))
                .unit("s")
                .displayMark(display)
                .points(points)
                .build();
    }

    /** A finalist who was absent or disqualified: no place, no points, no mark. */
    private static ChampionsDTO.PlacingDTO outcomePlacing(long userId, String outcome) {
        return ChampionsDTO.PlacingDTO.builder()
                .place(0)
                .userId(userId)
                .studentRef("F%04d".formatted(userId))
                .name("Finalist " + userId)
                .grade("A")
                .className("5A")
                .house("Red")
                .mark(null)
                .unit(null)
                .displayMark(outcome)
                .points(0)
                .schoolRecord(false)
                .build();
    }

    /** The final has been run: the standings are decided by it. */
    private void finalWasRun(List<ChampionsDTO.PlacingDTO> placings) {
        when(championService.standingsFor(SPRINT_ID)).thenReturn(
                standings("Boys 60M A Grade", Event.EventType.RUN_60M, EventStage.FINAL.name(), true, placings));
    }

    // -------------------------------------------------------------- helpers

    private static PDDocument load(byte[] pdf) throws Exception {
        assertNotNull(pdf);
        assertTrue(pdf.length > 1000, "PDF looks too small: " + pdf.length + " bytes");
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        return Loader.loadPDF(pdf);
    }

    /** The sheet's text, cell by cell — what each table holds. */
    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /**
     * The sheet's text in the order it reads down the page, with the spacing PDFBox
     * inserts between glyphs removed — so the run of headings and tables can be
     * asserted in the order an eye meets them.
     */
    private static String readingOrderOf(byte[] pdf) throws Exception {
        try (PDDocument document = load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document).replaceAll("\\s+", "");
        }
    }

    private static int pagesOf(byte[] pdf) throws Exception {
        try (PDDocument document = load(pdf)) {
            return document.getNumberOfPages();
        }
    }

    /** The extracted line an athlete's row was written on, or null when absent. */
    private static String lineContaining(String text, String needle) {
        for (String line : text.split("\\R")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return null;
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("an event that ran heats and a final prints the heats, then the final")
    void heatsAndFinalAreBothPrinted() throws Exception {
        when(eventGroupService.getGroupsWithAthletes(SPRINT_ID)).thenReturn(List.of(
                heat(1, List.of(
                        athlete(513, "S0512", "胡穎嵐", "A", "5B", "Red", 3),
                        athlete(473, "S0472", "呂楠威", "A", "5A", "Red", 2))),
                heat(2, List.of(
                        athlete(501, "S0500", "Chan Tai Man", "A", "6C", "Blue", 1))),
                finalGroup()));
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(SPRINT_ID, EventStage.HEAT))
                .thenReturn(List.of(
                        heatResult(513, "7.430", "s"),
                        heatResult(473, "7.630", "s"),
                        heatResult(501, "8.100", "s")));
        finalWasRun(List.of(placing(1, 513, "7.434s", 9), placing(2, 473, "7.659s", 6)));

        byte[] pdf = service.renderEventResults(SPRINT_ID);

        String text = textOf(pdf);
        assertTrue(text.contains("初賽 Heats"), "the heats are headed");
        assertTrue(text.contains("決賽 Final"), "and so is the final");
        assertTrue(text.contains("Heat 1") && text.contains("Heat 2"), "each heat is named");

        // Every heat athlete is on the sheet: the finalists ran their heat too, and
        // the athlete who did not reach the final has only a heat line.
        assertTrue(text.contains("S0512") && text.contains("呂楠威"), "heat 1 is listed");
        assertTrue(text.contains("S0500"), "heat 2, and its single athlete, is listed");

        // One headed table per heat, plus the final's. Points belong to the scoring
        // stage — the final — so exactly one table carries that column.
        assertEquals(3, countOf(text, "成績 Result"), "a result column per heat, and the final's");
        assertEquals(1, countOf(text, "分數 Points"), "only the final's table carries points");
        String fastestHeatRow = lineContaining(text, "S0512").trim();
        assertTrue(fastestHeatRow.endsWith("7.43s"),
                "the heat row ends at the result — there is no points cell after it");

        // The run of the page: heats, then the final.
        String order = readingOrderOf(pdf);
        assertTrue(order.contains("初賽Heats"), "the heats heading");
        assertTrue(order.contains("決賽Final"), "the final heading");
        assertTrue(order.indexOf("初賽Heats") < order.indexOf("決賽Final"),
                "the heats come first, then the final");
        assertTrue(order.indexOf("Heats") < order.indexOf("Heat1"), "the heats are headed before Heat 1");
        assertTrue(order.indexOf("Heat1") < order.indexOf("Heat2"), "heats are in heat order");
        assertTrue(order.indexOf("Heat2") < order.indexOf("決賽Final"), "and the final comes last");
        // Each heat is ranked within itself: 7.430 beats 7.630 in heat 1.
        assertTrue(order.indexOf("1S0512") < order.indexOf("2S0472"),
                "the faster heat time is ranked first within its heat");
        assertTrue(order.indexOf("7.43s") < order.indexOf("7.434s"),
                "the heat mark is printed in the heats, the final's in the final");
    }

    @Test
    @DisplayName("an event with no final prints one table, exactly as before")
    void anEventWithoutAFinalPrintsOneTable() throws Exception {
        when(championService.standingsFor(DISTANCE_ID)).thenReturn(
                standings("Boys 800M A Grade", Event.EventType.RUN_800M,
                        EventStage.HEAT.name(), false,
                        List.of(placing(1, 21, "2.15.500s", 9), placing(2, 22, "2.20.000s", 6))));

        byte[] pdf = service.renderEventResults(DISTANCE_ID);

        String text = textOf(pdf);
        assertFalse(text.contains("初賽 Heats"), "no heats heading on a single-run event");
        assertFalse(text.contains("決賽 Final"), "and no final heading either");
        assertFalse(text.contains("Heat 1"), "no heat sub-table");
        assertEquals(1, countOf(text, "成績 Result"), "one table");
        assertEquals(1, countOf(text, "分數 Points"), "one table, with its points column");
        assertTrue(text.contains("2.15.500s"), "the placings are printed as they always were");
        // With no final there is nothing to separate, so the groups are never read.
        verifyNoInteractions(eventGroupService);
    }

    @Test
    @DisplayName("a final that has been drawn but not yet run prints the heats alone")
    void aDrawnButUnrunFinalPrintsOneTable() throws Exception {
        // The final group exists, but no final marks do — so the heats still decide
        // the placings, and heading them "Final" would mislabel the runs.
        when(championService.standingsFor(SPRINT_ID)).thenReturn(
                standings("Boys 60M A Grade", Event.EventType.RUN_60M,
                        EventStage.HEAT.name(), true, List.of(placing(1, 513, "7.430s", 9))));

        String text = textOf(service.renderEventResults(SPRINT_ID));

        assertFalse(text.contains("初賽 Heats"), "one table, as before the final was run");
        assertFalse(text.contains("決賽 Final"), "and nothing is called a final");
        assertEquals(1, countOf(text, "分數 Points"), "the heats are scoring, so they carry points");
    }

    @Test
    @DisplayName("a heat athlete with no mark still gets a line, with - for the result")
    void anUnmarkedHeatAthleteStillAppears() throws Exception {
        when(eventGroupService.getGroupsWithAthletes(SPRINT_ID)).thenReturn(List.of(
                heat(1, List.of(
                        athlete(11, "S0011", "Fast Runner", "A", "5A", "Red", 1),
                        athlete(12, "S0012", "Slow Runner", "A", "5A", "Blue", 2),
                        athlete(13, "S0013", "No Mark", "A", "5B", "Green", 3))),
                finalGroup()));
        // Two of the three heat athletes have a mark recorded.
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(SPRINT_ID, EventStage.HEAT))
                .thenReturn(List.of(heatResult(11, "7.100", "s"), heatResult(12, "7.900", "s")));
        finalWasRun(List.of(placing(1, 11, "7.050s", 9)));

        String text = textOf(service.renderEventResults(SPRINT_ID));

        String unmarked = lineContaining(text, "S0013");
        assertNotNull(unmarked, "the athlete with no mark is still on the sheet");
        assertTrue(unmarked.trim().startsWith("- S0013"), "with no place, because they have no mark");
        assertTrue(unmarked.trim().endsWith("-"), "and - where the result goes");

        String marked = lineContaining(text, "S0011").trim();
        assertTrue(marked.startsWith("1 "), "the fastest heat time still takes place 1");
        assertTrue(marked.endsWith("7.1s"), "with its mark");
    }

    @Test
    @DisplayName("a heat is ranked in the event's own direction — furthest first in the field")
    void aFieldHeatIsRankedFurthestFirst() throws Exception {
        // Only the sprints can run a final today, but the direction rule is the
        // event's own, so the sheet follows it whichever stage it is printing.
        when(eventGroupService.getGroupsWithAthletes(SPRINT_ID)).thenReturn(List.of(
                heat(1, List.of(
                        athlete(21, "S0021", "Short Throw", "B", "4A", "Red", 1),
                        athlete(22, "S0022", "Long Throw", "B", "4B", "Blue", 2))),
                finalGroup()));
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(SPRINT_ID, EventStage.HEAT))
                .thenReturn(List.of(heatResult(21, "15.000", "M"), heatResult(22, "18.120", "M")));
        when(championService.standingsFor(SPRINT_ID)).thenReturn(
                standings("Boys Shot Put A Grade", Event.EventType.SHOT_PUT,
                        EventStage.FINAL.name(), true, List.of(placing(1, 22, "18.12M", 9))));

        byte[] pdf = service.renderEventResults(SPRINT_ID);

        String order = readingOrderOf(pdf);
        assertTrue(order.indexOf("1S0022") < order.indexOf("2S0021"),
                "18.12M leads the heat, 15M follows");
        String furthest = lineContaining(textOf(pdf), "S0022").trim();
        assertTrue(furthest.startsWith("1 "), "the furthest throw takes place 1");
        assertTrue(furthest.endsWith("18.12M"), "written with its unit");
    }

    @Test
    @DisplayName("the whole programme gives every event its own parts")
    void theProgrammePrintsBothPartsPerEvent() throws Exception {
        Event sprint = Event.builder()
                .id(SPRINT_ID).name("Boys 60M A Grade").type(Event.EventType.RUN_60M)
                .category(EventCategory.TRACK).sex(Sex.MALE).grade(Grade.A)
                .eventDate(LocalDate.of(2026, 10, 4)).groupSize(8).maxParticipants(512)
                .enabled(true).directToFinal(false)
                .build();
        Event distance = Event.builder()
                .id(DISTANCE_ID).name("Boys 800M A Grade").type(Event.EventType.RUN_800M)
                .category(EventCategory.TRACK).sex(Sex.MALE).grade(Grade.A)
                .eventDate(LocalDate.of(2026, 10, 4)).groupSize(24).maxParticipants(512)
                .enabled(true).directToFinal(true)
                .build();
        when(eventRepository.findAll()).thenReturn(List.of(sprint, distance));

        when(eventGroupService.getGroupsWithAthletes(SPRINT_ID)).thenReturn(List.of(
                heat(1, List.of(athlete(11, "S0011", "Fast Runner", "A", "5A", "Red", 1))),
                finalGroup()));
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(SPRINT_ID, EventStage.HEAT))
                .thenReturn(List.of(heatResult(11, "7.100", "s")));

        when(championService.standingsFor(SPRINT_ID)).thenReturn(
                standings("Boys 60M A Grade", Event.EventType.RUN_60M,
                        EventStage.FINAL.name(), true, List.of(placing(1, 11, "7.050s", 9))));
        when(championService.standingsFor(DISTANCE_ID)).thenReturn(
                standings("Boys 800M A Grade", Event.EventType.RUN_800M,
                        EventStage.HEAT.name(), false, List.of(placing(1, 21, "2.15.500s", 9))));

        byte[] pdf = service.renderProgrammeResults(null, null);

        String text = textOf(pdf);
        assertTrue(pagesOf(pdf) >= 1);
        assertEquals(1, countOf(text, "初賽 Heats"), "only the sprint has heats to separate");
        assertEquals(1, countOf(text, "決賽 Final"), "and only the sprint has a final");
        assertTrue(text.contains("Boys 60M A Grade") && text.contains("Boys 800M A Grade"),
                "every event with results is in the programme");
        // The sprint's final carries points and so does the 800M's single table; the
        // sprint's heat table does not.
        assertEquals(2, countOf(text, "分數 Points"),
                "one points table for the sprint's final, one for the 800M");
        String order = readingOrderOf(pdf);
        assertTrue(order.indexOf("初賽Heats") < order.indexOf("Boys800MAGrade"),
                "the sprint's two parts stay together, in programme order");
    }

    // --------------------------------------------- absent and disqualified

    @Test
    @DisplayName("an ABS/DQ athlete is printed after the heat's placed athletes, with no place")
    void absentHeatAthletesFollowThePlacedOnes() throws Exception {
        when(eventGroupService.getGroupsWithAthletes(SPRINT_ID)).thenReturn(List.of(
                heat(1, List.of(
                        athlete(11, "S0011", "Fast Runner", "A", "5A", "Red", 1),
                        athlete(12, "S0012", "Absent Runner", "A", "5A", "Blue", 2),
                        athlete(13, "S0013", "Disqualified", "A", "5B", "Green", 3))),
                finalGroup()));
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(SPRINT_ID, EventStage.HEAT))
                .thenReturn(List.of(
                        heatResult(11, "7.100", "s"),
                        heatOutcome(12, EventResult.Outcome.ABS),
                        heatOutcome(13, EventResult.Outcome.DQ)));
        finalWasRun(List.of(placing(1, 11, "7.050s", 9)));

        byte[] pdf = service.renderEventResults(SPRINT_ID);

        String text = textOf(pdf);
        String absent = lineContaining(text, "S0012");
        String disqualified = lineContaining(text, "S0013");
        assertNotNull(absent, "an absent athlete is not hidden");
        assertNotNull(disqualified, "and neither is a disqualified one");
        assertTrue(absent.trim().startsWith("- S0012"), "no place number: " + absent.trim());
        assertTrue(absent.trim().endsWith("ABS"), "and ABS where the result goes: " + absent.trim());
        assertTrue(disqualified.trim().startsWith("- S0013"), "no place number: " + disqualified.trim());
        assertTrue(disqualified.trim().endsWith("DQ"), "and DQ where the result goes: " + disqualified.trim());

        // Ranked first, then the two who produced no performance.
        String order = readingOrderOf(pdf);
        assertTrue(order.indexOf("1S0011") < order.indexOf("-S0012"),
                "the ranked athlete comes before the absent one");
        assertTrue(order.indexOf("-S0012") < order.indexOf("-S0013"),
                "and the unplaced athletes follow in lane order");
        assertFalse(order.contains("ABS★"), "an absent athlete can never hold the school record");
    }

    @Test
    @DisplayName("an ABS/DQ finalist is printed after the placed finalists, with no place and no points")
    void absentFinalistsFollowThePlacedOnes() throws Exception {
        when(eventGroupService.getGroupsWithAthletes(SPRINT_ID)).thenReturn(List.of(
                heat(1, List.of(athlete(21, "S0021", "Winner", "A", "5A", "Red", 1))),
                finalGroup()));
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(SPRINT_ID, EventStage.HEAT))
                .thenReturn(List.of(heatResult(21, "7.050", "s")));
        finalWasRun(List.of(
                placing(1, 21, "7.010s", 9),
                outcomePlacing(22, "ABS"),
                outcomePlacing(23, "DQ")));

        byte[] pdf = service.renderEventResults(SPRINT_ID);

        String text = textOf(pdf);
        // The final's rows carry the placing DTOs' own student refs (F…), so the
        // winner is found by the result it printed rather than by its name.
        String winner = lineContaining(text, "7.010s");
        String absent = lineContaining(text, "F0022");
        String disqualified = lineContaining(text, "F0023");
        assertNotNull(winner, "the winner's final row is printed");
        assertNotNull(absent, "an absent finalist is not hidden");
        assertNotNull(disqualified, "and neither is a disqualified one");
        assertTrue(winner.trim().startsWith("1 "), "the winner keeps place 1: " + winner.trim());
        assertTrue(absent.trim().startsWith("- F0022"),
                "no place number for an absent finalist: " + absent.trim());
        assertTrue(absent.trim().endsWith("ABS -"),
                "and no points after the result: " + absent.trim());
        assertTrue(disqualified.trim().startsWith("- F0023"),
                "no place number for a disqualified finalist: " + disqualified.trim());
        assertTrue(disqualified.trim().endsWith("DQ -"),
                "and no points either: " + disqualified.trim());

        String order = readingOrderOf(pdf);
        assertTrue(order.indexOf("1F0021") < order.indexOf("-F0022"),
                "the finalists who ran are placed before the ones who did not");
        assertTrue(order.indexOf("-F0022") < order.indexOf("-F0023"));
        assertFalse(order.contains("ABS★") || order.contains("DQ★"),
                "and neither can carry the school-record star");
    }
}
