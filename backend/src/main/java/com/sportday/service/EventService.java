package com.sportday.service;

import com.sportday.dto.EventDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventGroup;
import com.sportday.entity.Grade;
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
    private final FinalQualificationService finalQualificationService;

    /**
     * Brings every event's format back in step with how many are entered.
     *
     * <p>Used after a season reset clears the entries: with nobody entered, no sprint
     * should still be claiming heats and a final, or the programme would advertise a
     * final for an empty field until somebody entered it again.</p>
     *
     * @return how many events changed
     */
    @Transactional
    public int reapplyFinalFormat() {
        int changed = 0;
        for (Event event : eventRepository.findAll()) {
            if (finalQualificationService.syncFinalFormat(event)) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * The order a sports-day programme runs in: 徑項 before 田項, then the natural
     * event progression (60M, 100M, 200M, …), then boys before girls, then the
     * grades A, B, C — so the programme lists Boys 100M A, B, C and then
     * Girls 100M A, B, C, which is how the school reads a programme.
     *
     * <p>Applied in Java rather than left to {@code ORDER BY type}: the column is a
     * MySQL ENUM and MySQL sorts it by declaration order, but {@code RUN_60M} was
     * appended to the column when the revamp added it, so the database would place
     * 60M after 800M. Sorting on the Java enum ordinal keeps the intended order.</p>
     */
    public static final Comparator<Event> EVENT_ORDER = Comparator
            .comparingInt((Event event) -> event.getCategoryOrDefault().ordinal())
            .thenComparingInt(event -> event.getType() == null ? Integer.MAX_VALUE : event.getType().ordinal())
            .thenComparingInt(event -> event.getSex() == null ? Integer.MAX_VALUE : event.getSex().ordinal())
            .thenComparingInt(event -> event.getGrade() == null ? Integer.MAX_VALUE : event.getGrade().ordinal());

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
     *
     * <p>A grade is required, and it must be one the type is run by: an event
     * without a grade would put A, B and C athletes into one ranking, and a
     * C-grade 5000M is a race that grade does not run.</p>
     */
    @Transactional
    public EventDTO createEvent(EventDTO eventDTO) {
        if (eventDTO.getType() == null || eventDTO.getType().isBlank()) {
            throw new IllegalArgumentException("An event type is required.");
        }
        Event.EventType type = parseType(eventDTO.getType());
        Sex sex = resolveSex(eventDTO.getSex());
        Grade grade = requireGradeFor(type, parseGrade(eventDTO.getGrade()));

        Event event = Event.builder()
                .name(resolveName(eventDTO, type, sex, grade))
                .description(eventDTO.getDescription())
                .type(type)
                .category(type.getCategory())
                .sex(sex)
                .grade(grade)
                .eventDate(eventDTO.getEventDate() != null ? eventDTO.getEventDate() : java.time.LocalDate.now())
                .location(eventDTO.getLocation())
                .maxParticipants(eventDTO.getMaxParticipants() != null
                        ? eventDTO.getMaxParticipants() : Event.DEFAULT_MAX_PARTICIPANTS)
                .groupSize(eventDTO.getGroupSize() != null && eventDTO.getGroupSize() > 0
                        ? eventDTO.getGroupSize() : type.getDefaultGroupSize())
                .enabled(eventDTO.getEnabled() == null || eventDTO.getEnabled())
                // Direct to a final unless the school asks otherwise, and only an
                // event that may have a final can be asked to.
                .directToFinal(!requestedFinal(eventDTO, type))
                // Settling for a final by default is the system's doing, not the
                // school's, so a sprint opens its final by itself once more than a
                // group's worth have entered. Asking for one, or for none, is a
                // decision and is left alone.
                .directToFinalAuto(eventDTO.getDirectToFinal() == null && type.isShortSprint())
                // A new event joins the year the school is working on, so it lands
                // in this year's programme rather than nowhere.
                .season(eventDTO.getSeasonId() != null
                        ? seasonService.find(eventDTO.getSeasonId()).orElse(null)
                        : seasonService.currentSeason())
                .build();

        Event saved = eventRepository.save(event);
        // Every event has a record from the start — the event is one grade now, so
        // that is one record row.
        recordService.seedForEvent(saved);
        log.info("Created event '{}' ({} {} {} {}) enabled={} groupSize={}",
                saved.getName(), saved.getType(), saved.getSex(), saved.getGrade(), saved.getCategory(),
                saved.getEnabled(), saved.getGroupSize());
        return describe(saved);
    }

    /**
     * Updates an event.
     *
     * <p>The event must still have a grade afterwards, and the grade must be one the
     * type it ends up with is run by — changing a 100M into a 5000M on a C-grade
     * event is refused, because that grade does not run the 5000M. The check is on
     * the combination the event will have when the update is applied, so a request
     * that changes the type and the grade together is judged on both.</p>
     */
    @Transactional
    public EventDTO updateEvent(Long id, EventDTO eventDTO) {
        Event event = requireEvent(id);

        // Kept before anything is overwritten: the name is rewritten below only when
        // it was still the default one, so a school's own title is never clobbered.
        String previousDefaultName = defaultName(event.getType(), event.getSex(), event.getGrade());

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
        if (eventDTO.getGrade() != null && !eventDTO.getGrade().isBlank()) {
            event.setGrade(parseGrade(eventDTO.getGrade()));
        }
        // The event must still carry a grade, and one the type it ends up with is run
        // by: a 5000M on a C-grade event is refused however it was reached.
        requireGradeFor(event.getType(), event.getGrade());
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
        if (eventDTO.getDirectToFinal() != null) {
            // Changing the type and the final flag together must be judged on the
            // type the event will end up with, which is set above.
            event.setDirectToFinal(!requestedFinal(eventDTO, event.getType()));
            // The school has spoken, so the automatic switch must not undo it.
            event.setDirectToFinalAuto(false);
        }
        // Moving an event to another type or grade renames it only while it still
        // carries its own default name; a title the school chose is left alone.
        if (previousDefaultName != null && previousDefaultName.equals(event.getName())) {
            event.setName(defaultName(event.getType(), event.getSex(), event.getGrade()));
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

    /**
     * Creates the standard catalogue of sport-day events for the season: every
     * event type, in both divisions, for each grade that runs it.
     *
     * <p>An event belongs to exactly one grade, so one type and division yields two
     * or three events — {@code Boys 100M · A Grade}, {@code Boys 100M · B Grade}
     * and {@code Boys 100M · C Grade} — rather than one grade-mixed race. Whether a
     * grade runs an event is decided by {@link Event.EventType#allowedGrades()}: the
     * 1500M and the 110M hurdles have no C grade, and only the A grade runs the
     * 5000M.</p>
     *
     * <p>Idempotent: an event that already exists for a type, division and grade is
     * left alone, so running this again only fills in what is missing.</p>
     */
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
                for (Grade grade : java.util.EnumSet.allOf(Grade.class)) {
                    if (!type.runsGrade(grade)) {
                        // This grade does not run this event, so it has no event at all.
                        continue;
                    }
                    if (eventRepository.findFirstByTypeAndSexAndGrade(type, sex, grade).isPresent()) {
                        continue;
                    }
                    Event saved = eventRepository.save(Event.builder()
                            .name(defaultName(type, sex, grade))
                            .description(type.getCategory().getLabel() + " " + type.getDisplayName())
                            .type(type)
                            .category(type.getCategory())
                            .sex(sex)
                            .grade(grade)
                            .eventDate(eventDate)
                            .location("Main Sports Ground")
                            .maxParticipants(Event.DEFAULT_MAX_PARTICIPANTS)
                            .groupSize(type.getDefaultGroupSize())
                            .enabled(true)
                            // Every seeded event starts direct to a final, which is what
                            // most of a school day is. A sprint is marked as the system's
                            // own doing, so once more than a group's worth enter, the
                            // final opens by itself — the entry count decides.
                            .directToFinal(true)
                            .directToFinalAuto(type.isShortSprint())
                            .season(seasonService.currentSeason())
                            .build());
                    // Every event has a record from the start, and the event is one
                    // grade, so that is one record row.
                    recordService.seedForEvent(saved);
                    created++;
                }
            }
        }
        return created;
    }

    /**
     * The name an event is given when the school does not supply one: the division,
     * the type and the grade, e.g. {@code Boys 100M · A Grade}.
     *
     * <p>The grade is <em>stored in the name</em> rather than rendered beside it.
     * Every consumer that shows an event — the marking-sheet heading, the results
     * PDF, the entry list, the mark-entry picker — already prints
     * {@code event.getName()}, so storing the suffixed name means all of them show
     * the grade with no further changes, and there is one place to read a name from
     * rather than two that can disagree. The structured {@code grade} field is kept
     * as well, so a client can still filter or style on it.</p>
     */
    public static String defaultName(Event.EventType type, Sex sex, Grade grade) {
        if (type == null || sex == null || grade == null) {
            return null;
        }
        return (sex == Sex.MALE ? "Boys " : "Girls ") + type.getDisplayName() + " · " + grade.getLabel();
    }

    /**
     * Whether the request asks for heats and a final.
     *
     * <p>A new event is direct to a final unless asked otherwise, and only
     * 60M/100M/200M/400M can be asked — everything else, including every field
     * event, is decided by its own run. Asking for a final on one of those is
     * refused rather than quietly ignored, so the school knows why.</p>
     */
    private static boolean requestedFinal(EventDTO eventDTO, Event.EventType type) {
        if (eventDTO.getDirectToFinal() == null || Boolean.TRUE.equals(eventDTO.getDirectToFinal())) {
            return false;
        }
        if (type == null || !type.isShortSprint()) {
            throw new IllegalArgumentException(
                    (type == null ? "This event" : type.getDisplayName())
                            + " is run straight to a final — only 60M, 100M, 200M and 400M can be "
                            + "split into heats and a final.");
        }
        return true;
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
        // The entry limit is assigned rather than a constant, so it is filled in here.
        dto.setMaxEntriesPerStudent(settingsService.maxEntriesFor(event.getCategoryOrDefault()));
        // The event's own grade. Which grades may enter used to be a rule looked up
        // per event type; now the event simply is one grade.
        dto.setGrade(event.getGrade() == null ? null : event.getGrade().name());
        dto.setGradeLabel(event.getGrade() == null ? null : event.getGrade().getLabel());
        return dto;
    }

    private static String resolveName(EventDTO dto, Event.EventType type, Sex sex, Grade grade) {
        if (dto.getName() != null && !dto.getName().isBlank()) {
            return dto.getName();
        }
        // No name given: the programme's own, which carries the grade.
        return defaultName(type, sex, grade);
    }

    private static Sex resolveSex(String raw) {
        Sex sex = Sex.fromCode(raw);
        if (sex == null) {
            throw new IllegalArgumentException(
                    "A sex division is required: MALE (M) or FEMALE (F). Got: " + raw);
        }
        return sex;
    }

    /**
     * Reads a grade from a request. {@code A}, {@code B} and {@code C} — or the
     * labels the UI shows, {@code A Grade} and so on — are the only accepted values.
     *
     * @throws IllegalArgumentException when a value was sent that is not a grade
     */
    private static Grade parseGrade(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        for (Grade grade : Grade.values()) {
            if (value.equals(grade.name()) || value.equals(grade.getLabel().toUpperCase())) {
                return grade;
            }
        }
        throw new IllegalArgumentException("Unknown grade: " + raw + " — use A, B or C.");
    }

    /**
     * An event must have a grade, and it must be one the event's type is run by.
     *
     * <p>The grade is what keeps one grade from being ranked against another, so an
     * event without one is meaningless; and the catalogue does not offer every grade
     * every race, so a C-grade 5000M is refused here rather than created and left
     * empty.</p>
     *
     * @return the grade, so a caller can use it directly
     * @throws IllegalArgumentException when the grade is missing or the type does not run it
     */
    private static Grade requireGradeFor(Event.EventType type, Grade grade) {
        if (grade == null) {
            throw new IllegalArgumentException("A grade is required: an event is run by exactly "
                    + "one grade — A, B or C.");
        }
        if (type != null && !type.runsGrade(grade)) {
            throw new IllegalArgumentException(type.getDisplayName() + " is not run by the "
                    + grade.getLabel() + " — it is open to " + gradeList(type.allowedGrades()) + ".");
        }
        return grade;
    }

    /** "A Grade", "A Grade and B Grade" — how the refusal above names the grades. */
    private static String gradeList(java.util.Collection<Grade> grades) {
        return grades.stream()
                .map(Grade::getLabel)
                .collect(Collectors.joining(" and "));
    }

    private static Event.EventType parseType(String raw) {
        try {
            return Event.EventType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown event type: " + raw);
        }
    }
}
