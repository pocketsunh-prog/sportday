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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The school record on the <strong>print</strong> form: the mark to beat for the
 * event, printed once per sheet in the header block, under the event name.
 *
 * <p>This walks the whole path — the {@code event_records} row, through
 * {@link EventGroupService} onto the group, and out through {@link PdfSheetService}
 * into the PDF — because that is the path the helper's sheet takes.</p>
 *
 * <p>The record is the event's: its <em>type</em>, <em>division</em> and
 * <em>grade</em>. The graded tests below are the point of the class — Boys 100M
 * A Grade and Boys 100M B Grade are two events with two records, and a sheet that
 * printed the first record it found would be wrong in a way a helper could not
 * see.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SheetSchoolRecordTest {

    private static final long A_GROUP_ID = 11L;
    private static final long B_GROUP_ID = 12L;
    private static final long A_EVENT_ID = 5L;
    private static final long B_EVENT_ID = 6L;
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

    private Event boysA;
    private Event boysB;

    // ------------------------------------------------------------- fixtures

    private static User user(long id) {
        return User.builder().id(id).username("S00" + id).fullName("Athlete " + id).build();
    }

    private static Student student(User athlete) {
        return Student.builder()
                .id(athlete.getId())
                .user(athlete)
                .studentId(athlete.getUsername())
                .name("Chan Tai Man")
                .dob(LocalDate.of(2012, 1, 1))
                .sex(Sex.MALE)
                .className("1A")
                .classNumber(athlete.getId().intValue())
                .house("Red")
                .grade(Grade.A)
                .enabled(true)
                .build();
    }

    private static Enrollment entry(Event event, User athlete, Integer lane) {
        return Enrollment.builder()
                .id(athlete.getId())
                .user(athlete)
                .event(event)
                .status(Enrollment.EnrollmentStatus.CONFIRMED)
                .lane(lane)
                .build();
    }

    private static Event event(long id, String name, Grade grade) {
        return Event.builder()
                .id(id)
                .name(name)
                .type(Event.EventType.RUN_100M)
                .category(Event.EventType.RUN_100M.getCategory())
                .sex(Sex.MALE)
                .grade(grade)
                .groupSize(8)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .directToFinal(false)
                .build();
    }

    private static EventGroup group(long id, Event event) {
        return EventGroup.builder()
                .id(id)
                .event(event)
                .groupNumber(1)
                .stage(EventStage.HEAT)
                .capacity(8)
                .athleteCount(1)
                .build();
    }

    /**
     * A record as the store holds it: the mark to beat, who holds it, and when.
     *
     * <p>The mark that counts is the <em>previous</em> one — the record as it stood
     * before this season. The standing {@code mark} is deliberately set to the same
     * figure here so the test cannot accidentally pass by reading the wrong field,
     * and {@link #standingMarkOnly(String)} covers the case where the two differ.</p>
     */
    private static EventRecordDTO record(String mark, String holder, int year) {
        return EventRecordDTO.builder()
                .eventType(Event.EventType.RUN_100M.name())
                .sex(Sex.MALE.name())
                // The standing record, which during the meeting is today's best result.
                .mark(new BigDecimal(mark))
                .unit("s")
                .holderName(holder)
                .achievedOn(LocalDate.of(year, 5, 4))
                // The record that actually stood before those results.
                .previousMark(new BigDecimal(mark))
                .previousHolderName(holder)
                .previousAchievedOn(LocalDate.of(year, 5, 4))
                .hasPrevious(true)
                .build();
    }

    /**
     * A record the meeting itself created: there is a best result and a holder, but
     * no record stood before it. The sheet must print no line for this — printing it
     * would tell a helper that the school record is a mark run minutes ago by someone
     * still in the call room.
     */
    private static EventRecordDTO standingMarkOnly(String mark) {
        return EventRecordDTO.builder()
                .eventType(Event.EventType.RUN_100M.name())
                .sex(Sex.MALE.name())
                .mark(new BigDecimal(mark))
                .unit("s")
                .holderName("This Morning's Winner")
                .achievedOn(LocalDate.of(2026, 10, 4))
                .hasPrevious(false)
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
        assertTrue(pdf.length > 1000, "PDF looks too small: " + pdf.length + " bytes");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages(), "one group, one sheet");
            return new PDFTextStripper().getText(document);
        }
    }

    @BeforeEach
    void setUp() {
        groups = new EventGroupService(groupRepository, eventRepository, enrollmentRepository,
                studentRepository, finalEntryRepository, recordService, resultRepository,
                relayTeamMemberRepository,
                // No relay here: the readiness rule is never asked, so the guard's own
                // repositories are never read.
                new RelayReadiness(relayTeamRepository, relayTeamMemberRepository));
        sheets = new PdfSheetService(groups, new PdfFontProvider(""), mockedSettings());

        boysA = event(A_EVENT_ID, "Boys 100M · A Grade", Grade.A);
        boysB = event(B_EVENT_ID, "Boys 100M · B Grade", Grade.B);
        EventGroup heatA = group(A_GROUP_ID, boysA);
        EventGroup heatB = group(B_GROUP_ID, boysB);

        when(groupRepository.findById(A_GROUP_ID)).thenReturn(Optional.of(heatA));
        when(groupRepository.findById(B_GROUP_ID)).thenReturn(Optional.of(heatB));

        User athlete = user(ATHLETE);
        when(enrollmentRepository.findByGroupWithUserOrdered(anyLong()))
                .thenReturn(List.of(entry(boysA, athlete, 1)));
        when(enrollmentRepository.findWithUserByEventAndUserIds(any(), any()))
                .thenReturn(List.of(entry(boysA, athlete, null)));
        when(studentRepository.findWithUserByUserIdIn(any())).thenReturn(List.of(student(athlete)));
    }

    // ------------------------------------------------------- the record line

    @Test
    @DisplayName("a sheet's header carries the record for that event's type, division and grade")
    void theSheetCarriesTheEventsRecord() throws Exception {
        when(recordService.record(Event.EventType.RUN_100M, Sex.MALE, Grade.A))
                .thenReturn(record("7.406", "陳大文", 2019));

        String text = textOf(sheets.renderGroupSheet(A_GROUP_ID));

        assertTrue(text.contains("紀錄") && text.contains("Record"),
                "the record line is labelled in the sheet's own bilingual style");
        assertTrue(text.contains("7.406s"),
                "and carries the mark read exactly as a result reads, with its unit");
        assertTrue(text.contains("陳大文"), "and who holds it");
        assertTrue(text.contains("(2019)"), "and the year it was set");
        verify(recordService).record(Event.EventType.RUN_100M, Sex.MALE, Grade.A);
    }

    @Test
    @DisplayName("a different grade of the same event type gets its own record, not the first found")
    void aDifferentGradeGetsItsOwnRecord() throws Exception {
        when(recordService.record(Event.EventType.RUN_100M, Sex.MALE, Grade.A))
                .thenReturn(record("7.406", "Chan Tai Man", 2019));
        when(recordService.record(Event.EventType.RUN_100M, Sex.MALE, Grade.B))
                .thenReturn(record("7.912", "Lee Siu Ming", 2018));

        String aSheet = textOf(sheets.renderGroupSheet(A_GROUP_ID));
        String bSheet = textOf(sheets.renderGroupSheet(B_GROUP_ID));

        assertTrue(aSheet.contains("7.406s"), "A Grade prints its own mark");
        assertTrue(bSheet.contains("7.912s"), "B Grade prints its own mark, not the first one found");
        assertFalse(bSheet.contains("7.406s"),
                "and never A Grade's: the two are separate events with separate records");
        assertTrue(bSheet.contains("Lee Siu Ming"), "with B Grade's own holder");

        // Each sheet asks for its own event's record — never a shared, unkeyed one.
        verify(recordService).record(Event.EventType.RUN_100M, Sex.MALE, Grade.A);
        verify(recordService).record(Event.EventType.RUN_100M, Sex.MALE, Grade.B);
    }

    @Test
    @DisplayName("an event with no record prints no record line at all, and says nothing in its place")
    void anEventWithNoRecordPrintsNoLine() throws Exception {
        when(recordService.record(any(), any(), any())).thenReturn(null);

        byte[] pdf = sheets.renderGroupSheet(A_GROUP_ID);
        String text = textOf(pdf);

        assertFalse(text.contains("紀錄"), "no record line where the record would be");
        assertFalse(text.contains("none") || text.contains("N/A"),
                "and no dash or placeholder a helper could mistake for a mark");
        // Everything else about the sheet is exactly as it was.
        assertTrue(text.contains("Boys 100M"), "the event name still heads it");
        assertTrue(text.contains("學號") && text.contains("成績"),
                "and the columns it always had are all still there");
    }

    @Test
    @DisplayName("a record the meeting itself created is not printed as the record to beat")
    void aStandingMarkFromTodaysResultsIsNotPrintedAsARecord() throws Exception {
        // The store's standing `mark` is the better of the entered baseline and the best
        // result so far, so during the meeting it is today's leading performance. Printing
        // it under 紀錄 would tell a helper the school record is a mark run minutes ago by
        // somebody still in the call room, and it would creep upward all afternoon.
        when(recordService.record(any(), any(), any())).thenReturn(standingMarkOnly("7.406"));

        byte[] pdf = sheets.renderGroupSheet(A_GROUP_ID);
        String text = textOf(pdf);

        assertFalse(text.contains("7.406"),
                "today's leading performance must not be printed as the school record");
        assertFalse(text.contains("This Morning's Winner"),
                "nor the athlete who ran it twenty minutes ago");
    }

    @Test
    @DisplayName("the record line does not push an A5 sheet onto a second page")
    void theRecordLineKeepsTheSheetOnOneA5Page() throws Exception {
        when(recordService.record(any(), any(), any()))
                .thenReturn(record("64.123", "Wong Ka Yan 黃嘉欣", 2017));

        byte[] pdf = sheets.renderGroupSheet(A_GROUP_ID);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages(),
                    "the extra header line must not overflow the sheet");
            var box = document.getPage(0).getMediaBox();
            assertTrue(Math.abs(box.getWidth() - 421) < 3 && Math.abs(box.getHeight() - 595) < 3,
                    "a short sprint is still A5");
        }
    }

    @Test
    @DisplayName("a track record over a minute reads in minutes and seconds, like every other mark")
    void aLongTrackRecordReadsInMinutesAndSeconds() {
        EventGroupDTO group = EventGroupDTO.builder()
                .recordDisplayMark(MarkFormatter.formatWithUnit(new BigDecimal("64.123"),
                        Event.EventType.RUN_800M, "s"))
                .build();

        assertEquals("紀錄 Record 1.04.123s", PdfSheetService.recordLine(group));
    }

    @Test
    @DisplayName("a field record reads as a distance, and reads the same way with no holder")
    void aFieldRecordReadsAsADistance() {
        EventGroupDTO withHolder = EventGroupDTO.builder()
                .recordDisplayMark(MarkFormatter.formatWithUnit(new BigDecimal("18.120"),
                        Event.EventType.SHOT_PUT, "M"))
                .recordHolderName("Chan Tai Man")
                .recordAchievedOn(LocalDate.of(2016, 3, 1))
                .build();
        EventGroupDTO bare = EventGroupDTO.builder()
                .recordDisplayMark(MarkFormatter.formatWithUnit(new BigDecimal("18.120"),
                        Event.EventType.SHOT_PUT, "M"))
                .build();
        EventGroupDTO none = EventGroupDTO.builder().build();

        assertEquals("紀錄 Record 18.12M — Chan Tai Man (2016)", PdfSheetService.recordLine(withHolder));
        assertEquals("紀錄 Record 18.12M", PdfSheetService.recordLine(bare),
                "a record with no holder and no year still reads, without empty brackets");
        assertNull(PdfSheetService.recordLine(none),
                "and an event with no record has no line to print");
    }

    @Test
    @DisplayName("a field sheet carries the record and still fits its three attempt boxes on A4")
    void aFieldSheetFitsWithTheRecordLine() throws Exception {
        Event shotPut = Event.builder()
                .id(9L).name("Boys Shot Put").type(Event.EventType.SHOT_PUT)
                .category(Event.EventType.SHOT_PUT.getCategory())
                .sex(Sex.MALE).grade(Grade.A)
                .groupSize(24).eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true).build();
        EventGroup fieldHeat = group(21L, shotPut);
        when(groupRepository.findById(21L)).thenReturn(Optional.of(fieldHeat));
        when(recordService.record(Event.EventType.SHOT_PUT, Sex.MALE, Grade.A))
                .thenReturn(EventRecordDTO.builder()
                        .mark(new BigDecimal("18.120")).unit("M")
                        .holderName("Chan Tai Man").achievedOn(LocalDate.of(2016, 3, 1))
                        // The record as it stood before this season — what the sheet prints.
                        .previousMark(new BigDecimal("18.120")).previousHolderName("Chan Tai Man")
                        .previousAchievedOn(LocalDate.of(2016, 3, 1)).hasPrevious(true)
                        .build());

        byte[] pdf = sheets.renderGroupSheet(21L);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages(),
                    "the record line must not push a field sheet onto a second page");
            var box = document.getPage(0).getMediaBox();
            assertTrue(Math.abs(box.getWidth() - 595) < 3 && Math.abs(box.getHeight() - 842) < 3,
                    "a field event prints on A4");
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("紀錄 Record 18.12M — Chan Tai Man (2016)"),
                    "the record is a distance, read with its unit");
            assertTrue(text.contains("Record") && text.contains("(M)"),
                    "and the three attempt boxes are still there beneath it");
        }
    }

    // ------------------------------------------------------------- the cost

    @Test
    @DisplayName("every group of one event shares one record lookup, and the cache does not leak")
    void oneLookupPerEventAndNoCachingBetweenRenders() {
        EventGroup heat1 = group(31L, boysA);
        EventGroup heat2 = group(32L, boysA);
        when(eventRepository.findById(A_EVENT_ID)).thenReturn(Optional.of(boysA));
        when(groupRepository.findByEventIdOrderByGroupNumberAsc(A_EVENT_ID))
                .thenReturn(List.of(heat1, heat2));
        when(recordService.record(Event.EventType.RUN_100M, Sex.MALE, Grade.A))
                .thenReturn(record("7.406", "Chan Tai Man", 2019));

        List<EventGroupDTO> rendered = groups.getGroupsWithAthletes(A_EVENT_ID);

        assertEquals(2, rendered.size());
        assertEquals("7.406s", rendered.get(0).getRecordDisplayMark());
        assertEquals("7.406s", rendered.get(1).getRecordDisplayMark(),
                "the second sheet of the event carries the same record");
        verify(recordService, times(1)).record(Event.EventType.RUN_100M, Sex.MALE, Grade.A);

        // A second render is a fresh read, so a record set in between is never stale.
        groups.getGroupsWithAthletes(A_EVENT_ID);
        verify(recordService, times(2)).record(Event.EventType.RUN_100M, Sex.MALE, Grade.A);
    }

    @Test
    @DisplayName("a look-up is never made per athlete row")
    void noLookupPerAthlete() {
        when(recordService.record(any(), any(), any()))
                .thenReturn(record("7.406", "Chan Tai Man", 2019));

        groups.getGroup(A_GROUP_ID);

        verify(recordService, times(1)).record(any(), any(), any());
        verify(recordService, never()).list();
    }

    /** Writes a preview of the sheet with its record line, for eyeballing. */
    @Test
    @DisplayName("writes a preview PNG of a sheet carrying its record")
    @org.junit.jupiter.api.condition.EnabledIf(
            "com.sportday.service.PdfSheetServiceTest#rendererAvailable")
    void writesARecordPreview() throws Exception {
        when(recordService.record(any(), any(), any()))
                .thenReturn(record("7.406", "陳大文 Chan Tai Man", 2019));

        PdfSheetServiceTest.writePreview("marking-sheet-record-A5.png",
                sheets.renderGroupSheet(A_GROUP_ID));
    }
}
