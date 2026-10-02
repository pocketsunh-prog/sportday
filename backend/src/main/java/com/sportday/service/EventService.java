package com.sportday.service;

import com.sportday.dto.EventDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventGroup;
import com.sportday.entity.Sex;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final EventGroupRepository eventGroupRepository;
    private final EventResultRepository eventResultRepository;
    private final SettingsService settingsService;
    private final RecordService recordService;
    private final SeasonService seasonService;

    /**
     * The order a sports-day programme runs in: 徑項 before 田項, then the natural
     * event progression (60M, 100M, 200M, …), then boys before girls.
     *
     * <p>Applied in Java rather than left to {@code ORDER BY type}: the column is a
     * MySQL ENUM and MySQL sorts it by declaration order, but {@code RUN_60M} was
     * appended to the column when the revamp added it, so the database would place
     * 60M after 800M. Sorting on the Java enum ordinal keeps the intended order.</p>
     */
    public static final Comparator<Event> EVENT_ORDER = Comparator
            .comparingInt((Event event) -> event.getCategoryOrDefault().ordinal())
            .thenComparingInt(event -> event.getType() == null ? Integer.MAX_VALUE : event.getType().ordinal())
            .thenComparingInt(event -> event.getSex() == null ? Integer.MAX_VALUE : event.getSex().ordinal());

    public List<EventDTO> getAllEvents() {
        return eventRepository.findAll().stream()
                .sorted(EVENT_ORDER)
                .map(this::describe)
                .collect(Collectors.toList());
    }

    /**
     * The event list with any combination of filters applied. Every filter is
     * optional and they compose — asking for the enabled boys' field events
     * returns exactly those.
     */
    public List<EventDTO> searchEvents(boolean onlyEnabled, Sex sex, EventCategory category) {
        return searchEvents(onlyEnabled, sex, category, null);
    }

    /**
     * As above, plus a date. Passing a date shows just that day's programme, which
     * is what a multi-day sport meeting needs.
     */
    public List<EventDTO> searchEvents(boolean onlyEnabled, Sex sex, EventCategory category,
                                       java.time.LocalDate date) {
        return searchEvents(onlyEnabled, sex, category, date, null);
    }

    /**
     * As above, plus a school year. Passing a year shows one sport day's programme,
     * which is how a previous year is looked back at without this year's events
     * mixing in.
     */
    public List<EventDTO> searchEvents(boolean onlyEnabled, Sex sex, EventCategory category,
                                       java.time.LocalDate date, Long seasonId) {
        List<Event> base = onlyEnabled ? eventRepository.findByEnabledTrue() : eventRepository.findAll();
        return base.stream()
                .filter(event -> sex == null || event.getSex() == sex)
                .filter(event -> category == null || event.getCategoryOrDefault() == category)
                .filter(event -> date == null || date.equals(event.getEventDate()))
                .filter(event -> seasonId == null
                        || (event.getSeason() != null && seasonId.equals(event.getSeason().getId())))
                .sorted(EVENT_ORDER)
                .map(this::describe)
                .collect(Collectors.toList());
    }

    /**
     * The dates the programme runs on, with how many events fall on each — what the
     * date picker offers. Most recent first.
     */
    public List<Map<String, Object>> getEventDates() {
        return eventRepository.findAll().stream()
                .filter(event -> event.getEventDate() != null)
                .collect(Collectors.groupingBy(Event::getEventDate, java.util.TreeMap::new, Collectors.counting()))
                .descendingMap()
                .entrySet().stream()
                .map(entry -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("date", entry.getKey().toString());
                    row.put("eventCount", entry.getValue());
                    row.put("isToday", entry.getKey().equals(java.time.LocalDate.now()));
                    row.put("isPast", entry.getKey().isBefore(java.time.LocalDate.now()));
                    return row;
                })
                .collect(Collectors.toList());
    }

    public List<EventDTO> getEnabledEvents() {
        return eventRepository.findByEnabledTrue().stream()
                .map(this::describe)
                .collect(Collectors.toList());
    }

    /** Enabled events in one sex division — what a student sees on the entry page. */
    public List<EventDTO> getEnabledEventsForSex(Sex sex) {
        return eventRepository.findByEnabledTrueAndSex(sex).stream()
                .map(this::describe)
                .collect(Collectors.toList());
    }

    public List<EventDTO> getEnabledEventsByCategory(EventCategory category) {
        return eventRepository.findByEnabledTrueAndCategoryOrderByTypeAsc(category).stream()
                .map(this::describe)
                .collect(Collectors.toList());
    }

    public EventDTO getEventById(Long id) {
        return describe(requireEvent(id));
    }

    public Event requireEvent(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + id));
    }

    /**
     * Creates an event. New events are <strong>enabled by default</strong> — the
     * administrator opts out rather than opting in — and inherit their category,
     * group size and marking-sheet size from the event type.
     */
    @Transactional
    public EventDTO createEvent(EventDTO eventDTO) {
        if (eventDTO.getType() == null || eventDTO.getType().isBlank()) {
            throw new IllegalArgumentException("An event type is required.");
        }
        Event.EventType type = parseType(eventDTO.getType());

        Event event = Event.builder()
                .name(resolveName(eventDTO, type))
                .description(eventDTO.getDescription())
                .type(type)
                .category(type.getCategory())
                .sex(resolveSex(eventDTO.getSex()))
                .eventDate(eventDTO.getEventDate() != null ? eventDTO.getEventDate() : java.time.LocalDate.now())
                .location(eventDTO.getLocation())
                .maxParticipants(eventDTO.getMaxParticipants() != null
                        ? eventDTO.getMaxParticipants() : Event.DEFAULT_MAX_PARTICIPANTS)
                .groupSize(eventDTO.getGroupSize() != null && eventDTO.getGroupSize() > 0
                        ? eventDTO.getGroupSize() : type.getDefaultGroupSize())
                .enabled(eventDTO.getEnabled() == null || eventDTO.getEnabled())
                // A new event joins the year the school is working on, so it lands
                // in this year's programme rather than nowhere.
                .season(eventDTO.getSeasonId() != null
                        ? seasonService.find(eventDTO.getSeasonId()).orElse(null)
                        : seasonService.currentSeason())
                .build();

        Event saved = eventRepository.save(event);
        // Every event has a record from the start, one per grade.
        recordService.seedForEvent(saved);
        log.info("Created event '{}' ({} {} {}) enabled={} groupSize={}",
                saved.getName(), saved.getType(), saved.getSex(), saved.getCategory(),
                saved.getEnabled(), saved.getGroupSize());
        return describe(saved);
    }

    @Transactional
    public EventDTO updateEvent(Long id, EventDTO eventDTO) {
        Event event = requireEvent(id);

        if (eventDTO.getName() != null) event.setName(eventDTO.getName());
        if (eventDTO.getDescription() != null) event.setDescription(eventDTO.getDescription());
        if (eventDTO.getType() != null && !eventDTO.getType().isBlank()) {
            Event.EventType type = parseType(eventDTO.getType());
            event.setType(type);
            event.setCategory(type.getCategory());
            if (eventDTO.getGroupSize() == null) {
                event.setGroupSize(type.getDefaultGroupSize());
            }
        }
        if (eventDTO.getSex() != null && !eventDTO.getSex().isBlank()) {
            event.setSex(resolveSex(eventDTO.getSex()));
        }
        if (eventDTO.getEventDate() != null) event.setEventDate(eventDTO.getEventDate());
        if (eventDTO.getLocation() != null) event.setLocation(eventDTO.getLocation());
        if (eventDTO.getMaxParticipants() != null) event.setMaxParticipants(eventDTO.getMaxParticipants());
        if (eventDTO.getGroupSize() != null && eventDTO.getGroupSize() > 0) {
            event.setGroupSize(eventDTO.getGroupSize());
        }
        if (eventDTO.getEnabled() != null) event.setEnabled(eventDTO.getEnabled());
        if (eventDTO.getSeasonId() != null) {
            event.setSeason(seasonService.find(eventDTO.getSeasonId()).orElse(null));
        }

        event.applyTypeDefaults();
        return describe(eventRepository.save(event));
    }

    /**
     * Enables or disables an event. Disabling closes it to new entries but keeps
     * existing entries, groups and results intact.
     */
    @Transactional
    public EventDTO setEventEnabled(Long id, boolean enabled) {
        Event event = requireEvent(id);
        event.setEnabled(enabled);
        Event saved = eventRepository.save(event);
        log.info("Event {} ('{}') {}", id, saved.getName(), enabled ? "enabled" : "disabled");
        return describe(saved);
    }

    /**
     * Deletes an event and everything hanging off it. Entries and results are
     * removed first so the foreign keys stay valid.
     */
    @Transactional
    public void deleteEvent(Long id) {
        Event event = requireEvent(id);

        // A record is not owned by one event — it spans every edition of that type
        // and division — so let it go of this event's results and rebuild it, rather
        // than deleting a record an administrator may have typed in.
        recordService.detachForEvent(id);

        List<EventGroup> groups = eventGroupRepository.findByEventIdOrderByGroupNumberAsc(id);
        List<Enrollment> enrollments = enrollmentRepository.findByEventId(id);
        if (!enrollments.isEmpty()) {
            enrollmentRepository.deleteAll(enrollments);
            enrollmentRepository.flush();
        }
        if (!groups.isEmpty()) {
            eventGroupRepository.deleteAll(groups);
        }
        eventResultRepository.deleteByEventId(id);
        eventRepository.delete(event);
        recordService.recomputeAll();
        log.info("Deleted event {} ('{}') with {} entries and {} groups",
                id, event.getName(), enrollments.size(), groups.size());
    }

    /** Copies the fee-free default catalogue of sport-day events into the season. */
    @Transactional
    public int createDefaults(java.time.LocalDate eventDate, boolean includeField) {
        int created = 0;
        for (Event.EventType type : Event.EventType.values()) {
            if (type == Event.EventType.OTHER) {
                continue;
            }
            if (!includeField && type.getCategory() == EventCategory.FIELD) {
                continue;
            }
            for (Sex sex : Sex.values()) {
                if (eventRepository.findFirstByTypeAndSex(type, sex).isPresent()) {
                    continue;
                }
                Event saved = eventRepository.save(Event.builder()
                        .name(defaultName(type, sex))
                        .description(type.getCategory().getLabel() + " " + type.getDisplayName())
                        .type(type)
                        .category(type.getCategory())
                        .sex(sex)
                        .eventDate(eventDate)
                        .location("Main Sports Ground")
                        .maxParticipants(Event.DEFAULT_MAX_PARTICIPANTS)
                        .groupSize(type.getDefaultGroupSize())
                        .enabled(true)
                        .season(seasonService.currentSeason())
                        .build());
                // Every event has a record from the start, one per grade.
                recordService.seedForEvent(saved);
                created++;
            }
        }
        return created;
    }

    public static String defaultName(Event.EventType type, Sex sex) {
        return (sex == Sex.MALE ? "Boys " : "Girls ") + type.getDisplayName();
    }

    /**
     * Events that have already been held, most recent first. "Past" means the event
     * date is today or earlier, so on the day itself the events being run are
     * included — which is when staff most want to look back at them.
     */
    @Transactional(readOnly = true)
    public List<EventDTO> getPastEvents() {
        java.time.LocalDate today = java.time.LocalDate.now();
        return eventRepository.findAll().stream()
                .filter(event -> event.getEventDate() != null && !event.getEventDate().isAfter(today))
                .sorted(EVENT_ORDER.reversed())
                .map(this::describe)
                .toList();
    }

    private EventDTO describe(Event event) {
        long confirmed = enrollmentRepository.countByEventIdAndStatus(
                event.getId(), Enrollment.EnrollmentStatus.CONFIRMED);
        EventDTO dto = EventDTO.from(event, (int) confirmed);
        dto.setGroupCount(eventGroupRepository.countByEventId(event.getId()));
        dto.setUngroupedCount(enrollmentRepository.countUngroupedByEvent(
                event.getId(), Enrollment.EnrollmentStatus.CONFIRMED));
        // The entry limit is a setting, not a constant, so it is filled in here.
        dto.setMaxEntriesPerStudent(settingsService.maxEntriesFor(event.getCategoryOrDefault()));
        return dto;
    }

    private static String resolveName(EventDTO dto, Event.EventType type) {
        if (dto.getName() != null && !dto.getName().isBlank()) {
            return dto.getName();
        }
        return type.getDisplayName();
    }

    private static Sex resolveSex(String raw) {
        Sex sex = Sex.fromCode(raw);
        if (sex == null) {
            throw new IllegalArgumentException(
                    "A sex division is required: MALE (M) or FEMALE (F). Got: " + raw);
        }
        return sex;
    }

    private static Event.EventType parseType(String raw) {
        try {
            return Event.EventType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown event type: " + raw);
        }
    }
}
