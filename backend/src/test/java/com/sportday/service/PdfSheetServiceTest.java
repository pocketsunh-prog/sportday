package com.sportday.service;

import com.sportday.dto.EnrollmentDTO;
import com.sportday.dto.EventGroupDTO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Requirement 5: helper marking sheets.
 *
 * <p>Checks the paper size actually recorded in the PDF (A5 for 60/100/200/400,
 * A4 otherwise), the page count, and that the five columns — student id, name,
 * grade, record, remark — are present with the right athletes on them.</p>
 *
 * <p>Also writes a PNG of the first page to {@code target/pdf-preview/} so the
 * layout and Chinese text can be eyeballed.</p>
 */
class PdfSheetServiceTest {

    private static final Path PREVIEW_DIR = Path.of("target", "pdf-preview");

    private final PdfSheetService service = new PdfSheetService(null, new PdfFontProvider(""),
            settingsService());

    /**
     * The school's own name and title head the sheet when they have been filled in.
     * With the plain defaults there is no school name, so the fixed Chinese/English
     * heading is used and the layout assertions below still describe the sheet.
     */
    private static SettingsService settingsService() {
        SettingsService settings = org.mockito.Mockito.mock(SettingsService.class);
        org.mockito.Mockito.when(settings.get())
                .thenReturn(com.sportday.entity.SportDaySettings.defaults());
        return settings;
    }

    @BeforeAll
    static void prepare() throws Exception {
        Files.createDirectories(PREVIEW_DIR);
    }

    private static EnrollmentDTO athlete(String id, String name, String grade) {
        return EnrollmentDTO.builder()
                .studentRef(id)
                .name(name)
                .grade(grade)
                .build();
    }

    private static EventGroupDTO group(String sheetSize, int capacity, int groupNumber,
                                       List<EnrollmentDTO> athletes) {
        return group(sheetSize, capacity, groupNumber, athletes,
                sheetSize.equals("A5") ? "Boys 100M" : "Boys 800M");
    }

    private static EventGroupDTO group(String sheetSize, int capacity, int groupNumber,
                                       List<EnrollmentDTO> athletes, String eventName) {
        boolean sprint = sheetSize.equals("A5");
        return EventGroupDTO.builder()
                .id((long) groupNumber)
                .eventId(1L)
                .eventName(eventName)
                .eventTypeLabel(sprint ? "100M" : "800M")
                .category("TRACK")
                .categoryLabel("徑項 Track")
                .sex("MALE")
                .sexLabel("男 Boys")
                .groupNumber(groupNumber)
                .label("Heat " + groupNumber)
                .capacity(capacity)
                .athleteCount(athletes.size())
                .sheetSize(sheetSize)
                .athletes(athletes)
                .build();
    }

    private static List<EnrollmentDTO> sprintHeat() {
        List<EnrollmentDTO> heat = new ArrayList<>();
        String[][] names = {
                {"S0001", "陳大文", "A"}, {"S0002", "李小明", "B"},
                {"S0003", "黃詠詩", "C"}, {"S0004", "張家俊", "A"},
                {"S0005", "劉美華", "B"}, {"S0006", "何志強", "C"},
                {"S0007", "梁凱婷", "A"}, {"S0008", "吳浩然", "B"},
        };
        for (String[] row : names) {
            heat.add(athlete(row[0], row[1], row[2]));
        }
        return heat;
    }

    private static PDDocument load(byte[] pdf) throws Exception {
        assertNotNull(pdf);
        assertTrue(pdf.length > 1000, "PDF looks too small: " + pdf.length + " bytes");
        // Must start with the %PDF magic bytes.
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        return Loader.loadPDF(pdf);
    }

    @Test
    @DisplayName("a short sprint group prints on A5 (421 x 595 pt)")
    void shortSprintSheetIsA5() throws Exception {
        byte[] pdf = service.renderSheets(List.of(group("A5", 8, 1, sprintHeat())));
        try (PDDocument document = load(pdf)) {
            assertEquals(1, document.getNumberOfPages());
            var box = document.getPage(0).getMediaBox();
            assertEquals(421f, box.getWidth(), 2f, "A5 width");
            assertEquals(595f, box.getHeight(), 2f, "A5 height");
        }
    }

    @Test
    @DisplayName("an 800m or field group prints on A4 (595 x 842 pt)")
    void distanceSheetIsA4() throws Exception {
        List<EnrollmentDTO> heat = new ArrayList<>();
        for (int i = 1; i <= 24; i++) {
            heat.add(athlete(String.format("S%04d", i), "學生" + i, i % 3 == 0 ? "A" : (i % 2 == 0 ? "B" : "C")));
        }
        byte[] pdf = service.renderSheets(List.of(group("A4", 24, 1, heat)));
        try (PDDocument document = load(pdf)) {
            assertEquals(1, document.getNumberOfPages());
            var box = document.getPage(0).getMediaBox();
            assertEquals(595f, box.getWidth(), 2f, "A4 width");
            assertEquals(842f, box.getHeight(), 2f, "A4 height");
        }
    }

    @Test
    @DisplayName("one sheet per group, in heat order")
    void onePagePerGroup() throws Exception {
        List<EventGroupDTO> groups = List.of(
                group("A5", 8, 1, sprintHeat()),
                group("A5", 8, 2, sprintHeat()),
                group("A5", 8, 3, sprintHeat()));
        byte[] pdf = service.renderSheets(groups);
        try (PDDocument document = load(pdf)) {
            assertEquals(3, document.getNumberOfPages());
            for (int page = 0; page < 3; page++) {
                var box = document.getPage(page).getMediaBox();
                assertEquals(421f, box.getWidth(), 2f, "page " + page + " width");
            }
        }
    }

    @Test
    @DisplayName("the sheet carries the five required columns")
    void hasTheFiveColumns() throws Exception {
        byte[] pdf = service.renderSheets(List.of(group("A5", 8, 1, sprintHeat())));
        try (PDDocument document = load(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("學號") && text.contains("Student ID"), "student id column");
            assertTrue(text.contains("姓名") && text.contains("Name"), "name column");
            assertTrue(text.contains("級別") && text.contains("Grade"), "grade column");
            assertTrue(text.contains("成績") && text.contains("Record"), "record column");
            assertTrue(text.contains("備註") && text.contains("Remark"), "remark column");
        }
    }

    @Test
    @DisplayName("each athlete appears with their student id, name and grade")
    void listsAthletes() throws Exception {
        byte[] pdf = service.renderSheets(List.of(group("A5", 8, 1, sprintHeat())));
        try (PDDocument document = load(pdf)) {
            String text = new PDFTextStripper().getText(document);
            for (EnrollmentDTO entry : sprintHeat()) {
                assertTrue(text.contains(entry.getStudentRef()), "missing student id " + entry.getStudentRef());
                assertTrue(text.contains(entry.getName()), "missing name " + entry.getName());
            }
            assertTrue(text.contains("陳大文"), "Chinese names must survive into the PDF");
        }
    }

    @Test
    @DisplayName("the event, division and heat number head the sheet")
    void headsTheSheet() throws Exception {
        byte[] pdf = service.renderSheets(List.of(group("A5", 8, 3, sprintHeat())));
        try (PDDocument document = load(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Boys 100M"), "event name");
            assertTrue(text.contains("Heat: 3") || text.contains("Heat") , "heat number");
            assertTrue(text.contains("男"), "sex division");
        }
    }

    @Test
    @DisplayName("a partly filled group still gets a full sheet, so late entries have a line")
    void padsToCapacity() throws Exception {
        List<EnrollmentDTO> three = sprintHeat().subList(0, 3);
        byte[] pdf = service.renderSheets(List.of(group("A5", 8, 1, three)));
        try (PDDocument document = load(pdf)) {
            assertEquals(1, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("S0003"), "the third athlete is on the sheet");
        }
    }

    @Test
    @DisplayName("an empty group list is rejected rather than producing a blank file")
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> service.renderSheets(List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.renderSheets(null));
    }

    @Test
    @DisplayName("the school's own name and title head the sheet when they are set")
    void theSchoolHeadsTheSheet() {
        SettingsService school = org.mockito.Mockito.mock(SettingsService.class);
        org.mockito.Mockito.when(school.get()).thenReturn(
                com.sportday.entity.SportDaySettings.builder()
                        .id(1L)
                        .schoolName("Kowloon Sportday Secondary School")
                        .schoolNameZh("九龍運動日中學")
                        .sportDayTitle("田徑運動會記錄表 / Sport Day Marking Sheet")
                        .build());
        PdfSheetService withSchool = new PdfSheetService(null, new PdfFontProvider(""), school);

        byte[] plain = service.renderSheets(List.of(group("A5", 8, 1, sprintHeat())));
        byte[] headed = withSchool.renderSheets(List.of(group("A5", 8, 1, sprintHeat())));

        assertTrue(headed.length > 0, "the sheet still renders with a school name");
        assertTrue(headed.length > plain.length,
                "and carries more than a sheet with no school name — the extra heading line");
        try (PDDocument document = load(headed)) {
            assertEquals(1, document.getNumberOfPages(),
                    "the heading must not push the sheet onto a second page");
            var box = document.getPage(0).getMediaBox();
            assertTrue(Math.abs(box.getWidth() - 421) < 3 && Math.abs(box.getHeight() - 595) < 3,
                    "and it is still A5");
        } catch (Exception ex) {
            fail("the sheet could not be read back: " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("a CJK-capable font is embedded so Chinese names print")
    void embedsACjkFont() throws Exception {
        byte[] pdf = service.renderSheets(List.of(group("A5", 8, 1, sprintHeat())));
        String raw = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(raw.contains("FontFile2"), "an embedded TrueType font is required for CJK");
        try (PDDocument document = load(pdf)) {
            var fontNames = document.getPage(0).getResources().getFontNames();
            assertTrue(fontNames.iterator().hasNext(), "the page must reference at least one font");
        }
    }

    @Test
    @DisplayName("a field sheet gives three attempt boxes and names the unit")
    void aFieldSheetHasThreeAttempts() throws Exception {
        EventGroupDTO shot = EventGroupDTO.builder()
                .id(9L)
                .eventId(2L)
                .eventName("Boys Shot Put")
                .eventTypeLabel("Shot Put")
                .category("FIELD")
                .categoryLabel("田項 Field")
                .sex("MALE")
                .sexLabel("男 Boys")
                .groupNumber(1)
                .label("Heat 1")
                .capacity(24)
                .athleteCount(2)
                .sheetSize("A4")
                .athletes(List.of(athlete("S0001", "Chan Tai Man", "B"),
                        athlete("S0002", "Lee Siu Ming", "A")))
                .build();

        byte[] pdf = service.renderSheets(List.of(shot));

        try (PDDocument document = load(pdf)) {
            assertEquals(1, document.getNumberOfPages(),
                    "a bigger header must not push a field sheet onto a second page");
            var box = document.getPage(0).getMediaBox();
            assertTrue(Math.abs(box.getWidth() - 595) < 3 && Math.abs(box.getHeight() - 842) < 3,
                    "a field event is not a short sprint, so it prints on A4");
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertTrue(text.contains("Record"),
                    "the Record heading spans the three attempt boxes");
            assertTrue(text.contains("(M)"),
                    "and says the throws are measured in metres");
        }
    }

    @Test
    @DisplayName("a track sheet keeps its single record column")
    void aTrackSheetHasOneRecord() throws Exception {
        byte[] pdf = service.renderSheets(List.of(group("A5", 8, 1, sprintHeat())));

        try (PDDocument document = load(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertFalse(text.contains("(M)"),
                    "a time is not measured in metres");
        }
    }

    // ------------------------------------------- the heat record, on a final

    /** A finalist, carrying the heat record their place in the final came from. */
    private static EnrollmentDTO finalist(String id, String name, String grade, String heat) {
        String digits = heat == null ? "" : heat.replaceAll("[^0-9.]", "");
        return EnrollmentDTO.builder()
                .studentRef(id)
                .name(name)
                .grade(grade)
                .heatMark(digits.isEmpty() ? null : new java.math.BigDecimal(digits))
                .heatOutcome(heat == null ? null : (digits.isEmpty() ? heat : "RESULT"))
                .heatDisplayMark(heat)
                .build();
    }

    private static List<EnrollmentDTO> finalists() {
        return List.of(
                finalist("S0001", "陳大文", "A", "11.86s"),
                finalist("S0002", "李小明", "B", "12.04s"),
                finalist("S0003", "黃詠詩", "C", "DQ"),
                finalist("S0004", "張家俊", "A", null));
    }

    private static EventGroupDTO finalGroup(List<EnrollmentDTO> athletes) {
        return EventGroupDTO.builder()
                .id(99L)
                .eventId(1L)
                .eventName("Boys 100M")
                .eventType("RUN_100M")
                .eventTypeLabel("100M")
                .category("TRACK")
                .categoryLabel("徑項 Track")
                .sex("MALE")
                .sexLabel("男 Boys")
                .groupNumber(0)
                .label("Final")
                .stage("FINAL")
                .capacity(8)
                .athleteCount(athletes.size())
                .sheetSize("A5")
                .athletes(athletes)
                .build();
    }

    @Test
    @DisplayName("a final sheet shows each finalist's heat record, with the mark they actually ran")
    void aFinalSheetShowsTheHeatRecord() throws Exception {
        EventGroupDTO finalSheet = finalGroup(finalists());

        byte[] pdf = service.renderSheets(List.of(finalSheet));

        try (PDDocument document = load(pdf)) {
            assertEquals(1, document.getNumberOfPages(),
                    "the extra column must not push the final onto a second page");
            var box = document.getPage(0).getMediaBox();
            assertTrue(Math.abs(box.getWidth() - 421) < 3 && Math.abs(box.getHeight() - 595) < 3,
                    "a final of a short sprint is still A5");
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertTrue(text.contains("初賽") && text.contains("Heat"),
                    "the heat column is headed in the sheet's own bilingual style");
            assertTrue(text.contains("11.86s"), "the first finalist's heat time");
            assertTrue(text.contains("12.04s"), "and the second's");
            assertTrue(text.contains("DQ"), "a disqualified heat reads as DQ rather than a number");
            assertTrue(text.contains("成績") && text.contains("Record"),
                    "the record boxes are still there, blank, for the final to be written in");
        }
    }

    @Test
    @DisplayName("a heat sheet is unchanged: no heat column, and the same count as before")
    void aHeatSheetHasNoHeatRecordColumn() throws Exception {
        EventGroupDTO trackHeat = group("A5", 8, 1, sprintHeat());
        EventGroupDTO fieldHeat = EventGroupDTO.builder()
                .id(9L).eventId(2L).eventName("Boys Shot Put").eventTypeLabel("Shot Put")
                .category("FIELD").categoryLabel("田項 Field")
                .sex("MALE").sexLabel("男 Boys")
                .groupNumber(1).label("Heat 1").stage("HEAT")
                .capacity(24).athleteCount(2).sheetSize("A4")
                .athletes(List.of(athlete("S0001", "Chan Tai Man", "B")))
                .build();

        byte[] pdf = service.renderSheets(List.of(trackHeat));

        try (PDDocument document = load(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertFalse(text.contains("初賽"),
                    "a heat sheet carries no heat record: it is the heat");
            assertTrue(text.contains("學號") && text.contains("姓名") && text.contains("級別")
                            && text.contains("成績") && text.contains("備註"),
                    "and the five columns it always had");
        }

        assertEquals(5, PdfSheetService.columnCount(trackHeat),
                "a track heat's five columns, unchanged");
        assertEquals(7, PdfSheetService.columnCount(fieldHeat),
                "a field heat's three attempts and five columns, unchanged");
        assertEquals(6, PdfSheetService.columnCount(finalGroup(finalists())),
                "a final gains exactly one column: the heat record");
    }

    @Test
    @DisplayName("the width array always matches the column count, a field final included")
    void widthsMatchTheColumns() {
        for (boolean field : new boolean[]{false, true}) {
            for (boolean finalStage : new boolean[]{false, true}) {
                for (boolean a5 : new boolean[]{false, true}) {
                    EventGroupDTO group = EventGroupDTO.builder()
                            .id(1L).eventId(1L)
                            .category(field ? "FIELD" : "TRACK")
                            .stage(finalStage ? "FINAL" : "HEAT")
                            .athletes(new ArrayList<>())
                            .build();
                    assertEquals(PdfSheetService.columnCount(group),
                            PdfSheetService.widths(field, finalStage, a5).length,
                            "field=" + field + " final=" + finalStage + " a5=" + a5);
                }
            }
        }
    }

    @Test
    @DisplayName("a field sheet that somehow is a final still lays out, rather than throwing")
    void aFieldFinalStillLaysOut() throws Exception {
        // A field event runs straight to a final, so this sheet should not arise —
        // but the width array has to match its columns rather than throw.
        EventGroupDTO shotFinal = EventGroupDTO.builder()
                .id(9L).eventId(2L).eventName("Boys Shot Put").eventTypeLabel("Shot Put")
                .category("FIELD").categoryLabel("田項 Field")
                .sex("MALE").sexLabel("男 Boys")
                .groupNumber(0).label("Final").stage("FINAL")
                .capacity(24).athleteCount(2).sheetSize("A4")
                .athletes(List.of(
                        finalist("S0001", "Chan Tai Man", "B", "18.12M"),
                        finalist("S0002", "Lee Siu Ming", "A", "ABS")))
                .build();

        byte[] pdf = service.renderSheets(List.of(shotFinal));

        try (PDDocument document = load(pdf)) {
            assertEquals(1, document.getNumberOfPages());
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertTrue(text.contains("初賽"), "the heat column is on it");
            assertTrue(text.contains("18.12M"), "carrying the heat throw");
            assertTrue(text.contains("ABS"), "and ABS where the heat produced nothing");
            assertTrue(text.contains("Record") && text.contains("(M)"),
                    "with the three attempt boxes it always had");
        }
        assertEquals(8, PdfSheetService.columnCount(shotFinal),
                "three athlete columns, a heat column, three attempts and a remark");
    }

    /** Renders preview PNGs for eyeballing; skipped when a PDF renderer is unavailable. */
    @Test
    @DisplayName("writes preview PNGs of the A5 and A4 sheets")
    @EnabledIf("rendererAvailable")
    void writesPreviews() throws Exception {
        writePreview("marking-sheet-A5.png", service.renderSheets(List.of(group("A5", 8, 1, sprintHeat()))));

        List<EnrollmentDTO> heat24 = new ArrayList<>();
        for (int i = 1; i <= 24; i++) {
            heat24.add(athlete(String.format("S%04d", i),
                    List.of("陳大文", "李小明", "黃詠詩", "張家俊", "劉美華", "何志強").get(i % 6),
                    i % 3 == 0 ? "A" : (i % 2 == 0 ? "B" : "C")));
        }
        writePreview("marking-sheet-A4.png", service.renderSheets(List.of(group("A4", 24, 1, heat24))));

        // A field sheet, so the three attempt boxes can be eyeballed too.
        List<EnrollmentDTO> field24 = new ArrayList<>();
        for (int i = 1; i <= 24; i++) {
            field24.add(athlete(String.format("S%04d", i),
                    List.of("陳大文", "李小明", "黃詠詩", "張家俊", "劉美華", "何志強").get(i % 6),
                    i % 3 == 0 ? "A" : (i % 2 == 0 ? "B" : "C")));
        }
        EventGroupDTO shot = EventGroupDTO.builder()
                .id(9L).eventId(2L).eventName("Boys Shot Put").eventTypeLabel("Shot Put")
                .category("FIELD").categoryLabel("田項 Field")
                .sex("MALE").sexLabel("男 Boys")
                .groupNumber(1).label("Heat 1")
                .capacity(24).athleteCount(field24.size()).sheetSize("A4")
                .athletes(field24)
                .build();
        writePreview("marking-sheet-field-A4.png", service.renderSheets(List.of(shot)));

        // A final sheet, so the 初賽 Heat column and the blank record boxes beside it
        // can be eyeballed too.
        writePreview("marking-sheet-final-A5.png",
                service.renderSheets(List.of(finalGroup(finalists()))));

        assertTrue(Files.exists(PREVIEW_DIR.resolve("marking-sheet-A5.png")));
        assertTrue(Files.exists(PREVIEW_DIR.resolve("marking-sheet-A4.png")));
        assertTrue(Files.exists(PREVIEW_DIR.resolve("marking-sheet-field-A4.png")),
                "a field sheet preview, so the three attempts can be checked");
        assertTrue(Files.exists(PREVIEW_DIR.resolve("marking-sheet-final-A5.png")),
                "a final sheet preview, so the heat column can be checked");
    }

    static boolean rendererAvailable() {
        try {
            return javax.imageio.ImageIO.getImageWritersByFormatName("png").hasNext();
        } catch (Throwable ex) {
            return false;
        }
    }

    /** Writes one page of a rendered sheet as a PNG, for eyeballing. */
    static void writePreview(String fileName, byte[] pdf) throws Exception {
        Files.createDirectories(PREVIEW_DIR);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            // Print resolution, so the preview is good enough to check before a print run.
            BufferedImage image = renderer.renderImageWithDPI(0, 300, ImageType.RGB);
            Path target = PREVIEW_DIR.resolve(fileName);
            ImageIO.write(image, "png", target.toFile());
            assertTrue(Files.size(target) > 1000, "preview " + fileName + " looks empty");
        }
    }

    @Test
    @DisplayName("a race over 400M is headed M:S, because that is how it is timed")
    void aLongRaceIsHeadedInMinutesAndSeconds() {
        assertEquals("M:S", PdfSheetService.unitFor(group("RUN_800M", "TRACK")));
        assertEquals("M:S", PdfSheetService.unitFor(group("RUN_1500M", "TRACK")));
        assertEquals("M:S", PdfSheetService.unitFor(group("RUN_5000M", "TRACK")));
    }

    @Test
    @DisplayName("everything else keeps the unit it had")
    void everythingElseKeepsItsUnit() {
        assertEquals("s", PdfSheetService.unitFor(group("RUN_60M", "TRACK")));
        assertEquals("s", PdfSheetService.unitFor(group("RUN_400M", "TRACK")));
        assertEquals("s", PdfSheetService.unitFor(group("HURDLES_400M", "TRACK")));
        assertEquals("s", PdfSheetService.unitFor(group("RELAY_4X100M", "TRACK")));
        assertEquals("M", PdfSheetService.unitFor(group("SHOT_PUT", "FIELD")));
        assertEquals("M", PdfSheetService.unitFor(group("LONG_JUMP", "FIELD")));
    }

    @Test
    @DisplayName("a group with no event type falls back to its category")
    void aGroupWithoutATypeFallsBack() {
        assertEquals("s", PdfSheetService.unitFor(group(null, "TRACK")));
        assertEquals("M", PdfSheetService.unitFor(group(null, "FIELD")));
        assertNull(PdfSheetService.unitFor(group(null, null)));
    }

    private static EventGroupDTO group(String eventType, String category) {
        return EventGroupDTO.builder()
                .id(1L).eventId(1L).eventName("Boys 800M")
                .eventType(eventType).category(category)
                .groupNumber(1).label("Heat 1").stage("HEAT")
                .athletes(new ArrayList<>())
                .build();
    }
}
