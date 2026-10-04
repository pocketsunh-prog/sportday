package com.sportday.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outcome of a result: a mark was recorded, or the athlete was absent or
 * disqualified.
 *
 * <p>Requirement: a mark-entry sheet records ABS and DQ as well as a number, and a
 * row written before the outcome existed — or one with nothing recorded yet — must
 * keep reading as a plain result, so the column is nullable exactly as
 * {@code stage} is.</p>
 */
class EventResultOutcomeTest {

    @Test
    @DisplayName("a row with no outcome reads as a result, which is what every old row is")
    void aNullOutcomeReadsAsResult() {
        EventResult result = EventResult.builder().id(1L).build();

        assertEquals(EventResult.Outcome.RESULT, result.getOutcomeOrDefault());
        assertFalse(result.isAbsentOrDisqualified());
    }

    @Test
    @DisplayName("ABS and DQ are no mark produced, and they are what they say they are")
    void absentAndDisqualifiedMeanNoMark() {
        EventResult absent = EventResult.builder().id(1L).outcome(EventResult.Outcome.ABS).build();
        EventResult disqualified = EventResult.builder().id(2L).outcome(EventResult.Outcome.DQ).build();

        assertTrue(absent.isAbsentOrDisqualified());
        assertTrue(disqualified.isAbsentOrDisqualified());
        assertEquals(EventResult.Outcome.ABS, absent.getOutcomeOrDefault());
        assertEquals(EventResult.Outcome.DQ, disqualified.getOutcomeOrDefault());
    }

    @Test
    @DisplayName("each outcome has a label to print")
    void everyOutcomeHasALabel() {
        assertEquals("ABS", EventResult.Outcome.ABS.getLabel());
        assertEquals("DQ", EventResult.Outcome.DQ.getLabel());
        assertEquals("Result", EventResult.Outcome.RESULT.getLabel());
        assertFalse(EventResult.Outcome.RESULT.isNoMark());
        assertTrue(EventResult.Outcome.ABS.isNoMark());
        assertTrue(EventResult.Outcome.DQ.isNoMark());
    }

    @Test
    @DisplayName("an outcome is read case-insensitively, and anything else is not one")
    void outcomesAreReadLooselyButNotInvented() {
        assertEquals(EventResult.Outcome.ABS, EventResult.Outcome.fromCode("abs"));
        assertEquals(EventResult.Outcome.ABS, EventResult.Outcome.fromCode("  Abs "));
        assertEquals(EventResult.Outcome.DQ, EventResult.Outcome.fromCode("dq"));
        assertEquals(EventResult.Outcome.RESULT, EventResult.Outcome.fromCode("result"));

        assertNull(EventResult.Outcome.fromCode(null), "nothing sent means a result");
        assertNull(EventResult.Outcome.fromCode(""), "and so does a blank");
        assertNull(EventResult.Outcome.fromCode("NR"), "there is deliberately no NR state");
    }

    @Test
    @DisplayName("an absent athlete has no attempts to count")
    void anAbsentAthleteHasNoAttempts() {
        EventResult absent = EventResult.builder()
                .id(1L)
                .event(Event.builder().id(1L).type(Event.EventType.SHOT_PUT)
                        .category(EventCategory.FIELD).sex(Sex.MALE).build())
                .stage(EventStage.HEAT)
                .outcome(EventResult.Outcome.ABS)
                .build();

        assertFalse(absent.hasAttempts());
        assertNull(absent.bestAttempt());
        assertNull(absent.getMark());
    }

    @Test
    @DisplayName("a recorded mark is untouched by the outcome beside it")
    void aRecordedMarkStillCounts() {
        EventResult mark = EventResult.builder()
                .id(1L)
                .event(Event.builder().id(1L).type(Event.EventType.RUN_100M)
                        .category(EventCategory.TRACK).sex(Sex.MALE).build())
                .stage(EventStage.HEAT)
                .outcome(EventResult.Outcome.RESULT)
                .mark(new BigDecimal("11.860"))
                .build();

        assertEquals(0, new BigDecimal("11.860").compareTo(mark.bestAttempt()));
        assertFalse(mark.isAbsentOrDisqualified());
    }
}
