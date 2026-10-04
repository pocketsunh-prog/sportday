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
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Sex;
import com.sportday.entity.SportDaySettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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

    private final ChampionService championService;
    private final com.sportday.repository.EventRepository eventRepository;
    private final PdfFontProvider fonts;
    private final SettingsService settingsService;

    /** One event's results, in placings order. */
    public byte[] renderEventResults(Long eventId) {
        ChampionsDTO.EventStandingsDTO standings = championService.standingsFor(eventId);
        if (standings == null || standings.getPlacings() == null || standings.getPlacings().isEmpty()) {
            throw new IllegalStateException(
                    "This event has no results recorded yet, so there is nothing to print.");
        }
        return render(List.of(standings), null);
    }

    /**
     * Every event that has results, in programme order, as one document.
     *
     * @param sex      restrict to one division, or null for both
     * @param category restrict to track or field, or null for both
     */
    public byte[] renderProgrammeResults(Sex sex, EventCategory category) {
        List<Event> events = eventRepository.findAll().stream()
                .sorted(EventService.EVENT_ORDER)
                .filter(event -> sex == null || event.getSex() == sex)
                .filter(event -> category == null || event.getCategoryOrDefault() == category)
                .toList();

        List<ChampionsDTO.EventStandingsDTO> withResults = new ArrayList<>();
        for (Event event : events) {
            try {
                ChampionsDTO.EventStandingsDTO standings = championService.standingsFor(event.getId());
                if (standings != null && standings.getPlacings() != null
                        && !standings.getPlacings().isEmpty()) {
                    withResults.add(standings);
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

    private byte[] render(List<ChampionsDTO.EventStandingsDTO> sheets, String scope) {
        Document document = new Document(PageSize.A4, 36f, 36f, 40f, 36f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            addHeading(document, scope);
            for (ChampionsDTO.EventStandingsDTO standings : sheets) {
                addEvent(document, standings);
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

    private void addEvent(Document document, ChampionsDTO.EventStandingsDTO standings)
            throws DocumentException {
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

    /** The result with its unit, noting a school record beside it. */
    private static String resultText(ChampionsDTO.PlacingDTO placing) {
        String display = placing.getDisplayMark();
        if (display == null) {
            display = placing.getMark() == null ? "-" : placing.getMark().toPlainString();
        }
        return placing.isSchoolRecord() ? display + " ★" : display;
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
