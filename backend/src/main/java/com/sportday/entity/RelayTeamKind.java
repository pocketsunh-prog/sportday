package com.sportday.entity;

/**
 * How a relay event's teams are divided — requirement 3, "have form class relay
 * base on each form and house relay base on each grade and house".
 *
 * <ul>
 *   <li>{@link #FORM} — one team per <strong>form</strong> (中一 to 中六) of the
 *       event's own grade and division. A form is the leading number of
 *       {@link Student#getClassName()} — {@code 1A}, {@code 1B} and {@code 1C} are
 *       all Form 1 — so {@code 10B} is Form 10 rather than Form 1, which is why the
 *       form is read as a whole run of digits and never as a single character.</li>
 *   <li>{@link #HOUSE} — one team per <strong>house</strong> within the event's
 *       grade. An event already belongs to exactly one grade, so a house relay is
 *       one team per house present among that grade's students in the event's
 *       division — e.g. the A Grade 4x100M has a Red, a Blue and a Green team.</li>
 * </ul>
 *
 * <p>The kind lives on {@link Event} and is <strong>nullable</strong>: a relay with
 * no kind is simply undivided, which is how the relay events already in the
 * programme behave. Nothing is forced onto them, so adding this feature changes no
 * existing event, team or result.</p>
 */
public enum RelayTeamKind {

    /** One team per form of the event's grade — 中一 to 中六. */
    FORM("Form", "班際"),

    /** One team per house within the event's grade — Red, Blue, Green, Yellow. */
    HOUSE("House", "社際");

    private final String label;
    private final String labelZh;

    RelayTeamKind(String label, String labelZh) {
        this.label = label;
        this.labelZh = labelZh;
    }

    public String getLabel() {
        return label;
    }

    /** The Chinese label, as the rest of the domain prints it. */
    public String getLabelZh() {
        return labelZh;
    }

    /**
     * Tolerant parser for a request. {@code FORM}, {@code CLASS} and {@code HOUSE}
     * are accepted, in any case and with surrounding space; anything else — and a
     * blank string — returns {@code null}, so the caller decides whether that is a
     * refusal or "leave the event alone".
     */
    public static RelayTeamKind fromCode(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        return switch (value) {
            // The school calls a form relay a "class relay" as often as a form one.
            case "FORM", "CLASS" -> FORM;
            case "HOUSE" -> HOUSE;
            default -> null;
        };
    }

    /**
     * The label a team of this kind is shown under. A form team reads
     * {@code Form 1}; a house team reads as the house itself — {@code Red} — because
     * the register already names it in full and "Red House House" helps nobody.
     */
    public String labelFor(String teamKey) {
        if (teamKey == null || teamKey.isBlank()) {
            return label;
        }
        return this == FORM ? label + " " + teamKey.trim() : teamKey.trim();
    }
}
