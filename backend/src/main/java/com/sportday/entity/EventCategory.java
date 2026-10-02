package com.sportday.entity;

/**
 * The two families of athletics event a student may enter.
 *
 * <p>A student may enter at most <strong>two</strong> {@link #TRACK} events
 * (徑項) and at most <strong>one</strong> {@link #FIELD} event (田項).</p>
 */
public enum EventCategory {

    TRACK("徑項", "Track"),
    FIELD("田項", "Field");

    private final String labelZh;
    private final String labelEn;

    EventCategory(String labelZh, String labelEn) {
        this.labelZh = labelZh;
        this.labelEn = labelEn;
    }

    public String getLabelZh() {
        return labelZh;
    }

    public String getLabelEn() {
        return labelEn;
    }

    public String getLabel() {
        return labelZh + " " + labelEn;
    }

    /** Maximum number of events in this category a single student may enter. */
    public int getMaxEntriesPerStudent() {
        return this == TRACK ? 2 : 1;
    }

    public static EventCategory fromCode(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("TRACK") || value.equals("徑") || value.equals("徑項") || value.equals("T")) {
            return TRACK;
        }
        if (value.startsWith("FIELD") || value.equals("田") || value.equals("田項") || value.equals("F")) {
            return FIELD;
        }
        return null;
    }
}
