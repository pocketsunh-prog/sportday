package com.sportday.service;

import com.sportday.dto.StandardDefaultDTO;
import com.sportday.entity.Event;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.StandardDefault;
import com.sportday.repository.EventRepository;
import com.sportday.repository.StandardDefaultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <strong>default required standard</strong> for each event type, grade and sex
 * division — "400M, A grade, boys = 64.0 s" — and the one place that applies a
 * default to the events that inherit it.
 *
 * <h2>The key is type &times; grade &times; sex, and the sex is not optional</h2>
 * <p>A default keyed on grade alone would give the boys' 400M and the girls' 400M
 * the same qualifying time. The live programme holds a Boys 400M and a Girls 400M at
 * every grade, so that cannot be right. Saying it plainly here so it can be
 * corrected if the school disagrees: <strong>the sex division is part of the
 * key</strong>. To key on grade alone, drop {@code sex} from
 * {@link StandardDefault} and from
 * {@link StandardDefaultRepository#findByTypeAndGradeAndSex}; nothing else moves.</p>
 *
 * <h2>Where the standard lives</h2>
 * <p>In its own table ({@link StandardDefault}), not in the settings structure: it
 * must survive a restart, it is one row per key (72 on the live programme), and a
 * table is what lets the whole set be read in one query and the key be made unique
 * by the database. Every read here goes through
 * {@link StandardDefaultRepository#findAll()} or its ordered twin — there is no
 * per-event lookup anywhere in a list.</p>
 *
 * <h2>Saving a default does not touch a single event</h2>
 * <p>Deliberate; see {@link #set} and {@link #apply}. The short of it: a live
 * programme is a school's real data, 72 events would change at once, and a
 * mis-typed box must not rewrite them. The change is saved, and then applied as a
 * separate, previewable step.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandardDefaultService {

    private final StandardDefaultRepository standardDefaultRepository;
    private final EventRepository eventRepository;

    /**
     * Every event type that carries a required standard, in programme order.
     *
     * <p>Derived from {@link Event.EventType#carriesAStandard()} rather than listed
     * by hand, so this page cannot come to offer a type the marking sheet would
     * refuse. Relays and the short sprints are therefore absent, which is what the
     * school asked for.</p>
     */
    public static final List<Event.EventType> STANDARD_TYPES = Arrays.stream(Event.EventType.values())
            .filter(Event.EventType::carriesAStandard)
            .toList();

    /**
     * Every default the school has configured, keyed as the page needs it.
     *
     * <p>Only rows that exist. A key with no row has no standard, which every caller
     * reads as "not set" — the page asks the same question of its own catalogue.</p>
     */
    @Transactional(readOnly = true)
    public List<StandardDefaultDTO> list() {
        return standardDefaultRepository.findAllByOrderByTypeAscGradeAscSexAsc().stream()
                .map(StandardDefaultDTO::from)
                .toList();
    }

    /**
     * Sets — or clears — the default for one type, grade and division.
     *
     * <p>Refuses a type that does not carry a standard ({@link StandardRule}), so the
     * school cannot set a default for a relay or a 100M and then wonder why no event
     * inherits it, and refuses a number that is not greater than zero, exactly as a
     * single event's box does.</p>
     *
     * <p><strong>Nothing already on file is touched here.</strong> Saving a default
     * changes what a <em>new</em> event will inherit; the events that already exist
     * are re-pointed by {@link #apply}, on purpose and after a preview. A null
     * standard removes the row, which is the same thing to every reader as a row
     * holding no number.</p>
     */
    @Transactional
    public StandardDefaultDTO set(Event.EventType type, Grade grade, Sex sex, BigDecimal standard) {
        StandardRule.requireCarriesAStandard(type);
        if (grade == null) {
            throw new IllegalArgumentException("A default standard is set per grade: A, B or C.");
        }
        if (sex == null) {
            throw new IllegalArgumentException("A default standard is set per division: MALE (M) "
                    + "or FEMALE (F). A grade alone would give the boys' race and the girls' race "
                    + "the same qualifying time.");
        }
        StandardDefault row = standardDefaultRepository.findByTypeAndGradeAndSex(type, grade, sex)
                .orElseGet(() -> StandardDefault.builder()
                        .type(type).grade(grade).sex(sex).build());
        if (standard == null) {
            // No row at all and a row with no number mean the same to every caller,
            // so an empty box removes it rather than leaving a husk behind.
            if (row.getId() != null) {
                standardDefaultRepository.delete(row);
            }
            // The answer must not carry the number that was just cleared: a page that
            // echoed it back would go on showing a default that no longer exists.
            row.setStandard(null);
            log.info("Cleared the default standard for {} {} {}", type, grade, sex);
            return StandardDefaultDTO.from(row);
        }
        row.setStandard(StandardRule.requirePositive(standard));
        StandardDefault saved = standardDefaultRepository.save(row);
        log.info("Set the default standard for {} {} {} to {}", type, grade, sex, standard);
        return StandardDefaultDTO.from(saved);
    }

    /**
     * Whether applying the defaults is allowed to overwrite a standard somebody typed
     * on one event.
     *
     * <p>Two modes rather than a boolean, because "apply" has two honest meanings and
     * the safe one must be the default a caller gets by saying nothing.</p>
     */
    public enum ApplyMode {
        /**
         * The safe default: re-point only the events that <em>follow</em> a default —
         * and the events that hold no standard at all — and <strong>leave every
         * hand-set number exactly as it is</strong>, reporting how many were kept.
         */
        INHERITED,
        /**
         * Overwrite every matching event whatever its standard's origin. This is the
         * only way a hand-set number is ever replaced by a default, it has to be asked
         * for by name, and the preview says how many it would replace.
         */
        ALL
    }

    /**
     * Applying every configured default — the standards page's "apply now", and the
     * only thing that changes an event that already exists.
     *
     * @param mode   whether hand-set numbers are left alone ({@link ApplyMode#INHERITED})
     *               or replaced ({@link ApplyMode#ALL})
     * @param dryRun true to work out and report what <em>would</em> change and write
     *               nothing, which is what the page shows before the administrator
     *               commits
     */
    @Transactional
    public ApplyResult apply(ApplyMode mode, boolean dryRun) {
        Map<String, StandardDefault> byKey = new LinkedHashMap<>();
        for (StandardDefault value : standardDefaultRepository.findAll()) {
            byKey.put(StandardDefaultDTO.keyOf(value.getType(), value.getGrade(), value.getSex()), value);
        }
        Map<String, StandardDefaultDTO> keys = new LinkedHashMap<>();
        List<Event> events = new ArrayList<>();
        int changed = 0;
        int kept = 0;
        for (Event event : eventRepository.findAll()) {
            if (event.getType() == null || !event.getType().carriesAStandard()) {
                // A relay and a sprint are never affected: they carry no standard at
                // all, so there is nothing for a default to stand in for.
                continue;
            }
            StandardDefault value = byKey.get(
                    StandardDefaultDTO.keyOf(event.getType(), event.getGrade(), event.getSex()));
            if (value == null || value.getStandard() == null) {
                // No default for this key, or a default holding no number. Neither
                // says anything about this event, so nothing is touched or reported:
                // an event is never emptied because somebody else's key has no number.
                continue;
            }
            if (!mode.equals(ApplyMode.ALL) && !mayBeRepointed(event)) {
                kept++;
                continue;
            }
            if (alreadyInStep(event, value)) {
                // Nothing to do: the event already holds this default and is stamped as
                // following it, so a second apply reports zero rather than pretending
                // to have worked.
                continue;
            }
            if (!dryRun) {
                // The mutation happens only on a real run, so a preview leaves not one
                // trace — not even in the objects it was handed.
                event.setStandardFromDefault(value.getStandard(), value.getId());
            }
            changed++;
            keys.putIfAbsent(StandardDefaultDTO.keyOf(value.getType(), value.getGrade(), value.getSex()),
                    StandardDefaultDTO.from(value));
            events.add(event);
        }
        if (!dryRun && changed > 0) {
            eventRepository.saveAll(events);
        }
        log.info("{} standard defaults: {} event(s) changed, {} hand-set value(s) kept{}",
                dryRun ? "Previewed" : "Applied", changed, kept, dryRun ? " (dry run)" : "");
        return ApplyResult.builder()
                .dryRun(dryRun)
                .mode(mode.name())
                .changed(changed)
                .kept(kept)
                .keys(List.copyOf(keys.values()))
                .events(events.stream().map(StandardDefaultDTO::fromEvent).toList())
                .build();
    }

    /**
     * Whether a default may re-point this event's standard.
     *
     * <ul>
     *   <li>the event was stamped as <strong>following</strong> a default when its
     *       number was last set — it is not the school's own value, so it moves with
     *       the default;</li>
     *   <li>or the event <strong>holds no standard at all</strong> and carries no stamp
     *       either way. This is the case the live programme is in: 72 qualifying events
     *       with an empty box, where "apply now" is the whole point;</li>
     *   <li>or it was cleared on purpose. Clearing stamps the event as hand-set, and a
     *       blank that somebody chose is not the same as a blank nobody has filled in
     *       — so a cleared event is left alone and counted as kept.</li>
     * </ul>
     *
     * <p>An event with a number and no record of where it came from — a standard typed
     * before this feature existed — is treated as hand-set and never overwritten. That
     * is the conservative reading, and {@link ApplyMode#ALL} is the way to overrule it
     * deliberately.</p>
     */
    private static boolean mayBeRepointed(Event event) {
        if (event.isStandardInherited()) {
            return true;
        }
        return event.getStandard() == null && event.getStandardIsDefault() == null;
    }

    /**
     * Whether one event already holds a default — the same number <em>and</em> the
     * stamp saying it follows one. Returns whether anything would actually change, so
     * an apply that is already in step reports zero rather than pretending to work.
     */
    private static boolean alreadyInStep(Event event, StandardDefault value) {
        return event.isStandardInherited()
                && event.getStandard() != null
                && event.getStandard().compareTo(value.getStandard()) == 0;
    }

    /**
     * What an apply did — or, on a dry run, exactly what it would do.
     *
     * <p>The counts are the point: an administrator is about to change live
     * programme data, and the page says how many events are affected and how many
     * hand-set numbers are being left alone <em>before</em> anything is written.</p>
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ApplyResult {

        /** True when nothing was written and this is only a preview. */
        private boolean dryRun;

        /** {@code INHERITED} or {@code ALL} — what was asked for. */
        private String mode;

        /** How many events were changed, or would be. */
        private int changed;

        /** How many hand-set numbers were left alone (always 0 in {@link ApplyMode#ALL}). */
        private int kept;

        /** One entry per default that would change something. */
        private List<StandardDefaultDTO> keys;

        /** The events that were changed, or would be. */
        private List<StandardDefaultDTO> events;
    }
}
