package com.sportday.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the administrator's teacher list out of a CSV or XLSX file.
 *
 * <p>It is deliberately the same shape as {@link StudentImportParser}: the file
 * is read into raw cells by that class (so encodings, quoting, leading blank
 * lines and the optional POI-backed {@code .xlsx} path are handled in exactly one
 * place), headings are matched loosely and in English or Chinese, and a missing
 * <em>required</em> column fails the whole file with the accepted headings named.
 * A bad individual row is reported per row by {@link TeacherService} instead, so
 * one mistyped teacher never loses the rest of the staff list.</p>
 *
 * <h2>The classes column</h2>
 * A teacher takes one or more classes, written in a single cell separated by
 * <strong>semicolons, commas, vertical bars or newlines</strong> — {@code 1A;3B},
 * {@code 1A,3B}, {@code 1A | 3B} all mean the same two classes — and the parts are
 * trimmed and upper-cased, so {@code " 1a , 3b "} is the same assignment as
 * {@code 1A;3B}. A file that puts each class in its own column ({@code 班別} and
 * {@code 班級}) has both columns read and pooled, so splitting the classes across
 * columns loses nothing.
 */
@Component
public class TeacherImportParser {

    /** The fields a teacher row can carry. */
    public enum Field {
        USERNAME, NAME, EMAIL, CLASSES, PASSWORD
    }

    /** A single data row, keyed by canonical field. Row numbers are 1-based as in a spreadsheet. */
    public record RawRow(int rowNumber, Map<Field, String> values) {
        public String get(Field field) {
            return values.get(field);
        }
    }

    private static final Map<Field, Set<String>> HEADER_ALIASES = buildAliases();

    /** Characters that separate one class name from another inside a single cell. */
    private static final String CLASS_SEPARATORS = "[,;|\\r\\n　、]+";

    private static Map<Field, Set<String>> buildAliases() {
        Map<Field, Set<String>> map = new LinkedHashMap<>();
        map.put(Field.USERNAME, aliases(
                "username", "user", "login", "loginname", "account", "accountname", "staffid",
                "teacherid", "teachercode", "userid",
                "帳號", "账号", "用戶名", "用户名", "登入名稱", "登录名", "教師編號", "教师编号", "職員編號"));
        map.put(Field.NAME, aliases(
                "name", "fullname", "teachername", "chinesename", "englishname", "displayname",
                "姓名", "教師姓名", "教师姓名", "老師姓名", "老师姓名", "中文姓名", "全名"));
        map.put(Field.EMAIL, aliases(
                "email", "mail", "emailaddress", "e-mail",
                "電郵", "电邮", "電子郵件", "电子邮件", "郵箱", "邮箱"));
        map.put(Field.CLASSES, aliases(
                "classes", "classnames", "classlist", "classlists",
                "classteaching", "teachingclass", "teachingclasses", "assignedclasses",
                "班別", "班别", "班級", "班级", "任教班別", "任教班别", "任教班級", "任教班级",
                "負責班別", "负责班别", "所教班別", "所教班别"));
        map.put(Field.PASSWORD, aliases(
                "password", "passwd", "pwd", "initialpassword", "temporarypassword",
                "密碼", "密码", "初始密碼", "初始密码"));
        return map;
    }

    private static Set<String> aliases(String... names) {
        return new LinkedHashSet<>(Arrays.asList(names));
    }

    public List<RawRow> parse(String fileName, InputStream input) throws IOException {
        return toRows(StudentImportParser.parseGrid(fileName, input));
    }

    /** The same, from cells already read — used by tests and by other importers. */
    public List<RawRow> toRows(StudentImportParser.Grid grid) {
        List<Integer> usernameColumns = columns(grid.header(), Field.USERNAME);
        List<Integer> nameColumns = columns(grid.header(), Field.NAME);
        List<Integer> classesColumns = columns(grid.header(), Field.CLASSES);

        List<String> missing = new ArrayList<>();
        if (usernameColumns.isEmpty()) {
            missing.add("username (accepted headings: " + HEADER_ALIASES.get(Field.USERNAME) + ")");
        }
        if (nameColumns.isEmpty()) {
            missing.add("name (accepted headings: " + HEADER_ALIASES.get(Field.NAME) + ")");
        }
        if (classesColumns.isEmpty()) {
            missing.add("classes (accepted headings: " + HEADER_ALIASES.get(Field.CLASSES) + ")");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "The uploaded file is missing required column(s): " + String.join("; ", missing)
                            + ". Found headings: " + grid.header());
        }

        List<Integer> emailColumns = columns(grid.header(), Field.EMAIL);
        List<Integer> passwordColumns = columns(grid.header(), Field.PASSWORD);

        List<RawRow> rows = new ArrayList<>(grid.dataRows().size());
        int rowNumber = 2; // the first data row of a spreadsheet, counting its header
        for (List<String> cells : grid.dataRows()) {
            Map<Field, String> values = new LinkedHashMap<>();
            values.put(Field.USERNAME, join(cells, usernameColumns));
            values.put(Field.NAME, join(cells, nameColumns));
            values.put(Field.EMAIL, join(cells, emailColumns));
            values.put(Field.CLASSES, join(cells, classesColumns));
            values.put(Field.PASSWORD, join(cells, passwordColumns));
            rows.add(new RawRow(rowNumber++, values));
        }
        return rows;
    }

    /** Every column position matching one of a field's accepted headings. */
    private static List<Integer> columns(List<String> header, Field field) {
        Set<Integer> matches = new LinkedHashSet<>();
        for (int i = 0; i < header.size(); i++) {
            String key = StudentImportParser.normaliseHeader(header.get(i));
            if (key.isEmpty()) {
                continue;
            }
            for (String alias : HEADER_ALIASES.get(field)) {
                if (StudentImportParser.normaliseHeader(alias).equals(key)) {
                    matches.add(i);
                    break;
                }
            }
        }
        return new ArrayList<>(matches);
    }

    /** The non-blank cells of the matching columns, joined so two columns both count. */
    private static String join(List<String> cells, Collection<Integer> columns) {
        StringBuilder joined = new StringBuilder();
        for (Integer index : columns) {
            if (index == null || index >= cells.size()) {
                continue;
            }
            String value = cells.get(index);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(';');
            }
            joined.append(value.trim());
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    /**
     * Splits a classes cell into the class names it names, or returns an empty
     * list when it names none — which the service reports as a row error rather
     * than accepting a teacher who could help nobody.
     */
    public static List<String> parseClasses(String raw) {
        List<String> classes = new ArrayList<>();
        if (raw == null) {
            return classes;
        }
        for (String part : raw.split(CLASS_SEPARATORS)) {
            String className = StudentPasswordPolicy.normalizeClass(part);
            if (className != null && !className.isEmpty() && !classes.contains(className)) {
                classes.add(className);
            }
        }
        return classes;
    }
}
