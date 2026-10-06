package com.sportday.service;

import com.sportday.entity.Event;

import java.math.BigDecimal;

/**
 * The two rules a <strong>required standard</strong> must satisfy, in one place.
 *
 * <p>A standard is set from two directions now: an administrator types one into a
 * single event's box on {@code /admin/standards}, or sets the <strong>default</strong>
 * that a whole grade and division inherits
 * ({@code StandardDefaultService}). Both must refuse the same things in the same
 * words, so the judgement is kept here rather than written twice — the second copy
 * is always the one that drifts.</p>
 *
 * <ul>
 *   <li><strong>Which events may carry one</strong> is
 *       {@link Event.EventType#carriesAStandard()}'s business and is not restated
 *       here: the track races of 400M and over, and the field events. A relay is
 *       never one of them, and neither is a sprint under 400M.</li>
 *   <li><strong>Positive.</strong> A time of zero or a negative distance is not a
 *       qualifying mark, it is a typo.</li>
 * </ul>
 *
 * <p>{@code null} is not a value here — it is the absence of one, and both callers
 * treat it as "no standard". Which way round a mark <em>meets</em> a standard is
 * {@link Event.EventType#meetsStandard(BigDecimal, BigDecimal)} and
 * {@link Event.EventType#isLowerBetter()}, and is deliberately not restated.</p>
 */
final class StandardRule {

    private StandardRule() {
    }

    /**
     * Refuses a standard on an event type that does not carry one, judged on the
     * type the event has or is becoming rather than the name it still carries.
     *
     * @param type the type the standard would be stored against; null is refused
     * @throws IllegalArgumentException when that type carries no standard
     */
    static void requireCarriesAStandard(Event.EventType type) {
        if (type == null || !type.carriesAStandard()) {
            throw new IllegalArgumentException(
                    (type == null ? "This event" : type.getDisplayName())
                    + " is not an event that carries a required standard. Only the "
                    + "track races of 400M and over, and the field events, can have one.");
        }
    }

    /**
     * Refuses a standard that is not greater than zero, and hands the same number
     * back so a caller can use it directly.
     *
     * @throws IllegalArgumentException when the number is zero or less
     */
    static BigDecimal requirePositive(BigDecimal standard) {
        if (standard.signum() <= 0) {
            throw new IllegalArgumentException(
                    "A required standard must be greater than zero.");
        }
        return standard;
    }
}
