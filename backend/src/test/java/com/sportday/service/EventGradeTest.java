package com.sportday.service;

import com.sportday.dto.EventDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * An event belongs to exactly one grade.
 *
 * <p>Requirement: "Boys 100M · A Grade", "· B Grade" and "· C Grade" are three
 * separate events, so no grade is ever ranked against another. The catalogue
 * therefore seeds two or three events per type and division rather than one
 * grade-mixed race, and a create or an update that would leave an event without a
 * grade — or give it a grade the type does not run — is refused.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventGradeTest {

    @Mock private EventRepository eventRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private EventGroupRepository eventGroupRepository;
    @Mock private EventResultRepository eventResultRepository;
    @Mock private SettingsService settingsService;
    @Mock private RecordService recordService;
    @Mock private SeasonService seasonService;
    @Mock private FinalQualificationService finalQualificationService;
    /** The readiness rule, mocked: none of these events is a half-built relay. */
    @Mock private RelayReadiness relayReadiness;
    /** The school's per-grade default standard, read when an event is created. */
    @Mock private StandardDefaultRepository standardDefaultRepository;

    @InjectMocks private EventService service;

    private final List<Event> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> {
            Event event = invocation.getArgument(0);
            if (event.getId() == null) {
                event.setId((long) saved.size() + 1);
            }
            saved.add(event);
            return event;
        });
        when(eventRepository.findFirstByTypeAndSexAndGrade(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(relayReadiness.shortfallsOf(any())).thenReturn(java.util.Map.of());
    }

    // -------------------------------------------------------------- seeding

    @Test
    @DisplayName("the catalogue seeds one event per grade, so a type all grades run yields six")
    void everyGradeGetsItsOwnEvent() {
        int created = service.createDefaults(LocalDate.of(2026, 11, 6), true);

        List<Event> hundred = savedOfType(Event.EventType.RUN_100M);
        assertEquals(6, hundred.size(),
                "Boys and Girls, each in the A, B and C grade — three races per division");
        assertEquals(List.of("Boys 100M · A Grade", "Boys 100M · B Grade", "Boys 100M · C Grade",
                        "Girls 100M · A Grade", "Girls 100M · B Grade", "Girls 100M · C Grade"),
                hundred.stream().map(Event::getName).toList(),
                "each event's name carries its grade, which is what the sheets and lists print");
        assertTrue(saved.stream().allMatch(event -> event.getGrade() != null),
                "no seeded event is left without a grade");
        assertTrue(created > 0);
    }

    @Test
    @DisplayName("the 5000M exists for the A grade only, and the 1500M and 110M hurdles for A and B")
    void theRestrictedEventsExistOnlyForTheirGrades() {
        service.createDefaults(LocalDate.of(2026, 11, 6), true);

        assertEquals(List.of(Grade.A, Grade.A),
                savedOfType(Event.EventType.RUN_5000M).stream().map(Event::getGrade).toList(),
                "only the A grade runs the 5000M");
        assertEquals(List.of(Grade.A, Grade.B, Grade.A, Grade.B),
                savedOfType(Event.EventType.RUN_1500M).stream().map(Event::getGrade).toList(),
                "the C grade does not run the 1500M");
        assertEquals(List.of(Grade.A, Grade.B, Grade.A, Grade.B),
                savedOfType(Event.EventType.HURDLES_110M).stream().map(Event::getGrade).toList(),
                "nor the 110M hurdles — the C grade runs the 100M hurdles instead");
        assertEquals(6, savedOfType(Event.EventType.HURDLES_100M).size(),
                "the 100M hurdles is run by all three grades");
    }

    @Test
    @DisplayName("the catalogue is 112 events: 20 types x 2 divisions, less the grade exclusions")
    void theCatalogueHoldsOneHundredAndTwelveEvents() {
        int created = service.createDefaults(LocalDate.of(2026, 11, 6), true);

        // 20 event types (OTHER is not part of the catalogue) x 2 divisions x 3 grades
        // = 120; less the C grade's 1500M (2 races), the 110M hurdles (2) and the
        // B and C grades' 5000M (4) = 112.
        assertEquals(112, created);
        assertEquals(112, saved.size());
        assertEquals(20 * 2 * 3 - 2 - 2 - 4, created,
                "three events per type and division, less the eight that no grade runs");
    }

    @Test
    @DisplayName("seeding again adds nothing — an existing event of that type, division and grade stands")
    void seedingIsIdempotent() {
        when(eventRepository.findFirstByTypeAndSexAndGrade(any(), any(), any()))
                .thenReturn(Optional.of(Event.builder().id(1L).build()));

        assertEquals(0, service.createDefaults(LocalDate.of(2026, 11, 6), true));
        verify(eventRepository, never()).save(any(Event.class));
    }

    // -------------------------------------------------- creating and updating

    @Test
    @DisplayName("a new event without a grade is refused")
    void createWithoutAGradeIsRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createEvent(EventDTO.builder()
                        .type("RUN_100M").sex("MALE").build()));

        assertTrue(error.getMessage().contains("grade"), error.getMessage());
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("an event is named for its division, type and grade")
    void aNewEventCarriesItsGradeInItsName() {
        EventDTO created = service.createEvent(EventDTO.builder()
                .type("RUN_100M").sex("MALE").grade("A").build());

        assertEquals("Boys 100M · A Grade", created.getName());
        assertEquals("A", created.getGrade());
        assertEquals("A Grade", created.getGradeLabel());
    }

    @Test
    @DisplayName("a grade the type does not run is refused — a C grade 5000M is not a race")
    void createWithAGradeTheTypeDoesNotRunIsRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createEvent(EventDTO.builder()
                        .type("RUN_5000M").sex("MALE").grade("C").build()));

        assertTrue(error.getMessage().contains("5000M"), error.getMessage());
        assertTrue(error.getMessage().contains("C Grade"), error.getMessage());
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("a value that is not one of the three grades is refused")
    void anUnknownGradeIsRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createEvent(EventDTO.builder()
                        .type("RUN_100M").sex("MALE").grade("D").build()));

        assertTrue(error.getMessage().contains("Unknown grade"), error.getMessage());
    }

    @Test
    @DisplayName("moving a C-grade event onto the 5000M is refused, because that grade does not run it")
    void changingAnEventTypeToOneTheGradeDoesNotRunIsRefused() {
        Event existing = Event.builder().id(7L).name("Boys 100M · C Grade")
                .type(Event.EventType.RUN_100M).category(EventCategory.TRACK)
                .sex(Sex.MALE).grade(Grade.C)
                .eventDate(LocalDate.of(2026, 11, 6)).enabled(true).groupSize(8).build();
        when(eventRepository.findById(7L)).thenReturn(Optional.of(existing));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateEvent(7L, EventDTO.builder().type("RUN_5000M").build()));

        assertTrue(error.getMessage().contains("C Grade"), error.getMessage());
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("changing an event's grade renames it while it still carries its default name")
    void changingTheGradeRenamesADefaultName() {
        Event existing = Event.builder().id(7L).name("Boys 100M · B Grade")
                .type(Event.EventType.RUN_100M).category(EventCategory.TRACK)
                .sex(Sex.MALE).grade(Grade.B)
                .eventDate(LocalDate.of(2026, 11, 6)).enabled(true).groupSize(8).build();
        when(eventRepository.findById(7L)).thenReturn(Optional.of(existing));

        EventDTO updated = service.updateEvent(7L, EventDTO.builder().grade("A").build());

        assertEquals("A", updated.getGrade());
        assertEquals("Boys 100M · A Grade", updated.getName(),
                "a name the school did not choose follows the event");
    }

    @Test
    @DisplayName("a school's own event title is never overwritten by a grade change")
    void aChosenNameIsLeftAlone() {
        Event existing = Event.builder().id(7L).name("Senior Boys Sprint")
                .type(Event.EventType.RUN_100M).category(EventCategory.TRACK)
                .sex(Sex.MALE).grade(Grade.B)
                .eventDate(LocalDate.of(2026, 11, 6)).enabled(true).groupSize(8).build();
        when(eventRepository.findById(7L)).thenReturn(Optional.of(existing));

        EventDTO updated = service.updateEvent(7L, EventDTO.builder().grade("A").build());

        assertEquals("Senior Boys Sprint", updated.getName());
    }

    // ---------------------------------------------------------------- order

    @Test
    @DisplayName("the programme lists Boys 100M A, B, C and then Girls 100M A, B, C")
    void theProgrammeOrdersTheGradesNaturally() {
        List<Event> programme = new ArrayList<>(List.of(
                event(1L, Sex.FEMALE, Grade.B, Event.EventType.RUN_100M),
                event(2L, Sex.MALE, Grade.C, Event.EventType.RUN_100M),
                event(3L, Sex.FEMALE, Grade.A, Event.EventType.RUN_100M),
                event(4L, Sex.MALE, Grade.A, Event.EventType.RUN_100M),
                event(5L, Sex.FEMALE, Grade.C, Event.EventType.RUN_100M),
                event(6L, Sex.MALE, Grade.B, Event.EventType.RUN_100M),
                // A field event follows every track one.
                event(7L, Sex.MALE, Grade.A, Event.EventType.SHOT_PUT)));

        programme.sort(EventService.EVENT_ORDER);

        assertEquals(List.of("MALE-A", "MALE-B", "MALE-C", "FEMALE-A", "FEMALE-B", "FEMALE-C"),
                programme.subList(0, 6).stream()
                        .map(event -> event.getSex().name() + "-" + event.getGrade().name())
                        .toList(),
                "boys before girls, and the grades read A, B, C");
        assertEquals(Event.EventType.SHOT_PUT, programme.get(6).getType(),
                "徑項 track comes before 田項 field");
    }

    // -------------------------------------------------------------- helpers

    private List<Event> savedOfType(Event.EventType type) {
        return saved.stream().filter(event -> event.getType() == type).toList();
    }

    private Event event(Long id, Sex sex, Grade grade, Event.EventType type) {
        return Event.builder()
                .id(id)
                .name(EventService.defaultName(type, sex, grade))
                .type(type)
                .category(type.getCategory())
                .sex(sex)
                .grade(grade)
                .eventDate(LocalDate.of(2026, 11, 6))
                .enabled(true)
                .groupSize(type.getDefaultGroupSize())
                .build();
    }
}
