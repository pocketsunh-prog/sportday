package com.sportday.dto;

import com.sportday.entity.EventGroup;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * One heat/group plus its roster, ready to render as a marking sheet.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventGroupDTO {

    private Long id;
    private Long eventId;
    private String eventName;

    /**
     * <strong>The line the sheet heads this event with</strong>, when the event's own
     * name is not it — {@code Boys 4x100M Relay · Form 3} for a form relay the school
     * stored as {@code Boys 4x100M Relay · B Grade} — or null for an event that is not
     * a relay. On a relay whose stored name already names the right scope it is that
     * name, word for word, so the sheet prints what it always printed.
     *
     * <p>It is {@link com.sportday.entity.Event#getRelayTitle()}, resolved while the
     * group is built rather than by the renderer: the rule belongs to the event and
     * its own name, so the sheet, the print run that names it and the event list all
     * read the one derivation and cannot drift apart. The stored name is never
     * rewritten; it is the <em>heading</em> that is corrected.</p>
     *
     * @see #sheetHeading()
     */
    private String relayTitle;

    private String eventTypeLabel;

    /** The event type code, e.g. {@code RUN_800M} — what decides the time format. */
    private String eventType;
    private String category;
    private String categoryLabel;
    private String sex;
    private String sexLabel;

    /** The one grade the event is run by — what the school record is keyed on. */
    private String grade;
    private String gradeLabel;

    private Integer groupNumber;
    private String label;

    /** {@code HEAT} or {@code FINAL}. */
    private String stage;

    /** Localised label for {@link #stage}, e.g. {@code Heat 初賽}. */
    private String stageLabel;

    private Integer capacity;
    private Integer athleteCount;

    /** {@code A5} for 60/100/200/400, otherwise {@code A4}. */
    private String sheetSize;

    // ---------------------------------------------- the school record to beat

    /**
     * The event's own record, read the way every mark reads — {@code 7.406s},
     * {@code 1.04.123s}, {@code 18.12M} — or null when there is no record yet.
     *
     * <p>It belongs to the event, not to the sheet: every group of an event carries
     * the same one, and the marking sheet prints it once in its header. It is
     * resolved while the group is built rather than by the renderer, so a
     * whole-programme print run costs one record lookup per event.</p>
     */
    private String recordDisplayMark;

    /**
     * The event's <strong>required standard</strong> with its unit - {@code 64.123 s}
     * - or null for an event that carries none. Null prints no line at all, which is
     * what keeps a sheet for an event without a standard exactly as it was.
     */
    private String standardLabel;

    /** Who holds the record, when that is known. Null for a record with no name. */
    private String recordHolderName;

    /** When the record was set, when that is known. */
    private java.time.LocalDate recordAchievedOn;

    @Builder.Default
    private List<EnrollmentDTO> athletes = new ArrayList<>();

    /**
     * The <strong>relay teams this group's sheet is drawn with</strong>, in the order
     * the mark grid lists them — one entry per team, the school's own name for it
     * ({@code 1A}, {@code B Grade Green}) — or null/empty on an individual event and
     * on a relay that has no teams yet.
     *
     * <p><strong>A relay sheet's lines are the event's teams, not the heat's
     * entrants.</strong> A relay is run and scored by team, and its teams belong to
     * the <em>event</em> while {@link #athletes} is the group's own roster of
     * entries — the two are not the same list: a form relay's derived teams are that
     * form's first two classes, built from the register, and the students who happened
     * to enter the event need not be the ones running in it. Reading the team names off
     * the roster therefore printed the entrants' names on a sheet whose lines are
     * teams.
     * This field is what the sheet draws those lines from, so the paper a helper marks
     * and the grid they type into name the same teams in the same order.</p>
     *
     * <p>Resolved once per event while the group is built (see
     * {@code EventGroupService}), so a whole-programme print run costs no per-sheet
     * lookup here.</p>
     */
    private List<String> relayTeamLabels;

    /**
     * The line this group's sheet heads the event with: the relay's own title when it
     * has one, and the stored event name otherwise.
     *
     * <p>The fallback is what keeps <strong>an individual event untouched</strong>: a
     * sprint or a field event carries no relay title and prints exactly the name it
     * has always printed.</p>
     */
    public String sheetHeading() {
        return relayTitle == null || relayTitle.isBlank() ? eventName : relayTitle;
    }

    public static EventGroupDTO from(EventGroup group) {
        var event = group.getEvent();
        var stage = group.getStageOrDefault();
        return EventGroupDTO.builder()
                .id(group.getId())
                .eventId(event == null ? null : event.getId())
                .eventName(event == null ? null : event.getName())
                // A relay is headed by the scope its own kind is divided by, not by
                // whichever scope its stored name happens to carry — see Event.
                .relayTitle(event == null ? null : event.getRelayTitle())
                .eventType(event == null || event.getType() == null ? null : event.getType().name())
                .eventTypeLabel(event == null || event.getType() == null ? null : event.getType().getDisplayName())
                .category(event == null ? null : event.getCategoryOrDefault().name())
                .categoryLabel(event == null ? null : event.getCategoryOrDefault().getLabel())
                .sex(event == null || event.getSex() == null ? null : event.getSex().name())
                .sexLabel(event == null || event.getSex() == null ? null : event.getSex().getLabel())
                .grade(event == null || event.getGrade() == null ? null : event.getGrade().name())
                .gradeLabel(event == null || event.getGrade() == null ? null : event.getGrade().getLabel())
                .groupNumber(group.getGroupNumber())
                .label(group.getLabel())
                .stage(stage.name())
                .stageLabel(stage.getLabelEn() + " " + stage.getLabelZh())
                .capacity(group.getCapacity())
                .athleteCount(group.getAthleteCount())
                .sheetSize(event != null && event.isShortSprint() ? "A5" : "A4")
                .build();
    }
}
