package com.sportday.service;

import com.sportday.entity.Event;
import com.sportday.entity.EventGroup;
import com.sportday.entity.EventStage;
import com.sportday.repository.EventGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * One rule, in one place: <strong>the final must wait for the heat results.</strong>
 *
 * <p>A short sprint is run in two stages. Everyone runs a heat, the marks are
 * recorded, and only then can the final be drawn from them. Until the draw has
 * happened there is no final field — no athletes, no lanes, no sheet and nothing
 * to write a time against — so an attempt to work on the final before it exists
 * would either write a mark against nobody or print an empty sheet.</p>
 *
 * <p>{@link FinalQualificationService} already refuses to <em>draw</em> a final
 * without heat results. This is the same rule seen from the other end — the final
 * cannot be <em>worked on</em> before it has been drawn — so it is stated once
 * here and every caller shares it rather than each inventing a wording. The three
 * callers are:</p>
 *
 * <ul>
 *   <li>{@code GET /api/events/{id}/marks?stage=FINAL} and
 *       {@code POST /api/events/{id}/marks} with {@code stage: FINAL} —
 *       {@link MarkEntryService};</li>
 *   <li>an event's print run, through {@code EventGroupController} and
 *       {@link PdfSheetService};</li>
 *   <li>drawing the final itself — {@link FinalQualificationService}.</li>
 * </ul>
 *
 * <p>Every refusal is an {@link IllegalStateException}, which the application's
 * {@code GlobalExceptionHandler} turns into <strong>409 Conflict</strong> — the
 * status this codebase already uses for "the request is understood, but the
 * programme is not in a state where it can be honoured". The message travels to
 * the client as written, in the plain style the rest of the product uses.</p>
 *
 * <h2>The three states, and the three messages</h2>
 * <p>An event is in exactly one of them, and they are checked in this order:</p>
 * <ol>
 *   <li><strong>No final stage at all.</strong> Only 60M, 100M, 200M and 400M can
 *       be split into heats and a final, so an 800M, a hurdles race, a relay or
 *       any field event simply has no final to fill in. Refused with
 *       {@link #NO_FINAL_STAGE}.</li>
 *   <li><strong>A final stage, but the event runs straight to it.</strong> The
 *       event allows a final and the school has asked not to use one, so there is
 *       nothing drawn and nothing to draw. Refused with
 *       {@link #DIRECT_TO_FINAL}.</li>
 *   <li><strong>A final stage that has not been drawn yet.</strong> Heats are in
 *       play and the draw has not run. Refused with {@link #NOT_DRAWN}.</li>
 * </ol>
 *
 * <p>Once the final group exists, the request is allowed through — including a
 * re-draw of the final, since {@link FinalQualificationService#generate} replaces
 * the previous one.</p>
 *
 * <h2>Why the rule is static</h2>
 * <p>The rule is about the event and whether its final exists, and nothing else,
 * so the three methods that state it are static — a caller that already knows
 * whether the final is drawn does not need this component injected to ask. The
 * component exists for the one thing a caller cannot answer on its own: looking
 * the final up. It is the component that owns the wording, so every caller that
 * asks it refuses in the same words.</p>
 */
@Component
@RequiredArgsConstructor
public class FinalStageGuard {

    /** A race that cannot be split, so it has no final stage to work on. */
    public static final String NO_FINAL_STAGE =
            " is run straight to a final, so there is no final to enter marks for or print a sheet from. "
                    + "Only 60M, 100M, 200M and 400M can be split into heats and a final.";

    /** An event that could be split, but the school has not asked for a final. */
    public static final String DIRECT_TO_FINAL =
            " is set to run direct to a final, so there is no final to work on. "
                    + "Untick \"direct to final\" on the event first.";

    /** Heats are in play and the draw has not run. */
    public static final String NOT_DRAWN =
            "The final has not been drawn yet. Record the heat marks first, then draw the final.";

    private final EventGroupRepository groupRepository;

    /**
     * The final of an event, if one has been drawn.
     *
     * @return the final group, or empty when the event has no final drawn — which
     *         includes an event that cannot have one and one set to run straight
     *         to a final
     */
    public Optional<EventGroup> finalOf(Long eventId) {
        return groupRepository.findFirstByEventIdAndStage(eventId, EventStage.FINAL);
    }

    /** True when the event has a final drawn, so its marks and its sheet exist. */
    public boolean finalDrawn(Long eventId) {
        return finalOf(eventId).isPresent();
    }

    /**
     * Refuses the call unless the event has a final that has actually been drawn.
     *
     * @throws IllegalStateException with the reason — the handler turns it into a 409
     */
    public void requireDrawnFinal(Event event) {
        if (event != null) {
            requireDrawnFinal(event, finalDrawn(event.getId()));
        }
    }

    /**
     * The state of an event's final, for a client that has to gate its own buttons
     * rather than parse a message.
     *
     * <ul>
     *   <li>{@link FinalState#NONE} — the event has no final stage (not a short
     *       sprint);</li>
     *   <li>{@link FinalState#DIRECT} — the event could be split and is run
     *       straight to a final instead;</li>
     *   <li>{@link FinalState#NOT_DRAWN} — heats are in play and the draw has not
     *       run;</li>
     *   <li>{@link FinalState#DRAWN} — the final exists and its marks and sheet are
     *       live.</li>
     * </ul>
     */
    public FinalState state(Event event) {
        return event == null
                ? FinalState.NONE
                : state(event, finalDrawn(event.getId()));
    }

    // ------------------------------------------------------------- the rule

    /**
     * Refuses the call unless {@code finalDrawn} — which the caller has already
     * established — is true and the event is one that can have a final.
     */
    public static void requireDrawnFinal(Event event, boolean finalDrawn) {
        requireAFinalIsPossible(event);
        if (!finalDrawn) {
            throw new IllegalStateException(NOT_DRAWN);
        }
    }

    /**
     * Refuses the call unless the event could have a final at all. Static because
     * the answer is entirely the event's own: no final is drawn or looked up.
     */
    public static void requireAFinalIsPossible(Event event) {
        if (event == null || event.runsAFinal()) {
            return;
        }
        String name = event.getName() == null ? "This event" : event.getName();
        throw new IllegalStateException(event.mayHaveFinal()
                ? name + DIRECT_TO_FINAL
                : name + NO_FINAL_STAGE);
    }

    /** {@link #state(Event)} from a final-drawn answer the caller already has. */
    public static FinalState state(Event event, boolean finalDrawn) {
        if (event == null || !event.mayHaveFinal()) {
            return FinalState.NONE;
        }
        if (event.isDirectToFinal()) {
            return FinalState.DIRECT;
        }
        return finalDrawn ? FinalState.DRAWN : FinalState.NOT_DRAWN;
    }

    /** The four states of an event's final. See {@link #state(Event)}. */
    public enum FinalState {
        /** No final stage at all: the event is not one that can be split. */
        NONE,
        /** A final stage exists in principle and the event runs straight to it. */
        DIRECT,
        /** Heats are in play and the final has not been drawn from them yet. */
        NOT_DRAWN,
        /** The final has been drawn: its marks and its sheet are live. */
        DRAWN
    }
}
