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
 * <p>Paper size follows the event: 60/100/200/400 run 8 to a group and print on
 * <strong>A5</strong>; everything else runs 24 to a group and prints on
 * <strong>A4</strong>.</p>
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

        // ---- the five columns ----
        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100f);
        // student id | name | grade | record | remark
        table.setWidths(a5
                ? new float[]{2.1f, 2.7f, 1.0f, 2.0f, 2.0f}
                : new float[]{2.2f, 3.0f, 0.9f, 2.0f, 1.9f});
        table.setHeaderRows(1);

        table.addCell(headerCell("學號", "Student ID", headFont, metaFont, a5));
        table.addCell(headerCell("姓名", "Name", headFont, metaFont, a5));
        table.addCell(headerCell("級別", "Grade", headFont, metaFont, a5));
        table.addCell(headerCell("成績", "Record", headFont, metaFont, a5));
        table.addCell(headerCell("備註", "Remark", headFont, metaFont, a5));

        List<EnrollmentDTO> athletes = group.getAthletes() == null ? List.of() : group.getAthletes();
        // Pad out to the group's capacity so a late entry can still be written in.
        int rows = Math.max(athletes.size(), group.getCapacity() == null ? athletes.size() : group.getCapacity());

        float rowHeight = rowHeight(pageSize, margin, a5, rows);

        for (int i = 0; i < rows; i++) {
            EnrollmentDTO athlete = i < athletes.size() ? athletes.get(i) : null;
            table.addCell(bodyCell(athlete == null ? "" : nullSafe(athlete.getStudentRef()),
                    cellFont, Element.ALIGN_CENTER, rowHeight, a5));
            table.addCell(bodyCell(athlete == null ? "" : nullSafe(athlete.getName()),
                    cellFont, Element.ALIGN_LEFT, rowHeight, a5));
            table.addCell(bodyCell(athlete == null || athlete.getGrade() == null ? "" : athlete.getGrade(),
                    cellFont, Element.ALIGN_CENTER, rowHeight, a5));
            table.addCell(bodyCell("", cellFont, Element.ALIGN_CENTER, rowHeight, a5));
            table.addCell(bodyCell("", cellFont, Element.ALIGN_LEFT, rowHeight, a5));
        }
        document.add(table);

        Paragraph footer = new Paragraph(
                "裁判長 Chief Judge: ______________    計時員 Timekeeper: ______________    覆核 Checked by: ______________",
                metaFont);
        footer.setSpacingBefore(a5 ? 7f : 10f);
        document.add(footer);
    }

    /** Chooses a row height that fills the sheet without overflowing the page. */
    private float rowHeight(Rectangle pageSize, float margin, boolean a5, int rows) {
        float headerBlock = a5 ? 96f : 122f;
        float footerBlock = a5 ? 24f : 30f;
        float usable = pageSize.getHeight() - (2 * margin) - headerBlock - footerBlock;
        float ideal = rows <= 0 ? 18f : usable / rows;
        float min = a5 ? 16f : 12f;
        float max = a5 ? 34f : 22f;
        return Math.max(min, Math.min(max, ideal));
    }

    private PdfPCell headerCell(String zh, String en, Font zhFont, Font enFont, boolean a5) {
        Phrase phrase = new Phrase();
        phrase.add(new Chunk(zh, zhFont));
        phrase.add(Chunk.NEWLINE);
        phrase.add(new Chunk(en, enFont));
        PdfPCell cell = new PdfPCell(phrase);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(a5 ? 3f : 4f);
        cell.setMinimumHeight(a5 ? 22f : 26f);
        cell.setBackgroundColor(new java.awt.Color(232, 232, 232));
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
