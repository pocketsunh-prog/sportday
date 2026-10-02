package com.sportday.service;

import com.sportday.dto.SeasonDTO;
import com.sportday.entity.Event;
import com.sportday.entity.Season;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.EventRepository;
import com.sportday.repository.SeasonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The school years.
 *
 * <p>Each sport day belongs to a year, and every event belongs to one of those
 * years. That is what lets the school open entries for this year while last year's
 * programme and results stay intact and can still be looked at and corrected.</p>
 *
 * <p>One year is <em>current</em>: the one students may enter. Normally the most
 * recent year, but the school can reopen an older one.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeasonService {

    private final SeasonRepository seasonRepository;
    private final EventRepository eventRepository;

    // -------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public List<SeasonDTO> list() {
        Season current = currentSeason();
        return seasonRepository.findAllByOrderByYearDesc().stream()
                .map(season -> SeasonDTO.from(season, eventCount(season.getId()),
                        current != null && current.getId().equals(season.getId())))
                .toList();
    }

    /** The year students may enter, or the most recent one, or null when there are none. */
    @Transactional(readOnly = true)
    public SeasonDTO current() {
        Season season = currentSeason();
        return season == null ? null : SeasonDTO.from(season, eventCount(season.getId()), true);
    }

    @Transactional(readOnly = true)
    public SeasonDTO byId(Long id) {
        Season season = require(id);
        Season current = currentSeason();
        return SeasonDTO.from(season, eventCount(season.getId()),
                current != null && current.getId().equals(season.getId()));
    }

    /** The entity, for callers that need to enforce enrolment. */
    @Transactional(readOnly = true)
    public Season currentSeason() {
        return seasonRepository.findFirstByEnrollmentOpenTrueOrderByYearDesc()
                .or(() -> seasonRepository.findFirstByOrderByYearDesc())
                .orElse(null);
    }

    // -------------------------------------------------------------- writing

    /** Creates a year, optionally copying another year's event catalogue into it. */
    @Transactional
    public SeasonDTO create(SeasonDTO payload) {
        int year = payload.getYear() == null ? LocalDate.now().getYear() : payload.getYear();
        if (seasonRepository.findByYear(year).isPresent()) {
            throw new IllegalArgumentException("There is already a sport day for " + year + ".");
        }
        Season season = Season.builder()
                .year(year)
                .name(payload.getName() == null || payload.getName().isBlank()
                        ? year + " Sports Day" : payload.getName().trim())
                .sportDayDate(payload.getSportDayDate())
                .enrollmentOpen(Boolean.TRUE.equals(payload.getEnrollmentOpen()))
                .notes(payload.getNotes())
                .build();
        Season saved = seasonRepository.save(season);

        int copied = 0;
        if (payload.getCopyEventsFromSeasonId() != null) {
            copied = copyEvents(payload.getCopyEventsFromSeasonId(), saved);
        }
        log.info("Created the {} sport day ({}), {} event(s) copied", saved.getYear(),
                saved.getDisplayName(), copied);
        return SeasonDTO.from(saved, eventCount(saved.getId()), false);
    }

    @Transactional
    public SeasonDTO update(Long id, SeasonDTO payload) {
        Season season = require(id);
        if (payload.getYear() != null && !payload.getYear().equals(season.getYear())) {
            seasonRepository.findByYear(payload.getYear()).ifPresent(other -> {
                throw new IllegalArgumentException(
                        "There is already a sport day for " + payload.getYear() + ".");
            });
            season.setYear(payload.getYear());
        }
        if (payload.getName() != null) season.setName(payload.getName());
        if (payload.getSportDayDate() != null) season.setSportDayDate(payload.getSportDayDate());
        if (payload.getEnrollmentOpen() != null) {
            season.setEnrollmentOpen(payload.getEnrollmentOpen());
            if (Boolean.TRUE.equals(payload.getEnrollmentOpen())) {
                // Only one year may be open: two would let a student enter events
                // for the wrong sport day. Reopening entries has the same effect as
                // activating the year, so both paths enforce it.
                closeOthers(season.getId());
            }
        }
        if (payload.getNotes() != null) season.setNotes(payload.getNotes());
        Season saved = seasonRepository.save(season);
        log.info("Updated the {} sport day: date {}, enrolment {}",
                saved.getYear(), saved.getSportDayDate(), saved.getEnrollmentOpen());
        Season current = currentSeason();
        return SeasonDTO.from(saved, eventCount(saved.getId()),
                current != null && current.getId().equals(saved.getId()));
    }

    /**
     * Makes this the year students may enter, closing the others — two open years
     * at once would let a student enter events for the wrong sport day.
     */
    @Transactional
    public SeasonDTO activate(Long id) {
        Season season = require(id);
        closeOthers(season.getId());
        season.setEnrollmentOpen(true);
        seasonRepository.save(season);
        log.info("Opened entries for the {} sport day; other years closed", season.getYear());
        return SeasonDTO.from(season, eventCount(season.getId()), true);
    }

    /** Closes every year except one, so exactly one can be open. */
    private void closeOthers(Long keepId) {
        for (Season other : seasonRepository.findAll()) {
            if (!other.getId().equals(keepId) && Boolean.TRUE.equals(other.getEnrollmentOpen())) {
                other.setEnrollmentOpen(false);
                seasonRepository.save(other);
            }
        }
    }

    /** Removes a year. Refused while it still has events, so nothing is orphaned. */
    @Transactional
    public void delete(Long id) {
        Season season = require(id);
        long events = eventCount(season.getId());
        if (events > 0) {
            throw new IllegalStateException("This year still has " + events
                    + " event(s). Delete or move them first.");
        }
        seasonRepository.delete(season);
        log.info("Deleted the {} sport day", season.getYear());
    }

    /** Copies another year's events into this one, keeping the types and divisions. */
    @Transactional
    public int copyEvents(Long fromSeasonId, Season target) {
        List<Event> source = eventRepository.findBySeasonIdOrderByTypeAscSexAsc(fromSeasonId);
        LocalDate on = target.getSportDayDate() != null
                ? target.getSportDayDate()
                : LocalDate.now();
        int copied = 0;
        for (Event original : source) {
            eventRepository.save(Event.builder()
                    .name(original.getName())
                    .description(original.getDescription())
                    .type(original.getType())
                    .category(original.getCategoryOrDefault())
                    .sex(original.getSex())
                    .eventDate(on)
                    .location(original.getLocation())
                    .maxParticipants(original.getMaxParticipants())
                    .groupSize(original.getGroupSize())
                    .enabled(original.getEnabled())
                    .season(target)
                    .build());
            copied++;
        }
        return copied;
    }

    /**
     * Adopts events that predate seasons — they belong to the year of their own
     * date. Called at startup so the year picker is never empty.
     */
    @Transactional
    public int adoptEventsWithoutSeason(Season fallback) {
        List<Event> orphans = eventRepository.findBySeasonIsNull();
        if (orphans.isEmpty()) {
            return 0;
        }
        int adopted = 0;
        for (Event event : orphans) {
            Season target = fallback;
            if (event.getEventDate() != null) {
                int year = event.getEventDate().getYear();
                target = seasonRepository.findByYear(year).orElseGet(() -> seasonRepository.save(
                        Season.builder()
                                .year(year)
                                .name(year + " Sports Day")
                                .sportDayDate(event.getEventDate())
                                .enrollmentOpen(false)
                                .build()));
            }
            if (target == null) {
                continue;
            }
            event.setSeason(target);
            eventRepository.save(event);
            adopted++;
        }
        log.info("Assigned {} event(s) from before seasons existed to a school year", adopted);
        return adopted;
    }

    // -------------------------------------------------------------- helpers

    /** The number of events in a year. */
    @Transactional(readOnly = true)
    public int eventCount(Long seasonId) {
        return seasonId == null ? 0 : (int) eventRepository.countBySeasonId(seasonId);
    }

    private Season require(Long id) {
        return seasonRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sport day year not found: " + id));
    }

    /** The open year's id, or null — used to default the event list to this year. */
    @Transactional(readOnly = true)
    public Long currentSeasonId() {
        Season season = currentSeason();
        return season == null ? null : season.getId();
    }

    @Transactional(readOnly = true)
    public Optional<Season> find(Long id) {
        return seasonRepository.findById(id);
    }
}
