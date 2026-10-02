package com.sportday.service;

import com.sportday.entity.Grade;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;

/**
 * Derives a student's competition grade from their date of birth.
 *
 * <p>Bands: {@code C} = 14 or below, {@code B} = 15–16, {@code A} = 17 or above.
 * Age is measured in whole years on a <em>reference date</em>, which is the day
 * the grade is being decided for (normally the sport day itself, not the day of
 * the upload). The reference date comes from
 * {@code app.grade.reference-date} and falls back to today when unset.</p>
 */
@Component
public class GradeCalculator {

    private final LocalDate configuredReferenceDate;

    public GradeCalculator(@Value("${app.grade.reference-date:}") String referenceDate) {
        this.configuredReferenceDate = parse(referenceDate);
    }

    private static LocalDate parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalStateException(
                    "app.grade.reference-date must be an ISO date (yyyy-MM-dd) but was: " + raw, ex);
        }
    }

    /** The date grades are currently computed against; today unless configured. */
    public LocalDate referenceDate() {
        return configuredReferenceDate != null ? configuredReferenceDate : LocalDate.now();
    }

    public boolean isReferenceDateConfigured() {
        return configuredReferenceDate != null;
    }

    /** Completed years between {@code dob} and {@code reference}. */
    public int ageOn(LocalDate dob, LocalDate reference) {
        if (dob == null) {
            throw new IllegalArgumentException("date of birth is required to compute a grade");
        }
        LocalDate on = reference != null ? reference : referenceDate();
        if (dob.isAfter(on)) {
            throw new IllegalArgumentException("date of birth " + dob + " is after the reference date " + on);
        }
        return Period.between(dob, on).getYears();
    }

    public Grade gradeFor(LocalDate dob, LocalDate reference) {
        return Grade.fromAge(ageOn(dob, reference));
    }

    /** Grade using the configured (or current) reference date. */
    public Grade gradeFor(LocalDate dob) {
        return gradeFor(dob, referenceDate());
    }
}
