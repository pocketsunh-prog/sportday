package com.sportday.dto;

import com.sportday.entity.EventRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A school record: the mark that stands, who holds it, what it beat, and the
 * baseline an administrator entered.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventRecordDTO {

    private Long id;

    private String eventType;
    private String eventTypeLabel;
    private String category;
    private String categoryLabel;

    private String sex;
    private String sexLabel;

    private String grade;
    private String gradeLabel;

    /** The mark that stands — the best of the baseline and the results. */
    private BigDecimal mark;
    private String unit;

    /**
     * {@link #mark} as it reads, with its unit: {@code 14.123s} on a sprint,
     * {@code 1.04.123s} on a race timed on a stopwatch (the 400M and over, and both
     * relays), {@code 18.12M} in the field. A record shown beside a result must read
     * the same way that result does, so this is written by the same
     * {@code MarkFormatter} the results and the sheet use.
     */
    private String displayMark;

    /** {@code BASELINE}, {@code RESULT}, or {@code NONE} while the record is empty. */
    private String source;

    private Long holderUserId;
    private String holderStudentRef;
    private String holderName;

    private Long eventId;
    private String eventName;

    private LocalDate achievedOn;

    // --------------------------------------------- the administrator's baseline

    private BigDecimal manualMark;
    private String manualUnit;
    private String manualHolderName;
    private LocalDate manualAchievedOn;

    // --------------------------------------------- what the standing mark beat

    private BigDecimal previousMark;
    private String previousHolderName;
    private LocalDate previousAchievedOn;
    private Boolean hasPrevious;

    /**
     * {@link #previousMark} as it reads, with its unit — the record that stood
     * before this one. Written the same way {@link #displayMark} is, so the "what
     * it beat" line reads in the same shape as the mark that beat it.
     */
    private String previousDisplayMark;

    private LocalDateTime updatedAt;

    public static EventRecordDTO from(EventRecord record, String holderStudentRef) {
        var type = record.getEventType();
        return EventRecordDTO.builder()
                .id(record.getId())
                .eventType(type == null ? null : type.name())
                .eventTypeLabel(type == null ? null : type.getDisplayName())
                .category(type == null ? null : type.getCategory().name())
                .categoryLabel(type == null ? null : type.getCategory().getLabel())
                .sex(record.getSex() == null ? null : record.getSex().name())
                .sexLabel(record.getSex() == null ? null : record.getSex().getLabel())
                .grade(record.getGrade() == null ? null : record.getGrade().name())
                .gradeLabel(record.getGrade() == null ? null : record.getGrade().getLabel())
                .mark(record.getMark())
                .unit(record.getUnit())
                // The mark reads the way a result of the same event reads: the shape
                // comes from the one formatter, so the records page and the results
                // page cannot spell a 400M two ways.
                .displayMark(com.sportday.service.MarkFormatter.formatWithUnit(
                        record.getMark(), type, record.getUnit()))
                .source(record.getSource().name())
                .holderUserId(record.getHolder() == null ? null : record.getHolder().getId())
                .holderStudentRef(holderStudentRef)
                .holderName(record.getHolderName())
                .eventId(record.getEvent() == null ? null : record.getEvent().getId())
                .eventName(record.getEvent() == null ? null : record.getEvent().getName())
                .achievedOn(record.getAchievedOn())
                .manualMark(record.getManualMark())
                .manualUnit(record.getManualUnit())
                .manualHolderName(record.getManualHolderName())
                .manualAchievedOn(record.getManualAchievedOn())
                .previousMark(record.getPreviousMark())
                .previousHolderName(record.getPreviousHolderName())
                .previousAchievedOn(record.getPreviousAchievedOn())
                .hasPrevious(record.getHasPrevious())
                .previousDisplayMark(com.sportday.service.MarkFormatter.formatWithUnit(
                        record.getPreviousMark(), type, record.getUnit()))
                .updatedAt(record.getUpdatedAt())
                .build();
    }
}
