package com.sportday.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Requirement 1: the administrator uploads a register exported from whatever the
 * school already uses, so the reader has to cope with different headings, date
 * spellings and a UTF-8 BOM from Excel.
 */
class StudentImportParserTest {

    private final StudentImportParser parser = new StudentImportParser();

    private List<StudentImportParser.RawRow> parseCsv(String csv) throws Exception {
        String withBom = "\uFEFF" + csv;
        return parser.parse("students.csv", new ByteArrayInputStream(withBom.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("reads the documented header, in any column order")
    void readsTheDocumentedHeader() throws Exception {
        List<StudentImportParser.RawRow> rows = parseCsv("""
                studentId,name,dob,sex,className,classNumber,house
                S0001,陳大文,2010-03-15,M,5A,12,Red
                S0002,李小明,2009-07-02,F,5B,7,Blue
                """);

        assertEquals(2, rows.size());
        assertEquals("S0001", rows.get(0).get(StudentImportParser.Field.STUDENT_ID));
        assertEquals("陳大文", rows.get(0).get(StudentImportParser.Field.NAME));
        assertEquals("2010-03-15", rows.get(0).get(StudentImportParser.Field.DOB));
        assertEquals("M", rows.get(0).get(StudentImportParser.Field.SEX));
        assertEquals("5A", rows.get(0).get(StudentImportParser.Field.CLASS_NAME));
        assertEquals("12", rows.get(0).get(StudentImportParser.Field.CLASS_NUMBER));
        assertEquals("Red", rows.get(0).get(StudentImportParser.Field.HOUSE));
    }

    @Test
    @DisplayName("the BOM Excel writes does not corrupt the first heading")
    void toleratesUtf8Bom() throws Exception {
        List<StudentImportParser.RawRow> rows = parseCsv("""
                studentId,name,dob,sex,className,classNumber,house
                S0001,陳大文,2010-03-15,M,5A,12,Red
                """);
        assertEquals("S0001", rows.get(0).get(StudentImportParser.Field.STUDENT_ID));
    }

    @Test
    @DisplayName("accepts Chinese headings")
    void acceptsChineseHeadings() throws Exception {
        List<StudentImportParser.RawRow> rows = parseCsv("""
                學號,姓名,出生日期,性別,班別,班號,社
                S0001,陳大文,2010-03-15,男,5A,12,Red
                """);
        assertEquals(1, rows.size());
        assertEquals("S0001", rows.get(0).get(StudentImportParser.Field.STUDENT_ID));
        assertEquals("陳大文", rows.get(0).get(StudentImportParser.Field.NAME));
        assertEquals("男", rows.get(0).get(StudentImportParser.Field.SEX));
        assertEquals("5A", rows.get(0).get(StudentImportParser.Field.CLASS_NAME));
        assertEquals("12", rows.get(0).get(StudentImportParser.Field.CLASS_NUMBER));
    }

    @Test
    @DisplayName("heading spelling, case, spaces and underscores do not matter")
    void headingMatchingIsLoose() throws Exception {
        List<StudentImportParser.RawRow> rows = parseCsv("""
                 Student_ID ,  Full Name ,Date Of Birth,Sex,Class No,Class,House
                S0001,陳大文,2010-03-15,M,12,5A,Red
                """);
        assertEquals(1, rows.size());
        assertEquals("S0001", rows.get(0).get(StudentImportParser.Field.STUDENT_ID));
        assertEquals("陳大文", rows.get(0).get(StudentImportParser.Field.NAME));
        assertEquals("5A", rows.get(0).get(StudentImportParser.Field.CLASS_NAME));
        assertEquals("12", rows.get(0).get(StudentImportParser.Field.CLASS_NUMBER));
    }

    @Test
    @DisplayName("a 'grade' column is not mistaken for the class")
    void gradeHeadingIsNotAClass() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parseCsv("""
                studentId,name,dob,sex,grade,classNumber
                S0001,陳大文,2010-03-15,M,A,12
                """));
        assertTrue(error.getMessage().contains("className") || error.getMessage().contains("CLASS_NAME"),
                "the error should name the missing class column: " + error.getMessage());
    }

    @Test
    @DisplayName("a missing required column fails the whole file with a helpful message")
    void reportsMissingColumns() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parseCsv("""
                studentId,name,dob
                S0001,陳大文,2010-03-15
                """));
        assertTrue(error.getMessage().startsWith("The uploaded file is missing required column(s)"),
                error.getMessage());
        assertTrue(error.getMessage().contains("SEX"), error.getMessage());
        assertTrue(error.getMessage().contains("Found headings"), error.getMessage());
    }

    @Test
    @DisplayName("the house column is optional")
    void houseIsOptional() throws Exception {
        List<StudentImportParser.RawRow> rows = parseCsv("""
                studentId,name,dob,sex,className,classNumber
                S0001,陳大文,2010-03-15,M,5A,12
                """);
        assertEquals(1, rows.size());
        assertNull(rows.get(0).get(StudentImportParser.Field.HOUSE));
    }

    @Test
    @DisplayName("blank lines and trailing newlines are ignored")
    void skipsBlankLines() throws Exception {
        List<StudentImportParser.RawRow> rows = parseCsv("""
                studentId,name,dob,sex,className,classNumber,house

                S0001,陳大文,2010-03-15,M,5A,12,Red

                S0002,李小明,2009-07-02,F,5B,7,Blue

                """);
        assertEquals(2, rows.size());
        assertEquals(3, rows.get(0).rowNumber(), "row numbers should reflect the source file");
    }

    @Test
    @DisplayName("quoted fields containing commas survive")
    void handlesQuotedFields() throws Exception {
        List<StudentImportParser.RawRow> rows = parseCsv("""
                studentId,name,dob,sex,className,classNumber,house
                S0001,"Chan, Tai Man",2010-03-15,M,5A,12,"Red, East"
                """);
        assertEquals("Chan, Tai Man", rows.get(0).get(StudentImportParser.Field.NAME));
        assertEquals("Red, East", rows.get(0).get(StudentImportParser.Field.HOUSE));
    }

    @Test
    @DisplayName("CRLF line endings from Excel are handled")
    void handlesCrlf() throws Exception {
        List<StudentImportParser.RawRow> rows = parseCsv(
                "studentId,name,dob,sex,className,classNumber,house\r\n"
                        + "S0001,陳大文,2010-03-15,M,5A,12,Red\r\n"
                        + "S0002,李小明,2009-07-02,F,5B,7,Blue\r\n");
        assertEquals(2, rows.size());
        assertEquals("S0002", rows.get(1).get(StudentImportParser.Field.STUDENT_ID));
    }

    @Test
    @DisplayName("a file with a header but no students is rejected")
    void rejectsEmptyBody() {
        assertThrows(IllegalArgumentException.class, () -> parseCsv("""
                studentId,name,dob,sex,className,classNumber,house
                """));
    }

    @Test
    @DisplayName("an entirely empty file is rejected")
    void rejectsEmptyFile() {
        assertThrows(IllegalArgumentException.class, () -> parseCsv(""));
    }

    @Test
    @DisplayName("legacy .xls is refused with advice rather than a stack trace")
    void rejectsLegacyXls() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                parser.parse("register.xls", new ByteArrayInputStream("junk".getBytes(StandardCharsets.UTF_8))));
        assertTrue(error.getMessage().contains(".xlsx"), error.getMessage());
    }

    @Test
    @DisplayName("the parser exposes no Apache POI types, so a missing POI jar cannot block startup")
    void parserApiIsFreeOfPoiTypes() throws Exception {
        // Spring introspects every bean at startup, and introspection loads the
        // classes named in method signatures. Keeping POI out of this class's API
        // is what lets the application start when POI is absent from the
        // classpath — Excel import then reports a clear message on upload
        // instead of the whole service refusing to boot.
        Class<?> parser = com.sportday.service.StudentImportParser.class;
        for (java.lang.reflect.Method method : parser.getDeclaredMethods()) {
            assertFalse(method.getReturnType().getName().startsWith("org.apache.poi"),
                    "return type of " + method.getName() + " pulls in POI");
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(parameter.getName().startsWith("org.apache.poi"),
                        "parameter of " + method.getName() + " pulls in POI");
            }
            for (Class<?> exception : method.getExceptionTypes()) {
                assertFalse(exception.getName().startsWith("org.apache.poi"),
                        "throws clause of " + method.getName() + " pulls in POI");
            }
        }
        for (java.lang.reflect.Field field : parser.getDeclaredFields()) {
            assertFalse(field.getType().getName().startsWith("org.apache.poi"),
                    "field " + field.getName() + " pulls in POI");
        }
        for (java.lang.reflect.Constructor<?> constructor : parser.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(parameter.getName().startsWith("org.apache.poi"),
                        "constructor parameter pulls in POI");
            }
        }
        // The POI-backed reader must stay out of Spring's component scan.
        assertFalse(parser.getPackageName().isEmpty());
        assertFalse(StudentXlsxReader.class.isAnnotationPresent(
                        org.springframework.stereotype.Component.class),
                "StudentXlsxReader must not be a Spring bean: it would be introspected at startup");
    }

    @Test
    @DisplayName("the POI-backed reader is reachable by the name the parser looks up")
    void xlsxReaderIsReachableByName() throws Exception {
        Class<?> reader = Class.forName(com.sportday.service.StudentImportParser.XLSX_READER_CLASS);
        assertNotNull(reader.getMethod("read", java.io.InputStream.class),
                "the parser invokes read(InputStream) reflectively");
        assertFalse(reader.isAnnotationPresent(org.springframework.stereotype.Component.class),
                "a bean would be introspected at startup, which fails when POI is missing");
    }

    @Test
    @DisplayName("a real .xlsx register is read when POI is available")
    void readsARealWorkbook() throws Exception {
        byte[] workbook = buildTinyWorkbook();
        List<StudentImportParser.RawRow> rows = parser.parse("students.xlsx",
                new ByteArrayInputStream(workbook));
        assertEquals(1, rows.size());
        assertEquals("S0001", rows.get(0).get(StudentImportParser.Field.STUDENT_ID));
        assertEquals("陳大文", rows.get(0).get(StudentImportParser.Field.NAME));
    }

    private static byte[] buildTinyWorkbook() throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Students");
            String[][] data = {
                    {"studentId", "name", "dob", "sex", "className", "classNumber", "house"},
                    {"S0001", "陳大文", "2010-03-15", "M", "5A", "12", "Red"},
            };
            for (int r = 0; r < data.length; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r);
                for (int c = 0; c < data[r].length; c++) {
                    row.createCell(c).setCellValue(data[r][c]);
                }
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    @ParameterizedTest(name = "\"{0}\" parses as {1}")
    @CsvSource({
            "2010-03-15, 2010-03-15",
            "2010/3/15,  2010-03-15",
            "2010-3-15,  2010-03-15",
            "15/3/2010,  2010-03-15",
            "15-3-2010,  2010-03-15",
            "20100315,   2010-03-15",
            "2010.3.15,  2010-03-15",
    })
    void parsesCommonDateSpellings(String raw, String expected) {
        assertEquals(LocalDate.parse(expected), StudentImportParser.parseDate(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "not a date", "2010-13-45", "15/15/2010"})
    @DisplayName("an unparseable date yields null so the row can be reported")
    void rejectsBadDates(String raw) {
        assertNull(StudentImportParser.parseDate(raw));
    }
}
