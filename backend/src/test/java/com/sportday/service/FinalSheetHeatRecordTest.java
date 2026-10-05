package com.sportday.service;

import com.sportday.dto.EnrollmentDTO;
import com.sportday.dto.EventGroupDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventGroup;
import com.sportday.entity.EventResult;
import com.sportday.entity.EventStage;
import com.sportday.entity.FinalEntry;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The heat record on the <strong>print</strong> form: a final's marking sheet shows
 * what each finalist ran in their heat, beside the blank boxes the final is written
 * in.
 *
 * <p>This walks the whole path — the {@code HEAT} {@link EventResult}s in the
 * database, through {@link EventGroupService} onto the final group's roster, and
 * out through {@link PdfSheetService} into the PDF — because that is the path the
 * official's sheet takes. The heat reads as it reads anywhere else
 * ({@link MarkFormatter}), a disqualified heat reads {@code DQ}, and a
 * <em>heat</em> sheet is untouched: no heat column, and no heat lookup at all.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FinalSheetHeatRecordTest {

    private static final long EVENT_ID = 2L;
    private static final long HEAT_GROUP_ID = 1L;
    private static final long FINAL_GROUP_ID = 99L;
    private static final long RAN_THE_HEAT = 61L;
    private static final long DISQUALIFIED = 62L;

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

    private Event sprint;
    private EventGroup finalGroup;

    // ------------------------------------------------------------- fixtures

    private static User user(long id) {
        return User.builder().id(id).username("S00" + id).fullName("Athlete " + id).build();
    }

    private static Student student(User athlete) {
        return Student.builder()
                .id(athlete.getId())
                .user(athlete)
                .studentId(athlete.getUsername())
                .name("Athlete " + athlete.getId())
                .dob(LocalDate.of(2012, 1, 1))
                .sex(Sex.MALE)
                .className("1A")
                .classNumber(athlete.getId().intValue())
                .house("Red")
                .grade(Grade.C)
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

        sprint = Event.builder()
                .id(EVENT_ID)
                .name("Boys 100M")
                .type(Event.EventType.RUN_100M)
                .category(Event.EventType.RUN_100M.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.B)
                .groupSize(8)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .directToFinal(false)
                .build();

        User ranIt = user(RAN_THE_HEAT);
        User disqualified = user(DISQUALIFIED);
        finalGroup = EventGroup.builder()
                .id(FINAL_GROUP_ID)
                .event(sprint)
                .groupNumber(EventGroup.FINAL_GROUP_NUMBER)
                .stage(EventStage.FINAL)
                .capacity(8)
                .athleteCount(2)
                .build();
        EventGroup heat = EventGroup.builder()
                .id(HEAT_GROUP_ID)
                .event(sprint)
                .groupNumber(1)
                .stage(EventStage.HEAT)
                .capacity(8)
                .athleteCount(2)
                .build();

        when(groupRepository.findById(FINAL_GROUP_ID)).thenReturn(Optional.of(finalGroup));
        when(groupRepository.findById(HEAT_GROUP_ID)).thenReturn(Optional.of(heat));

        // The final's field, and the heats both athletes ran.
        when(finalEntryRepository.findByGroupIdOrderByLaneAsc(FINAL_GROUP_ID)).thenReturn(List.of(
                FinalEntry.builder().id(1L).group(finalGroup).user(ranIt).lane(1).seed(1)
                        .seedMark(new BigDecimal("11.860")).seedUnit("s").build(),
                FinalEntry.builder().id(2L).group(finalGroup).user(disqualified).lane(2).seed(2)
                        .build()));
        when(enrollmentRepository.findByGroupWithUserOrdered(HEAT_GROUP_ID)).thenReturn(List.of(
                entry(sprint, ranIt, 1), entry(sprint, disqualified, 2)));
        when(enrollmentRepository.findWithUserByEventAndUserIds(any(), any())).thenReturn(List.of(
                entry(sprint, ranIt, null), entry(sprint, disqualified, null)));
        when(studentRepository.findWithUserByUserIdIn(any()))
                .thenReturn(List.of(student(ranIt), student(disqualified)));

        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(EVENT_ID, EventStage.HEAT))
                .thenReturn(List.of(
                        EventResult.builder().id(101L).user(ranIt).event(sprint)
                                .stage(EventStage.HEAT).outcome(EventResult.Outcome.RESULT)
                                .mark(new BigDecimal("11.860")).unit("s").build(),
                        EventResult.builder().id(102L).user(disqualified).event(sprint)
                                .stage(EventStage.HEAT).outcome(EventResult.Outcome.DQ).build()));
    }

    private static SettingsService mockedSettings() {
        SettingsService settings = org.mockito.Mockito.mock(SettingsService.class);
        org.mockito.Mockito.when(settings.get())
                .thenReturn(com.sportday.entity.SportDaySettings.defaults());
        return settings;
    }

    // ---------------------------------------------------------- the roster

    @Test
    @DisplayName("a final's roster carries the heat each finalist ran, read the one way")
    void theFinalRosterCarriesTheHeat() {
        List<EnrollmentDTO> roster = groups.athletesOf(finalGroup);

        assertEquals(2, roster.size());
        EnrollmentDTO ranIt = roster.get(0);
        assertEquals(RAN_THE_HEAT, ranIt.getUserId().longValue());
        assertEquals(0, new BigDecimal("11.860").compareTo(ranIt.getHeatMark()));
        assertEquals("RESULT", ranIt.getHeatOutcome());
        assertEquals("11.86s", ranIt.getHeatDisplayMark());

        EnrollmentDTO disqualified = roster.get(1);
        assertNull(disqualified.getHeatMark(), "a disqualified heat has no mark");
        assertEquals("DQ", disqualified.getHeatOutcome());
        assertEquals("DQ", disqualified.getHeatDisplayMark(),
                "and the heat column can print that as it stands");

        // One lookup for the whole roster, not one per athlete.
        verify(resultRepository, org.mockito.Mockito.times(1))
                .findByEventIdAndStageOrderByMarkAsc(EVENT_ID, EventStage.HEAT);
    }

    // ------------------------------------------------------------ the PDF

    @Test
    @DisplayName("a final sheet prints each finalist's heat record, DQ and all")
    void aFinalSheetPrintsTheHeatRecord() throws Exception {
        String text = textOf(sheets.renderGroupSheet(FINAL_GROUP_ID));

        assertTrue(text.contains("初賽") && text.contains("Heat"),
                "the final sheet has a heat record column");
        assertTrue(text.contains("11.86s"),
                "carrying what the athlete actually ran in their heat");
        assertTrue(text.contains("DQ"), "and DQ where the heat produced no number");
        assertTrue(text.contains("決賽"), "the sheet is still unmistakably the final's");
        assertTrue(text.contains("成績") && text.contains("備註"),
                "with the record and remark columns the official writes in");
    }

    @Test
    @DisplayName("a heat sheet is untouched: no heat column, and no heat lookup behind it")
    void aHeatSheetIsUntouched() throws Exception {
        String text = textOf(sheets.renderGroupSheet(HEAT_GROUP_ID));

        assertFalse(text.contains("初賽"),
                "a heat sheet does not show the heat: it is the heat");
        assertTrue(text.contains("S0061"), "and still lists its runners");

        verify(resultRepository, never())
                .findByEventIdAndStageOrderByMarkAsc(anyLong(), any());
    }

    @Test
    @DisplayName("the final's rasteriser keeps the A5 page and one page, extra column and all")
    void theFinalSheetKeepsItsPaper() throws Exception {
        byte[] pdf = sheets.renderGroupSheet(FINAL_GROUP_ID);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());
            var box = document.getPage(0).getMediaBox();
            assertTrue(Math.abs(box.getWidth() - 421) < 3 && Math.abs(box.getHeight() - 595) < 3,
                    "a short sprint's final prints on A5, as its heats do");
        }
    }

    @Test
    @DisplayName("a heat group's roster carries no heat record, so nothing can print one")
    void aHeatRosterCarriesNoHeat() {
        EventGroupDTO heat = groups.getGroup(HEAT_GROUP_ID);

        assertEquals("HEAT", heat.getStage());
        assertEquals(2, heat.getAthletes().size());
        for (EnrollmentDTO athlete : heat.getAthletes()) {
            assertNull(athlete.getHeatMark(), "a heat has no earlier heat to show");
            assertNull(athlete.getHeatOutcome());
            assertNull(athlete.getHeatDisplayMark());
        }
    }
}
