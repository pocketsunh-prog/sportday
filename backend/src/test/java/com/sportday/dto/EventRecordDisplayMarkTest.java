package com.sportday.dto;

import com.sportday.entity.Event;
import com.sportday.entity.EventRecord;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A school record reads the way the result that set it reads.
 *
 * <p>Requirement: a race timed on a stopwatch — the 400M and over, and both relays
 * — is shown as {@code M.SS.mmm} wherever its mark appears. A record shown beside a
 * result must not spell the same mark two ways, so the records page gets the mark
 * already written by the one formatter, and a sprint keeps the seconds it is.</p>
 */
class EventRecordDisplayMarkTest {

    private static EventRecordDTO record(Event.EventType type, String mark, String unit,
                                         String previousMark) {
        return EventRecordDTO.from(EventRecord.builder()
                .id(1L)
                .eventType(type)
                .sex(Sex.MALE)
                .grade(Grade.A)
                .mark(mark == null ? null : new BigDecimal(mark))
                .unit(unit)
                .previousMark(previousMark == null ? null : new BigDecimal(previousMark))
                .hasPrevious(previousMark != null)
                .build(), null);
    }

    @Test
    @DisplayName("a 400M record reads in the school's shape, not as a count of seconds")
    void aStopwatchRecordReadsInTheShape() {
        EventRecordDTO dto = record(Event.EventType.RUN_400M, "54.321", "s", "56.000");

        assertEquals("0.54.321s", dto.getDisplayMark());
        assertEquals("0.56.000s", dto.getPreviousDisplayMark(),
                "and what it beat reads the same way");
    }

    @Test
    @DisplayName("a relay record reads the same way a race does")
    void aRelayRecordReadsInTheShape() {
        EventRecordDTO dto = record(Event.EventType.RELAY_4X100M, "44.5", "s", null);

        assertEquals("0.44.500s", dto.getDisplayMark());
        assertNull(dto.getPreviousDisplayMark(), "nothing has been beaten yet");
    }

    @Test
    @DisplayName("a sprint and a field record are unchanged")
    void sprintsAndFieldEventsAreUnchanged() {
        assertEquals("11.86s", record(Event.EventType.RUN_100M, "11.860", "s", null)
                .getDisplayMark());
        assertEquals("18.12M", record(Event.EventType.SHOT_PUT, "18.120", "M", null)
                .getDisplayMark());
    }

    @Test
    @DisplayName("an empty record has nothing to show")
    void anEmptyRecordShowsNothing() {
        EventRecordDTO dto = record(Event.EventType.RUN_400M, null, null, null);

        assertNull(dto.getDisplayMark());
        assertNull(dto.getMark());
        assertNull(dto.getPreviousDisplayMark());
    }
}
