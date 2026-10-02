package com.sportday.config;

import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventStage;
import com.sportday.entity.Season;
import com.sportday.entity.Sex;
import com.sportday.entity.User;
import com.sportday.dto.SeasonDTO;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.StudentRepository;
import com.sportday.repository.UserRepository;
import com.sportday.service.EventService;
import com.sportday.service.SeasonService;
import com.sportday.service.SettingsService;
import com.sportday.service.StudentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;

/**
 * First-run bootstrap: the administrator account, the standard event catalogue
 * and (optionally) a set of sample students.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    private final EventRepository eventRepository;

    @Bean
    public CommandLineRunner initData(UserRepository userRepository,
                                      PasswordEncoder passwordEncoder,
                                      EventService eventService,
                                      StudentService studentService,
                                      StudentRepository studentRepository,
                                      EventGroupRepository eventGroupRepository,
                                      EventResultRepository eventResultRepository,
                                      SettingsService settingsService,
                                      SeasonService seasonService,
                                      @Value("${app.events.seed-defaults:true}") boolean seedDefaults,
                                      @Value("${app.events.seed-event-date:}") String seedEventDate,
                                      @Value("${app.students.seed-sample-count:0}") int seedSampleCount) {
        return args -> {
            if (!userRepository.existsByUsername("admin")) {
                User admin = User.builder()
                        .username("admin")
                        .password(passwordEncoder.encode("admin123"))
                        .email("admin@sportday.com")
                        .fullName("System Administrator")
                        .role(User.Role.ADMIN)
                        .enabled(true)
                        .build();
                userRepository.save(admin);
                log.info("Created default administrator: admin / admin123");
            }

            // The rules row is created on first read anyway; doing it here means the
            // marking sheets only ever read it, and an administrator always has a row
            // to edit.
            settingsService.get();

            backfillLegacyEvents();
            backfillStages(eventGroupRepository, eventResultRepository);
            normaliseUnits(eventResultRepository);
            backfillDirectToFinal(eventRepository, eventGroupRepository);

            LocalDate seedDate = seedEventDate == null || seedEventDate.isBlank()
                    ? LocalDate.now() : LocalDate.parse(seedEventDate.trim());
            Season season = ensureSeason(seasonService, seedDate);

            if (seedDefaults && eventRepository.count() == 0) {
                int created = eventService.createDefaults(seedDate, true);
                log.info("Seeded {} default events for {} (all enabled)", created, seedDate);
            }

            // Events from before school years existed belong to the year of their own
            // date, so the year picker is never empty.
            seasonService.adoptEventsWithoutSeason(season);

            if (seedSampleCount > 0 && studentRepository.count() == 0) {
                var result = studentService.seedSampleData(seedSampleCount, null);
                log.info("Seeded {} sample students ({} created)", seedSampleCount, result.getCreated());
            }
        };
    }

    /**
     * Makes sure there is a school year to work on. On a fresh database this creates
     * this year's sport day with entries open, so students can enter straight away;
     * on an existing one it leaves whatever the school has already set up.
     */
    private Season ensureSeason(SeasonService seasonService, LocalDate seedDate) {
        Season existing = seasonService.currentSeason();
        if (existing != null) {
            return existing;
        }
        int year = seedDate.getYear();
        try {
            SeasonDTO created = seasonService.create(SeasonDTO.builder()
                    .year(year)
                    .name(year + " Sports Day")
                    .sportDayDate(seedDate)
                    .enrollmentOpen(true)
                    .build());
            log.info("Created the {} sport day with entries open", year);
            return seasonService.find(created.getId()).orElse(null);
        } catch (IllegalArgumentException ex) {
            // Already there — nothing to do.
            return seasonService.currentSeason();
        }
    }

    /**
     * Decides, once, whether each event runs straight to a final.
     *
     * <p>A new event does. An event that already exists keeps whatever it was
     * doing: a 60/100/200/400 that has heats drawn was clearly being run as heats
     * and a final, so it stays that way rather than losing a final the school has
     * already set up. Everything else — including a distance event split into
     * several sheets — is decided by its own run.</p>
     */
    private void backfillDirectToFinal(EventRepository eventRepository,
                                       EventGroupRepository groupRepository) {
        int changed = 0;
        for (Event event : eventRepository.findAll()) {
            if (event.getDirectToFinal() != null) {
                continue;
            }
            boolean alreadySplit = event.mayHaveFinal()
                    && groupRepository.countByEventId(event.getId()) > 0;
            event.setDirectToFinal(!alreadySplit);
            eventRepository.save(event);
            changed++;
        }
        if (changed > 0) {
            log.info("Decided the final format for {} event(s): the ones already split keep their "
                    + "heats and final, the rest run straight to a final", changed);
        }
    }

    /**
     * Marks carry the unit they were recorded in. That used to be the spelled-out
     * "metres" and "seconds"; a programme writes {@code M} and {@code s}, so what
     * is already stored is rewritten once and any later drift (a helper typing
     * "metres" by hand) is cleaned up on the next start.
     */
    private void normaliseUnits(EventResultRepository resultRepository) {
        int field = resultRepository.normaliseUnits(EventCategory.FIELD,
                java.util.List.of("metres", "metre", "m"), Event.EventType.UNIT_FIELD);
        int track = resultRepository.normaliseUnits(EventCategory.TRACK,
                java.util.List.of("seconds", "second", "sec", "s"), Event.EventType.UNIT_TRACK);
        if (field > 0 || track > 0) {
            log.info("Shortened the unit on {} field and {} track mark(s) to M and s", field, track);
        }
    }

    /**
     * Groups and marks that predate the heat/final split have no stage. They are
     * all heats — the final did not exist yet — so claiming them keeps the heat
     * listings and the heat leaderboard working even if the SQL migration was
     * never run.
     */
    private void backfillStages(EventGroupRepository groupRepository,
                                EventResultRepository resultRepository) {
        int groups = groupRepository.backfillNullStages(EventStage.HEAT);
        int results = resultRepository.backfillNullStages(EventStage.HEAT);
        if (groups > 0 || results > 0) {
            log.info("Adopted {} group(s) and {} result(s) from before the heat/final split as heats",
                    groups, results);
        }
    }

    /**
     * Events created before the revamp have no category, division or group size.
     * Fill them in from the event type so old rows behave like new ones.
     */
    private void backfillLegacyEvents() {
        int repaired = 0;
        for (Event event : eventRepository.findAll()) {
            boolean changed = false;
            if (event.getType() == null) {
                event.setType(Event.EventType.OTHER);
                changed = true;
            }
            if (event.getCategory() == null) {
                event.setCategory(event.getType().getCategory());
                changed = true;
            }
            if (event.getSex() == null) {
                // Legacy events predate the boys/girls split; default to the
                // boys' division and let the administrator re-assign them.
                event.setSex(Sex.MALE);
                changed = true;
            }
            if (event.getGroupSize() == null || event.getGroupSize() <= 0) {
                event.setGroupSize(event.getType().getDefaultGroupSize());
                changed = true;
            }
            if (event.getEnabled() == null) {
                event.setEnabled(true);
                changed = true;
            }
            if (event.getMaxParticipants() == null) {
                event.setMaxParticipants(Event.DEFAULT_MAX_PARTICIPANTS);
                changed = true;
            }
            if (changed) {
                eventRepository.save(event);
                repaired++;
            }
        }
        if (repaired > 0) {
            log.info("Repaired {} event record(s) carried over from an earlier schema", repaired);
        }
    }
}
