package com.sportday.service;

import com.sportday.dto.GradeEligibilityDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventGradeRule;
import com.sportday.entity.Grade;
import com.sportday.repository.EventGradeRuleRepository;
import com.sportday.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which grades may enter which events.
 *
 * <p>Not every event is for everybody: a C grade student — fourteen or under — does
 * not run the 1500M, and only the oldest grade runs the 5000M. The rules are stored
 * per <strong>event type</strong>, because Boys 1500M and Girls 1500M are the same
 * race in two divisions, and an administrator assigns them on one page.</p>
 *
 * <p>A missing rule means allowed, so an event type added later is open to every
 * grade until somebody says otherwise.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GradeEligibilityService {

    private final EventGradeRuleRepository ruleRepository;
    private final EventRepository eventRepository;

    /** The grades, in the order the grid shows them. */
    private static final List<Grade> GRADE_ORDER = List.of(Grade.A, Grade.B, Grade.C);

    // -------------------------------------------------------------- reading

    /** The whole grid, with how many events each grade may enter. */
    @Transactional
    public GradeEligibilityDTO matrix() {
        List<Event.EventType> types = eventTypesInUse();
        List<GradeEligibilityDTO.EventRow> rows = new ArrayList<>(types.size());
        Map<String, Integer> allowedCounts = new LinkedHashMap<>();
        for (Grade grade : GRADE_ORDER) {
            allowedCounts.put(grade.name(), 0);
        }

        Map<Event.EventType, Set<Grade>> rules = allRules();
        for (Event.EventType type : types) {
            Set<Grade> allowed = rules.getOrDefault(type, EnumSet.allOf(Grade.class));
            Map<String, Boolean> cells = new LinkedHashMap<>();
            for (Grade grade : GRADE_ORDER) {
                cells.put(grade.name(), allowed.contains(grade));
            }
            rows.add(GradeEligibilityDTO.EventRow.builder()
                    .eventType(type.name())
                    .eventTypeLabel(type.getDisplayName())
                    .category(type.getCategory().name())
                    .categoryLabel(type.getCategory().getLabel())
                    .allowed(cells)
                    .build());
        }

        // How many events of the programme each grade can actually enter, which is
        // the number a school cares about — not how many rows are ticked.
        List<Event> programme = eventRepository.findAll();
        for (Event event : programme) {
            if (event.getType() == null) {
                continue;
            }
            Set<Grade> allowed = rules.getOrDefault(event.getType(), EnumSet.allOf(Grade.class));
            for (Grade grade : allowed) {
                allowedCounts.merge(grade.name(), 1, Integer::sum);
            }
        }

        return GradeEligibilityDTO.builder()
                .grades(GRADE_ORDER.stream().map(Enum::name).toList())
                .events(rows)
                .allowedEventCounts(allowedCounts)
                .totalEvents(programme.size())
                .build();
    }

    /**
     * The grades that may enter this event type.
     *
     * <p>Starts from every grade and removes the ones a rule closes, because a
     * missing row means allowed. Reading it off the "allowed" rows alone would
     * report no grades at all for an event whose only rule is a refusal — and the
     * entry page would then hide a race from everybody.</p>
     */
    @Transactional(readOnly = true)
    public Set<Grade> allowedGrades(Event.EventType type) {
        if (type == null) {
            return EnumSet.allOf(Grade.class);
        }
        Set<Grade> allowed = EnumSet.allOf(Grade.class);
        for (EventGradeRule rule : ruleRepository.findByEventType(type)) {
            if (!Boolean.TRUE.equals(rule.getAllowed())) {
                allowed.remove(rule.getGrade());
            }
        }
        return allowed;
    }

    /**
     * Whether a grade may enter an event. An event type with no rules at all is open
     * to every grade, so a newly added type is never accidentally closed.
     */
    @Transactional(readOnly = true)
    public boolean isAllowed(Event.EventType type, Grade grade) {
        if (type == null || grade == null) {
            return true;
        }
        return ruleRepository.findByEventTypeAndGrade(type, grade)
                .map(rule -> Boolean.TRUE.equals(rule.getAllowed()))
                .orElse(true);
    }

    // -------------------------------------------------------------- writing

    /** Applies a set of cell changes and returns the grid as it now stands. */
    @Transactional
    public GradeEligibilityDTO update(List<GradeEligibilityDTO.RuleUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return matrix();
        }
        int changed = 0;
        for (GradeEligibilityDTO.RuleUpdate update : updates) {
            if (update == null || update.getEventType() == null || update.getGrade() == null
                    || update.getAllowed() == null) {
                continue;
            }
            Event.EventType type;
            Grade grade;
            try {
                type = Event.EventType.valueOf(update.getEventType().trim().toUpperCase());
                grade = Grade.valueOf(update.getGrade().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unknown event type or grade: "
                        + update.getEventType() + " / " + update.getGrade());
            }
            EventGradeRule rule = ruleRepository.findByEventTypeAndGrade(type, grade)
                    .orElseGet(() -> EventGradeRule.builder()
                            .eventType(type)
                            .grade(grade)
                            .build());
            if (!update.getAllowed().equals(rule.getAllowed())) {
                rule.setAllowed(update.getAllowed());
                ruleRepository.save(rule);
                changed++;
            }
        }
        if (changed > 0) {
            log.info("Grade eligibility updated: {} cell(s) changed", changed);
        }
        return matrix();
    }

    /**
     * Fills in the school's starting rules — everything open, except the long
     * distances — without touching a rule an administrator has already set.
     */
    @Transactional
    public int seedDefaults() {
        int created = 0;
        for (Event.EventType type : Event.EventType.values()) {
            if (type == Event.EventType.OTHER) {
                continue;
            }
            Set<Grade> allowed = EventGradeRule.defaultAllowedGrades(type);
            for (Grade grade : GRADE_ORDER) {
                if (ruleRepository.findByEventTypeAndGrade(type, grade).isPresent()) {
                    continue;
                }
                ruleRepository.save(EventGradeRule.builder()
                        .eventType(type)
                        .grade(grade)
                        .allowed(allowed.contains(grade))
                        .build());
                created++;
            }
        }
        if (created > 0) {
            log.info("Created {} grade eligibility rule(s) — C grade does not run the 1500M or "
                    + "5000M, and only the A grade runs the 5000M", created);
        }
        return created;
    }

    /** Resets every rule back to the school's starting position. */
    @Transactional
    public int resetToDefaults() {
        ruleRepository.deleteAllInBatch();
        return seedDefaults();
    }

    // -------------------------------------------------------------- helpers

    /**
     * Every rule, as a map of event type to the grades it allows.
     *
     * <p>Starts from <em>all</em> grades allowed and removes only the grades a rule
     * explicitly closes. That matters because a missing row means allowed: if the
     * table held a single "the C grade does not run the 1500M" row, reading the
     * allowed grades off the rows alone would come back empty and the grid would
     * show the A and B grades locked out of a race they can enter.</p>
     */
    private Map<Event.EventType, Set<Grade>> allRules() {
        Map<Event.EventType, Set<Grade>> rules = new LinkedHashMap<>();
        for (EventGradeRule rule : ruleRepository.findAll()) {
            Set<Grade> allowed = rules.computeIfAbsent(rule.getEventType(),
                    key -> EnumSet.allOf(Grade.class));
            if (!Boolean.TRUE.equals(rule.getAllowed())) {
                allowed.remove(rule.getGrade());
            }
        }
        return rules;
    }

    /** The event types the programme actually uses, in programme order. */
    private List<Event.EventType> eventTypesInUse() {
        Set<Event.EventType> used = EnumSet.noneOf(Event.EventType.class);
        for (Event event : eventRepository.findAll()) {
            if (event.getType() != null && event.getType() != Event.EventType.OTHER) {
                used.add(event.getType());
            }
        }
        if (used.isEmpty()) {
            for (Event.EventType type : Event.EventType.values()) {
                if (type != Event.EventType.OTHER) {
                    used.add(type);
                }
            }
        }
        List<Event.EventType> ordered = new ArrayList<>(used);
        ordered.sort(java.util.Comparator.comparingInt(Enum::ordinal));
        return ordered;
    }

}
