package com.sportday.dto;

import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventDTO {
    private Long id;
    private String name;
    private String description;

    /** Enum name, e.g. {@code RUN_100M}. */
    private String type;

    /** Printable event name, e.g. {@code 100M}. */
    private String typeLabel;

    /** {@code TRACK} (徑項), {@code FIELD} (田項) or {@code RELAY} (接力). */
    private String category;
    private String categoryLabel;

    /** {@code MALE} or {@code FEMALE} — the division this event is run in. */
    private String sex;
    private String sexLabel;

    /**
     * {@code A}, {@code B} or {@code C} — the one grade that competes in this event.
     * Required: an event without a grade would rank one grade against another.
     */
    private String grade;
    private String gradeLabel;

    /**
     * The <strong>form</strong> this relay is scoped to — {@code 1} for a "Form 1
     * 4x100M" whose teams are {@code 1A}, {@code 1B}, {@code 1C} and {@code 1D} — or
     * null for every event scoped by grade, which is all of them today.
     *
     * <p>Only a relay divided into {@code FORM} teams may carry one; a create or an
     * update that would give one to a sprint, or to a house relay, is refused. On an
     * update, null means "leave it alone" and an <strong>empty string clears it</strong>,
     * exactly as {@link #relayTeamKind} behaves. A form event admits students of any
     * grade so long as they are in that form and the event's division, so null is the
     * whole of the old behaviour.</p>
     */
    private String form;

    /** Printable form, {@code Form 1}; null when the event is graded. */
    private String formLabel;

    private LocalDate eventDate;
    private String location;
    private Integer maxParticipants;

    /** Athletes per heat: 8 for 60/100/200/400, 24 otherwise. */
    private Integer groupSize;

    /** True when this event is laid out 8 to a group on an A5 marking sheet. */
    private Boolean shortSprint;
    /** {@code A5} or {@code A4} — the marking sheet paper size for this event. */
    private String sheetSize;

    /**
     * The unit this event is recorded in: {@code s} for a track event, {@code M}
     * for a field one. What the entry page and the mark grid show next to the box.
     */
    private String defaultUnit;

    /**
     * True when the event is decided by its own run and no final is drawn. The
     * default for a new event, and what most of a school day is.
     */
    private Boolean directToFinal;

    /** True when this event may be run as heats and a final: only 60/100/200/400. */
    private Boolean mayHaveFinal;

    /**
     * True when a final is actually in play: the event <em>may</em> have one and the
     * school has not set it to run straight to a final.
     *
     * <p>{@link #directToFinal} and {@link #mayHaveFinal} already say this between
     * them, but only to a client willing to combine them. Stated here, the three
     * final states an event can be in read straight off the DTO:
     * {@code mayHaveFinal=false} — no final stage at all, so there is no final to
     * wait for; {@code runsAFinal=false} with {@code mayHaveFinal=true} — run
     * straight to a final; {@code runsAFinal=true} — heats then a final, so the
     * final's marks and sheet wait for the draw (see {@code MarkSheetDTO.finalState},
     * which says whether that draw has happened).</p>
     */
    private Boolean runsAFinal;

    /**
     * True when the system set {@link #directToFinal} because the field is no bigger
     * than a final would be, so the school can see it was not its own choice.
     */
    private Boolean directToFinalAutomatic;

    /**
     * True for a race longer than 400M, whose time is typed as minutes and seconds.
     * The event list says so rather than making the entry page work it out.
     */
    private Boolean timeInMinutes;

    /** True when this event is a relay — 4x100M or 4x400M. */
    private Boolean relay;

    /**
     * {@code FORM} or {@code HOUSE} — how this relay's teams are divided into one
     * team per form, or one per house within the event's grade. <strong>Null is
     * meaningful and normal</strong>: a relay with no kind is simply undivided,
     * which is how the relay events already in the programme behave. Only a relay
     * may carry one; an empty string on an update clears it.
     */
    private String relayTeamKind;

    /** Printable kind, {@code Form} or {@code House}. */
    private String relayTeamKindLabel;

    /**
     * Legs in a team — four for a 4x100M or a 4x400M. Only meaningful for a relay,
     * where it is the race's own size rather than a constant, so a school running a
     * longer squad changes the event rather than the code.
     */
    private Integer relayTeamSize;

    /** True when a team may also name reserves past the race's own legs. */
    private Boolean relayReservesAllowed;

    /**
     * True when this event may be marked and printed <em>now</em>: a relay whose
     * teams are built, or any event that is not a relay.
     *
     * <p>It is {@code RelayReadiness}'s own verdict, stated here so a list can leave
     * a half-built relay out without asking per event. <strong>An individual event
     * is always ready</strong> — the rule is about a relay's teams and a race of
     * athletes has none — so no marks or print page changes for one. A relay with
     * fewer than two teams, or with a team short of its runners, reports false and
     * carries the reason in {@link #readinessReason}.</p>
     *
     * <p>Showing is not refusing: the mark grid and the print run refuse a
     * not-ready relay whether or not a client looked at this flag.</p>
     */
    private Boolean relayReady;

    /**
     * Why this event cannot be marked yet, or null when it can — the same sentence
     * {@code RelayReadiness} puts in the 409 a direct call is refused with, so the
     * list and the refusal cannot drift apart about what is missing.
     */
    private String readinessReason;

    /**
     * True when this event is a <strong>draft</strong>: a relay event made to hold the
     * teams the school is building by hand, before the race itself is real.
     *
     * <p>Null in a request means "an ordinary event", which is what every caller that
     * does not know about drafts is sending. A draft is never on the programme — the
     * listing, the date picker, the past-events list and the results print run all
     * exclude it — so a client only ever sees {@code true} on the draft's own board,
     * in the administrator's draft list, or on an event it deliberately asked to
     * create as one.</p>
     */
    private Boolean draft;

    /** How many runners one team may hold in total, reserves included. */
    private Integer relayMemberCap;

    private Boolean enabled;
    private LocalDateTime createdAt;
    private Integer enrolledCount;

    /** Number of heats currently generated for this event. */
    private Long groupCount;

    /** Confirmed entries not yet placed in a heat. */
    private Long ungroupedCount;

    /**
     * How many relay teams this event holds. Filled in by
     * {@code EventService.createDraftEvent} on the draft it has just made, so the
     * answer reports the teams it took; a draft's board is the full account.
     */
    private Integer teamCount;

    /**
     * How many events of this category a student may enter. Filled in by
     * {@code EventService} from the editable settings rather than from a constant,
     * so the entry page reflects whatever an administrator has configured.
     */
    private Integer maxEntriesPerStudent;

    /** The school year this event belongs to. */
    private Long seasonId;
    private Integer seasonYear;
    private String seasonName;

    public static EventDTO from(Event event) {
        Event.EventType type = event.getType();
        EventCategory category = event.getCategoryOrDefault();
        return EventDTO.builder()
                .id(event.getId())
                .name(event.getName())
                .description(event.getDescription())
                .type(type == null ? null : type.name())
                .typeLabel(type == null ? null : type.getDisplayName())
                .category(category.name())
                .categoryLabel(category.getLabel())
                .sex(event.getSex() == null ? null : event.getSex().name())
                .sexLabel(event.getSex() == null ? null : event.getSex().getLabel())
                .grade(event.getGrade() == null ? null : event.getGrade().name())
                .gradeLabel(event.getGrade() == null ? null : event.getGrade().getLabel())
                .form(event.getForm())
                .formLabel(event.getFormLabel())
                .eventDate(event.getEventDate())
                .location(event.getLocation())
                .maxParticipants(event.getMaxParticipants())
                .groupSize(event.getGroupSize())
                .shortSprint(event.isShortSprint())
                .sheetSize(event.isShortSprint() ? "A5" : "A4")
                .defaultUnit(type == null ? null : type.getDefaultUnit())
                .directToFinal(event.isDirectToFinal())
                .directToFinalAutomatic(Boolean.TRUE.equals(event.getDirectToFinalAuto()))
                .timeInMinutes(event.usesMinutesAndSeconds())
                .mayHaveFinal(event.mayHaveFinal())
                .runsAFinal(event.runsAFinal())
                .relay(event.isRelay())
                .relayTeamKind(event.getRelayTeamKind() == null ? null : event.getRelayTeamKind().name())
                .relayTeamKindLabel(event.getRelayTeamKind() == null
                        ? null : event.getRelayTeamKind().getLabel())
                .relayTeamSize(event.getEffectiveRelayTeamSize())
                .relayReservesAllowed(event.isRelayReservesAllowed())
                // An individual event is ready by definition — the rule is about a
                // relay's teams, and a race of athletes has none — so it is answered
                // here rather than left to a caller. A relay is only answered by a
                // caller that has read its teams: `EventService` fills both fields in
                // from `RelayReadiness` as it describes the event.
                .relayReady(!event.isRelay())
                .relayMemberCap(event.getRelayMemberCap())
                .draft(event.isDraft())
                .enabled(event.getEnabled())
                .createdAt(event.getCreatedAt())
                .seasonId(event.getSeason() == null ? null : event.getSeason().getId())
                .seasonYear(event.getSeason() == null ? null : event.getSeason().getYear())
                .seasonName(event.getSeason() == null ? null : event.getSeason().getDisplayName())
                .build();
    }

    public static EventDTO from(Event event, int enrolledCount) {
        EventDTO dto = from(event);
        dto.setEnrolledCount(enrolledCount);
        return dto;
    }
}
