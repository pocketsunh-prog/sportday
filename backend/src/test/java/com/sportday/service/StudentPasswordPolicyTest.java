package com.sportday.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Requirement 1: the student password is derived from the record as
 * {@code yyyyMMdd + class + class number} with no separators.
 */
class StudentPasswordPolicyTest {

    private final StudentPasswordPolicy policy = new StudentPasswordPolicy();

    @Test
    @DisplayName("2010-03-15 / 5A / 12 -> 201003155A12")
    void buildsTheDocumentedPassword() {
        assertEquals("201003155A12",
                policy.generate(LocalDate.of(2010, 3, 15), "5A", 12));
    }

    @Test
    @DisplayName("single-digit days and months are still zero padded")
    void zeroPadsTheDate() {
        assertEquals("201401052C3",
                policy.generate(LocalDate.of(2014, 1, 5), "2C", 3));
    }

    @Test
    @DisplayName("the class code is upper-cased and stripped of whitespace")
    void normalisesTheClassCode() {
        assertEquals("201003155A12", policy.generate(LocalDate.of(2010, 3, 15), " 5a ", 12));
        assertEquals("201003155A12", policy.generate(LocalDate.of(2010, 3, 15), "5 a", 12));
        assertEquals("5A", StudentPasswordPolicy.normalizeClass("5a"));
        assertEquals("6D", StudentPasswordPolicy.normalizeClass(" 6 d "));
    }

    @Test
    @DisplayName("a three-digit class number is kept whole")
    void handlesLargeClassNumbers() {
        assertEquals("201003155A100",
                policy.generate(LocalDate.of(2010, 3, 15), "5A", 100));
    }

    @Test
    @DisplayName("incomplete records are rejected rather than producing a weak password")
    void rejectsIncompleteRecords() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> policy.generate(null, "5A", 12)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> policy.generate(LocalDate.of(2010, 3, 15), null, 12)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> policy.generate(LocalDate.of(2010, 3, 15), "  ", 12)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> policy.generate(LocalDate.of(2010, 3, 15), "5A", null)));
    }

    @Test
    @DisplayName("the rule is described for the login page")
    void describesTheRule() {
        assertTrue(policy.describeRule().contains("yyyyMMdd"));
        assertTrue(policy.describeRule().contains("201003155A12"));
    }
}
