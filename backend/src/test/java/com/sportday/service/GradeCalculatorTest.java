package com.sportday.service;

import com.sportday.entity.Grade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Requirement 6: grade is derived from date of birth against a reference date
 * (the sport day) — C = 14 or below, B = 15-16, A = 17 or above.
 */
class GradeCalculatorTest {

    private final LocalDate sportDay = LocalDate.of(2026, 10, 1);

    private GradeCalculator calculator() {
        // No configured reference date; tests always pass one explicitly.
        return new GradeCalculator("");
    }

    @Test
    @DisplayName("the configured reference date falls back to today when unset")
    void fallsBackToToday() {
        GradeCalculator calculator = new GradeCalculator("");
        assertEquals(LocalDate.now(), calculator.referenceDate());
        assertFalse(calculator.isReferenceDateConfigured());
    }

    @Test
    @DisplayName("a configured reference date is used verbatim")
    void usesConfiguredReferenceDate() {
        GradeCalculator calculator = new GradeCalculator("2026-11-06");
        assertEquals(LocalDate.of(2026, 11, 6), calculator.referenceDate());
        assertTrue(calculator.isReferenceDateConfigured());
    }

    @Test
    @DisplayName("a malformed reference date is rejected at construction")
    void rejectsMalformedReferenceDate() {
        assertThrows(IllegalStateException.class, () -> new GradeCalculator("06/11/2026"));
    }

    @ParameterizedTest(name = "born {0} -> age {1} -> grade {2} on the sport day")
    @CsvSource({
            // exactly 14 on the day -> C (the top of the C band)
            "2012-10-01, 14, C",
            // one day short of 15 -> still 14 -> C
            "2011-10-02, 14, C",
            // turns 15 on the day -> B (the bottom of the B band)
            "2011-10-01, 15, B",
            "2010-10-01, 16, B",
            // one day short of 17 -> still 16 -> B (the top of the B band)
            "2009-10-02, 16, B",
            // turns 17 on the day -> A (the bottom of the A band)
            "2009-10-01, 17, A",
            "2008-10-01, 18, A",
            "2000-01-01, 26, A",
    })
    void mapsAgeOntoGradeBands(String dob, int expectedAge, String expectedGrade) {
        LocalDate birth = LocalDate.parse(dob);
        GradeCalculator calculator = calculator();
        assertEquals(expectedAge, calculator.ageOn(birth, sportDay));
        assertEquals(Grade.valueOf(expectedGrade), calculator.gradeFor(birth, sportDay));
    }

    @Test
    @DisplayName("a leap-day birthday ages correctly")
    void handlesLeapDayBirthdays() {
        GradeCalculator calculator = calculator();
        LocalDate born = LocalDate.of(2012, 2, 29);
        // 29 Feb 2012 -> 1 Oct 2026 is 14 years and 7 months.
        assertEquals(14, calculator.ageOn(born, LocalDate.of(2026, 10, 1)));
        assertEquals(Grade.C, calculator.gradeFor(born, LocalDate.of(2026, 10, 1)));
        assertEquals(15, calculator.ageOn(born, LocalDate.of(2027, 10, 1)));
    }

    @Test
    @DisplayName("the reference date changes who is in which grade")
    void gradeDependsOnReferenceDate() {
        GradeCalculator calculator = calculator();
        LocalDate born = LocalDate.of(2011, 9, 1);
        assertEquals(Grade.C, calculator.gradeFor(born, LocalDate.of(2026, 8, 1)));
        assertEquals(Grade.B, calculator.gradeFor(born, LocalDate.of(2026, 10, 1)));
    }

    @Test
    @DisplayName("the grade boundaries are exactly 14/15/16/17")
    void boundariesAreInclusive() {
        assertAll(
                () -> assertEquals(Grade.C, Grade.fromAge(0)),
                () -> assertEquals(Grade.C, Grade.fromAge(14)),
                () -> assertEquals(Grade.B, Grade.fromAge(15)),
                () -> assertEquals(Grade.B, Grade.fromAge(16)),
                () -> assertEquals(Grade.A, Grade.fromAge(17)),
                () -> assertEquals(Grade.A, Grade.fromAge(40)));
    }

    @Test
    @DisplayName("a date of birth after the reference date is rejected")
    void rejectsFutureBirthDate() {
        GradeCalculator calculator = calculator();
        assertThrows(IllegalArgumentException.class,
                () -> calculator.gradeFor(LocalDate.of(2027, 1, 1), sportDay));
    }

    @Test
    @DisplayName("a missing date of birth is rejected")
    void rejectsMissingBirthDate() {
        GradeCalculator calculator = calculator();
        assertThrows(IllegalArgumentException.class, () -> calculator.gradeFor(null, sportDay));
    }
}
