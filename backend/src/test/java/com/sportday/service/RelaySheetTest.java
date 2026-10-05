package com.sportday.service;

import com.sportday.dto.EnrollmentDTO;
import com.sportday.dto.EventGroupDTO;
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
 * A relay's marking sheet.
 *
 * <p>A 4x100M is scored by team — one time for the four runners together — so its
 * sheet has one line per <strong>team</strong>, and that line <strong>is</strong> the
 * team: the school confirmed it, and the line therefore carries the team's name and
 * nothing else. The runners are not printed on it, not beside the team and not on
 * lines of their own, because the one record box the line has is the team's one time.
 * An individual event — where a line is the athlete, whose name and student id are
 * printed — is untouched.</p>
 *
 * <p>A relay is only printed once it is <strong>ready</strong> — two teams, each
 * holding its four runners ({@link RelayReadiness}) — so the athlete-per-line fallback
 * a relay used to take when its teams had not been derived is now <em>unreachable</em>:
 * a relay with no teams is refused with the reason rather than rendered the other way
 * round. The tests below hold both halves of that.</p>
 */
class RelaySheetTest {

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

    private static EventGroupDTO relayGroup(List<EnrollmentDTO> athletes, String eventType) {
        return EventGroupDTO.builder()
                .id(11L)
                .eventId(5L)
                .eventName("Boys 4x100M Relay · A Grade")
                .eventType(eventType)
                .category(EventCategory.TRACK.name())
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

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** The sheet's text as trimmed, non-blank lines. */
    private static List<String> lines(String text) {
        return text.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
    }

    @Test
    @DisplayName("a relay sheet's line is the team: the team's name, and no runner's name")
    void aRelaySheetLineIsTheTeam() throws Exception {
        EventGroupDTO group = relayGroup(List.of(
                runner("S0001", "Chan Tai Man", "A", "5A"),
                runner("S0002", "Lee Siu Ming", "A", "5A"),
                runner("S0003", "Wong Ka Yan", "A", "5B"),
                runner("S0004", "Ho Cheuk Yiu", "A", "5B")), "RELAY_4X100M");

        String text = textOf(sheets.renderSheets(List.of(group)));
        List<String> sheetLines = lines(text);

        assertTrue(text.contains("5A"), "the first team's name is on the sheet: " + text);
        assertTrue(text.contains("5B"), "and the second's: " + text);
        // The line is the team's, so the team's name is all there is on it: a name
        // goes in the Name column and the other cells are left for the helper's pen.
        assertTrue(sheetLines.contains("5A"),
                "the team's name is a line of its own, holding nothing else: " + text);
        assertTrue(sheetLines.contains("5B"),
                "and so is the second team's: " + text);
        // The one that matters: no runner is named anywhere on a relay sheet.
        for (String runner : List.of("Chan Tai Man", "Lee Siu Ming", "Wong Ka Yan", "Ho Cheuk Yiu")) {
            assertFalse(text.contains(runner),
                    "a runner must not be printed on a relay sheet, and " + runner
                            + " is: " + text);
        }
        assertFalse(text.contains("Chan Tai Man, Lee Siu Ming"),
                "the runners are not joined onto the team's line either: " + text);
        // Four runners, two teams: the legs collapse into the team's one line.
        assertEquals(2, sheetLines.stream().filter(line -> line.equals("5A") || line.equals("5B")).count(),
                "one line per team, not one per athlete: " + text);
    }

    @Test
    @DisplayName("an individual event keeps one line per athlete, each with their name and id")
    void anIndividualSheetIsUnchanged() throws Exception {
        EventGroupDTO group = relayGroup(List.of(
                runner("S0001", "Chan Tai Man", "A", null),
                runner("S0002", "Lee Siu Ming", "A", null)), "RUN_100M");

        String text = textOf(sheets.renderSheets(List.of(group)));
        List<String> sheetLines = lines(text);

        assertTrue(text.contains("Chan Tai Man") && text.contains("Lee Siu Ming"),
                "both athletes are on it: " + text);
        assertFalse(text.contains("Chan Tai Man, Lee Siu Ming"),
                "but they are not run together onto one line: " + text);
        // Here the line IS the athlete, so each of them is printed with their own
        // student id and their own grade — this sheet must not change.
        for (String[] athlete : List.of(new String[]{"S0001", "Chan Tai Man"},
                new String[]{"S0002", "Lee Siu Ming"})) {
            String line = sheetLines.stream().filter(one -> one.contains(athlete[1]))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            athlete[1] + " is not printed on a line: " + text));
            assertTrue(line.contains(athlete[0]),
                    "the athlete's own line carries their student id: " + line);
            assertTrue(line.contains("A"), "and their grade: " + line);
        }
    }

    @Test
    @DisplayName("an undivided relay is refused with its reason, not printed as an athlete sheet")
    void anUndividedRelayIsRefused() {
        // Every relay event in the programme starts like this: no kind, so no teams.
        // Zero teams is not ready — a relay cannot be marked at all — so the sheet is
        // never drawn: it would have to fall back to one line per athlete, which scores
        // the race the wrong way. The relay is held back with the reason instead, and
        // the athlete-per-line fallback is unreachable for a relay.
        var event = com.sportday.entity.Event.builder()
                .id(5L)
                .name("Girls 4x100M Relay · A Grade")
                .type(com.sportday.entity.Event.EventType.RELAY_4X100M)
                .relayTeamKind(null)
                .build();

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> RelayReadiness.requireRelayIsReadyToMark(event, List.of()));

        assertTrue(refusal.getMessage().startsWith("Girls 4x100M Relay · A Grade has 0 team(s)"),
                "the refusal names the relay and what it has: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("at least 2 before its marks can be entered"),
                "and the rule it falls short of: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("Build another team first."),
                "and what to do about it: " + refusal.getMessage());
    }
}
