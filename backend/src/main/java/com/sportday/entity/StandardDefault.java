package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * The <strong>default required standard</strong> for one event type, grade and sex
 * division — "400M, A grade, boys = 64.0 s" — which every event of that type, grade
 * and division then inherits.
 *
 * <h2>Why this exists</h2>
 * <p>The school's requirement: "base on each grade update standard record for
 * default". A required standard is a property of the <em>race a school runs</em>,
 * not of one row in the programme: the boys' 400M is the same qualifying time
 * whether it was seeded in 2026 or added by hand in November. Before this table the
 * standard was typed 72 times — once on each qualifying event on the live programme
 * — and setting it for a new grade meant finding every event again.</p>
 *
 * <h2>The key is type &times; grade &times; sex, and the sex is not optional</h2>
 * <p>A default keyed on grade alone would give the boys' 400M and the girls' 400M
 * the <em>same</em> qualifying time, which cannot be right: the live programme holds
 * a Boys 400M and a Girls 400M at every grade, and a school's qualifying times
 * differ between them. So the unique key is the type, the grade <strong>and</strong>
 * the division. If the school disagrees and wants one time per grade, the sex column
 * is dropped from {@code uk_standard_default_type_grade_sex} — the rest of the design
 * does not move.</p>
 *
 * <h2>Where it lives, and why a table rather than a settings blob</h2>
 * <p>It is a table because it must survive a restart and there are 72 of them (13
 * qualifying types &times; grades &times; 2 divisions), and because a table is what
 * makes the inheritance cheap and the update safe:</p>
 * <ul>
 *   <li>{@code StandardDefaultRepository.findAll()} reads all of them in
 *       <strong>one query</strong>, so a caller that needs several — the standards
 *       page, or an "apply now" over the whole programme — builds one map and never
 *       looks an event up one at a time;</li>
 *   <li>the unique key means a lookup for one key cannot return two rows, so
 *       inheritance is a map read rather than a "which of these wins" question;</li>
 *   <li>a settings structure (a JSON blob on {@link SportDaySettings}) would have to
 *       be parsed and re-written whole for every single box an administrator edits,
 *       and could not be constrained to one row per key by the database.</li>
 * </ul>
 *
 * <p>A default with no row at all means "this school has not set one", and is
 * exactly equivalent to a row whose number is null: nothing is inherited.</p>
 *
 * <h2>What inherits it</h2>
 * <p>Only events whose type {@link Event.EventType#carriesAStandard()} — the same
 * rule as the single event box, asked in {@code StandardRule} rather than restated.
 * A relay and a 100M are never offered one, so a default cannot be set for them
 * either.</p>
 *
 * @see com.sportday.service.StandardDefaultService
 */
@Entity
@Table(name = "standard_defaults",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_standard_default_type_grade_sex",
                columnNames = {"type", "grade", "sex"}),
        indexes = @Index(name = "idx_standard_defaults_type", columnList = "type"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StandardDefault {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The event type the default is for, e.g. {@code RUN_400M}. Which types may
     * have one is {@link Event.EventType#carriesAStandard()}; a default is refused
     * for anything else, so a relay default cannot be stored and then offered.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Event.EventType type;

    /** The one grade the default is for — A, B or C. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private Grade grade;

    /**
     * The division the default is for. Part of the key on purpose: the boys' 400M
     * and the girls' 400M are different races with different qualifying times.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Sex sex;

    /**
     * The qualifying mark, in the event type's own unit — seconds on the track,
     * metres in the field — or null for "this school sets no standard here".
     *
     * <p>Which way round a mark meets it is
     * {@link Event.EventType#isLowerBetter()}, the codebase's one direction rule,
     * and is not stored. A number that is not greater than zero is refused, exactly
     * as it is on a single event, because it is a typo rather than a mark.</p>
     */
    @Column(name = "standard", precision = 10, scale = 3)
    private BigDecimal standard;
}
