package com.sportday.dto;

import com.sportday.entity.EventResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventResultDTO {
    private Long id;
    private Long userId;
    private String username;
    private String fullName;
    private Long eventId;
    private String eventName;

    /** {@code HEAT} or {@code FINAL}. */
    private String stage;

    private BigDecimal mark;
    private String unit;
    private String notes;

    /** True when this performance is the current school record for its event. */
    private Boolean newRecord;

    private LocalDateTime recordedAt;

    public static EventResultDTO from(EventResult result) {
        return EventResultDTO.builder()
                .id(result.getId())
                .userId(result.getUser().getId())
                .username(result.getUser().getUsername())
                .fullName(result.getUser().getFullName())
                .eventId(result.getEvent().getId())
                .eventName(result.getEvent().getName())
                .stage(result.getStageOrDefault().name())
                .mark(result.getMark())
                .unit(result.getUnit())
                .notes(result.getNotes())
                .recordedAt(result.getRecordedAt())
                .build();
    }
}
