package com.sportday.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A field athlete's three attempts, of which the best is their result.
 *
 * <p>Requirement: a field marking sheet has three attempt boxes and the best of
 * them counts.</p>
 */
class EventResultAttemptsTest {

    private EventResult result(BigDecimal... attempts) {
        List<BigDecimal> sent = Arrays.asList(attempts);
        return EventResult.builder()
                .id(1L)
                .event(Event.builder().id(1L).type(Event.EventType.SHOT_PUT)
                        .category(EventCategory.FIELD).sex(Sex.MALE).build())
                .stage(EventStage.HEAT)
                .attempt1(sent.get(0))
                .attempt2(sent.size() > 1 ? sent.get(1) : null)
                .attempt3(sent.size() > 2 ? sent.get(2) : null)
                .build();
    }

    @Test
    @DisplayName("the best of the three attempts is the mark that counts")
    void theBestAttemptCounts() {
        EventResult shot = result(new BigDecimal("9.10"), new BigDecimal("11.42"),
                new BigDecimal("10.05"));

        assertEquals(new BigDecimal("11.42"), shot.bestAttempt(),
                "a field event is won by the longest or highest attempt");
        assertTrue(shot.hasAttempts());
    }

    @Test
    @DisplayName("a missed attempt is ignored rather than counted as zero")
    void aMissedAttemptIsIgnored() {
        EventResult shot = result(null, new BigDecimal("8.75"), null);

        assertEquals(new BigDecimal("8.75"), shot.bestAttempt());
        assertEquals(0, new BigDecimal("8.75").compareTo(shot.bestAttempt()));
    }

    @Test
    @DisplayName("an athlete who has not thrown yet has nothing to count")
    void noAttemptsMeansNoMark() {
        EventResult shot = result(null, null, null);

        assertFalse(shot.hasAttempts(), "three empty boxes are not an attempt");
        assertNull(shot.bestAttempt());
    }

    @Test
    @DisplayName("the attempts are kept in order, so a sheet can print them back")
    void theAttemptsKeepTheirOrder() {
        EventResult shot = result(new BigDecimal("9.10"), null, new BigDecimal("11.42"));

        List<BigDecimal> attempts = shot.getAttempts();
        assertEquals(Event.EventType.FIELD_ATTEMPTS, attempts.size());
        assertEquals(new BigDecimal("9.10"), attempts.get(0));
        assertNull(attempts.get(1), "the second throw was a miss");
        assertEquals(new BigDecimal("11.42"), attempts.get(2));
    }

    @Test
    @DisplayName("a track event has one performance, not attempts")
    void aTrackEventHasASinglePerformance() {
        EventResult run = EventResult.builder()
                .id(2L)
                .event(Event.builder().id(2L).type(Event.EventType.RUN_100M)
                        .category(EventCategory.TRACK).sex(Sex.MALE).build())
                .stage(EventStage.HEAT)
                .mark(new BigDecimal("12.34"))
                .build();

        assertFalse(run.hasAttempts());
        assertEquals(new BigDecimal("12.34"), run.bestAttempt(),
                "the single time is the performance");
    }

    @Test
    @DisplayName("a field event records three attempts and the best becomes the mark")
    void threeAttemptsBecomeTheMark() {
        EventResult shot = result(new BigDecimal("9.10"), new BigDecimal("11.42"),
                new BigDecimal("10.05"));

        assertEquals(3, Event.EventType.FIELD_ATTEMPTS, "a field sheet prints three boxes");
        assertEquals(new BigDecimal("11.42"), shot.bestAttempt());
    }
}
