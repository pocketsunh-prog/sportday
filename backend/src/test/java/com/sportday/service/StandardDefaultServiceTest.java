package com.sportday.service;

import com.sportday.dto.StandardDefaultDTO;
import com.sportday.entity.Event;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.StandardDefault;
import com.sportday.repository.EventRepository;
import com.sportday.repository.StandardDefaultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The <strong>default required standard</strong> per event type, grade and sex
 * division, and what applying one does to the events that inherit it.
 *
 * <p>The school's requirement: "base on each grade update standard record for
 * default". A standard is set once — "400M, A grade, boys = 64.0 s" — and the
 * events of that key inherit it.</p>
 *
 * <p>What this test pins down, in the order the requirement asks for it:</p>
 *
 * <ul>
 *   <li>the key is <strong>type &times; grade &times; sex</strong>, and a boys'
 *       default is <strong>never</strong> applied to a girls' event — the
 *       regression that matters, because the live programme holds both at every
 *       grade and a grade-only key would give them one qualifying time;</li>
 *   <li>a default for a type that carries no standard is <strong>refused</strong>,
 *       so a relay or a 100M cannot be given one and then quietly inherit
 *       nothing;</li>
 *   <li>changing a default updates the events that inherit it — the "update
 *       standard record" half — and reports what it did;</li>
 *   <li>a <strong>hand-set</strong> standard is left alone by a plain apply
 *       (counted and reported), and is only replaced by the explicit
 *       {@code mode=ALL};</li>
 *   <li>a <strong>relay is never affected</strong>, even when a matching key
 *       somehow existed;</li>
 *   <li>a dry run writes nothing at all.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StandardDefaultServiceTest {

    @Mock private StandardDefaultRepository standardDefaultRepository;
    @Mock private EventRepository eventRepository;

    @InjectMocks private StandardDefaultService service;

    @BeforeEach
    void setUp() {
        when(standardDefaultRepository.save(any(StandardDefault.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ------------------------------------------------------------------ helpers

    /** A stored default for one key. A fresh instance per call — an apply mutates nothing on it. */
    private static StandardDefault row(long id, Event.EventType type, Grade grade, Sex sex,
                                       String standard) {
        return StandardDefault.builder()
                .id(id)
                .type(type)
                .grade(grade)
                .sex(sex)
                .standard(standard == null ? null : new BigDecimal(standard))
                .build();
    }

    /** An event of the live programme's shape: one type, one grade, one division. */
    private static Event event(long id, Event.EventType type, Grade grade, Sex sex, String standard,
                               Boolean standardIsDefault) {
        return Event.builder()
                .id(id)
                .name((sex == Sex.MALE ? "Boys " : "Girls ") + type.getDisplayName()
                        + " · " + grade.getLabel())
                .type(type)
                .category(type.getCategory())
                .sex(sex)
                .grade(grade)
                .standard(standard == null ? null : new BigDecimal(standard))
                .standardIsDefault(standardIsDefault)
                .eventDate(java.time.LocalDate.of(2026, 11, 6))
                .maxParticipants(512)
                .groupSize(type.getDefaultGroupSize())
                .enabled(true)
                .build();
    }

    private void repositoryHolds(StandardDefault... rows) {
        when(standardDefaultRepository.findAll()).thenReturn(new ArrayList<>(List.of(rows)));
    }

    // --------------------------------------------------------------------- set

    @Test
    @DisplayName("a default is set for a type, grade and division and comes back with its unit")
    void setsADefault() {
        when(standardDefaultRepository.findByTypeAndGradeAndSex(
                Event.EventType.RUN_400M, Grade.A, Sex.MALE)).thenReturn(Optional.empty());

        StandardDefaultDTO saved = service.set(
                Event.EventType.RUN_400M, Grade.A, Sex.MALE, new BigDecimal("64.0"));

        assertEquals("RUN_400M", saved.getType());
        assertEquals("A", saved.getGrade());
        assertEquals("MALE", saved.getSex());
        assertEquals(0, new BigDecimal("64.0").compareTo(saved.getStandard()));
        assertEquals("64 s", saved.getStandardLabel());
        assertEquals("s", saved.getUnit());
        assertEquals("RUN_400M|A|MALE", saved.getKey());
    }

    @Test
    @DisplayName("a field default is in metres, and the key still carries the division")
    void setsAFieldDefault() {
        when(standardDefaultRepository.findByTypeAndGradeAndSex(
                Event.EventType.SHOT_PUT, Grade.B, Sex.FEMALE)).thenReturn(Optional.empty());

        StandardDefaultDTO saved = service.set(
                Event.EventType.SHOT_PUT, Grade.B, Sex.FEMALE, new BigDecimal("7.5"));

        assertEquals("M", saved.getUnit());
        assertEquals("7.5 M", saved.getStandardLabel());
        assertEquals("SHOT_PUT|B|FEMALE", saved.getKey());
    }

    @Test
    @DisplayName("a default for a type that carries no standard is refused, and nothing is stored")
    void refusesADefaultForATypeThatCarriesNoStandard() {
        // A relay: run and scored by team, so there is no qualifying mark to inherit.
        IllegalArgumentException relay = assertThrows(IllegalArgumentException.class,
                () -> service.set(Event.EventType.RELAY_4X100M, Grade.A, Sex.MALE,
                        new BigDecimal("50")));
        assertTrue(relay.getMessage().contains("4x100M"), relay.getMessage());
        assertTrue(relay.getMessage().contains("carries a required standard"), relay.getMessage());

        // A short sprint: not qualifying in this school's programme.
        assertThrows(IllegalArgumentException.class,
                () -> service.set(Event.EventType.RUN_100M, Grade.A, Sex.MALE,
                        new BigDecimal("11.5")));

        verify(standardDefaultRepository, never()).save(any());
    }

    @Test
    @DisplayName("a default of zero or less is refused: it is a typo, not a qualifying mark")
    void refusesADefaultThatIsNotPositive() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.set(Event.EventType.RUN_800M, Grade.A, Sex.MALE, BigDecimal.ZERO));

        assertTrue(error.getMessage().contains("greater than zero"), error.getMessage());
        verify(standardDefaultRepository, never()).save(any());
    }

    @Test
    @DisplayName("a default cannot be set without a division: the boys' and girls' races differ")
    void refusesADefaultWithoutADivision() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.set(Event.EventType.RUN_400M, Grade.A, null, new BigDecimal("64")));

        assertTrue(error.getMessage().contains("division"), error.getMessage());
        verify(standardDefaultRepository, never()).save(any());
    }

    @Test
    @DisplayName("an empty box clears the default, leaving no row behind")
    void clearsADefault() {
        StandardDefault existing = row(9L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "64");
        when(standardDefaultRepository.findByTypeAndGradeAndSex(
                Event.EventType.RUN_400M, Grade.A, Sex.MALE)).thenReturn(Optional.of(existing));

        StandardDefaultDTO cleared = service.set(
                Event.EventType.RUN_400M, Grade.A, Sex.MALE, null);

        assertNull(cleared.getStandard(), "the default holds no number now");
        assertNull(cleared.getStandardLabel());
        assertEquals("RUN_400M|A|MALE", cleared.getKey(),
                "the answer still names the key that was cleared");
        verify(standardDefaultRepository).delete(existing);
        verify(standardDefaultRepository, never()).save(any());
    }

    @Test
    @DisplayName("setting a default changes no event at all: it is applied on purpose, later")
    void settingADefaultTouchesNoEvent() {
        when(standardDefaultRepository.findByTypeAndGradeAndSex(
                Event.EventType.RUN_400M, Grade.A, Sex.MALE)).thenReturn(Optional.empty());
        Event boys = event(1L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, null, null);

        service.set(Event.EventType.RUN_400M, Grade.A, Sex.MALE, new BigDecimal("64.0"));

        assertNull(boys.getStandard(), "the live event is untouched by the save");
        verifyNoInteractions(eventRepository);
    }

    // ------------------------------------------------------------------- apply

    @Test
    @DisplayName("applying a default fills the events of that key — the update-standard half")
    void applyUpdatesTheEventsThatInheritIt() {
        // What the live programme looks like today: 72 qualifying events, no standard.
        Event boysA = event(132L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, null, null);
        Event boysB = event(133L, Event.EventType.RUN_400M, Grade.B, Sex.MALE, null, null);
        // A girls' 400M at the same grade, with the A-grade girls' default of its own.
        Event girlsA = event(200L, Event.EventType.RUN_400M, Grade.A, Sex.FEMALE, null, null);
        when(eventRepository.findAll()).thenReturn(List.of(boysA, boysB, girlsA));
        repositoryHolds(
                row(1L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "64.0"),
                row(2L, Event.EventType.RUN_400M, Grade.A, Sex.FEMALE, "72.5"));

        StandardDefaultService.ApplyResult result =
                service.apply(StandardDefaultService.ApplyMode.INHERITED, false);

        assertEquals(2, result.getChanged(), "the A-grade boys' and girls' 400M, not the B grade");
        assertEquals(0, result.getKept());
        assertFalse(result.isDryRun());
        assertEquals(0, new BigDecimal("64.0").compareTo(boysA.getStandard()));
        assertTrue(boysA.isStandardInherited(), "and it is stamped as following the default");
        assertEquals(0, new BigDecimal("72.5").compareTo(girlsA.getStandard()));
        assertNull(boysB.getStandard(), "the B grade has no default, so it is left empty");
        verify(eventRepository).saveAll(any());
    }

    @Test
    @DisplayName("a boys' default is NOT applied to a girls' event: the division is part of the key")
    void aboysDefaultIsNeverAppliedToAGirlsEvent() {
        // The regression that matters. A default keyed on grade alone would hand the
        // girls' 400M the boys' 64.0 s, and both races exist at every grade.
        Event girlsA = event(200L, Event.EventType.RUN_400M, Grade.A, Sex.FEMALE, null, null);
        when(eventRepository.findAll()).thenReturn(List.of(girlsA));
        repositoryHolds(row(1L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "64.0"));

        StandardDefaultService.ApplyResult result =
                service.apply(StandardDefaultService.ApplyMode.INHERITED, false);

        assertEquals(0, result.getChanged());
        assertNull(girlsA.getStandard(), "the girls' 400M did not take the boys' time");
        assertFalse(girlsA.isStandardInherited());
        verify(eventRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("a relay is never affected by an apply, whatever the defaults hold")
    void arelayIsNeverAffected() {
        Event relay = event(300L, Event.EventType.RELAY_4X100M, Grade.A, Sex.MALE, null, null);
        when(eventRepository.findAll()).thenReturn(List.of(relay));
        // A row that the API would refuse anyway: the apply must not act on it.
        repositoryHolds(row(1L, Event.EventType.RELAY_4X100M, Grade.A, Sex.MALE, "50"));

        StandardDefaultService.ApplyResult result =
                service.apply(StandardDefaultService.ApplyMode.ALL, false);

        assertEquals(0, result.getChanged());
        assertNull(relay.getStandard());
        assertTrue(result.getKeys().isEmpty());
        verify(eventRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("a short sprint is never affected either: it carries no standard")
    void asprintIsNeverAffected() {
        Event sprint = event(400L, Event.EventType.RUN_100M, Grade.A, Sex.MALE, null, null);
        when(eventRepository.findAll()).thenReturn(List.of(sprint));
        repositoryHolds(row(1L, Event.EventType.RUN_100M, Grade.A, Sex.MALE, "11.5"));

        StandardDefaultService.ApplyResult result =
                service.apply(StandardDefaultService.ApplyMode.ALL, false);

        assertEquals(0, result.getChanged());
        assertNull(sprint.getStandard());
    }

    @Test
    @DisplayName("a plain apply re-points an event that follows a default")
    void applyRepointsAnInheritedStandard() {
        // Set from the default when the event was created, so it moves with it.
        Event boys = event(132L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "64.0", Boolean.TRUE);
        when(eventRepository.findAll()).thenReturn(List.of(boys));
        repositoryHolds(row(1L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "62.5"));

        StandardDefaultService.ApplyResult result =
                service.apply(StandardDefaultService.ApplyMode.INHERITED, false);

        assertEquals(1, result.getChanged());
        assertEquals(0, new BigDecimal("62.5").compareTo(boys.getStandard()));
        assertEquals(1, result.getEvents().size());
        assertEquals("Boys 400M · A Grade", result.getEvents().get(0).getEventName());
        assertEquals("RUN_400M|A|MALE", result.getKeys().get(0).getKey());
    }

    @Test
    @DisplayName("a hand-set standard is left exactly as it is, and reported as kept")
    void applyNeverOverwritesAHandSetStandard() {
        // Set by hand — an exception the school made for this one race.
        Event boys = event(132L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "70.0", Boolean.FALSE);
        when(eventRepository.findAll()).thenReturn(List.of(boys));
        repositoryHolds(row(1L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "64.0"));

        StandardDefaultService.ApplyResult result =
                service.apply(StandardDefaultService.ApplyMode.INHERITED, false);

        assertEquals(0, result.getChanged());
        assertEquals(1, result.getKept(), "and the administrator is told it was kept");
        assertEquals(0, new BigDecimal("70.0").compareTo(boys.getStandard()),
                "the hand-set number is untouched");
        assertFalse(boys.isStandardInherited());
        verify(eventRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("a standard with no record of its origin is treated as hand-set and left alone")
    void applyTreatsAnUnstampedStandardAsHandSet() {
        // What every standard typed before the flag existed looks like.
        Event boys = event(132L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "70.0", null);
        when(eventRepository.findAll()).thenReturn(List.of(boys));
        repositoryHolds(row(1L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "64.0"));

        StandardDefaultService.ApplyResult result =
                service.apply(StandardDefaultService.ApplyMode.INHERITED, false);

        assertEquals(0, result.getChanged());
        assertEquals(1, result.getKept());
        assertEquals(0, new BigDecimal("70.0").compareTo(boys.getStandard()));
    }

    @Test
    @DisplayName("mode=ALL is the only way a hand-set standard is replaced, and it says so")
    void applyAllOverwritesAHandSetStandard() {
        Event boys = event(132L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "70.0", Boolean.FALSE);
        when(eventRepository.findAll()).thenReturn(List.of(boys));
        repositoryHolds(row(1L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "64.0"));

        StandardDefaultService.ApplyResult result =
                service.apply(StandardDefaultService.ApplyMode.ALL, false);

        assertEquals(1, result.getChanged());
        assertEquals(0, result.getKept(), "nothing is spared in this mode");
        assertEquals("ALL", result.getMode());
        assertEquals(0, new BigDecimal("64.0").compareTo(boys.getStandard()));
        assertTrue(boys.isStandardInherited());
    }

    @Test
    @DisplayName("a dry run reports what would change and writes nothing")
    void dryRunWritesNothing() {
        Event boys = event(132L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, null, null);
        when(eventRepository.findAll()).thenReturn(List.of(boys));
        repositoryHolds(row(1L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "64.0"));

        StandardDefaultService.ApplyResult result =
                service.apply(StandardDefaultService.ApplyMode.INHERITED, true);

        assertTrue(result.isDryRun());
        assertEquals(1, result.getChanged(), "the count an administrator is shown first");
        assertNull(boys.getStandard(), "and the live event has not been touched");
        verify(eventRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("applying twice changes nothing the second time")
    void applyIsIdempotent() {
        Event boys = event(132L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, null, null);
        when(eventRepository.findAll()).thenReturn(List.of(boys));
        repositoryHolds(row(1L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "64.0"));

        assertEquals(1, service.apply(StandardDefaultService.ApplyMode.INHERITED, false)
                .getChanged());
        assertEquals(0, service.apply(StandardDefaultService.ApplyMode.INHERITED, false)
                .getChanged(), "already in step, so nothing is written");
    }

    @Test
    @DisplayName("a key with no default says nothing about its events, so nothing is emptied")
    void aKeyWithoutADefaultTouchesNothing() {
        Event boys = event(132L, Event.EventType.RUN_800M, Grade.A, Sex.MALE, "145", null);
        when(eventRepository.findAll()).thenReturn(List.of(boys));
        repositoryHolds();

        StandardDefaultService.ApplyResult result =
                service.apply(StandardDefaultService.ApplyMode.ALL, false);

        assertEquals(0, result.getChanged());
        assertEquals(0, result.getKept());
        assertEquals(0, new BigDecimal("145").compareTo(boys.getStandard()));
        verify(eventRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("a default holding no number says nothing either, and never empties an event")
    void anEmptyDefaultTouchesNothing() {
        Event boys = event(132L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "70.0", Boolean.TRUE);
        when(eventRepository.findAll()).thenReturn(List.of(boys));
        repositoryHolds(row(1L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, null));

        StandardDefaultService.ApplyResult result =
                service.apply(StandardDefaultService.ApplyMode.ALL, false);

        assertEquals(0, result.getChanged());
        assertEquals(0, new BigDecimal("70.0").compareTo(boys.getStandard()));
    }

    // -------------------------------------------------------------------- list

    @Test
    @DisplayName("the list reads every default once, in key order")
    void listsEveryDefault() {
        when(standardDefaultRepository.findAllByOrderByTypeAscGradeAscSexAsc())
                .thenReturn(List.of(row(1L, Event.EventType.RUN_400M, Grade.A, Sex.MALE, "64")));

        List<StandardDefaultDTO> listed = service.list();

        assertEquals(1, listed.size());
        assertEquals("RUN_400M", listed.get(0).getType());
        assertEquals("A Grade", listed.get(0).getGradeLabel());
        assertEquals(0, new BigDecimal("64").compareTo(listed.get(0).getStandard()));
    }

    @Test
    @DisplayName("only the types that carry a standard are offered, and no relay is among them")
    void onlyQualifyingTypesAreOffered() {
        assertTrue(StandardDefaultService.STANDARD_TYPES.contains(Event.EventType.RUN_400M));
        assertTrue(StandardDefaultService.STANDARD_TYPES.contains(Event.EventType.SHOT_PUT));
        assertTrue(StandardDefaultService.STANDARD_TYPES.contains(Event.EventType.HURDLES_400M));
        assertFalse(StandardDefaultService.STANDARD_TYPES.contains(Event.EventType.RUN_100M),
                "a short sprint is not qualifying");
        assertFalse(StandardDefaultService.STANDARD_TYPES.contains(Event.EventType.RUN_200M));
        assertFalse(StandardDefaultService.STANDARD_TYPES.contains(Event.EventType.RELAY_4X100M),
                "a relay is run and scored by team");
        assertFalse(StandardDefaultService.STANDARD_TYPES.contains(Event.EventType.RELAY_4X400M));
        // Every offered type really does carry one — the list is derived, not typed out.
        assertTrue(StandardDefaultService.STANDARD_TYPES.stream()
                .allMatch(Event.EventType::carriesAStandard));
    }
}
