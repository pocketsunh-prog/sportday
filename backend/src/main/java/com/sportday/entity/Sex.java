package com.sportday.entity;

/**
 * Sex of a student, and the sex division an event is offered in.
 * School sport days run separate boys' and girls' competitions, so every
 * {@link Event} belongs to exactly one division.
 */
public enum Sex {

    MALE("M", "男", "Boys"),
    FEMALE("F", "女", "Girls");

    private final String code;
    private final String labelZh;
    private final String labelEn;

    Sex(String code, String labelZh, String labelEn) {
        this.code = code;
        this.labelZh = labelZh;
        this.labelEn = labelEn;
    }

    /** Single-letter code used in CSV/XLSX columns: {@code M} or {@code F}. */
    public String getCode() {
        return code;
    }

    public String getLabelZh() {
        return labelZh;
    }

    public String getLabelEn() {
        return labelEn;
    }

    /** Human readable combined label, e.g. {@code 男 Boys}. */
    public String getLabel() {
        return labelZh + " " + labelEn;
    }

    /**
     * Tolerant parser for imported spreadsheets: accepts {@code M}, {@code m},
     * {@code Male}, {@code 男}, {@code B}, {@code Boys} and the equivalent
     * female spellings. Returns {@code null} when the value is not recognised
     * so callers can report a row level import error.
     */
    public static Sex fromCode(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        switch (value.toUpperCase()) {
            case "M":
            case "MALE":
            case "BOY":
            case "BOYS":
            case "男":
            case "B":
                return MALE;
            case "F":
            case "FEMALE":
            case "GIRL":
            case "GIRLS":
            case "女":
            case "G":
                return FEMALE;
            default:
                return null;
        }
    }
}
