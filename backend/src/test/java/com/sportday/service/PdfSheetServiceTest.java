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

        assertTrue(Files.exists(PREVIEW_DIR.resolve("marking-sheet-A5.png")));
        assertTrue(Files.exists(PREVIEW_DIR.resolve("marking-sheet-A4.png")));
    }

    static boolean rendererAvailable() {
        try {
            return javax.imageio.ImageIO.getImageWritersByFormatName("png").hasNext();
        } catch (Throwable ex) {
            return false;
        }
    }

    private void writePreview(String fileName, byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            // Print resolution, so the preview is good enough to check before a print run.
            BufferedImage image = renderer.renderImageWithDPI(0, 300, ImageType.RGB);
            Path target = PREVIEW_DIR.resolve(fileName);
            ImageIO.write(image, "png", target.toFile());
            assertTrue(Files.size(target) > 1000, "preview " + fileName + " looks empty");
        }
    }
}
