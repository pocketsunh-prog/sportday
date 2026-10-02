package com.sportday.service;

import com.sportday.dto.GradeEligibilityDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventGradeRule;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.repository.EventGradeRuleRepository;
import com.sportday.repository.EventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Which grades may enter which events.
 *
 * <p>Requirement: a C grade student cannot enter the 1500M or above, with a page
 * assigning which events each grade may enter.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GradeEligibilityServiceTest {

    @Mock private EventGradeRuleRepository ruleRepository;
    @Mock private EventRepository eventRepository;

    @InjectMocks private GradeEligibilityService service;

    private final List<EventGradeRule> rules = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();

    private Event event(long id, Event.EventType type, Sex sex) {
        Event created = Event.builder()
                .id(id)
                .name((sex == Sex.MALE ? "Boys " : "Girls ") + type.getDisplayName())
                .type(type)
                .category(type.getCategory())
                .sex(sex)
                .groupSize(type.getDefaultGroupSize())
                .eventDate(LocalDate.of(2026, 10, 1))
                .enabled(true)
                .directToFinal(true)
                .build();
        events.add(created);
        return created;
    }

    private void givenRules() {
        when(ruleRepository.findAll()).thenReturn(rules);
        when(ruleRepository.findByEventType(any()))
                .thenAnswer(inv -> rules.stream()
                        .filter(r -> r.getEventType() == inv.getArgument(0))
                        .toList());
        when(ruleRepository.findByEventTypeAndAllowedTrue(any()))
                .thenAnswer(inv -> rules.stream()
                        .filter(r -> r.getEventType() == inv.getArgument(0)
                                && Boolean.TRUE.equals(r.getAllowed()))
                        .toList());
        when(ruleRepository.findByEventTypeAndGrade(any(), any()))
                .thenAnswer(inv -> rules.stream()
                        .filter(r -> r.getEventType() == inv.getArgument(0)
                                && r.getGrade() == inv.getArgument(1))
                        .findFirst());
        when(ruleRepository.save(any(EventGradeRule.class))).thenAnswer(inv -> {
            EventGradeRule saved = inv.getArgument(0);
            rules.removeIf(r -> r.getEventType() == saved.getEventType()
                    && r.getGrade() == saved.getGrade());
            rules.add(saved);
            return saved;
        });
        when(eventRepository.findAll()).thenReturn(events);
    }

    // ------------------------------------------------------------- defaults

    @Test
    @DisplayName("the C grade does not run the 1500M or the 5000M")
    void theYoungestGradeDoesNotRunTheLongDistances() {
        assertEquals(Set.of(Grade.A, Grade.B), EventGradeRule.defaultAllowedGrades(Event.EventType.RUN_1500M));
        assertEquals(Set.of(Grade.A), EventGradeRule.defaultAllowedGrades(Event.EventType.RUN_5000M));
    }

    @Test
    @DisplayName("only the oldest grade runs the 5000M")
    void onlyTheOldestRunsTheFiveThousand() {
        assertFalse(EventGradeRule.defaultAllowedGrades(Event.EventType.RUN_5000M).contains(Grade.B));
        assertTrue(EventGradeRule.defaultAllowedGrades(Event.EventType.RUN_5000M).contains(Grade.A));
    }

    @Test
    @DisplayName("everything else is open to all three grades")
    void everythingElseIsOpen() {
        Set<Grade> all = Set.of(Grade.A, Grade.B, Grade.C);
        assertEquals(all, EventGradeRule.defaultAllowedGrades(Event.EventType.RUN_60M));
        assertEquals(all, EventGradeRule.defaultAllowedGrades(Event.EventType.RUN_800M));
        assertEquals(all, EventGradeRule.defaultAllowedGrades(Event.EventType.HURDLES_110M));
        assertEquals(all, EventGradeRule.defaultAllowedGrades(Event.EventType.RELAY_4X100M));
        assertEquals(all, EventGradeRule.defaultAllowedGrades(Event.EventType.SHOT_PUT));
        assertEquals(all, EventGradeRule.defaultAllowedGrades(Event.EventType.LONG_JUMP));
    }

    @Test
    @DisplayName("a field event is open to every grade, including the youngest")
    void fieldEventsAreOpenToEveryone() {
        for (Event.EventType type : Event.EventType.values()) {
            if (type.getCategory() == EventCategory.FIELD) {
                assertTrue(EventGradeRule.defaultAllowedGrades(type).contains(Grade.C),
                        type + " is open to the C grade");
            }
        }
    }

    // ------------------------------------------------------------ enforcement

    @Test
    @DisplayName("an event type with no rule at all is open, so a new event is never closed by accident")
    void aMissingRuleMeansAllowed() {
        when(ruleRepository.findByEventTypeAndGrade(any(), any())).thenReturn(Optional.empty());

        assertTrue(service.isAllowed(Event.EventType.RUN_1500M, Grade.C));
        assertTrue(service.isAllowed(Event.EventType.OTHER, Grade.C));
    }

    @Test
    @DisplayName("a rule that says no is honoured")
    void aRuleCanCloseAnEvent() {
        rules.add(EventGradeRule.builder().eventType(Event.EventType.RUN_1500M)
                .grade(Grade.C).allowed(false).build());
        givenRules();

        assertFalse(service.isAllowed(Event.EventType.RUN_1500M, Grade.C),
                "the C grade cannot enter the 1500M");
        assertTrue(service.isAllowed(Event.EventType.RUN_1500M, Grade.B),
                "the B grade still can");
        assertEquals(Set.of(Grade.A, Grade.B), service.allowedGrades(Event.EventType.RUN_1500M));
    }

    // ---------------------------------------------------------------- matrix

    @Test
    @DisplayName("the grid has a row per event and a column per grade")
    void theGridIsEventByGrade() {
        event(1L, Event.EventType.RUN_60M, Sex.MALE);
        event(2L, Event.EventType.RUN_1500M, Sex.MALE);
        rules.add(EventGradeRule.builder().eventType(Event.EventType.RUN_1500M)
                .grade(Grade.C).allowed(false).build());
        givenRules();

        GradeEligibilityDTO matrix = service.matrix();

        assertEquals(List.of("A", "B", "C"), matrix.getGrades());
        assertEquals(2, matrix.getEvents().size());
        GradeEligibilityDTO.EventRow sixty = matrix.getEvents().stream()
                .filter(row -> row.getEventType().equals("RUN_60M")).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, sixty.getAllowed().get("C"), "a 60M is open to the C grade");
        GradeEligibilityDTO.EventRow fifteen = matrix.getEvents().stream()
                .filter(row -> row.getEventType().equals("RUN_1500M")).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, fifteen.getAllowed().get("C"));
        assertEquals(Boolean.TRUE, fifteen.getAllowed().get("B"));
    }

    @Test
    @DisplayName("the grid counts how many events each grade may enter")
    void theGridCountsEachGradesEvents() {
        event(1L, Event.EventType.RUN_60M, Sex.MALE);
        event(2L, Event.EventType.RUN_60M, Sex.FEMALE);
        event(3L, Event.EventType.RUN_1500M, Sex.MALE);
        event(4L, Event.EventType.RUN_5000M, Sex.MALE);
        rules.add(EventGradeRule.builder().eventType(Event.EventType.RUN_1500M)
                .grade(Grade.C).allowed(false).build());
        rules.add(EventGradeRule.builder().eventType(Event.EventType.RUN_5000M)
                .grade(Grade.C).allowed(false).build());
        rules.add(EventGradeRule.builder().eventType(Event.EventType.RUN_5000M)
                .grade(Grade.B).allowed(false).build());
        givenRules();

        GradeEligibilityDTO matrix = service.matrix();

        assertEquals(4, matrix.getTotalEvents());
        assertEquals(4, matrix.getAllowedEventCounts().get("A"), "the A grade may enter everything");
        assertEquals(3, matrix.getAllowedEventCounts().get("B"), "the B grade sits out the 5000M");
        assertEquals(2, matrix.getAllowedEventCounts().get("C"),
                "the C grade runs only the two 60Ms");
    }

    // -------------------------------------------------------------- changing

    @Test
    @DisplayName("a cell can be changed and the grid reflects it")
    void aCellCanBeChanged() {
        event(1L, Event.EventType.RUN_60M, Sex.MALE);
        givenRules();

        service.update(List.of(GradeEligibilityDTO.RuleUpdate.builder()
                .eventType("RUN_60M").grade("C").allowed(false).build()));

        assertFalse(service.isAllowed(Event.EventType.RUN_60M, Grade.C));
        verify(ruleRepository, atLeastOnce()).save(any(EventGradeRule.class));
    }

    @Test
    @DisplayName("changing a cell back reopens the event")
    void aCellCanBeReopened() {
        event(1L, Event.EventType.RUN_60M, Sex.MALE);
        givenRules();

        service.update(List.of(GradeEligibilityDTO.RuleUpdate.builder()
                .eventType("RUN_60M").grade("C").allowed(false).build()));
        service.update(List.of(GradeEligibilityDTO.RuleUpdate.builder()
                .eventType("RUN_60M").grade("C").allowed(true).build()));

        assertTrue(service.isAllowed(Event.EventType.RUN_60M, Grade.C));
    }

    @Test
    @DisplayName("an unknown event type is rejected rather than silently ignored")
    void anUnknownTypeIsRejected() {
        givenRules();

        assertThrows(IllegalArgumentException.class, () -> service.update(
                List.of(GradeEligibilityDTO.RuleUpdate.builder()
                        .eventType("RUN_9999M").grade("C").allowed(false).build())));
    }

    @Test
    @DisplayName("seeding fills in the starting rules without disturbing the ones already set")
    void seedingDoesNotOverwriteDecisions() {
        event(1L, Event.EventType.RUN_1500M, Sex.MALE);
        // An administrator has already opened the 1500M to the C grade.
        EventGradeRule decided = EventGradeRule.builder()
                .eventType(Event.EventType.RUN_1500M).grade(Grade.C).allowed(true).build();
        rules.add(decided);
        givenRules();

        service.seedDefaults();

        assertTrue(decided.getAllowed(), "the administrator's choice stands");
        assertTrue(service.isAllowed(Event.EventType.RUN_1500M, Grade.C));
    }

    @Test
    @DisplayName("seeding covers every event type and grade")
    void seedingCoversTheCatalogue() {
        event(1L, Event.EventType.RUN_1500M, Sex.MALE);
        givenRules();

        service.seedDefaults();

        // Every type except OTHER, three grades each.
        int expected = (Event.EventType.values().length - 1) * Grade.values().length;
        assertEquals(expected, rules.size(), "a rule for every type and grade");
        assertFalse(service.isAllowed(Event.EventType.RUN_1500M, Grade.C),
                "and the school's starting rules applied");
        assertFalse(service.isAllowed(Event.EventType.RUN_5000M, Grade.B));
    }
}
