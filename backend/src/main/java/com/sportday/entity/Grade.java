package com.sportday.entity;

/**
 * Competition grade derived from a student's date of birth.
 *
 * <ul>
 *   <li>{@code C} — 14 years old or below</li>
 *   <li>{@code B} — 15 to 16 years old</li>
 *   <li>{@code A} — 17 years old or above</li>
 * </ul>
 *
 * The age is taken on a reference date (the sport day), see
 * {@code com.sportday.service.GradeCalculator}.
 */
public enum Grade {

    A("A Grade", "17 or above"),
    B("B Grade", "15 - 16"),
    C("C Grade", "14 or below");

    private final String label;
    private final String ageRange;

    Grade(String label, String ageRange) {
        this.label = label;
        this.ageRange = ageRange;
    }

    public String getLabel() {
        return label;
    }

    public String getAgeRange() {
        return ageRange;
    }

    /** Maps an age in whole years onto the grade bands above. */
    public static Grade fromAge(int age) {
        if (age <= 14) {
            return C;
        }
        if (age <= 16) {
            return B;
        }
        return A;
    }

    /** Tolerant parser used by imports; returns {@code null} when unrecognised. */
    public static Grade fromCode(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("A")) {
            return A;
        }
        if (value.startsWith("B")) {
            return B;
        }
        if (value.startsWith("C")) {
            return C;
        }
        return null;
    }
}
