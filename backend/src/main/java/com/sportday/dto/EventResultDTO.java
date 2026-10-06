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

    /**
     * The relay team this row is a time <em>for</em>, and the team's own name as the
     * school writes it — {@code 1A}, {@code B Grade Green}. Both null on an
     * individual event's row, and on a relay row that names no team.
     *
     * <p><strong>A relay is run and scored by team</strong>, so a relay row is the
     * team's time and a list of results must read by the team. The row still carries
     * {@link #userId} / {@link #username} / {@link #fullName}, deliberately: that
     * user is the team's <em>anchor</em> (its first runner) — the column
     * {@code event_results.user_id} is not nullable and every reader joins on it —
     * so "what did this athlete run?" still answers, with the team beside it. A list
     * of an event's results asks {@link #teamLabel} first; see
     * {@code EventResult#relayTeam}.</p>
     */
    private Long teamId;
    private String teamLabel;

    /** {@code HEAT} or {@code FINAL}. */
    private String stage;

    private BigDecimal mark;
    private String unit;

    /**
     * {@code RESULT} when a mark was recorded, {@code ABS} or {@code DQ} when the
     * athlete was absent or disqualified — so a client can style the row as well as
     * read it.
     */
    private String outcome;

    /**
     * The mark as it reads, with its unit: {@code 14.123s}, {@code 1.04.123s},
     * {@code 18.12M} — or {@code ABS} / {@code DQ} where there is no mark.
     * {@link #mark} and {@link #unit} stay as they are; this is the same value
     * written the way a person reads it, so a client does not have to rework the
     * number to show it.
     */
    private String displayMark;
    private String notes;

    /**
     * A field athlete's three attempts in order, with a missed one left absent.
     * Empty for a track event, which has a single performance.
     */
    private java.util.List<BigDecimal> attempts;

    /** True when this performance is the current school record for its event. */
    private Boolean newRecord;

    private LocalDateTime recordedAt;

    public static EventResultDTO from(EventResult result) {
        com.sportday.entity.RelayTeam team = result.getRelayTeam();
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
                .outcome(result.getOutcomeOrDefault().name())
                // A relay row is the team's time, so the team's own name travels with
                // it; an individual row carries neither field.
                .teamId(team == null ? null : team.getId())
                .teamLabel(team == null ? null : team.getLabel())
                .displayMark(com.sportday.service.MarkFormatter.formatWithOutcome(
                        result.getOutcomeOrDefault(),
                        result.getMark(),
                        result.getEvent() == null ? null : result.getEvent().getType(),
                        result.getUnit()))
                .notes(result.getNotes())
                .attempts(result.hasAttempts()
                        ? new java.util.ArrayList<>(result.getAttempts()) : null)
                .recordedAt(result.getRecordedAt())
                .build();
    }
}
