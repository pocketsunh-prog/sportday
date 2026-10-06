package com.sportday.service;

import com.sportday.dto.DraftRelayTeamRequest;
import com.sportday.dto.EventDTO;
import com.sportday.dto.RelayTeamCreateRequest;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventGroup;
import com.sportday.entity.Grade;
import com.sportday.entity.RelayTeamKind;
import com.sportday.entity.Sex;
import com.sportday.entity.Student;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final RelayTeamService relayTeamService;
    /**
     * The one readiness rule, asked on the <em>showing</em> side: the event list stamps
     * every relay with whether it may be marked yet, so a picker can leave a half-built
     * relay out instead of offering it and having the choice refused. It reads nothing
     * but the relay tables, so there is no cycle — {@code RelayReadiness} does not know
     * this service exists.
     */
    private final RelayReadiness relayReadiness;

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
        return describeAll(eventRepository.findAll().stream()
                .sorted(EVENT_ORDER)
                .toList());
    }

    /**
     * The event list with any combination of filters applied. Every filter is
     * optional and they compose — asking for the enabled boys' field events
     * returns exactly those.
     *
     * <p><strong>A draft is never on this list.</strong> It is a relay event made to
     * hold teams the school is still building, not a race the school has entered, so
     * it must not appear in the programme, offer entry, or be countable as part of a
     * sport day. The one list it does belong on is an administrator's
     * {@link #getDraftEvents()}.</p>
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
        return describeAll(base.stream()
                .filter(event -> !event.isDraft())
                .filter(event -> sex == null || event.getSex() == sex)
                .filter(event -> category == null || event.getCategoryOrDefault() == category)
                .filter(event -> date == null || date.equals(event.getEventDate()))
                .filter(event -> seasonId == null
                        || (event.getSeason() != null && seasonId.equals(event.getSeason().getId())))
                .sorted(EVENT_ORDER)
                .toList());
    }

    /**
     * The dates the programme runs on, with how many events fall on each — what the
     * date picker offers. Most recent first.
     *
     * <p>A draft is not on the programme, so it does not put a date on the picker and
     * is not counted on one. A date whose only events are drafts therefore has no row
     * at all, rather than a row reading "0 events".</p>
     */
    public List<Map<String, Object>> getEventDates() {
        return eventRepository.findAll().stream()
                .filter(event -> !event.isDraft())
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
     *
     * <p>A relay may also be given a <strong>form</strong> — {@code 1} for a "Form 1
     * 4x100M" whose teams are {@code 1A}, {@code 1B}, {@code 1C} and {@code 1D} drawn
     * from every class of Form 1 <em>whatever grade its students are in</em>. Only a
     * {@code FORM} relay may be scoped to a form: a sprint or a house relay that asked
     * for one is refused, because nothing would be divided by it. Leaving it out is
     * normal — an event with no form is scoped by its grade, exactly as every event
     * was before the form existed, so the live relays are untouched.</p>
     */
    @Transactional
    public EventDTO createEvent(EventDTO eventDTO) {
        if (eventDTO.getType() == null || eventDTO.getType().isBlank()) {
            throw new IllegalArgumentException("An event type is required.");
        }
        Event.EventType type = parseType(eventDTO.getType());
        Sex sex = resolveSex(eventDTO.getSex());
        Grade grade = requireGradeFor(type, parseGrade(eventDTO.getGrade()));
        // FORM or HOUSE — only a relay may be divided into teams, and leaving it out
        // is normal: an undivided relay is how the relay events already behave.
        RelayTeamKind relayTeamKind = parseRelayTeamKind(eventDTO.getRelayTeamKind());
        requireRelayKindAllowed(type, relayTeamKind);
        // The form scope, if one was sent. A create has nothing to leave alone, so a
        // blank here is simply no scope at all.
        String form = requireFormScopeAllowed(type, relayTeamKind, parseForm(eventDTO.getForm()));

        Event saved = eventRepository.save(buildEvent(eventDTO, type, sex, grade, relayTeamKind, form));
        // Every event has a record from the start — the event is one grade now, so
        // that is one record row.
        recordService.seedForEvent(saved);
        log.info("Created event '{}' ({} {} {} {}) enabled={} groupSize={}",
                saved.getName(), saved.getType(), saved.getSex(), saved.getGrade(), saved.getCategory(),
                saved.getEnabled(), saved.getGroupSize());
        return describe(saved);
    }

    /**
     * Creates a <strong>draft</strong> relay event — the event a school builds its
     * relay teams on before the race itself exists.
     *
     * <h2>Why a draft event at all</h2>
     * <p>The school's requirement: "create relay event base on selected relay team".
     * The teams come first, and a team cannot exist without an event:
     * {@code RelayTeam.event} is a non-null foreign key, and both the marking sheet
     * and the mark grid reach a team <em>through</em> its event, so a team with no
     * event could hold no mark and print no sheet. A draft is the honest way to give
     * the selections somewhere to live.</p>
     *
     * <h2>What it refuses</h2>
     * <ul>
     *   <li>anything that is not a relay: a draft only ever exists to hold relay
     *       teams, and the two relays the programme runs are the 4x100M and the
     *       4x400M;</li>
     *   <li>a draft without a kind — the school must say whether the teams are one
     *       per class or one per house, because that is what the board and the
     *       runners' own eligibility are judged against;</li>
     *   <li>a grade the type is not run by, exactly as an ordinary create does.</li>
     * </ul>
     *
     * <h2>The teams may be given with it</h2>
     * <p>{@code teams} is the students the school has already chosen, grouped and
     * named. Every one of them is created through
     * {@link RelayTeamService#createTeam} — the very call a team made by hand on its
     * own goes through — so the name rule, the four-plus-one cap, the division and
     * grade, one leg per athlete and the class rule a teacher is held to are all the
     * same rules, with no second copy. Every team is judged before any is written, so
     * a request naming an ineligible student in its last team leaves nothing
     * behind.</p>
     *
     * <p>A draft is <strong>not</strong> on the programme: it is excluded from every
     * listing and count the school reads (see {@link #searchEvents} and
     * {@link #getDraftEvents}).</p>
     */
    @Transactional
    public EventDTO createDraftEvent(DraftRelayTeamRequest request) {
        if (request == null || request.getType() == null || request.getType().isBlank()) {
            throw new IllegalArgumentException("An event type is required. A draft relay event is a "
                    + "4x100M or a 4x400M, so send one of those as the type.");
        }
        Event.EventType type = parseType(request.getType());
        if (!type.isRelay()) {
            throw new IllegalArgumentException(type.getDisplayName() + " is not a relay event, so a "
                    + "draft of it could hold no relay teams. A draft relay event is a 4x100M or a "
                    + "4x400M.");
        }
        RelayTeamKind relayTeamKind = parseRelayTeamKind(request.getRelayTeamKind());
        if (relayTeamKind == null) {
            throw new IllegalArgumentException("A draft relay event needs its kind: FORM for one team "
                    + "per class, or HOUSE for one per house. The teams cannot be judged against the "
                    + "register without it.");
        }
        Sex sex = resolveSex(request.getSex());
        Grade grade = requireGradeFor(type, parseGrade(request.getGrade()));
        // A draft may be scoped to a form too — a "Form 1 4x100M" built by hand before
        // the race exists — and only on a FORM draft: a house draft is divided by grade
        // and house, so a form on it is refused rather than stored and ignored.
        String form = requireFormScopeAllowed(type, relayTeamKind, parseForm(request.getForm()));

        Event event = buildEvent(draftFields(request, type), type, sex, grade, relayTeamKind, form);
        event.setDraft(true);
        Event saved = eventRepository.save(event);
        // The draft's record row is created too, so the race has its record the moment
        // its teams are moved onto a real event — a record is per type, division and
        // grade, and is kept in step by RecordService either way.
        recordService.seedForEvent(saved);
        log.info("Created DRAFT relay event {} ('{}', {} {} {} {})", saved.getId(), saved.getName(),
                saved.getType(), saved.getSex(), saved.getGrade(), relayTeamKind);

        List<RelayTeamCreateRequest> teams = request.getTeams() == null
                ? List.of() : request.getTeams();
        // Every team is judged before any is written: the name a draft may not hand
        // out twice is checked here, and createTeam does the rest of the rule book.
        // Nothing is written unless all of them pass, so a refused request leaves no
        // half-built draft behind for the rollback to tidy up.
        Set<String> names = new LinkedHashSet<>();
        for (RelayTeamCreateRequest team : teams) {
            if (team == null) {
                continue;
            }
            String name = team.getName() == null ? "" : team.getName().trim();
            if (!name.isEmpty() && !names.add(name.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("\"" + name + "\" is named twice among the teams "
                        + "of this draft relay event. Two teams of one race cannot share a name — a "
                        + "marking sheet lists them by it.");
            }
        }
        int created = 0;
        for (RelayTeamCreateRequest team : teams) {
            if (team == null) {
                continue;
            }
            relayTeamService.createTeam(saved.getId(), team);
            created++;
        }
        log.info("Draft relay event {} holds {} team(s)", saved.getId(), created);

        EventDTO dto = describe(saved);
        dto.setTeamCount(created);
        return dto;
    }

    /**
     * The administrator's list of the drafts — the relay events whose teams are still
     * being collected, which the programme deliberately does not show.
     *
     * <p>This is the one listing a draft belongs in. Without it a draft would be
     * invisible the moment its board was navigated away from, and there would be no
     * way to find the one being filled, or the one whose teams are ready to move.
     * In programme order, like the programme itself.</p>
     */
    @Transactional(readOnly = true)
    public List<EventDTO> getDraftEvents() {
        return describeAll(eventRepository.findAll().stream()
                .filter(Event::isDraft)
                .sorted(EVENT_ORDER)
                .toList());
    }

    /**
     * The fields a draft shares with an ordinary event, from the draft's own request.
     * The season follows the ordinary create: the year the school is working on.
     */
    private static EventDTO draftFields(DraftRelayTeamRequest request, Event.EventType type) {
        return EventDTO.builder()
                .name(request.getName())
                .description(request.getDescription())
                .eventDate(request.getEventDate())
                .location(request.getLocation())
                .maxParticipants(request.getMaxParticipants())
                .groupSize(request.getGroupSize())
                .relayTeamSize(request.getRelayTeamSize())
                .relayReservesAllowed(request.getRelayReservesAllowed())
                .seasonId(request.getSeasonId())
                .build();
    }

    /**
     * The event a create is about to store, whichever kind of create it is.
     *
     * <p>One builder rather than two, so a draft and an ordinary event cannot drift
     * apart on the fields they share — the category and group size from the type, the
     * season, and the two relay settings that are only ever set on a relay. The
     * caller decides whether what comes back is a draft.</p>
     */
    private Event buildEvent(EventDTO eventDTO, Event.EventType type, Sex sex, Grade grade,
                             RelayTeamKind relayTeamKind, String form) {
        return Event.builder()
                .name(resolveName(eventDTO, type, sex, grade))
                .description(eventDTO.getDescription())
                .type(type)
                .category(type.getCategory())
                .sex(sex)
                .grade(grade)
                // The form the event is scoped to, or null for one scoped by its grade.
                // Checked by the caller against the kind the event ends up with.
                .form(form)
                .eventDate(eventDTO.getEventDate() != null ? eventDTO.getEventDate() : java.time.LocalDate.now())
                .location(eventDTO.getLocation())
                .maxParticipants(eventDTO.getMaxParticipants() != null
                        ? eventDTO.getMaxParticipants() : Event.DEFAULT_MAX_PARTICIPANTS)
                .groupSize(eventDTO.getGroupSize() != null && eventDTO.getGroupSize() > 0
                        ? eventDTO.getGroupSize() : type.getDefaultGroupSize())
                .enabled(eventDTO.getEnabled() == null || eventDTO.getEnabled())
                // The relay team kind, the race's size and whether reserves are
                // allowed. All three are the event's business; the last two are only
                // ever set on a relay.
                .relayTeamKind(relayTeamKind)
                .relayTeamSize(type.isRelay()
                        ? resolveRelayTeamSize(eventDTO.getRelayTeamSize(), type) : null)
                .relayReservesAllowed(type.isRelay()
                        && Boolean.TRUE.equals(eventDTO.getRelayReservesAllowed()))
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
        applyRelayTeamSettings(event, eventDTO, id);
        applyFormScope(event, eventDTO);
        applyStandard(event, eventDTO);
        // Moving an event to another type or grade renames it only while it still
        // carries its own default name; a title the school chose is left alone.
        if (previousDefaultName != null && previousDefaultName.equals(event.getName())) {
            event.setName(defaultName(event.getType(), event.getSex(), event.getGrade()));
        }

        event.applyTypeDefaults();
        return describe(eventRepository.save(event));
    }

    /**
     * Applies the relay team settings — what kind of relay this is, how many legs a
     * team runs and whether reserves are allowed.
     *
     * <p>Three rules, all judged on the event the update will leave behind:</p>
     * <ul>
     *   <li>a <strong>non-relay</strong> may not have a relay team kind. A create
     *       that asks for one is refused outright; an update that would leave one on
     *       is refused too, with the way out named, because {@code null} in a request
     *       means "leave it alone" — an empty string is how a caller clears it;</li>
     *   <li>what kind of relay an event is is only changed while it has no teams.
     *       Changing or clearing it under existing teams would leave a board of form
     *       teams hanging off a house relay, so the change is refused until the
     *       administrator has removed them;</li>
     *   <li>the leg count and the reserve switch are only meaningful on a relay, and
     *       are reset when an event stops being one.</li>
     * </ul>
     */
    private void applyRelayTeamSettings(Event event, EventDTO eventDTO, Long id) {
        // 1. Ask for a different kind of relay (an empty string clears it outright).
        if (eventDTO.getRelayTeamKind() != null) {
            RelayTeamKind requested = parseRelayTeamKind(eventDTO.getRelayTeamKind());
            if (requested != event.getRelayTeamKind()) {
                requireNoTeamsToReKind(id, event);
                event.setRelayTeamKind(requested);
            }
        }

        // 2. The type may have just changed, so judge the combination the event will
        //    have rather than the one it came in with.
        if (!event.isRelay()) {
            if (event.getRelayTeamKind() != null) {
                requireNoTeamsToReKind(id, event);
                throw new IllegalArgumentException(event.getType() == null
                        ? "This event is not a relay, so it cannot have a relay team kind. "
                                + "Clear it by sending relayTeamKind as an empty string."
                        : event.getType().getDisplayName() + " is not a relay, so it cannot have a "
                                + "relay team kind. Clear it by sending relayTeamKind as an empty "
                                + "string, then change the type.");
            }
            event.setRelayTeamSize(null);
            event.setRelayReservesAllowed(false);
            return;
        }
        if (eventDTO.getRelayTeamSize() != null) {
            event.setRelayTeamSize(resolveRelayTeamSize(eventDTO.getRelayTeamSize(), event.getType()));
        }
        if (eventDTO.getRelayReservesAllowed() != null) {
            event.setRelayReservesAllowed(eventDTO.getRelayReservesAllowed());
        }
    }

    /**
     * Applies the event's <strong>form scope</strong> — the form a form relay's teams
     * are drawn from, which is what lets a "Form 1 4x100M" take {@code 1A}, {@code 1B},
     * {@code 1C} and {@code 1D} from every class of Form 1 whatever grade its students
     * are in.
     *
     * <p>Two rules, and the same reading of an absent value as the relay kind uses:</p>
     * <ul>
     *   <li>{@code null} means <em>not supplied</em>, so the event's form is left
     *       alone; an <strong>empty string clears it</strong>, which is how a form relay
     *       becomes a graded one again;</li>
     *   <li>a value may only be left on a relay whose kind is {@code FORM}. A sprint, an
     *       undivided relay and a house relay are all refused, judged on the event the
     *       update would leave behind — so changing a form relay into a house relay has
     *       to clear the form in the same request, and is refused with that way out
     *       named if it does not.</li>
     * </ul>
     */
    private void applyFormScope(Event event, EventDTO eventDTO) {
        if (eventDTO.getForm() != null) {
            // A blank string is the caller clearing the scope; anything else must name
            // a form, which is a plain number — the leading digits of a class name.
            event.setForm(parseForm(eventDTO.getForm()));
        }
        // Judged on what the event will be, not on what it came in as: the kind may
        // have just changed under it.
        requireFormScopeAllowed(event.getType(), event.getRelayTeamKind(), event.getForm());
    }

    /**
     * Applies the required standard, or clears it.
     *
     * <p>An omitted standard leaves the event's own alone, because several callers
     * send partial bodies — the relay board sends only a kind and a form — and a
     * missing field must never quietly wipe a number somebody set. Clearing is
     * therefore asked for: {@code clearStandard: true}.</p>
     *
     * <p>Refused on an event that does not carry a standard, judged on what the update
     * would leave behind, so a 400M cannot become a 100M while keeping one. A standard
     * must be positive: a time of zero or a negative distance is not a qualifying
     * mark, it is a typo.</p>
     *
     * <p>Which events carry one is {@link Event.EventType#carriesAStandard()}'s
     * business, not this method's, so the rule is not restated here.</p>
     */
    private void applyStandard(Event event, EventDTO eventDTO) {
        if (Boolean.TRUE.equals(eventDTO.getClearStandard())) {
            event.setStandard(null);
        } else if (eventDTO.getStandard() != null) {
            event.setStandard(eventDTO.getStandard());
        }
        if (event.getStandard() != null) {
            if (event.getType() == null || !event.getType().carriesAStandard()) {
                // The type it is becoming, not the name it still carries: a request
                // that changes both is judged on the event it would leave behind.
                throw new IllegalArgumentException(
                        (event.getType() == null ? "This event" : event.getType().getDisplayName())
                        + " is not an event that carries a required standard. Only the "
                        + "track races of 400M and over, and the field events, can have one.");
            }
            if (event.getStandard().signum() <= 0) {
                throw new IllegalArgumentException(
                        "A required standard must be greater than zero.");
            }
        }
    }

    /**
     * Refuses a form scope the event cannot carry, and hands the form back so a caller
     * can use it directly.
     *
     * <p>The one rule, asked by a create, a draft and an update: a form only ever means
     * something to a relay divided into {@code FORM} teams. A sprint has no teams at
     * all; an undivided relay has no class teams to draw from; and a house relay is one
     * team per house within a grade, so a form on it would divide the race by one rule
     * while its runners were judged by another. {@code null} — no form — is always
     * allowed, because that is every event scoped by its grade.</p>
     *
     * @param form the form, already parsed; null when there is none
     * @return the same form, so a caller can use it directly
     * @throws IllegalArgumentException when the event could not be scoped to it
     */
    private static String requireFormScopeAllowed(Event.EventType type, RelayTeamKind kind,
                                                  String form) {
        if (form == null) {
            return null;
        }
        if (type == null || !type.isRelay()) {
            throw new IllegalArgumentException((type == null ? "This event" : type.getDisplayName())
                    + " is not a relay, so it cannot be scoped to a form. Only a relay has teams "
                    + "to draw from the classes of a form. Clear it by sending form as an empty "
                    + "string.");
        }
        if (kind != RelayTeamKind.FORM) {
            throw new IllegalArgumentException(type.getDisplayName() + " is "
                    + (kind == null
                            ? "not divided into form teams, so it cannot be scoped to a form. Send "
                                    + "relayTeamKind as FORM to run it by class."
                            : "a house relay, so it cannot be scoped to a form: a house relay is "
                                    + "one team per house within the event's grade.")
                    + " Clear the form by sending form as an empty string.");
        }
        return form;
    }

    /**
     * Refuses a change to what kind of relay an event is while it already has teams.
     *
     * <p>Those teams hold real selections — somebody decided which students run — so
     * they are not silently thrown away to make room for a different division. The
     * administrator clears them first ({@code DELETE /api/admin/events/{id}/relay-teams}),
     * which is one deliberate step.</p>
     */
    private void requireNoTeamsToReKind(Long eventId, Event event) {
        long teams = relayTeamService.countTeamsForEvent(eventId);
        if (teams > 0) {
            throw new IllegalStateException(event.getName() + " already has " + teams
                    + " relay team(s) with their runners. Remove them before changing what kind "
                    + "of relay it is.");
        }
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
     *
     * <h2>A draft's teams are not deleted as a side effect</h2>
     * <p>Deleting an event takes its relay teams with it
     * ({@link RelayTeamService#removeTeamsForEvent}), which is right for a race the
     * school is abandoning — but a <strong>draft</strong> event exists for no other
     * reason than to hold the teams somebody built by hand, so deleting it would
     * destroy exactly the selections it was made to keep. That delete is therefore
     * refused while the draft still has teams: the administrator moves them onto the
     * real event first, or removes them deliberately through
     * {@code DELETE /api/admin/events/{id}/relay-teams} and then deletes the empty
     * draft. Removing an event is a normal thing to do, so this is one clear refusal
     * rather than a silent rescue.</p>
     */
    @Transactional
    public void deleteEvent(Long id) {
        Event event = requireEvent(id);
        requireDraftWithoutTeamsToDelete(event);

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
        // Relay teams point at the event and at the athletes, so they go with it —
        // the runners themselves are left alone, as they are for entries and results.
        int relayTeams = relayTeamService.removeTeamsForEvent(id);
        eventRepository.delete(event);
        recordService.recomputeAll();
        log.info("Deleted event {} ('{}') with {} entries, {} groups and {} relay team(s)",
                id, event.getName(), enrollments.size(), groups.size(), relayTeams);
    }

    /**
     * Refuses to delete a draft event that still holds teams.
     *
     * <p>The guard the school's selections need: a draft's whole purpose is the teams
     * it carries, and {@link #deleteEvent} would take them with the event, through the
     * very call that exists to clear a race's teams. Nothing else in the codebase
     * deletes an event — {@code SeasonResetService} clears entries, heats and marks
     * and leaves the programme alone, and {@code SeasonService} refuses to delete a
     * year that still has events — so this is the one place the guard is needed, and
     * it is stated here rather than hidden in
     * {@link RelayTeamService#removeTeamsForEvent}, which an administrator uses quite
     * legitimately to start an event's selections again.</p>
     */
    private void requireDraftWithoutTeamsToDelete(Event event) {
        if (!event.isDraft()) {
            return;
        }
        long teams = relayTeamService.countTeamsForEvent(event.getId());
        if (teams > 0) {
            throw new IllegalStateException(event.getName() + " is a draft relay event and still "
                    + "holds " + teams + " relay team(s) with their runners. A draft exists to "
                    + "carry those teams, so deleting it would destroy them. Move the teams onto "
                    + "the real event first, or remove them deliberately with "
                    + "DELETE /api/admin/events/" + event.getId() + "/relay-teams, then delete the "
                    + "empty draft.");
        }
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
     *
     * <p>A draft is excluded: looking back at a sport day must not show a relay whose
     * teams are still being collected as though it had been run.</p>
     */
    @Transactional(readOnly = true)
    public List<EventDTO> getPastEvents() {
        java.time.LocalDate today = java.time.LocalDate.now();
        return describeAll(eventRepository.findAll().stream()
                .filter(event -> !event.isDraft())
                .filter(event -> event.getEventDate() != null && !event.getEventDate().isAfter(today))
                .sorted(EVENT_ORDER.reversed())
                .toList());
    }

    /**
     * A whole list of events, with the relays' readiness read <strong>once for the
     * list</strong>.
     *
     * <p>This is the list's own mapper, and the reason it exists is cost: readiness is
     * the one fact about an event that is not on its own row, and asking
     * {@link #describe(Event)} per event would be two queries per relay. Read in one
     * batch, the twelve relays of the programme cost two queries between them and the
     * hundred events that are not relays cost <em>none</em> — {@code
     * RelayReadiness.shortfallsOf} holds only relays, and an individual event is ready
     * by definition.</p>
     *
     * <p>The order, filtering and sorting are the caller's and are already applied:
     * this only describes what it is handed, in the order it is handed.</p>
     */
    private List<EventDTO> describeAll(List<Event> events) {
        Map<Long, String> shortfalls = relayReadiness.shortfallsOf(events);
        List<EventDTO> described = new java.util.ArrayList<>(events.size());
        for (Event event : events) {
            described.add(describe(event, shortfalls));
        }
        return described;
    }

    /** One event, with its relay's readiness read for it alone. */
    private EventDTO describe(Event event) {
        return describe(event, relayReadiness.shortfallsOf(java.util.Collections.singletonList(event)));
    }

    /**
     * One event, described from facts the caller has already read.
     *
     * <p>{@code shortfalls} holds only the relays that are not ready yet, so an id
     * that is absent from it — every individual event, and every relay whose teams
     * are built — reports ready with no reason. That is the same map the list passes
     * in, which is what makes one event and a hundred cost the same to describe.</p>
     */
    private EventDTO describe(Event event, Map<Long, String> shortfalls) {
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
        // Whether the relay may be marked yet, in the wording the refusal uses. Absent
        // from the map means ready, which is what every non-relay event is.
        String shortfall = event.getId() == null ? null : shortfalls.get(event.getId());
        dto.setRelayReady(shortfall == null);
        dto.setReadinessReason(shortfall);
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
     * The longest form the schema stores: {@code events.form} is a four-character
     * column, so anything longer is refused here rather than by the database.
     */
    private static final int MAX_FORM_LENGTH = 4;

    /**
     * Reads the <strong>form</strong> a relay is scoped to from a request.
     *
     * <p>A form is the number a class belongs to — {@code 1} for {@code 1A}, {@code 10}
     * for {@code 10B} — so it is written as digits and nothing else: {@code Form 1},
     * {@code 1A} and {@code A} are all refused rather than stored as a scope no student
     * could ever match. Leading zeros are dropped by {@link Student#formOf(String)}, the
     * one place a form is read off a class name, so a caller sending {@code 01} scopes
     * the event to the same Form 1 the register reads and the teams still appear.</p>
     *
     * <p>{@code null} means "not supplied"; a blank string is the way a caller
     * <em>clears</em> the scope, and reads as null here exactly as an empty
     * {@code relayTeamKind} does.</p>
     *
     * @return the canonical form — {@code 1}, {@code 10} — or null when there is none
     * @throws IllegalArgumentException when a value was sent that names no form
     */
    private static String parseForm(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Unknown form: " + raw + " — a form is the number "
                    + "its classes belong to, written in digits: 1 for Form 1, 10 for Form 10.");
        }
        String form = Student.formOf(value);
        if (form == null || form.length() > MAX_FORM_LENGTH) {
            throw new IllegalArgumentException("Unknown form: " + raw + " — a form is at most "
                    + MAX_FORM_LENGTH + " digits, e.g. 1 for Form 1.");
        }
        return form;
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

    /**
     * Reads a relay team kind from a request.
     *
     * <p>{@code null} means "not supplied" and is handed straight back, because on an
     * update it means <em>leave the event's kind alone</em>. A blank string is the
     * way a caller clears it, so that reads as null as well. Anything else must name
     * a kind: {@code FORM} (or {@code CLASS}) or {@code HOUSE}.</p>
     *
     * @throws IllegalArgumentException when a value was sent that is not a kind
     */
    private static RelayTeamKind parseRelayTeamKind(String raw) {
        if (raw == null) {
            return null;
        }
        RelayTeamKind kind = RelayTeamKind.fromCode(raw);
        if (kind == null && !raw.isBlank()) {
            throw new IllegalArgumentException(
                    "Unknown relay team kind: " + raw + " — use FORM or HOUSE.");
        }
        return kind;
    }

    /**
     * Only a relay may be divided into form or house teams. A sprint, a field event
     * or a hurdles race with a relay team kind would advertise teams nothing could
     * ever fill, so asking for one is refused rather than stored and ignored.
     */
    private static void requireRelayKindAllowed(Event.EventType type, RelayTeamKind kind) {
        if (kind == null || (type != null && type.isRelay())) {
            return;
        }
        throw new IllegalArgumentException((type == null ? "This event" : type.getDisplayName())
                + " is not a relay, so it cannot have a relay team kind. Only the 4x100M and the "
                + "4x400M are divided into form or house teams.");
    }

    /**
     * The legs a team of this relay runs. The race's own size is the default — four —
     * and a school that runs a longer squad says so on the event rather than in the
     * code.
     *
     * @throws IllegalArgumentException when the size names no sensibly sized team
     */
    private static int resolveRelayTeamSize(Integer requested, Event.EventType type) {
        if (requested == null || requested <= 0) {
            return type == null ? 0 : type.getDefaultRelayLegs();
        }
        if (requested > Event.MAX_RELAY_LEGS) {
            throw new IllegalArgumentException("A relay team may have at most "
                    + Event.MAX_RELAY_LEGS + " legs, so " + requested + " is not a team size. "
                    + "A 4x100M or a 4x400M runs four.");
        }
        return requested;
    }
}
