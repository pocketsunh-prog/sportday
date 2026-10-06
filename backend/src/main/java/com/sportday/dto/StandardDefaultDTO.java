package com.sportday.dto;

import com.sportday.entity.Event;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.StandardDefault;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One <strong>default required standard</strong> — the number a whole event type,
 * grade and sex division inherits — as {@code GET /api/admin/standard-defaults}
 * lists it.
 *
 * <p>The key is spelled out in full, with the labels a page renders, so the client
 * never has to map an enum to a heading of its own and cannot come to disagree with
 * the server about which grade or division a row is for.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StandardDefaultDTO {

    /** Enum name, e.g. {@code RUN_400M}. */
    private String type;

    /** Human label, e.g. {@code 400M}. */
    private String typeLabel;

    /**
     * The event category the type belongs to — {@code TRACK}, {@code FIELD} or
     * {@code RELAY}. Every default is for a qualifying type, so this is never
     * {@code RELAY}; it is here so a page can group by family without a second lookup.
     */
    private String category;

    /** {@code A}, {@code B} or {@code C}. */
    private String grade;

    /** e.g. {@code A Grade}. */
    private String gradeLabel;

    /** {@code MALE} or {@code FEMALE} — part of the key, never omitted. */
    private String sex;

    /** e.g. {@code 男 Boys}. */
    private String sexLabel;

    /** The qualifying mark, or null for "this school sets no standard here". */
    private BigDecimal standard;

    /** e.g. {@code 64 s}. The default with its unit, for a heading or a table. */
    private String standardLabel;

    /**
     * The unit the mark is in — {@code s} on the track, {@code M} in the field —
     * from the type itself, so a page labels the box from the server's own answer.
     */
    private String unit;

    /**
     * The key as one string — {@code RUN_400M|A|MALE} — so a client can match a
     * default to an event, or to a row of its own grid, without re-implementing the
     * join. One place spells it, so two lookups cannot disagree.
     */
    private String key;

    /** The event this row is about, when the row describes an event rather than a key. */
    private Long eventId;

    /** The event's own name — {@code Boys 400M · A Grade} — when it describes one. */
    private String eventName;

    /** Whether that event's standard now follows a default rather than being its own. */
    private Boolean standardFromDefault;

    public static StandardDefaultDTO from(StandardDefault value) {
        Event.EventType type = value.getType();
        Grade grade = value.getGrade();
        Sex sex = value.getSex();
        BigDecimal standard = type != null && type.carriesAStandard() ? value.getStandard() : null;
        return StandardDefaultDTO.builder()
                .type(type == null ? null : type.name())
                .typeLabel(type == null ? null : type.getDisplayName())
                .category(type == null ? null : type.getCategory().name())
                .grade(grade == null ? null : grade.name())
                .gradeLabel(grade == null ? null : grade.getLabel())
                .sex(sex == null ? null : sex.name())
                .sexLabel(sex == null ? null : sex.getLabel())
                .standard(standard)
                .standardLabel(standard == null || type == null
                        ? null
                        : standard.stripTrailingZeros().toPlainString() + " " + type.getDefaultUnit())
                .unit(type == null ? null : type.getDefaultUnit())
                .key(type == null || grade == null || sex == null ? null : keyOf(type, grade, sex))
                .build();
    }

    /**
     * The same shape, describing an <strong>event</strong> rather than a default.
     *
     * <p>An apply has to name the events it changed, and they are the same four facts
     * a default carries — type, grade, division and a number. Rather than invent a
     * second nearly identical DTO, the event's own id and name are filled in and
     * {@link #standard} is the number it now holds.</p>
     */
    public static StandardDefaultDTO fromEvent(Event event) {
        Event.EventType type = event.getType();
        // Built without `.standard(...)` when the event holds none, because Lombok's
        // builder rejects a null in a primitive-shaped slot by name — the point of
        // this shape is that "no standard" is a legitimate answer, not an error.
        StandardDefaultDTO described = from(StandardDefault.builder()
                .type(type)
                .grade(event.getGrade())
                .sex(event.getSex())
                .build());
        if (event.getStandard() != null) {
            described.setStandard(event.getStandard());
            described.setStandardLabel(type == null
                    ? null
                    : event.getStandard().stripTrailingZeros().toPlainString()
                            + " " + type.getDefaultUnit());
        }
        described.setEventId(event.getId());
        described.setEventName(event.getName());
        described.setStandardFromDefault(event.isStandardInherited());
        return described;
    }

    /**
     * The one spelling of the key — {@code RUN_400M|A|MALE}. A client matches a
     * default to an event with it rather than re-implementing the join, so two
     * lookups cannot disagree about what "the same key" means.
     */
    public static String keyOf(Event.EventType type, Grade grade, Sex sex) {
        return type + "|" + grade + "|" + sex;
    }
}
