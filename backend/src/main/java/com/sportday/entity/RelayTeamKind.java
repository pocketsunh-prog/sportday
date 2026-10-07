package com.sportday.entity;

/**
 * How a relay event's teams are divided — requirement 3, "have form class relay
 * base on each form and house relay base on each grade and house".
 *
 * <ul>
 *   <li>{@link #FORM} — a form relay's teams are <strong>the classes its entrants are
 *       in</strong>: one team per class of the event's form that a student has
 *       <em>entered</em> the relay from — {@code 3A} and {@code 3B} when the Form 3
 *       relay's entrants are in those two classes — and the school wants at least two of
 *       them. It is the entries, not the register's first two classes, that decide which
 *       teams exist; fewer than two classes with an entrant is not an error, it is simply
 *       a relay that is not ready to be marked yet. An event with no form keeps the older
 *       reading — one team per class of its own grade and division: {@code 1A},
 *       {@code 1B}, {@code 1C}, {@code 1D}, then {@code 2A}. A team's name is the class
 *       name, and a class is taken from {@link Student#getClassName()} whole, so
 *       {@code 10B} is class {@code 10B} rather than being folded in with
 *       {@code 1B}.</li>
 *   <li>{@link #HOUSE} — one team per <strong>house</strong> within the event's
 *       grade. An event already belongs to exactly one grade, so a house relay is
 *       one team per house present among that grade's students in the event's
 *       division — e.g. the C Grade 4x100M has a Yellow, a Green and a Red team,
 *       which the school writes as {@code C Grade Yellow}.</li>
 * </ul>
 *
 * <p>The kind lives on {@link Event} and is <strong>nullable</strong>: a relay with
 * no kind is simply undivided, which is how the relay events already in the
 * programme behave. Nothing is forced onto them, so adding this feature changes no
 * existing event, team or result.</p>
 */
public enum RelayTeamKind {

    /** A form relay's teams — one per class that entered it, 中一 to 中六. */
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
     * The label a team of this kind is shown under — which is its own key.
     *
     * <p>A class team is named by its <strong>class</strong>: {@code 1A}, never
     * "Form 1A", because a class is what the school enters and what it writes on the
     * sheet. A house team reads as the house itself — {@code Red}, or {@code C Grade
     * Yellow} once the house relay names it that way — because the register already
     * names it in full and "Red House House" helps nobody.</p>
     */
    public String labelFor(String teamKey) {
        if (teamKey == null || teamKey.isBlank()) {
            return label;
        }
        return teamKey.trim();
    }

    /**
     * The label a team of this kind is shown under, read together with the grade its
     * event belongs to.
     *
     * <p>A <strong>house</strong> team reads {@code C Grade Yellow}: an event already
     * belongs to exactly one grade, so a house team <em>is</em> that grade's team, and
     * the school writes the two together — "Yellow" alone names no race. A
     * <strong>class</strong> team is unaffected, because its class already says which
     * grade it is in: {@code 1A}, never "C Grade 1A".</p>
     *
     * <p>{@link #labelFor(String)} stays the key-only spelling, so nothing that has no
     * grade to hand has to invent one.</p>
     */
    public String labelFor(Grade grade, String teamKey) {
        String key = labelFor(teamKey);
        if (this != HOUSE || grade == null || teamKey == null || teamKey.isBlank()) {
            return key;
        }
        return grade.getLabel() + " " + key;
    }
}
