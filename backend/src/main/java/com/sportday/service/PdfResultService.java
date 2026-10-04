package com.sportday.service;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.sportday.dto.ChampionsDTO;
import com.sportday.dto.EnrollmentDTO;
import com.sportday.dto.EventGroupDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventResult;
import com.sportday.entity.EventStage;
import com.sportday.entity.Sex;
import com.sportday.entity.SportDaySettings;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders results as PDF — the sheet that goes on the results board.
 *
 * <p>Where a marking sheet is blank for a helper to write on, a results sheet is the
 * opposite: it carries what has already been recorded, in placings order, so a
 * result can be read off a wall rather than a screen.</p>
 *
 * <p>Columns are place, student id, name, grade, class, house, result and points.
 * The result reads the way the sport writes it — {@code 14.123s}, {@code 1.04.123s},
 * {@code 18.12M} — because a wall sheet is read, not compared.</p>
 *
 * <p>Where an event ran heats and then a final, both are printed: the heats first,
 * one sub-table per heat under {@code 初賽 Heats}, and then the final's placings
 * under {@code 決賽 Final}. The heats are qualifying only, so they carry no points
 * column — points belong to the stage that decides them, which is the final. An
 * event that ran one straight group, and one whose final has been drawn but not yet
 * run, prints exactly one table as it always has.</p>
 *
 * <p>One event fills a page or two; the whole programme keeps flowing, with the
 * column headings repeating at the top of every page so a sheet torn off still
 * makes sense.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfResultService {

    private static final DateTimeFormatter SHEET_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final String[] COLUMNS = {
            "名次 Place", "學號 Student ID", "姓名 Name", "級別 Grade",
            "班別 Class", "社 House", "成績 Result", "分數 Points"
    };

    /** Column widths, summing to 100. */
    private static final float[] WIDTHS = {8f, 13f, 17f, 9f, 10f, 11f, 17f, 9f};

    /**
     * The heats table — the final's columns without points, because a heat does not
     * score: it only decides who reaches the final.
     */
    private static final String[] HEAT_COLUMNS = {
            "名次 Place", "學號 Student ID", "姓名 Name", "級別 Grade",
            "班別 Class", "社 House", "成績 Result"
    };

    private static final float[] HEAT_WIDTHS = {8f, 13f, 17f, 9f, 10f, 11f, 17f};

    private static final String HEATS_HEADING = "初賽 Heats";
    private static final String FINAL_HEADING = "決賽 Final";

    private final ChampionService championService;
    private final EventRepository eventRepository;
    private final EventResultRepository resultRepository;
    private final EventGroupService eventGroupService;
    private final PdfFontProvider fonts;
    private final SettingsService settingsService;

    /** One event's results, in placings order. */
    @Transactional(readOnly = true)
    public byte[] renderEventResults(Long eventId) {
        ChampionsDTO.EventStandingsDTO standings = championService.standingsFor(eventId);
        if (standings == null || standings.getPlacings() == null || standings.getPlacings().isEmpty()) {
            throw new IllegalStateException(
                    "This event has no results recorded yet, so there is nothing to print.");
        }
        return render(List.of(sheetFor(standings)), null);
    }

    /**
     * Every event that has results, in programme order, as one document.
     *
     * @param sex      restrict to one division, or null for both
     * @param category restrict to track or field, or null for both
     */
    @Transactional(readOnly = true)
    public byte[] renderProgrammeResults(Sex sex, EventCategory category) {
        List<Event> events = eventRepository.findAll().stream()
                .sorted(EventService.EVENT_ORDER)
                .filter(event -> sex == null || event.getSex() == sex)
                .filter(event -> category == null || event.getCategoryOrDefault() == category)
                .toList();

        List<EventSheet> withResults = new ArrayList<>();
        for (Event event : events) {
            try {
                ChampionsDTO.EventStandingsDTO standings = championService.standingsFor(event.getId());
                if (standings != null && standings.getPlacings() != null
                        && !standings.getPlacings().isEmpty()) {
                    withResults.add(sheetFor(standings));
                }
            } catch (RuntimeException ex) {
                // One unreadable event must not cost the school the whole print run.
                log.warn("Skipping event {} in the results PDF: {}",
                        event.getId(), ex.getMessage());
            }
        }
        if (withResults.isEmpty()) {
            throw new IllegalStateException(
                    "No results have been recorded yet, so there is nothing to print.");
        }
        String scope = describeScope(sex, category);
        return render(withResults, scope);
    }

    // ----------------------------------------------------------------- render

    private byte[] render(List<EventSheet> sheets, String scope) {
        Document document = new Document(PageSize.A4, 36f, 36f, 40f, 36f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            addHeading(document, scope);
            for (EventSheet sheet : sheets) {
                addEvent(document, sheet);
            }
            document.close();
        } catch (DocumentException ex) {
            throw new IllegalStateException("Failed to build the results PDF", ex);
        }
        return out.toByteArray();
    }

    private void addHeading(Document document, String scope) throws DocumentException {
        SportDaySettings settings = settingsService.get();

        Paragraph heading = new Paragraph();
        heading.setAlignment(Element.ALIGN_CENTER);
        String schoolName = schoolLine(settings);
        if (schoolName != null) {
            heading.add(new Chunk(schoolName, fonts.boldFont(12f)));
            heading.add(Chunk.NEWLINE);
        }
        String title = settings.getSportDayTitle();
        if (title == null || title.isBlank()) {
            heading.add(new Chunk("田徑運動會成績表", fonts.boldFont(16f)));
            heading.add(Chunk.NEWLINE);
            heading.add(new Chunk("Sport Day Results", fonts.font(10f)));
        } else {
            heading.add(new Chunk(title, fonts.boldFont(16f)));
            heading.add(Chunk.NEWLINE);
            heading.add(new Chunk("成績表 Results", fonts.font(10f)));
        }
        heading.setSpacingAfter(4f);
        document.add(heading);

        if (scope != null) {
            Paragraph line = new Paragraph(scope, fonts.font(9.5f));
            line.setAlignment(Element.ALIGN_CENTER);
            line.setSpacingAfter(8f);
            document.add(line);
        }
    }

    private void addEvent(Document document, EventSheet sheet) throws DocumentException {
        ChampionsDTO.EventStandingsDTO standings = sheet.standings();

        Paragraph title = new Paragraph();
        title.setSpacingBefore(6f);
        title.setSpacingAfter(3f);
        title.add(new Chunk(nullSafe(standings.getEventName()), fonts.boldFont(12f)));

        StringBuilder meta = new StringBuilder();
        meta.append("項目 Event: ").append(nullSafe(standings.getEventTypeLabel()));
        meta.append("  |  ").append(nullSafe(standings.getCategoryLabel()));
        meta.append("  |  ").append(nullSafe(standings.getSexLabel()));
        if (standings.getEventDate() != null) {
            meta.append("  |  日期 Date: ").append(standings.getEventDate().format(SHEET_DATE));
        }
        meta.append("  |  計分 Stage: ").append(nullSafe(standings.getScoringStage()));
        if (standings.isRelay()) {
            meta.append("  |  接力 Relay — 分數歸社");
        }
        title.add(Chunk.NEWLINE);
        title.add(new Chunk(meta.toString(), fonts.font(8.5f)));
        document.add(title);

        // Heats and a final are two parts of one event, so they are headed apart: a
        // reader must be able to tell a heat time from the final that decided it.
        if (!sheet.heats().isEmpty()) {
            addHeats(document, sheet.heats());
            addSectionHeading(document, FINAL_HEADING, 8f);
        }
        addPlacings(document, standings);
    }

    /** The heats behind a final, one sub-table per heat in heat order. */
    private void addHeats(Document document, List<HeatSheet> heats) throws DocumentException {
        addSectionHeading(document, HEATS_HEADING, 6f);
        for (HeatSheet heat : heats) {
            Paragraph label = new Paragraph(new Chunk(nullSafe(heat.label()), fonts.boldFont(9.5f)));
            label.setSpacingBefore(3f);
            label.setSpacingAfter(2f);
            document.add(label);

            PdfPTable table = new PdfPTable(HEAT_COLUMNS.length);
            table.setWidthPercentage(100f);
            table.setWidths(HEAT_WIDTHS);
            // Repeat the headings on every page, as the final's table does.
            table.setHeaderRows(1);
            for (String column : HEAT_COLUMNS) {
                table.addCell(headingCell(column));
            }
            for (HeatRow row : heat.rows()) {
                EnrollmentDTO athlete = row.athlete();
                table.addCell(bodyCell(row.place(), Element.ALIGN_CENTER, false));
                table.addCell(bodyCell(nullSafe(athlete.getStudentRef()), Element.ALIGN_LEFT, false));
                table.addCell(bodyCell(nullSafe(athlete.getName()), Element.ALIGN_LEFT, false));
                table.addCell(bodyCell(nullSafe(athlete.getGrade()), Element.ALIGN_CENTER, false));
                table.addCell(bodyCell(nullSafe(athlete.getClassName()), Element.ALIGN_CENTER, false));
                table.addCell(bodyCell(nullSafe(athlete.getHouse()), Element.ALIGN_LEFT, false));
                // The result reads as the sport writes it: 14.123s, 18.12M.
                table.addCell(bodyCell(row.result(), Element.ALIGN_RIGHT, false));
            }
            document.add(table);
        }
    }

    /** The scoring stage's placings — points and the school-record star included. */
    private void addPlacings(Document document, ChampionsDTO.EventStandingsDTO standings)
            throws DocumentException {
        PdfPTable table = new PdfPTable(COLUMNS.length);
        table.setWidthPercentage(100f);
        table.setWidths(WIDTHS);
        // Repeat the headings on every page, so a sheet lifted off the board still
        // says what its columns are.
        table.setHeaderRows(1);
        for (String column : COLUMNS) {
            table.addCell(headingCell(column));
        }
        for (ChampionsDTO.PlacingDTO placing : standings.getPlacings()) {
            table.addCell(bodyCell(String.valueOf(placing.getPlace()), Element.ALIGN_CENTER, false));
            table.addCell(bodyCell(nullSafe(placing.getStudentRef()), Element.ALIGN_LEFT, false));
            table.addCell(bodyCell(nullSafe(placing.getName()), Element.ALIGN_LEFT, false));
            table.addCell(bodyCell(nullSafe(placing.getGrade()), Element.ALIGN_CENTER, false));
            table.addCell(bodyCell(nullSafe(placing.getClassName()), Element.ALIGN_CENTER, false));
            table.addCell(bodyCell(nullSafe(placing.getHouse()), Element.ALIGN_LEFT, false));
            // The result reads as the sport writes it: 14.123s, 1.04.123s, 18.12M.
            table.addCell(bodyCell(resultText(placing), Element.ALIGN_RIGHT,
                    placing.isSchoolRecord()));
            table.addCell(bodyCell(String.valueOf(placing.getPoints()), Element.ALIGN_CENTER, false));
        }
        document.add(table);
    }

    private void addSectionHeading(Document document, String text, float spacingBefore)
            throws DocumentException {
        Paragraph heading = new Paragraph(new Chunk(text, fonts.boldFont(10.5f)));
        heading.setSpacingBefore(spacingBefore);
        heading.setSpacingAfter(2f);
        document.add(heading);
    }

    /** The result with its unit, noting a school record beside it. */
    private static String resultText(ChampionsDTO.PlacingDTO placing) {
        String display = placing.getDisplayMark();
        if (display == null) {
            display = placing.getMark() == null ? "-" : placing.getMark().toPlainString();
        }
        return placing.isSchoolRecord() ? display + " ★" : display;
    }

    // ------------------------------------------------------------------ heats

    /** One event's section: the scoring stage, plus the heats behind a final. */
    private record EventSheet(ChampionsDTO.EventStandingsDTO standings, List<HeatSheet> heats) {
    }

    /** One heat of an event, with its athletes ranked within it. */
    private record HeatSheet(String label, List<HeatRow> rows) {
    }

    /** One athlete's line in a heat: the place they took in it, and their mark. */
    private record HeatRow(String place, EnrollmentDTO athlete, String result) {
    }

    private EventSheet sheetFor(ChampionsDTO.EventStandingsDTO standings) {
        return new EventSheet(standings, heatsBehind(standings));
    }

    /**
     * The heats behind a final, or an empty list when there is nothing to separate.
     *
     * <p>Both stages are printed only when a final was <em>actually run</em>: a
     * final group exists and it has marks, which is what makes the final the
     * scoring stage. An event that runs one straight group — and one whose final
     * has been drawn but not yet run — prints a single table, exactly as before.</p>
     */
    private List<HeatSheet> heatsBehind(ChampionsDTO.EventStandingsDTO standings) {
        if (standings == null || standings.getEventId() == null
                || !standings.isHasFinal()
                || !EventStage.FINAL.name().equals(standings.getScoringStage())) {
            return List.of();
        }

        List<EventGroupDTO> heats = new ArrayList<>();
        for (EventGroupDTO group : eventGroupService.getGroupsWithAthletes(standings.getEventId())) {
            if (EventStage.FINAL.name().equalsIgnoreCase(group.getStage())) {
                continue;
            }
            if (group.getAthletes() == null || group.getAthletes().isEmpty()) {
                continue;
            }
            heats.add(group);
        }
        if (heats.isEmpty()) {
            return List.of();
        }

        Event.EventType type = eventTypeOf(standings.getEventType());
        // A track event is decided by the smallest mark (a time); a field event by
        // the largest (a distance or a height). This is the same rule the event
        // itself carries, and the one the championship placings and the final draw
        // rank by — there is deliberately no second ranking rule here.
        boolean lowerBetter = type != null
                ? type.isLowerBetter()
                : !EventCategory.FIELD.name().equalsIgnoreCase(standings.getCategory());
        List<EventResult> ordered = heatResultsInPerformanceOrder(standings.getEventId(), lowerBetter);

        List<HeatSheet> sheets = new ArrayList<>(heats.size());
        for (EventGroupDTO heat : heats) {
            sheets.add(new HeatSheet(heat.getLabel(), heatRows(heat, ordered, type)));
        }
        return sheets;
    }

    /** Every heat mark of an event, in the event's own performance order. */
    private List<EventResult> heatResultsInPerformanceOrder(Long eventId, boolean lowerBetter) {
        Comparator<EventResult> performance = lowerBetter
                ? Comparator.comparing(EventResult::getMark)
                : Comparator.<EventResult, BigDecimal>comparing(EventResult::getMark).reversed();
        return resultRepository.findByEventIdAndStageOrderByMarkAsc(eventId, EventStage.HEAT).stream()
                .filter(result -> result.getUser() != null && result.getMark() != null)
                // Ties are broken by athlete id, which needs no extra query and keeps
                // the same marks producing the same sheet every time.
                .sorted(performance.thenComparing(result -> result.getUser().getId()))
                .toList();
    }

    /**
     * One heat's athletes, ranked within that heat by their mark.
     *
     * <p>An athlete with no mark recorded still ran — they keep their line, with
     * the result left as {@code -} rather than dropping off the sheet.</p>
     */
    private static List<HeatRow> heatRows(EventGroupDTO heat, List<EventResult> ordered,
                                          Event.EventType type) {
        List<EnrollmentDTO> athletes = heat.getAthletes();
        Map<Long, EnrollmentDTO> byUser = new HashMap<>();
        for (EnrollmentDTO athlete : athletes) {
            if (athlete.getUserId() != null) {
                byUser.putIfAbsent(athlete.getUserId(), athlete);
            }
        }

        List<HeatRow> rows = new ArrayList<>(athletes.size());
        Set<Long> placed = new HashSet<>();
        int place = 1;
        for (EventResult result : ordered) {
            Long userId = result.getUser().getId();
            EnrollmentDTO athlete = byUser.get(userId);
            if (athlete == null || !placed.add(userId)) {
                continue;
            }
            rows.add(new HeatRow(String.valueOf(place++), athlete, heatResult(result, type)));
        }

        List<EnrollmentDTO> unmarked = new ArrayList<>();
        for (EnrollmentDTO athlete : athletes) {
            if (athlete.getUserId() == null || !placed.contains(athlete.getUserId())) {
                unmarked.add(athlete);
            }
        }
        unmarked.sort(Comparator
                .comparingInt((EnrollmentDTO athlete) -> athlete.getLane() == null
                        ? Integer.MAX_VALUE : athlete.getLane())
                .thenComparing(athlete -> athlete.getStudentRef() == null ? "~" : athlete.getStudentRef()));
        for (EnrollmentDTO athlete : unmarked) {
            rows.add(new HeatRow("-", athlete, "-"));
        }
        return rows;
    }

    /** The mark with its unit, written as the final's table writes it; {@code -} when none. */
    private static String heatResult(EventResult result, Event.EventType type) {
        String display = MarkFormatter.formatWithUnit(result.getMark(), type, result.getUnit());
        return display == null ? "-" : display;
    }

    /** The event type behind a standings row, or null when this build does not know it. */
    private static Event.EventType eventTypeOf(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return Event.EventType.valueOf(code.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ cells

    private PdfPCell headingCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, fonts.boldFont(8.5f)));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(3f);
        cell.setGrayFill(0.9f);
        return cell;
    }

    private PdfPCell bodyCell(String text, int alignment, boolean record) {
        Font font = record ? fonts.boldFont(9f) : fonts.font(9f);
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(3f);
        return cell;
    }

    // ---------------------------------------------------------------- helpers

    /** The school's name line, or null when it has not been filled in. */
    private static String schoolLine(SportDaySettings settings) {
        String name = settings.getSchoolNameZh();
        String nameEn = settings.getSchoolName();
        if (name != null && !name.isBlank() && nameEn != null && !nameEn.isBlank()) {
            return name + " " + nameEn;
        }
        if (name != null && !name.isBlank()) {
            return name;
        }
        return nameEn == null || nameEn.isBlank() ? null : nameEn;
    }

    private static String describeScope(Sex sex, EventCategory category) {
        StringBuilder scope = new StringBuilder();
        if (sex != null) {
            scope.append(sex.getLabel()).append(' ');
        }
        if (category != null) {
            scope.append(category.getLabel()).append(' ');
        }
        scope.append("— every event with results");
        return scope.toString();
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
