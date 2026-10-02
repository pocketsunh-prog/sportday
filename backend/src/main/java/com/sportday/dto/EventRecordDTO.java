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
                .updatedAt(record.getUpdatedAt())
                .build();
    }
}
