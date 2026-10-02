package com.sportday.service;

import com.sportday.dto.SeasonDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Season;
import com.sportday.entity.Sex;
import com.sportday.repository.EventRepository;
import com.sportday.repository.SeasonRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * School years: one sport day each, with the year students may enter.
 *
 * <p>Requirement: the admin sets up this year's sport day for students to enter,
 * and can view and update past years.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeasonServiceTest {

    @Mock private SeasonRepository seasonRepository;
    @Mock private EventRepository eventRepository;

    @InjectMocks private SeasonService service;

    private Season season(Long id, int year, boolean open) {
        return Season.builder()
                .id(id)
                .year(year)
                .name(year + " Sports Day")
                .sportDayDate(LocalDate.of(year, 10, 1))
                .enrollmentOpen(open)
                .build();
    }

    @Test
    @DisplayName("a year can only exist once")
    void yearsAreUnique() {
        when(seasonRepository.findByYear(2026)).thenReturn(Optional.of(season(1L, 2026, false)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(SeasonDTO.builder().year(2026).build()));

        assertTrue(error.getMessage().contains("2026"), error.getMessage());
        verify(seasonRepository, never()).save(any());
    }

    @Test
    @DisplayName("a new year can copy another year's programme")
    void aNewYearCanCopyTheCatalogue() {
        when(seasonRepository.findByYear(2027)).thenReturn(Optional.empty());
        when(seasonRepository.save(any(Season.class))).thenAnswer(inv -> {
            Season saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(9L);
            return saved;
        });

        Event lastYear = Event.builder()
                .id(1L).name("Boys 100M").type(Event.EventType.RUN_100M)
                .category(EventCategory.TRACK).sex(Sex.MALE)
                .eventDate(LocalDate.of(2026, 10, 1)).enabled(true).groupSize(8).build();
        when(eventRepository.findBySeasonIdOrderByTypeAscSexAsc(4L)).thenReturn(List.of(lastYear));
        when(eventRepository.countBySeasonId(9L)).thenReturn(1L);

        service.create(SeasonDTO.builder()
                .year(2027)
                .sportDayDate(LocalDate.of(2027, 10, 7))
                .copyEventsFromSeasonId(4L)
                .build());

        ArgumentCaptor<Event> copied = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(copied.capture());
        Event copy = copied.getValue();
        assertEquals(Event.EventType.RUN_100M, copy.getType());
        assertEquals(Sex.MALE, copy.getSex());
        assertEquals(LocalDate.of(2027, 10, 7), copy.getEventDate(),
                "the copy is dated on the new year's sport day");
        assertNotNull(copy.getSeason(), "and belongs to the new year");
        assertEquals(2027, copy.getSeason().getYear());
    }

    @Test
    @DisplayName("opening a year closes the others, so nobody enters the wrong sport day")
    void activatingAYearClosesTheOthers() {
        Season past = season(1L, 2025, true);
        Season current = season(2L, 2026, false);
        when(seasonRepository.findById(2L)).thenReturn(Optional.of(current));
        when(seasonRepository.findAll()).thenReturn(List.of(past, current));

        service.activate(2L);

        assertTrue(current.getEnrollmentOpen(), "the chosen year is open");
        assertFalse(past.getEnrollmentOpen(), "the other year is closed");
        verify(seasonRepository, atLeastOnce()).save(past);
    }

    @Test
    @DisplayName("reopening a year's entries through an edit closes the others too")
    void editingEnrolmentOpenClosesTheOthers() {
        Season past = season(1L, 2025, true);
        Season current = season(2L, 2026, false);
        when(seasonRepository.findById(2L)).thenReturn(Optional.of(current));
        when(seasonRepository.findAll()).thenReturn(List.of(past, current));
        when(seasonRepository.findFirstByEnrollmentOpenTrueOrderByYearDesc())
                .thenReturn(Optional.of(current));
        when(seasonRepository.save(any(Season.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(2L, SeasonDTO.builder().enrollmentOpen(true).build());

        assertTrue(current.getEnrollmentOpen(), "the edited year is open");
        assertFalse(past.getEnrollmentOpen(),
                "the other year is closed — two open years would be ambiguous");
    }

    @Test
    @DisplayName("a year that still has events cannot be deleted")
    void aYearWithEventsCannotBeDeleted() {
        Season current = season(2L, 2026, true);
        when(seasonRepository.findById(2L)).thenReturn(Optional.of(current));
        when(eventRepository.countBySeasonId(2L)).thenReturn(37L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.delete(2L));

        assertTrue(error.getMessage().contains("37"), error.getMessage());
        verify(seasonRepository, never()).delete(any());
    }

    @Test
    @DisplayName("an empty year can be deleted")
    void anEmptyYearCanBeDeleted() {
        Season stale = season(3L, 2024, false);
        when(seasonRepository.findById(3L)).thenReturn(Optional.of(stale));
        when(eventRepository.countBySeasonId(3L)).thenReturn(0L);

        service.delete(3L);

        verify(seasonRepository).delete(stale);
    }

    @Test
    @DisplayName("the current year is the open one, or the most recent")
    void theCurrentYearIsTheOpenOne() {
        Season past = season(1L, 2025, false);
        Season open = season(2L, 2026, true);
        when(seasonRepository.findFirstByEnrollmentOpenTrueOrderByYearDesc())
                .thenReturn(Optional.of(open));

        assertEquals(2026, service.currentSeason().getYear());

        // Nothing open: fall back to the most recent year.
        when(seasonRepository.findFirstByEnrollmentOpenTrueOrderByYearDesc()).thenReturn(Optional.empty());
        when(seasonRepository.findFirstByOrderByYearDesc()).thenReturn(Optional.of(past));
        assertEquals(2025, service.currentSeason().getYear());
    }

    @Test
    @DisplayName("events from before years existed join the year of their own date")
    void legacyEventsJoinTheirOwnYear() {
        Event legacy = Event.builder()
                .id(7L).name("Boys 60M").type(Event.EventType.RUN_60M)
                .category(EventCategory.TRACK).sex(Sex.MALE)
                .eventDate(LocalDate.of(2024, 11, 8)).enabled(true).groupSize(8).build();
        when(eventRepository.findBySeasonIsNull()).thenReturn(List.of(legacy));
        when(seasonRepository.findByYear(2024)).thenReturn(Optional.empty());
        when(seasonRepository.save(any(Season.class))).thenAnswer(inv -> {
            Season saved = inv.getArgument(0);
            saved.setId(5L);
            return saved;
        });

        int adopted = service.adoptEventsWithoutSeason(season(2L, 2026, true));

        assertEquals(1, adopted);
        assertEquals(2024, legacy.getSeason().getYear(),
                "an event belongs to the year it was held, not to today");
        verify(eventRepository).save(legacy);
    }

    @Test
    @DisplayName("a year with no events is still listed, so a new season can be set up")
    void emptyYearsAreListed() {
        Season fresh = season(4L, 2027, false);
        when(seasonRepository.findAllByOrderByYearDesc()).thenReturn(List.of(fresh));
        when(eventRepository.countBySeasonId(4L)).thenReturn(0L);

        List<SeasonDTO> seasons = service.list();

        assertEquals(1, seasons.size());
        assertEquals(2027, seasons.get(0).getYear());
        assertEquals(0, seasons.get(0).getEventCount());
        assertEquals("2027 Sports Day", seasons.get(0).getDisplayName());
    }
}
