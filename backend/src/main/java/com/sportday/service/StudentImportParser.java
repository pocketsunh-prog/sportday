package com.sportday.service;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the administrator's student register out of a CSV or XLSX file and maps
 * its columns onto the fields the system needs.
 *
 * <p>Header names are matched loosely (case, spaces, underscores and hyphens are
 * ignored) and both English and Chinese headings are accepted, because schools
 * export registers in many shapes. A missing required column fails the whole
 * file with a message naming the accepted headings; a bad individual row is
 * reported per row by {@link StudentService} instead.</p>
 *
 * <h2>Why the XLSX path is reached by name</h2>
 * Excel support comes from Apache POI, which is only needed when somebody
 * actually uploads a <em>.xlsx</em> file — CSV is the core format and needs
 * nothing extra. This class therefore refers to POI not at all, and calls
 * {@link StudentXlsxReader} through reflection. If POI is missing from the
 * classpath, the application still starts and the admin gets a clear message on
 * the upload screen instead of a service that refuses to boot.
 */
@Component
public class StudentImportParser {

    /** The fields an import row can carry. */
    public enum Field {
        STUDENT_ID, NAME, DOB, SEX, CLASS_NAME, CLASS_NUMBER, HOUSE
    }

    /** A single data row, keyed by canonical field. Row numbers are 1-based as in a spreadsheet. */
    public record RawRow(int rowNumber, Map<Field, String> values) {
        public String get(Field field) {
            return values.get(field);
        }
    }

    private static final Map<Field, Set<String>> HEADER_ALIASES = buildAliases();

    /** The cells of an uploaded sheet, before any column is given a meaning. */
    public record Grid(List<String> header, List<List<String>> dataRows) {
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy.M.d"),
            DateTimeFormatter.ofPattern("d.M.yyyy")
    );

    /** Fully qualified name of the optional POI-backed reader. */
    static final String XLSX_READER_CLASS = "com.sportday.service.StudentXlsxReader";

    private static Map<Field, Set<String>> buildAliases() {
        Map<Field, Set<String>> map = new LinkedHashMap<>();
        map.put(Field.STUDENT_ID, aliases(
                "studentid", "student_id", "studentno", "studentnumber", "studentcode",
                "sid", "id",
                "學號", "学号", "學生編號", "学生编号", "學生證號", "准考证号"));
        map.put(Field.NAME, aliases(
                "name", "fullname", "studentname", "chinesename", "englishname",
                "姓名", "學生姓名", "学生姓名", "中文姓名"));
        map.put(Field.DOB, aliases(
                "dob", "dateofbirth", "birthdate", "birthday", "birth",
                "出生日期", "出生年月日", "生日"));
        map.put(Field.SEX, aliases(
                "sex", "gender",
                "性別", "性别"));
        map.put(Field.CLASS_NAME, aliases(
                // "grade" is deliberately NOT an alias here: in this system a
                // student's grade (A/B/C) is derived from date of birth, so a
                // column called "grade" must not be mistaken for the class.
                "class", "classname", "classcode", "form", "formname",
                "班別", "班别", "班級", "班级", "年級", "年级", "級別", "级别"));
        map.put(Field.CLASS_NUMBER, aliases(
                "classnumber", "classno", "classnum", "classindex", "number", "no", "num",
                "班號", "班号", "座號", "座号", "學號序", "编号"));
        map.put(Field.HOUSE, aliases(
                "house", "housename", "sportshouse", "team",
                "社", "學社", "学社", "社別", "社别", "隊伍", "队伍"));
        return map;
    }

    private static Set<String> aliases(String... names) {
        return new LinkedHashSet<>(Arrays.asList(names));
    }

    /** Normalises a heading: strips BOM, whitespace, underscores and hyphens. */
    public static String normaliseHeader(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\uFEFF", "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-()（）.]", "");
    }

    /**
     * Reads an uploaded CSV/XLSX into raw cells: the header row and every
     * non-blank row under it, each still padded out to the width of the header.
     *
     * <p>This is the file-format half of an import — encodings, quoting, the
     * leading blank lines a school's export often has, and the optional POI-backed
     * {@code .xlsx} path — with no opinion about what the columns mean. The
     * student register gives them one meaning and the teacher upload another, and
     * both go through this so a school's spreadsheet behaves the same way
     * whichever it is uploading.</p>
     *
     * <p>Row numbers are 1-based, as in the spreadsheet the file came from, so a
     * row error points at the line the administrator is looking at.</p>
     */
    public static Grid parseGrid(String fileName, InputStream input) throws IOException {
        List<List<String>> records = new StudentImportParser().readRecords(fileName, input);
        return toGrid(records);
    }

    /** The same, for a file already read as a list of rows. */
    public static Grid gridOf(List<List<String>> records) {
        return toGrid(records);
    }

    private static Grid toGrid(List<List<String>> records) {
        int headerIndex = -1;
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).stream().anyMatch(c -> c != null && !c.isBlank())) {
                headerIndex = i;
                break;
            }
        }
        if (headerIndex < 0) {
            throw new IllegalArgumentException("The uploaded file is empty.");
        }
        List<String> header = records.get(headerIndex);
        int width = header.size();
        List<List<String>> rows = new ArrayList<>();
        for (int i = headerIndex + 1; i < records.size(); i++) {
            List<String> cells = records.get(i);
            if (cells.stream().allMatch(c -> c == null || c.isBlank())) {
                continue;
            }
            List<String> padded = new ArrayList<>(width);
            for (int c = 0; c < width; c++) {
                padded.add(c < cells.size() ? cells.get(c) : null);
            }
            rows.add(padded);
        }
        return new Grid(header, rows);
    }

    /** The raw reader: CSV by default, XLSX through POI when the name says so. */
    private List<List<String>> readRecords(String fileName, InputStream input) throws IOException {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT).trim();
        if (name.endsWith(".xls")) {
            throw new IllegalArgumentException(
                    "Legacy .xls files are not supported — save the file as .xlsx or .csv and upload again.");
        }
        if (name.endsWith(".xlsx") || name.endsWith(".xlsm")) {
            return readXlsx(input);
        }
        return readCsv(input);
    }

    public List<RawRow> parse(String fileName, InputStream input) throws IOException {
        return toRows(readRecords(fileName, input));
    }

    // ------------------------------------------------------------------ CSV

    List<List<String>> readCsv(InputStream input) throws IOException {
        List<List<String>> records = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            List<String> current = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean inQuotes = false;
            int ch;
            while ((ch = reader.read()) != -1) {
                char c = (char) ch;
                if (inQuotes) {
                    if (c == '"') {
                        int next = reader.read();
                        if (next == '"') {
                            field.append('"');
                        } else {
                            inQuotes = false;
                            if (next == -1) {
                                break;
                            }
                            c = (char) next;
                            if (c == ',') {
                                current.add(field.toString());
                                field.setLength(0);
                            } else if (c == '\n') {
                                current.add(field.toString());
                                field.setLength(0);
                                records.add(current);
                                current = new ArrayList<>();
                            } else if (c != '\r') {
                                field.append(c);
                            }
                        }
                    } else {
                        field.append(c);
                    }
                } else if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    current.add(field.toString());
                    field.setLength(0);
                } else if (c == '\n') {
                    current.add(field.toString());
                    field.setLength(0);
                    records.add(current);
                    current = new ArrayList<>();
                } else if (c != '\r') {
                    field.append(c);
                }
            }
            if (field.length() > 0 || !current.isEmpty()) {
                current.add(field.toString());
                records.add(current);
            }
        }
        return records;
    }

    // ----------------------------------------------------------------- XLSX

    /**
     * Reads a workbook through the POI-backed reader, loaded by name so that a
     * missing POI jar surfaces as a helpful message on upload rather than as a
     * failure to start the application.
     */
    @SuppressWarnings("unchecked")
    List<List<String>> readXlsx(InputStream input) throws IOException {
        Class<?> readerType;
        try {
            readerType = Class.forName(XLSX_READER_CLASS);
        } catch (ClassNotFoundException | LinkageError ex) {
            throw poiUnavailable(ex);
        }
        try {
            Object reader = readerType.getDeclaredConstructor().newInstance();
            return (List<List<String>>) readerType
                    .getMethod("read", InputStream.class)
                    .invoke(reader, input);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            // POI is usually missing here: the reader class loads, but its body
            // reaches for POI and the JVM raises NoClassDefFoundError, which
            // arrives wrapped in the reflective call.
            if (cause instanceof LinkageError linkage) {
                throw poiUnavailable(linkage);
            }
            throw new IOException("Could not read the Excel workbook", cause);
        } catch (ReflectiveOperationException ex) {
            // Introspecting the reader is what fails when POI is absent, because
            // its method signatures name POI types.
            if (hasLinkageCause(ex)) {
                throw poiUnavailable(ex);
            }
            throw new IOException("Could not read the Excel workbook", ex);
        } catch (LinkageError ex) {
            throw poiUnavailable(ex);
        }
    }

    private static boolean hasLinkageCause(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof LinkageError) {
                return true;
            }
        }
        return false;
    }

    private static IllegalStateException poiUnavailable(Throwable cause) {
        return new IllegalStateException(
                "Excel (.xlsx) import is unavailable because the Apache POI library is not on the "
                        + "classpath (missing " + (cause instanceof NoClassDefFoundError noClass
                        ? noClass.getMessage() : "org.apache.poi:poi-ooxml")
                        + "). Add org.apache.poi:poi-ooxml and rebuild — if you run from an IDE, "
                        + "re-import the Maven project so it picks up the new dependency. In the "
                        + "meantime the register can be uploaded as a .csv file, which needs "
                        + "nothing extra.", cause);
    }

    // -------------------------------------------------------------- shared

    private List<RawRow> toRows(List<List<String>> records) {
        // Drop leading blank lines before the header.
        int headerIndex = -1;
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).stream().anyMatch(c -> c != null && !c.isBlank())) {
                headerIndex = i;
                break;
            }
        }
        if (headerIndex < 0) {
            throw new IllegalArgumentException("The uploaded file is empty.");
        }

        List<String> header = records.get(headerIndex);
        Map<Field, Integer> columns = mapColumns(header);

        List<RawRow> rows = new ArrayList<>();
        for (int i = headerIndex + 1; i < records.size(); i++) {
            List<String> cells = records.get(i);
            if (cells.stream().allMatch(c -> c == null || c.isBlank())) {
                continue;
            }
            Map<Field, String> values = new LinkedHashMap<>();
            for (Map.Entry<Field, Integer> entry : columns.entrySet()) {
                int index = entry.getValue();
                String value = index < cells.size() ? cells.get(index) : null;
                values.put(entry.getKey(), value == null ? null : value.trim());
            }
            rows.add(new RawRow(i + 1, values));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file has a header row but no student rows.");
        }
        return rows;
    }

    private Map<Field, Integer> mapColumns(List<String> header) {
        Map<String, Integer> normalised = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = normaliseHeader(header.get(i));
            if (!key.isEmpty()) {
                normalised.putIfAbsent(key, i);
            }
        }

        Map<Field, Integer> columns = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<Field, Set<String>> entry : HEADER_ALIASES.entrySet()) {
            Integer found = null;
            for (String alias : entry.getValue()) {
                Integer index = normalised.get(normaliseHeader(alias));
                if (index != null) {
                    found = index;
                    break;
                }
            }
            if (found == null) {
                if (entry.getKey() == Field.HOUSE) {
                    continue; // house is welcome but not mandatory
                }
                missing.add(entry.getKey() + " (accepted headings: " + entry.getValue() + ")");
            } else {
                columns.put(entry.getKey(), found);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "The uploaded file is missing required column(s): " + String.join("; ", missing)
                            + ". Found headings: " + header);
        }
        return columns;
    }

    /**
     * Parses the wide variety of date spellings found in school registers.
     * Returns {@code null} when the value is not a recognisable date.
     */
    public static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (RuntimeException ignored) {
                // try the next format
            }
        }
        return null;
    }
}
