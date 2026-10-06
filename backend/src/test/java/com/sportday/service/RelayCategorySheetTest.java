package com.sportday.service;

import com.sportday.dto.EnrollmentDTO;
import com.sportday.dto.EventGroupDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * A relay's marking sheet, now that a relay has a category of its own.
 *
 * <p>A relay sheet is a <em>race</em> sheet: one record box per line, because a
 * team runs once and is given one time, and one line per <strong>team</strong>. The
 * layout is chosen by asking whether the group is a {@code FIELD} group, so a
 * {@code RELAY} group must fall on the race side of that question — and it must do
 * so both before and after the database migration, when a live relay row still
 * says {@code TRACK}. The tests below hold both.</p>
 */
class RelayCategorySheetTest {

    private PdfSheetService sheets;

    @BeforeEach
    void setUp() {
        SettingsService settings = mock(SettingsService.class);
        org.mockito.Mockito.when(settings.get())
                .thenReturn(com.sportday.entity.SportDaySettings.defaults());
        sheets = new PdfSheetService(null, new PdfFontProvider(""), settings);
    }

    private static EnrollmentDTO runner(String ref, String name, String grade, String team) {
        EnrollmentDTO dto = new EnrollmentDTO();
        dto.setStudentRef(ref);
        dto.setName(name);
        dto.setGrade(grade);
        dto.setRelayTeamLabel(team);
        return dto;
    }

    private static EventGroupDTO relayGroup(String category, List<EnrollmentDTO> athletes) {
        return EventGroupDTO.builder()
                .id(11L)
                .eventId(5L)
                .eventName("Boys 4x100M Relay · A Grade")
                .eventType("RELAY_4X100M")
                .category(category)
                .sex(Sex.MALE.name())
                .grade(Grade.A.name())
                .stage("HEAT")
                .groupNumber(1)
                .label("Heat 1")
                .capacity(24)
                .athleteCount(athletes.size())
                .athletes(athletes)
                .build();
    }

    private static List<EnrollmentDTO> twoTeams() {
        return List.of(
                runner("S0001", "Chan Tai Man", "A", "5A"),
                runner("S0002", "Lee Siu Ming", "A", "5A"),
                runner("S0003", "Wong Ka Yan", "A", "5B"),
                runner("S0004", "Ho Cheuk Yiu", "A", "5B"));
    }

    private static List<String> lines(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).lines()
                    .map(String::trim).filter(line -> !line.isBlank()).toList();
        }
    }

    @Test
    @DisplayName("a RELAY group is laid out as a race: one record box and a time, not three distances")
    void aRelayGroupIsARaceSheet() {
        EventGroupDTO relay = relayGroup(EventCategory.RELAY.name(), twoTeams());
        EventGroupDTO field = relayGroup(EventCategory.FIELD.name(), twoTeams());

        assertEquals(5, PdfSheetService.columnCount(relay),
                "student id, name, grade, one record box, remark");
        assertEquals(5, PdfSheetService.columnCount(relayGroup(EventCategory.TRACK.name(), twoTeams())),
                "and a relay is laid out exactly as a race is");
        assertEquals(7, PdfSheetService.columnCount(field),
                "a field sheet's three attempts are the shape a relay must not take");

        assertEquals(Event.EventType.UNIT_TRACK, PdfSheetService.unitFor(relay),
                "a relay's marks are seconds");
        assertEquals(Event.EventType.UNIT_TRACK, PdfSheetService.unitFor(relayGroup("TRACK", twoTeams())),
                "and it stays seconds on a row that has not been migrated yet");
        assertEquals(Event.EventType.UNIT_FIELD, PdfSheetService.unitFor(field));
    }

    @Test
    @DisplayName("a relay sheet at its own category still renders one line per team, named")
    void oneLinePerTeamAtTheRelayCategory() throws Exception {
        EventGroupDTO group = relayGroup(EventCategory.RELAY.name(), twoTeams());

        List<String> sheetLines = lines(sheets.renderSheets(List.of(group)));
        String text = String.join("\n", sheetLines);

        assertTrue(sheetLines.contains("5A"), "the first team's name is its own line: " + text);
        assertTrue(sheetLines.contains("5B"), "and so is the second's: " + text);
        assertEquals(2, sheetLines.stream().filter(line -> line.equals("5A") || line.equals("5B")).count(),
                "one line per team, not one per runner: " + text);
        // The line is the team's, so no runner is named on it.
        for (String runner : List.of("Chan Tai Man", "Lee Siu Ming", "Wong Ka Yan", "Ho Cheuk Yiu")) {
            assertFalse(text.contains(runner),
                    "a runner must not be printed on a relay sheet, and " + runner + " is: " + text);
        }
    }

    @Test
    @DisplayName("a relay row that still says TRACK prints the same sheet while the migration waits")
    void theOldCategoryPrintsTheSameSheet() throws Exception {
        // Between deploying this build and running relay-category-migration.sql the
        // live relay rows still say TRACK. The sheet must not change shape because of
        // that: only the label the group carries differs, and the layout does not read
        // it beyond "is this a field event?".
        EventGroupDTO asRelay = relayGroup(EventCategory.RELAY.name(), twoTeams());
        EventGroupDTO asTrack = relayGroup(EventCategory.TRACK.name(), twoTeams());

        assertEquals(lines(sheets.renderSheets(List.of(asTrack))),
                lines(sheets.renderSheets(List.of(asRelay))),
                "the relay sheet is identical before and after the migration");
    }
}
