package com.sportday.service;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.sportday.dto.EnrollmentDTO;
import com.sportday.dto.EventGroupDTO;
import com.sportday.entity.Event;
import com.sportday.entity.SportDaySettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders helper marking sheets as PDF.
 *
 * <p>One sheet covers one group and carries the five columns a helper needs:</p>
 * <ol>
 *   <li>student id (學號)</li>
 *   <li>name (姓名)</li>
 *   <li>grade (級別)</li>
 *   <li>record (成績) — left blank for the helper to write in</li>
 *   <li>remark (備註) — left blank</li>
 * </ol>
 *
 * <p>A <strong>final</strong> sheet carries a sixth column, <strong>初賽 Heat</strong>,
 * between the grade and the record boxes: the heat performance that earned the
 * athlete their place, so the official holding the sheet can see what they ran in
 * the heats beside the box the final is being written in. The record boxes stay
 * blank. A heat sheet is unchanged — same columns, same paper.</p>
 *
 * <p>Paper size follows the event: 60/100/200/400 run 8 to a group and print on
 * <strong>A5</strong>; everything else runs 24 to a group and prints on
 * <strong>A4</strong>.</p>
 *
 * <p>On a <strong>relay</strong> a line is the <strong>team</strong>, not an athlete:
 * the school confirmed it, and one time is written for the four runners together. So a
 * relay sheet prints one line per team, carrying the team's own name in the Name column
 * and nothing else — no student id, no grade, no runners' names — leaving the one record
 * box beside it for the team's one time. The runners are named on the relay board, where
 * the legs are set, and not on the sheet a helper marks. An individual event is untouched:
 * there a line is the athlete, and their student id and name are printed. A relay whose
 * teams have not been derived carries no team labels and keeps that athlete-per-line
 * sheet.</p>
 *
 * <p>Under the event name the sheet carries the event's <strong>school record</strong>
 * — the mark to beat — once per sheet, in the header block, for example
 * {@code 紀錄 Record 7.406s — Chan Tai Man (2019)}. It is the record for the
 * event's own type, division and grade, which is what the print run already knows
 * about the group, so it arrives on the {@link EventGroupDTO} rather than being
 * looked up here. An event with no record prints no such line.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfSheetService {

    private static final DateTimeFormatter SHEET_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final EventGroupService eventGroupService;
    private final PdfFontProvider fonts;
    private final SettingsService settingsService;

    /** A single group's marking sheet. */
    public byte[] renderGroupSheet(Long groupId) {
        EventGroupDTO group = eventGroupService.getGroup(groupId);
        return renderSheets(List.of(group));
    }

    /** Every group of an event, one sheet per page, in heat order. */
    public byte[] renderEventSheets(Long eventId) {
        List<EventGroupDTO> groups = eventGroupService.getGroupsWithAthletes(eventId);
        if (groups.isEmpty()) {
            throw new IllegalStateException(
                    "Event " + eventId + " has no groups yet — run group allocation first.");
        }
        return renderSheets(groups);
    }

    /** Renders any collection of groups into one PDF, one sheet per page. */
    public byte[] renderSheets(List<EventGroupDTO> groups) {
        if (groups == null || groups.isEmpty()) {
            throw new IllegalArgumentException("No groups to render.");
        }
        String sheetSize = groups.get(0).getSheetSize();
        boolean a5 = "A5".equalsIgnoreCase(sheetSize);
        Rectangle pageSize = a5 ? PageSize.A5 : PageSize.A4;
        float margin = a5 ? 22f : 32f;

        Document document = new Document(pageSize, margin, margin, margin, margin);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            for (int i = 0; i < groups.size(); i++) {
                EventGroupDTO group = groups.get(i);
                if (i > 0) {
                    // Every group of an event shares a size, but honour a mixed
                    // batch if one is ever requested.
                    document.setPageSize("A5".equalsIgnoreCase(group.getSheetSize()) ? PageSize.A5 : PageSize.A4);
                    document.newPage();
                }
                addSheet(document, group, a5, pageSize, margin);
            }
            document.close();
        } catch (DocumentException ex) {
            throw new IllegalStateException("Failed to build the marking sheet PDF", ex);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ page

    private void addSheet(Document document, EventGroupDTO group, boolean a5,
                          Rectangle pageSize, float margin) throws DocumentException {
        float titleSize = a5 ? 12.5f : 15f;
        float bodySize = a5 ? 8.5f : 10f;

        Font titleFont = fonts.boldFont(titleSize);
        Font eventFont = fonts.boldFont(a5 ? 10.5f : 12.5f);
        Font metaFont = fonts.font(a5 ? 7.5f : 9f);
        Font headFont = fonts.boldFont(a5 ? 8f : 9.5f);
        Font cellFont = fonts.font(a5 ? 8.5f : 10f);

        Paragraph heading = new Paragraph();
        heading.setAlignment(Element.ALIGN_CENTER);
        // The school's own name and title head the sheet when they have been filled
        // in, so a printed sheet identifies the school without anyone writing it on.
        SportDaySettings settings = settingsService.get();
        String schoolName = schoolLine(settings);
        if (schoolName != null) {
            heading.add(new Chunk(schoolName, eventFont));
            heading.add(Chunk.NEWLINE);
        }
        String title = settings.getSportDayTitle();
        if (title == null || title.isBlank()) {
            heading.add(new Chunk("田徑運動會記錄表", titleFont));
            heading.add(Chunk.NEWLINE);
            heading.add(new Chunk("Sport Day Marking Sheet", metaFont));
        } else {
            heading.add(new Chunk(title, titleFont));
        }
        heading.setSpacingAfter(a5 ? 3f : 5f);
        document.add(heading);

        Paragraph event = new Paragraph(safe(group.getEventName()), eventFont);
        event.setAlignment(Element.ALIGN_CENTER);
        event.setSpacingAfter(a5 ? 2f : 3f);
        document.add(event);

        // The school record for this event — the mark to beat — printed once per
        // sheet, under the event name, in the sheet's own bilingual style. It comes
        // off the group rather than out of a repository: the renderer draws DTOs, so
        // a whole-programme print run costs no lookup here and none per athlete row.
        String recordLine = recordLine(group);
        if (recordLine != null) {
            Paragraph record = new Paragraph(recordLine, metaFont);
            record.setAlignment(Element.ALIGN_CENTER);
            record.setSpacingAfter(a5 ? 2f : 3f);
            document.add(record);
        }

        StringBuilder meta = new StringBuilder();
        meta.append("項目 Event: ").append(nullSafe(group.getEventTypeLabel()));
        meta.append("  |  ").append(nullSafe(group.getCategoryLabel()));
        meta.append("  |  ").append(nullSafe(group.getSexLabel()));
        meta.append("  |  組別 Group: ").append(nullSafe(group.getLabel()));
        meta.append("  |  人數 Entries: ").append(group.getAthleteCount()).append('/').append(group.getCapacity());
        meta.append("  |  紙張 Sheet: ").append(nullSafe(group.getSheetSize()));

        // The final is a stage of the same event; spell it out so a helper cannot
        // mistake the final sheet for another heat.
        if (group.getStage() != null && "FINAL".equalsIgnoreCase(group.getStage())) {
            meta.append("  |  決賽 Final");
        }

        Paragraph metaLine = new Paragraph(meta.toString(), metaFont);
        metaLine.setAlignment(Element.ALIGN_CENTER);
        metaLine.setSpacingAfter(a5 ? 2f : 3f);
        document.add(metaLine);

        Paragraph metaLine2 = new Paragraph(
                "日期 Date: " + LocalDate.now().format(SHEET_DATE)
                        + "  |  地點 Venue: ____________________"
                        + "  |  記錄員 Marker: ____________________", metaFont);
        metaLine2.setAlignment(Element.ALIGN_CENTER);
        metaLine2.setSpacingAfter(a5 ? 6f : 8f);
        document.add(metaLine2);

        // ---- the columns ----
        // A field event gives three attempts and the best one counts, so its sheet
        // carries three boxes under one Record heading instead of one. A final sheet
        // gains a 初賽 Heat column beside them, carrying what each finalist ran in
        // the heats that earned the place — and leaves the boxes below it blank.
        boolean field = "FIELD".equals(group.getCategory());
        boolean finalStage = isFinal(group);
        int attempts = field ? Event.EventType.FIELD_ATTEMPTS : 1;
        PdfPTable table = new PdfPTable(columnCount(group));
        table.setWidthPercentage(100f);
        // student id | name | grade | [初賽 Heat] | record (1..n) | remark
        table.setWidths(widths(field, finalStage, a5));
        table.setHeaderRows(field ? 2 : 1);

        String unit = unitFor(group);
        if (field) {
            // First header row: the three shared columns span both rows, and the
            // Record heading spans its three attempt boxes.
            table.addCell(headerCell("學號", "Student ID", headFont, metaFont, a5, 1, 2));
            table.addCell(headerCell("姓名", "Name", headFont, metaFont, a5, 1, 2));
            table.addCell(headerCell("級別", "Grade", headFont, metaFont, a5, 1, 2));
            if (finalStage) {
                // A field event runs straight to a final, so this column is only
                // ever reached by a final sheet somebody has created by hand — and
                // it still lays out rather than throwing on the width array.
                table.addCell(headerCell("初賽", "Heat", headFont, metaFont, a5, 1, 2));
            }
            table.addCell(headerCell("成績 Record" + (unit == null ? "" : " (" + unit + ")"),
                    null, headFont, metaFont, a5, attempts, 1));
            table.addCell(headerCell("備註", "Remark", headFont, metaFont, a5, 1, 2));
            for (int attempt = 1; attempt <= attempts; attempt++) {
                table.addCell(headerCell(String.valueOf(attempt), null, headFont, metaFont, a5, 1, 1));
            }
        } else {
            table.addCell(headerCell("學號", "Student ID", headFont, metaFont, a5));
            table.addCell(headerCell("姓名", "Name", headFont, metaFont, a5));
            table.addCell(headerCell("級別", "Grade", headFont, metaFont, a5));
            if (finalStage) {
                // The heat that earned the place, printed beside the blank box the
                // final is written in. The value carries its own unit (11.86s).
                table.addCell(headerCell("初賽", "Heat", headFont, metaFont, a5));
            }
            table.addCell(headerCell("成績", "Record", headFont, metaFont, a5));
            table.addCell(headerCell("備註", "Remark", headFont, metaFont, a5));
        }

        List<EnrollmentDTO> athletes = group.getAthletes() == null ? List.of() : group.getAthletes();
        /*
         * A relay is run by teams, not by individuals: one time is written against the
         * team, not four against the legs. So a relay sheet has one line per TEAM, and
         * that line IS the team — the team's name and nothing else, because there is one
         * record box beside it and one time to write in it. An individual event keeps one
         * line per athlete, where the line is the athlete and their name is printed. A
         * relay whose teams have not been derived carries no team labels at all and falls
         * back to the athlete-per-line sheet it has always had.
         */
        List<String> relayLines = relayLinesOf(athletes);
        // Pad out to the group's capacity so a late entry can still be written in —
        // but a relay is padded to its teams, because empty team lines help nobody.
        int lines = relayLines == null ? athletes.size() : relayLines.size();
        int rows = relayLines == null
                ? Math.max(lines, group.getCapacity() == null ? lines : group.getCapacity())
                : lines;

        float rowHeight = rowHeight(pageSize, margin, a5, rows, field);

        for (int i = 0; i < rows; i++) {
            if (relayLines != null) {
                /*
                 * The line is the TEAM's, so the team's name is the whole of it: it sits
                 * in the Name column, 姓名 being where a name belongs. The student id,
                 * the grade and the remark are printed blank, and every record box with
                 * them, so the helper has one box to write the team's one time in. The
                 * runners' names are deliberately absent — not against the team and not
                 * on lines of their own: the sheet has one line per team, and the line
                 * names the team.
                 */
                String team = i < relayLines.size() ? relayLines.get(i) : null;
                table.addCell(bodyCell("", cellFont, Element.ALIGN_CENTER, rowHeight, a5));
                table.addCell(bodyCell(team == null ? "" : team,
                        cellFont, Element.ALIGN_LEFT, rowHeight, a5));
                table.addCell(bodyCell("", cellFont, Element.ALIGN_CENTER, rowHeight, a5));
                if (finalStage) {
                    table.addCell(bodyCell("", cellFont, Element.ALIGN_CENTER, rowHeight, a5));
                }
                for (int attempt = 0; attempt < attempts; attempt++) {
                    table.addCell(bodyCell("", cellFont, Element.ALIGN_CENTER, rowHeight, a5));
                }
                table.addCell(bodyCell("", cellFont, Element.ALIGN_LEFT, rowHeight, a5));
                continue;
            }
            EnrollmentDTO athlete = i < athletes.size() ? athletes.get(i) : null;
            table.addCell(bodyCell(athlete == null ? "" : nullSafe(athlete.getStudentRef()),
                    cellFont, Element.ALIGN_CENTER, rowHeight, a5));
            table.addCell(bodyCell(athlete == null ? "" : nullSafe(athlete.getName()),
                    cellFont, Element.ALIGN_LEFT, rowHeight, a5));
            table.addCell(bodyCell(athlete == null || athlete.getGrade() == null ? "" : athlete.getGrade(),
                    cellFont, Element.ALIGN_CENTER, rowHeight, a5));
            if (finalStage) {
                // What they ran in the heat — or ABS / DQ when the heat produced no
                // number. A padded line, for an entry that arrived late, stays blank.
                table.addCell(bodyCell(athlete == null ? "" : nullSafe(athlete.getHeatDisplayMark()),
                        cellFont, Element.ALIGN_CENTER, rowHeight, a5));
            }
            for (int attempt = 0; attempt < attempts; attempt++) {
                table.addCell(bodyCell("", cellFont, Element.ALIGN_CENTER, rowHeight, a5));
            }
            table.addCell(bodyCell("", cellFont, Element.ALIGN_LEFT, rowHeight, a5));
        }
        document.add(table);

        Paragraph footer = new Paragraph(
                "裁判長 Chief Judge: ______________    計時員 Timekeeper: ______________    覆核 Checked by: ______________",
                metaFont);
        footer.setSpacingBefore(a5 ? 7f : 10f);
        document.add(footer);
    }

    /**
     * The team lines a relay sheet is drawn from — the team's own name, one entry per
     * team, in the order the roster lists them, so the sheet reads 5A, 5B, 5C down the
     * page — or null when this is not a relay with teams.
     *
     * <p><strong>A line is the team.</strong> The school confirmed it: on a relay the
     * line's identity is the team's name, with one time written for the team, so the
     * four runners are not named here — neither beside the team nor on lines of their
     * own. Who runs for a team, and in which leg, belongs on the relay board, where the
     * selection is made and the order is set; the sheet a helper marks carries no use
     * for it, and printing "5A — Chan Tai Man, Lee Siu Ming" against one record box
     * would read as a mark for each of them.</p>
     *
     * <p>Returning null is what keeps an individual event — where a line is the
     * athlete, whose student id and name are printed — and a relay whose teams have not
     * been derived on the athlete-per-line sheet; the two shapes cannot be confused for
     * one another because the team labels are either there or they are not.</p>
     */
    private static List<String> relayLinesOf(List<EnrollmentDTO> athletes) {
        boolean anyTeam = athletes.stream().anyMatch(a -> a.getRelayTeamLabel() != null);
        if (!anyTeam) {
            return null;
        }
        // A LinkedHashSet, so a team's four legs collapse to the one line the team gets
        // and the teams keep the order the roster lists them in.
        java.util.Set<String> teams = new java.util.LinkedHashSet<>();
        for (EnrollmentDTO athlete : athletes) {
            if (athlete.getRelayTeamLabel() != null) {
                teams.add(athlete.getRelayTeamLabel());
            }
        }
        return new java.util.ArrayList<>(teams);
    }

    /**
     * True when this sheet is a final's — the only sheet that carries a heat
     * record, because it is the only one with an earlier stage to show.
     */
    static boolean isFinal(EventGroupDTO group) {
        return group != null && "FINAL".equalsIgnoreCase(group.getStage());
    }

    /**
     * The header line carrying the event's school record — the mark to beat — or
     * null when the event has none.
     *
     * <pre>
     *   紀錄 Record 7.406s
     *   紀錄 Record 7.406s — Chan Tai Man (2019)
     *   紀錄 Record 18.12M — 陳大文 (2016)
     * </pre>
     *
     * <p>The mark is already formatted with its unit, by {@link MarkFormatter}, so
     * it reads exactly as a result reads anywhere else on the sheet. The holder and
     * the year are printed when the record knows them, because a helper holding the
     * sheet has a use for both; the em dash is drawn on one line rather than as a
     * second, because an A5 sheet has no room to spare in its header.</p>
     *
     * <p><strong>No record, no line.</strong> An event nobody has a mark for prints
     * nothing where the record would be — not a dash and not the word "none", which
     * a helper could read as a mark.</p>
     */
    static String recordLine(EventGroupDTO group) {
        String mark = group == null ? null : group.getRecordDisplayMark();
        if (mark == null || mark.isBlank()) {
            return null;
        }
        StringBuilder line = new StringBuilder("紀錄 Record ").append(mark);
        String holder = group.getRecordHolderName();
        String year = group.getRecordAchievedOn() == null
                ? null : String.valueOf(group.getRecordAchievedOn().getYear());
        if (holder != null && !holder.isBlank()) {
            line.append(" — ").append(holder.trim());
        }
        if (year != null) {
            line.append(" (").append(year).append(')');
        }
        return line.toString();
    }

    /**
     * How many columns the table has: student id, name, grade, the record boxes,
     * the remark — and, on a final sheet, the heat record between the grade and
     * the record. Five for a track heat (unchanged), seven for a field one,
     * six and eight respectively once the heat column is added.
     */
    static int columnCount(EventGroupDTO group) {
        int attempts = "FIELD".equals(group.getCategory()) ? Event.EventType.FIELD_ATTEMPTS : 1;
        return 3 + attempts + 1 + (isFinal(group) ? 1 : 0);
    }

    /**
     * The column widths, in the order the columns are drawn: student id, name,
     * grade, [初賽 Heat], record (1..n), remark.
     *
     * <p>A heat sheet's array is exactly what it always was. A final's array has
     * one more entry than a heat's, including the one case that should not arise —
     * a <em>field</em> final, where a field event runs straight to a final — so a
     * sheet like that lays out rather than throwing on a width array that does not
     * match its columns.</p>
     */
    static float[] widths(boolean field, boolean finalStage, boolean a5) {
        if (field && finalStage) {
            return a5
                    ? new float[]{1.5f, 2.0f, 0.7f, 1.5f, 0.95f, 0.95f, 0.95f, 1.5f}
                    : new float[]{1.6f, 2.2f, 0.7f, 1.6f, 1.0f, 1.0f, 1.0f, 1.4f};
        }
        if (field) {
            return a5
                    ? new float[]{1.9f, 2.4f, 0.8f, 1.05f, 1.05f, 1.05f, 1.75f}
                    : new float[]{2.0f, 2.7f, 0.8f, 1.1f, 1.1f, 1.1f, 1.7f};
        }
        if (finalStage) {
            return a5
                    ? new float[]{1.8f, 2.3f, 0.85f, 1.55f, 1.8f, 1.7f}
                    : new float[]{1.9f, 2.6f, 0.85f, 1.65f, 1.9f, 1.8f};
        }
        return a5
                ? new float[]{2.1f, 2.7f, 1.0f, 2.0f, 2.0f}
                : new float[]{2.2f, 3.0f, 0.9f, 2.0f, 1.9f};
    }

    /** Chooses a row height that fills the sheet without overflowing the page. */
    private float rowHeight(Rectangle pageSize, float margin, boolean a5, int rows, boolean field) {
        // A field sheet has a two-row header, so it starts a little lower.
        float headerBlock = a5 ? (field ? 108f : 96f) : (field ? 136f : 122f);
        float footerBlock = a5 ? 24f : 30f;
        float usable = pageSize.getHeight() - (2 * margin) - headerBlock - footerBlock;
        float ideal = rows <= 0 ? 18f : usable / rows;
        float min = a5 ? 16f : 12f;
        float max = a5 ? 34f : 22f;
        return Math.max(min, Math.min(max, ideal));
    }

    /** The unit this group's marks are recorded in, for the Record heading. */
    static String unitFor(EventGroupDTO group) {
        // A race over 400M is timed on a stopwatch, so its sheet reads M:S rather
        // than a bare count of seconds.
        if (group.getEventType() != null) {
            try {
                if (Event.EventType.valueOf(group.getEventType()).usesMinutesAndSeconds()) {
                    return "M:S";
                }
            } catch (IllegalArgumentException ignored) {
                // An event type this build does not know — fall back to the category.
            }
        }
        if ("FIELD".equals(group.getCategory())) {
            return Event.EventType.UNIT_FIELD;
        }
        if ("TRACK".equals(group.getCategory()) || "RELAY".equals(group.getCategory())) {
            return Event.EventType.UNIT_TRACK;
        }
        return null;
    }

    private PdfPCell headerCell(String zh, String en, Font zhFont, Font enFont, boolean a5) {
        return headerCell(zh, en, zhFont, enFont, a5, 1, 1);
    }

    /** A header cell that may span several columns or rows, for a field sheet. */
    private PdfPCell headerCell(String zh, String en, Font zhFont, Font enFont, boolean a5,
                                int colspan, int rowspan) {
        Phrase phrase = new Phrase();
        phrase.add(new Chunk(zh, zhFont));
        if (en != null) {
            phrase.add(Chunk.NEWLINE);
            phrase.add(new Chunk(en, enFont));
        }
        PdfPCell cell = new PdfPCell(phrase);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(a5 ? 3f : 4f);
        cell.setMinimumHeight(a5 ? 22f : 26f);
        cell.setBackgroundColor(new java.awt.Color(232, 232, 232));
        if (colspan > 1) {
            cell.setColspan(colspan);
        }
        if (rowspan > 1) {
            cell.setRowspan(rowspan);
        }
        return cell;
    }

    private PdfPCell bodyCell(String text, Font font, int alignment, float minHeight, boolean a5) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(a5 ? 2f : 3f);
        cell.setMinimumHeight(minHeight);
        return cell;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * The school's name for the head of the sheet — Chinese then English, as a
     * Hong Kong school would print it — or null when neither has been filled in.
     */
    private static String schoolLine(SportDaySettings settings) {
        String zh = settings.getSchoolNameZh() == null ? "" : settings.getSchoolNameZh().trim();
        String en = settings.getSchoolName() == null ? "" : settings.getSchoolName().trim();
        if (zh.isEmpty() && en.isEmpty()) {
            return null;
        }
        if (zh.isEmpty()) {
            return en;
        }
        return en.isEmpty() ? zh : zh + " " + en;
    }
}
