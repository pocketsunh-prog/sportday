package com.sportday.service;

import com.sportday.dto.EventDTO;
import com.sportday.entity.Event;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.StandardDefault;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.StandardDefaultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * An event <strong>inherits</strong> the school's default required standard for its
 * type, grade and division, without anybody setting it.
 *
 * <p>The school's requirement: "base on each grade update standard record for
 * default", and the half that makes it worth having is that a new event picks the
 * default up <em>by itself</em>. A standard set once — "400M, A grade, boys =
 * 64.0 s" — must appear on the next Boys 400M · A Grade event somebody creates, or
 * the school is back to typing it 72 times.</p>
 *
 * <p><strong>The key includes the sex division</strong>, and the case that matters is
 * the negative one: a boys' default must never reach a girls' event. The live
 * programme holds a Boys 400M and a Girls 400M at every grade, so a default keyed on
 * grade alone would give both races one qualifying time.</p>
 *
 * <p>Inheritance happens in {@code EventService.createEvent} through the one builder
 * every create goes through, so it is asserted here through the public API rather
 * than against a private method.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventStandardDefaultInheritanceTest {

    @Mock private EventRepository eventRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private EventGroupRepository eventGroupRepository;
    @Mock private EventResultRepository eventResultRepository;
    @Mock private SettingsService settingsService;
    @Mock private RecordService recordService;
    @Mock private SeasonService seasonService;
    @Mock private FinalQualificationService finalQualificationService;
    @Mock private RelayTeamService relayTeamService;
    @Mock private RelayReadiness relayReadiness;
    @Mock private StandardDefaultRepository standardDefaultRepository;

    @InjectMocks private EventService service;

    @BeforeEach
    void setUp() {
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(settingsService.maxEntriesFor(any())).thenReturn(2);
        when(seasonService.currentSeason()).thenReturn(null);
        when(enrollmentRepository.countByEventIdAndStatus(any(), any())).thenReturn(0L);
        when(enrollmentRepository.countUngroupedByEvent(any(), any())).thenReturn(0L);
        when(eventGroupRepository.countByEventId(any())).thenReturn(0L);
        when(relayTeamService.countTeamsForEvent(any())).thenReturn(0L);
        when(relayReadiness.shortfallsOf(any())).thenReturn(Map.of());
        // No default unless a test sets one, which is the state the live programme is in.
        when(standardDefaultRepository.findByTypeAndGradeAndSex(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    /** A create request naming only the type, grade and division. */
    private static EventDTO createFor(Event.EventType type, Grade grade, Sex sex) {
        return EventDTO.builder()
                .type(type.name())
                .grade(grade.name())
                .sex(sex.name())
                .eventDate(java.time.LocalDate.of(2026, 11, 6))
                .build();
    }

    /** A stored default for one key. */
    private void aDefaultOf(String standard, Event.EventType type, Grade grade, Sex sex) {
        when(standardDefaultRepository.findByTypeAndGradeAndSex(type, grade, sex))
                .thenReturn(Optional.of(StandardDefault.builder()
                        .id(1L).type(type).grade(grade).sex(sex).standard(new BigDecimal(standard))
                        .build()));
    }

    // ---------------------------------------------------------------- inherited

    @Test
    @DisplayName("a new event of that type, grade and division picks the default up by itself")
    void aNewEventInheritsTheDefault() {
        aDefaultOf("64.0", Event.EventType.RUN_400M, Grade.A, Sex.MALE);

        EventDTO created = service.createEvent(createFor(Event.EventType.RUN_400M, Grade.A, Sex.MALE));

        assertEquals(0, new BigDecimal("64.0").compareTo(created.getStandard()),
                "the school set it once and this event inherited it");
        assertEquals("64 s", created.getStandardLabel());
        assertTrue(created.getStandardFromDefault(),
                "and it is stamped as following the default, so a later change moves it");

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(saved.capture());
        assertEquals(0, new BigDecimal("64.0").compareTo(saved.getValue().getStandard()),
                "and the row really carries it");
    }

    @Test
    @DisplayName("a field event inherits its own default, in metres")
    void aFieldEventInheritsTheDefault() {
        aDefaultOf("12.5", Event.EventType.SHOT_PUT, Grade.B, Sex.FEMALE);

        EventDTO created = service.createEvent(createFor(Event.EventType.SHOT_PUT, Grade.B, Sex.FEMALE));

        assertEquals("12.5 M", created.getStandardLabel());
        assertTrue(created.getStandardFromDefault());
    }

    @Test
    @DisplayName("each grade inherits its own default, not another grade's")
    void eachGradeInheritsItsOwnDefault() {
        aDefaultOf("64.0", Event.EventType.RUN_400M, Grade.A, Sex.MALE);
        aDefaultOf("70.0", Event.EventType.RUN_400M, Grade.B, Sex.MALE);

        EventDTO aGrade = service.createEvent(createFor(Event.EventType.RUN_400M, Grade.A, Sex.MALE));
        EventDTO bGrade = service.createEvent(createFor(Event.EventType.RUN_400M, Grade.B, Sex.MALE));

        assertEquals(0, new BigDecimal("64.0").compareTo(aGrade.getStandard()));
        assertEquals(0, new BigDecimal("70.0").compareTo(bGrade.getStandard()));
    }

    // ------------------------------------------------------- the sex regression

    @Test
    @DisplayName("a boys' default is NOT inherited by a girls' event: the division is in the key")
    void aBoysDefaultIsNotAppliedToAGirlsEvent() {
        // The regression that matters. Both races exist at every grade on the live
        // programme, so a default keyed on grade alone would hand the girls' 400M the
        // boys' time.
        aDefaultOf("64.0", Event.EventType.RUN_400M, Grade.A, Sex.MALE);

        EventDTO girls = service.createEvent(createFor(Event.EventType.RUN_400M, Grade.A, Sex.FEMALE));

        assertNull(girls.getStandard(), "the girls' 400M did not take the boys' 64.0 s");
        assertFalse(girls.getStandardFromDefault());
    }

    @Test
    @DisplayName("the girls' default is the one a girls' event takes, and vice versa")
    void eachDivisionTakesItsOwnDefault() {
        aDefaultOf("64.0", Event.EventType.RUN_400M, Grade.A, Sex.MALE);
        aDefaultOf("72.5", Event.EventType.RUN_400M, Grade.A, Sex.FEMALE);

        EventDTO boys = service.createEvent(createFor(Event.EventType.RUN_400M, Grade.A, Sex.MALE));
        EventDTO girls = service.createEvent(createFor(Event.EventType.RUN_400M, Grade.A, Sex.FEMALE));

        assertEquals(0, new BigDecimal("64.0").compareTo(boys.getStandard()));
        assertEquals(0, new BigDecimal("72.5").compareTo(girls.getStandard()));
    }

    // --------------------------------------------------------- not offered at all

    @Test
    @DisplayName("a relay inherits nothing: it carries no standard and is never offered one")
    void aRelayInheritsNothing() {
        // Even a row the API would refuse cannot reach a relay.
        aDefaultOf("50", Event.EventType.RELAY_4X100M, Grade.A, Sex.MALE);

        EventDTO relay = service.createEvent(createFor(Event.EventType.RELAY_4X100M, Grade.A, Sex.MALE));

        assertNull(relay.getStandard());
        assertFalse(relay.getCarriesStandard(), "a relay carries no standard at all");
        assertFalse(relay.getStandardFromDefault());
    }

    @Test
    @DisplayName("a 100M inherits nothing: it is not a qualifying event")
    void aSprintInheritsNothing() {
        aDefaultOf("11.5", Event.EventType.RUN_100M, Grade.A, Sex.MALE);

        EventDTO sprint = service.createEvent(createFor(Event.EventType.RUN_100M, Grade.A, Sex.MALE));

        assertNull(sprint.getStandard());
        assertFalse(sprint.getCarriesStandard());
    }

    @Test
    @DisplayName("a key with no default leaves the event with no standard, as before")
    void aKeyWithoutADefaultLeavesNoStandard() {
        EventDTO created = service.createEvent(createFor(Event.EventType.RUN_800M, Grade.C, Sex.MALE));

        assertNull(created.getStandard(), "exactly the behaviour that came before this feature");
        assertFalse(created.getStandardFromDefault(),
                "no default was set for this key, so nothing was inherited");
    }

    // ------------------------------------------------------- an explicit number wins

    @Test
    @DisplayName("a create that names its own standard keeps it, and is marked as hand-set")
    void anExplicitStandardOnACreateWins() {
        aDefaultOf("64.0", Event.EventType.RUN_400M, Grade.A, Sex.MALE);

        EventDTO created = service.createEvent(EventDTO.builder()
                .type(Event.EventType.RUN_400M.name())
                .grade(Grade.A.name())
                .sex(Sex.MALE.name())
                .standard(new BigDecimal("61.5"))
                .eventDate(java.time.LocalDate.of(2026, 11, 6))
                .build());

        assertEquals(0, new BigDecimal("61.5").compareTo(created.getStandard()),
                "the school's own number for this race is not overruled by the default");
        assertFalse(created.getStandardFromDefault(),
                "and it is the school's own, so an apply must not replace it");
    }

    @Test
    @DisplayName("a created event can be put back on its grade default")
    void anEventCanBePutBackOnTheDefault() {
        aDefaultOf("64.0", Event.EventType.RUN_400M, Grade.A, Sex.MALE);
        Event existing = Event.builder()
                .id(132L)
                .name("Boys 400M · A Grade")
                .type(Event.EventType.RUN_400M)
                .category(Event.EventType.RUN_400M.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.A)
                .standard(new BigDecimal("61.5"))
                .standardIsDefault(false)
                .eventDate(java.time.LocalDate.of(2026, 11, 6))
                .maxParticipants(512)
                .groupSize(8)
                .enabled(true)
                .build();
        when(eventRepository.findById(132L)).thenReturn(Optional.of(existing));

        EventDTO updated = service.updateEvent(132L,
                EventDTO.builder().useDefaultStandard(true).build());

        assertEquals(0, new BigDecimal("64.0").compareTo(updated.getStandard()));
        assertTrue(updated.getStandardFromDefault());
    }

    @Test
    @DisplayName("asking for a default that is not set is refused, and names the key")
    void useDefaultStandardIsRefusedWhenThereIsNoDefault() {
        Event existing = Event.builder()
                .id(132L)
                .name("Boys 400M · A Grade")
                .type(Event.EventType.RUN_400M)
                .category(Event.EventType.RUN_400M.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.A)
                .eventDate(java.time.LocalDate.of(2026, 11, 6))
                .maxParticipants(512)
                .groupSize(8)
                .enabled(true)
                .build();
        when(eventRepository.findById(132L)).thenReturn(Optional.of(existing));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateEvent(132L, EventDTO.builder().useDefaultStandard(true).build()));

        assertTrue(error.getMessage().contains("400M"), error.getMessage());
        assertTrue(error.getMessage().contains("A Grade"), error.getMessage());
        assertTrue(error.getMessage().contains("no default standard"), error.getMessage());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("clearing a standard is a decision, so the event stops following the default")
    void clearingAStandardUnfollowsTheDefault() {
        Event existing = Event.builder()
                .id(132L)
                .name("Boys 400M · A Grade")
                .type(Event.EventType.RUN_400M)
                .category(Event.EventType.RUN_400M.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.A)
                .standard(new BigDecimal("64.0"))
                .standardIsDefault(true)
                .eventDate(java.time.LocalDate.of(2026, 11, 6))
                .maxParticipants(512)
                .groupSize(8)
                .enabled(true)
                .build();
        when(eventRepository.findById(132L)).thenReturn(Optional.of(existing));

        EventDTO updated = service.updateEvent(132L,
                EventDTO.builder().clearStandard(true).build());

        assertNull(updated.getStandard());
        assertFalse(updated.getStandardFromDefault(),
                "a blank somebody chose is not a blank nobody has filled in");
    }

    @Test
    @DisplayName("a number typed on one event is stamped as hand-set, even if it equals the default")
    void aTypedStandardIsStampedAsHandSet() {
        Event existing = Event.builder()
                .id(132L)
                .name("Boys 400M · A Grade")
                .type(Event.EventType.RUN_400M)
                .category(Event.EventType.RUN_400M.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.A)
                .standardIsDefault(true)
                .standard(new BigDecimal("64.0"))
                .eventDate(java.time.LocalDate.of(2026, 11, 6))
                .maxParticipants(512)
                .groupSize(8)
                .enabled(true)
                .build();
        when(eventRepository.findById(132L)).thenReturn(Optional.of(existing));

        EventDTO updated = service.updateEvent(132L,
                EventDTO.builder().standard(new BigDecimal("64.0")).build());

        assertFalse(updated.getStandardFromDefault(),
                "somebody decided this event's number, so an apply must leave it alone");
    }

    // ------------------------------------------------------------- the catalogue

    @Test
    @DisplayName("the seeded catalogue follows the defaults too, so no create path ignores them")
    void theSeededCatalogueInheritsTheDefaults() {
        aDefaultOf("64.0", Event.EventType.RUN_400M, Grade.A, Sex.MALE);

        service.createDefaults(java.time.LocalDate.of(2026, 11, 6), true);

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository, atLeastOnce()).save(saved.capture());
        Event boysA = saved.getAllValues().stream()
                .filter(event -> event.getType() == Event.EventType.RUN_400M)
                .filter(event -> event.getGrade() == Grade.A)
                .filter(event -> event.getSex() == Sex.MALE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the catalogue seeded no Boys 400M A Grade"));
        assertEquals(0, new BigDecimal("64.0").compareTo(boysA.getStandard()));
        assertTrue(boysA.isStandardInherited());

        Event girlsA = saved.getAllValues().stream()
                .filter(event -> event.getType() == Event.EventType.RUN_400M)
                .filter(event -> event.getGrade() == Grade.A)
                .filter(event -> event.getSex() == Sex.FEMALE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the catalogue seeded no Girls 400M A Grade"));
        assertNull(girlsA.getStandard(), "and the girls' event did not take the boys' default");
    }
}
