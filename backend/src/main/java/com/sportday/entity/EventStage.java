package com.sportday.entity;

/**
 * Which stage of an event a group or a result belongs to.
 *
 * <p>Short sprints — 60/100/200/400 — are run in two stages: everyone runs a
 * heat, and the fastest eight go through to the final. The other events run as a
 * single straight final, which is modelled as {@link #HEAT} so that everything
 * that existed before this stage split keeps working unchanged.</p>
 *
 * <p>Heat and final marks are stored separately, so a final time never overwrites
 * the heat time that earned the athlete their place.</p>
 */
public enum EventStage {

    HEAT("Heat", "初賽"),
    FINAL("Final", "決賽");

    private final String labelEn;
    private final String labelZh;

    EventStage(String labelEn, String labelZh) {
        this.labelEn = labelEn;
        this.labelZh = labelZh;
    }

    public String getLabelEn() {
        return labelEn;
    }

    public String getLabelZh() {
        return labelZh;
    }

    public static EventStage fromCode(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("FINAL") || value.equals("決賽") || value.equals("F")) {
            return FINAL;
        }
        if (value.startsWith("HEAT") || value.equals("初賽") || value.equals("H")) {
            return HEAT;
        }
        return null;
    }
}
