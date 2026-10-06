package com.sportday.entity;

/**
 * The three families of athletics event a student may enter.
 *
 * <p>A student may enter at most <strong>two</strong> {@link #TRACK} events
 * (徑項) and at most <strong>one</strong> {@link #FIELD} event (田項).</p>
 *
 * <p>A {@link #RELAY} (接力) is its own family rather than a track event, because
 * it is run and scored by <strong>team</strong> and never by an individual. It
 * shares the track's quota rather than the field's, so a student may still hold
 * both a house relay and a class relay — two teams, two events — while a team
 * still runs four legs and a marking sheet prints one line per team.</p>
 */
public enum EventCategory {

    TRACK("徑項", "Track"),
    FIELD("田項", "Field"),
    RELAY("接力", "Relay");

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

    /**
     * Maximum number of events in this category a single student may enter.
     *
     * <p>A relay is two, like the track and unlike the field: the school asks that
     * an athlete may hold a leg in a <strong>house</strong> relay and a leg in a
     * <strong>class</strong> relay, and those are two events. One would forbid the
     * very thing the school asked for.</p>
     */
    public int getMaxEntriesPerStudent() {
        return this == FIELD ? 1 : 2;
    }

    /** True when a mark in this family is a distance rather than a time. */
    public boolean isMeasuredInDistance() {
        return this == FIELD;
    }

    public static EventCategory fromCode(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        if (value.isEmpty()) {
            return null;
        }
        // Relay before the others: "RELAY" does not collide with them, but reading
        // the specific one first keeps the intent obvious.
        if (value.startsWith("RELAY") || value.equals("接力") || value.equals("R")) {
            return RELAY;
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
