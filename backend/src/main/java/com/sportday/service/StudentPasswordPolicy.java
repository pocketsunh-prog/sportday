package com.sportday.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Generates student passwords from the student's own record.
 *
 * <p>Rule: <code>yyyyMMdd(dob) + class + class number</code> with no separators.
 * For example a student born 2010-03-15, in class {@code 5A}, class number 12
 * has the password <code>201003155A12</code>.</p>
 *
 * <p>The class code is upper-cased and stripped of whitespace before use, so
 * {@code " 5a "} and {@code "5A"} produce the same password.</p>
 */
@Component
public class StudentPasswordPolicy {

    /** Compact ISO date, e.g. {@code 20100315}. */
    public static final DateTimeFormatter DOB_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** The password for this student record. Never stored in clear text. */
    public String generate(LocalDate dob, String className, Integer classNumber) {
        if (dob == null) {
            throw new IllegalArgumentException("date of birth is required to generate a student password");
        }
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("class is required to generate a student password");
        }
        if (classNumber == null) {
            throw new IllegalArgumentException("class number is required to generate a student password");
        }
        return dob.format(DOB_FORMAT) + normalizeClass(className) + classNumber;
    }

    /**
     * How the rule is described to students and printed on the login page.
     */
    public String describeRule() {
        return "date of birth as yyyyMMdd + class + class number, e.g. 201003155A12";
    }

    public static String normalizeClass(String className) {
        return className == null ? null : className.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
