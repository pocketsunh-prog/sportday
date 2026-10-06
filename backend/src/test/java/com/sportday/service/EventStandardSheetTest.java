package com.sportday.service;

import com.sportday.dto.EventGroupDTO;
import com.sportday.dto.EventRecordDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventGroup;
import com.sportday.entity.EventStage;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.Student;
import com.sportday.entity.User;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.FinalEntryRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * The required standard on the <strong>print</strong> form: one line in the sheet's
 * header, directly under the school record, for an event that has one.
 *
 * <p>This walks the whole path — the number on the {@code events} row, through
 * {@link EventGroupService} onto the group, and out through {@link PdfSheetService}
 * into the PDF — because that is the path a helper's sheet takes.</p>
 *
 * <h2>Why the header and not a column</h2>
 * <p>A column would have changed every sheet in the programme: the column count and
 * the width array decide the layout of all of them, including the ones for events
 * that carry no standard at all (every sprint and every relay). A header line is
 * additive — it is drawn only when the event has a standard — so an event without
 * one prints the sheet it always printed. The assertions below are the proof of
 * that: the standard's sheet carries one more line and nothing else moves, and the
 * plain sheet contains none of the new wording.</p>
 *
 * <p>The record line keeps its own place above the standard, and both are in the
 * sheet's bilingual style: {@code 紀錄 Record 7.406s} and
 * {@code 標準 Standard 64.123 s}.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventStandardSheetTest {

    private static final long GROUP_ID = 11L;
    private static final long EVENT_ID = 5L;
    private static final long ATHLETE = 61L;

    @Mock private EventGroupRepository groupRepository;
    @Mock private EventRepository eventRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private FinalEntryRepository finalEntryRepository;
    @Mock private RecordService recordService;
    @Mock private EventResultRepository resultRepository;
    @Mock private com.sportday.repository.RelayTeamMemberRepository relayTeamMemberRepository;
    @Mock private com.sportday.repository.RelayTeamRepository relayTeamRepository;

    private EventGroupService groups;
    private PdfSheetService sheets;

    // ------------------------------------------------------------- fixtures

    private static Event fourHundred(String standard) {
        return Event.builder()
                .id(EVENT_ID)
                .name("Boys 400M · A Grade")
                .type(Event.EventType.RUN_400M)
                .category(Event.EventType.RUN_400M.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.A)
                .standard(standard == null ? null : new BigDecimal(standard))
                .groupSize(8)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .build();
    }

    private static EventGroup group(Event event) {
        return EventGroup.builder()
                .id(GROUP_ID)
                .event(event)
                .groupNumber(1)
                .stage(EventStage.HEAT)
                .capacity(8)
                .athleteCount(1)
                .build();
    }

    private static EventRecordDTO record() {
        return EventRecordDTO.builder()
                .eventType(Event.EventType.RUN_400M.name())
                .sex(Sex.MALE.name())
                .mark(new BigDecimal("54.321"))
                .unit("s")
                .holderName("Chan Tai Man")
                .achievedOn(LocalDate.of(2019, 5, 4))
                .previousMark(new BigDecimal("54.321"))
                .previousHolderName("Chan Tai Man")
                .previousAchievedOn(LocalDate.of(2019, 5, 4))
                .hasPrevious(true)
                .build();
    }

    private static SettingsService mockedSettings() {
        SettingsService settings = org.mockito.Mockito.mock(SettingsService.class);
        org.mockito.Mockito.when(settings.get())
                .thenReturn(com.sportday.entity.SportDaySettings.defaults());
        return settings;
    }

    private static String textOf(byte[] pdf) throws Exception {
        assertNotNull(pdf);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages(), "one group, one sheet");
            var box = document.getPage(0).getMediaBox();
            assertTrue(Math.abs(box.getWidth() - 421) < 3 && Math.abs(box.getHeight() - 595) < 3,
                    "a 400M still prints on A5, standard or no standard");
            return new PDFTextStripper().getText(document);
        }
    }

    @BeforeEach
    void setUp() {
        groups = new EventGroupService(groupRepository, eventRepository, enrollmentRepository,
                studentRepository, finalEntryRepository, recordService, resultRepository,
                relayTeamMemberRepository,
                new RelayReadiness(relayTeamRepository, relayTeamMemberRepository));
        sheets = new PdfSheetService(groups, new PdfFontProvider(""), mockedSettings());
    }

    /** One athlete entered in the event's single heat, with the event's record set. */
    private void fixture(Event event) {
        EventGroup heat = group(event);
        User athlete = User.builder().id(ATHLETE).username("S0061").fullName("Chan Tai Man").build();
        Student student = Student.builder()
                .id(ATHLETE)
                .user(athlete)
                .studentId(athlete.getUsername())
                .name("Chan Tai Man")
                .dob(LocalDate.of(2012, 1, 1))
                .sex(Sex.MALE)
                .className("1A")
                .classNumber(1)
                .house("Red")
                .grade(Grade.A)
                .enabled(true)
                .build();
        Enrollment entry = Enrollment.builder()
                .id(ATHLETE)
                .user(athlete)
                .event(event)
                .status(Enrollment.EnrollmentStatus.CONFIRMED)
                .lane(1)
                .build();

        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(heat));
        when(enrollmentRepository.findByGroupWithUserOrdered(anyLong())).thenReturn(List.of(entry));
        when(enrollmentRepository.findWithUserByEventAndUserIds(any(), any())).thenReturn(List.of(entry));
        when(studentRepository.findWithUserByUserIdIn(any())).thenReturn(List.of(student));
        when(recordService.record(Event.EventType.RUN_400M, Sex.MALE, Grade.A)).thenReturn(record());
    }

    // -------------------------------------------------------------- the line

    @Test
    @DisplayName("a sheet for an event with a standard prints it under the record, in the sheet's own style")
    void theStandardIsPrintedInTheHeader() throws Exception {
        fixture(fourHundred("64.123"));

        String text = textOf(sheets.renderGroupSheet(GROUP_ID));

        assertTrue(text.contains("標準 Standard 64.123 s"),
                "the standard is on the sheet with its unit: " + text);
        assertTrue(text.contains("紀錄 Record 0.54.321s"),
                "and the record it sits under is still there, in the shape a 400M is timed in");
    }

    @Test
    @DisplayName("the standard comes from the event's own type, so a field event reads in metres")
    void aFieldStandardReadsInMetres() throws Exception {
        Event shotPut = Event.builder()
                .id(EVENT_ID).name("Boys Shot Put · A Grade")
                .type(Event.EventType.SHOT_PUT)
                .category(Event.EventType.SHOT_PUT.getCategory())
                .sex(Sex.MALE).grade(Grade.A)
                .standard(new BigDecimal("12.5"))
                .groupSize(24)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .build();
        fixture(shotPut);
        when(recordService.record(Event.EventType.SHOT_PUT, Sex.MALE, Grade.A)).thenReturn(null);

        byte[] pdf = sheets.renderGroupSheet(GROUP_ID);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages(), "a field sheet still fits on one page");
            var box = document.getPage(0).getMediaBox();
            assertTrue(Math.abs(box.getWidth() - 595) < 3 && Math.abs(box.getHeight() - 842) < 3,
                    "and still prints on A4");
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("標準 Standard 12.5 M"), text);
            assertTrue(text.contains("Record") && text.contains("(M)"),
                    "with the three attempt boxes still beneath it");
        }
    }

    @Test
    @DisplayName("an event with no standard prints the sheet it always printed, with none of the new wording")
    void anEventWithNoStandardPrintsThePlainSheet() throws Exception {
        fixture(fourHundred(null));

        String plain = textOf(sheets.renderGroupSheet(GROUP_ID));

        assertFalse(plain.contains("標準"), "no standard line where it would be: " + plain);
        assertFalse(plain.contains("Standard"), "and nothing in its place");
        // Everything the sheet always had is still there, exactly as before.
        assertTrue(plain.contains("Boys 400M"), "the event name still heads it");
        for (String column : List.of("學號", "姓名", "級別", "成績", "備註")) {
            assertTrue(plain.contains(column), column + " is still on the sheet");
        }
        assertTrue(plain.contains("紀錄 Record 0.54.321s"), "and so is the record line");
    }

    @Test
    @DisplayName("the standard adds one header line and moves nothing else on the sheet")
    void theStandardAddsOneLineAndChangesNothingElse() throws Exception {
        fixture(fourHundred("64.123"));
        String withStandard = textOf(sheets.renderGroupSheet(GROUP_ID));

        fixture(fourHundred(null));
        String withoutStandard = textOf(sheets.renderGroupSheet(GROUP_ID));

        assertEquals(withoutStandard.lines().count() + 1, withStandard.lines().count(),
                "one extra line — the standard — and not a line more");
        assertEquals(withStandard.replace("標準 Standard 64.123 s", "").replaceAll("(?m)^\\s*$\\R", ""),
                withoutStandard.replaceAll("(?m)^\\s*$\\R", ""),
                "and with that one line taken out, the two sheets read identically");
    }

    @Test
    @DisplayName("the group the renderer is handed carries the standard, or carries nothing at all")
    void theGroupCarriesTheStandardOnlyWhenThereIsOne() {
        fixture(fourHundred("64.123"));
        EventGroupDTO described = groups.getGroup(GROUP_ID);
        assertEquals("64.123 s", described.getStandardLabel());

        fixture(fourHundred(null));
        EventGroupDTO plain = groups.getGroup(GROUP_ID);
        assertNull(plain.getStandardLabel(), "no standard on the event, no label on the group");
        assertNull(PdfSheetService.standardLine(plain), "and so no line for the renderer to draw");
    }

    @Test
    @DisplayName("a standard left on an event that does not carry one is never printed")
    void aStandardOnANonQualifyingEventIsNotPrinted() throws Exception {
        // A number left on a sprint row by an older build: the sheet must not print a
        // standard for an event the school does not set one on.
        Event hundred = Event.builder()
                .id(EVENT_ID).name("Boys 100M · A Grade")
                .type(Event.EventType.RUN_100M)
                .category(Event.EventType.RUN_100M.getCategory())
                .sex(Sex.MALE).grade(Grade.A)
                .standard(new BigDecimal("11.5"))
                .groupSize(8)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .build();
        fixture(hundred);
        when(recordService.record(any(), any(), any())).thenReturn(null);

        String text = textOf(sheets.renderGroupSheet(GROUP_ID));

        assertFalse(text.contains("標準"), "a 100M prints no standard: " + text);
        assertNull(groups.getGroup(GROUP_ID).getStandardLabel());
    }
}
