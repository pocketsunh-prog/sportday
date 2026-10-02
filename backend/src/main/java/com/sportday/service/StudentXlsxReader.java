package com.sportday.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the raw cells of an Excel workbook's first sheet.
 *
 * <p>Everything that touches Apache POI lives in this one class, and
 * {@link StudentImportParser} reaches it by name rather than by reference. That
 * matters: POI is only needed for the optional <em>.xlsx</em> import, and a
 * missing POI jar must not stop the application from starting. When POI is
 * absent this class cannot be loaded at all, which is exactly the signal
 * {@link StudentImportParser} catches to report the problem at upload time.</p>
 *
 * <p>This class is deliberately <strong>not</strong> a Spring bean: a bean would
 * have to be introspected at startup, and introspection is what fails when the
 * library is missing.</p>
 */
public class StudentXlsxReader {

    /**
     * Reads sheet 0 as a grid of strings. Genuine Excel date cells are
     * normalised to ISO so the shared date parser accepts them whether the
     * school formatted the column as a date or as text.
     */
    public List<List<String>> read(InputStream input) throws IOException {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        List<List<String>> records = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                int last = Math.max(row.getLastCellNum(), 0);
                for (int i = 0; i < last; i++) {
                    cells.add(cellText(row.getCell(i), formatter));
                }
                records.add(cells);
            }
        }
        return records;
    }

    private String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            var date = cell.getLocalDateTimeCellValue().toLocalDate();
            return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        return formatter.formatCellValue(cell).trim();
    }
}
