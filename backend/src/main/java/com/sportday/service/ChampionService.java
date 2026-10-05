package com.sportday.service;

import com.sportday.dto.ChampionsDTO;
import com.sportday.dto.SportDaySettingsDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventResult;
import com.sportday.entity.EventStage;
import com.sportday.entity.Student;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The championships.
 *
 * <p>Placings are worked out per event and turned into points by
 * {@link SettingsService}. Where an event has a final, <strong>the final decides
 * the points</strong> and the heats are qualifying only; otherwise the single
 * straight run does. Each event contributes:
 *
 * <ul>
 *   <li>individual places 1/2/3 = 9/6/3 and 4th–8th = 1, by default;</li>
 *   <li>relays on their own scale — 30/20/10 and 1 from 4th to 8th;</li>
 *   <li><strong>relay points count for the house only</strong>, so the personal
 *       championship is decided on individual events;</li>
 *   <li>an athlete who was <strong>absent or disqualified</strong> is not placed
 *       and scores nothing — they are listed after the placed athletes, with no
 *       place number and the outcome where the mark would be.</li>
 * </ul>
 *
 * <p>Every number here comes from the settings, so an administrator can change the
 * scale without a rebuild.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChampionService {

    private final EventRepository eventRepository;
    private final EventGroupRepository groupRepository;
    private final EventResultRepository resultRepository;
    private final StudentRepository studentRepository;
    private final SettingsService settingsService;
    private final RecordService recordService;

    // ------------------------------------------------------------- public API

    /** The personal and house championship tables, plus the placings behind them. */
    @Transactional(readOnly = true)
    public ChampionsDTO calculate() {
        Set<Long> recordResultIds = recordService.recordResultIds();
        List<Event> events = eventRepository.findAll().stream()
                .sorted(EventService.EVENT_ORDER)
                .toList();

        Map<Long, Tally> personalTallies = new HashMap<>();
        Map<String, Tally> houseTallies = new LinkedHashMap<>();
        List<ChampionsDTO.EventStandingsDTO> standings = new ArrayList<>();

        for (Event event : events) {
            ChampionsDTO.EventStandingsDTO eventStandings = rank(event, recordResultIds);
            if (eventStandings.getPlacings().isEmpty()) {
                continue;
            }
            standings.add(eventStandings);
            boolean relay = eventStandings.isRelay();
            for (ChampionsDTO.PlacingDTO placing : eventStandings.getPlacings()) {
                if (placing.getPoints() <= 0) {
                    continue;
                }
                // Every scoring place counts for the house; only individual events
                // count towards the personal championship.
                Tally house = houseTallies.computeIfAbsent(
                        placing.getHouse() == null ? "Unassigned" : placing.getHouse(),
                        key -> new Tally());
                house.add(placing, placing.getUserId());

                if (!relay && placing.getUserId() != null) {
                    personalTallies.computeIfAbsent(placing.getUserId(), key -> new Tally())
                            .add(placing, placing.getUserId());
                }
            }
        }

        SportDaySettingsDTO settings = SportDaySettingsDTO.from(settingsService.get());
        return ChampionsDTO.builder()
                .referenceDate(LocalDate.now())
                .eventsScored(standings.size())
                .scoringStageNote("FINAL where a final was run, otherwise HEAT")
                .settings(settings)
                .personal(buildPersonal(personalTallies))
                .houses(buildHouses(houseTallies))
                .events(standings)
                .build();
    }

    /** The placings of a single event. */
    @Transactional(readOnly = true)
    public ChampionsDTO.EventStandingsDTO standingsFor(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + eventId));
        return rank(event, recordService.recordResultIds());
    }

    // ------------------------------------------------------------- ranking

    private ChampionsDTO.EventStandingsDTO rank(Event event, Set<Long> recordResultIds) {
        boolean hasFinal = groupRepository
                .findFirstByEventIdAndStage(event.getId(), EventStage.FINAL).isPresent();

        List<EventResult> finalResults = hasFinal
                ? resultRepository.findByEventIdAndStageOrderByMarkAsc(event.getId(), EventStage.FINAL)
                : List.of();
        // A final that has been drawn but not yet run cannot decide anything, so the
        // heats stand in until the final marks arrive.
        boolean decidedByFinal = hasFinal && !finalResults.isEmpty();
        EventStage stage = decidedByFinal ? EventStage.FINAL : EventStage.HEAT;
        List<EventResult> results = decidedByFinal
                ? finalResults
                : resultRepository.findByEventIdAndStageOrderByMarkAsc(event.getId(), EventStage.HEAT);

        Event.EventType type = event.getType();
        boolean lowerBetter = type != null && type.isLowerBetter();
        Comparator<EventResult> order = lowerBetter
                ? Comparator.comparing(EventResult::getMark)
                : Comparator.<EventResult, java.math.BigDecimal>comparing(EventResult::getMark).reversed();

        List<EventResult> ranked = results.stream()
                .filter(result -> result.getUser() != null && result.getMark() != null)
                .filter(result -> !result.isAbsentOrDisqualified())
                .sorted(order.thenComparing(result -> result.getUser().getId()))
                .toList();

        // An athlete who was absent or disqualified is not placed: they are listed
        // after the placed athletes, without a place and without points, and the
        // outcome stands in place of the mark.
        List<EventResult> notPlaced = results.stream()
                .filter(result -> result.getUser() != null)
                .filter(result -> result.isAbsentOrDisqualified() || result.getMark() == null)
                .sorted(Comparator.comparing(result -> result.getUser().getId()))
                .toList();

        boolean relay = type != null && type.isRelay();
        List<EventResult> listed = new ArrayList<>(ranked.size() + notPlaced.size());
        listed.addAll(ranked);
        listed.addAll(notPlaced);
        Map<Long, Student> rosters = rostersFor(listed);

        List<ChampionsDTO.PlacingDTO> placings = new ArrayList<>(ranked.size() + notPlaced.size());
        int place = 1;
        for (EventResult result : ranked) {
            Student roster = rosters.get(result.getUser().getId());
            placings.add(ChampionsDTO.PlacingDTO.builder()
                    .place(place)
                    .userId(result.getUser().getId())
                    .studentRef(roster != null ? roster.getStudentId() : result.getUser().getUsername())
                    .name(roster != null ? roster.getName() : result.getUser().getFullName())
                    .grade(roster != null && roster.getGrade() != null ? roster.getGrade().name() : null)
                    .className(roster != null ? roster.getClassName() : null)
                    .form(roster != null ? roster.getForm() : null)
                    .house(roster != null ? roster.getHouse() : null)
                    .houseCode(roster != null ? roster.getHouseCode() : null)
                    .mark(result.getMark())
                    .unit(result.getUnit())
                    .displayMark(MarkFormatter.formatWithUnit(
                            result.getMark(), event.getType(), result.getUnit()))
                    .points(settingsService.pointsForPlace(place, relay))
                    .schoolRecord(recordResultIds.contains(result.getId()))
                    .build());
            place++;
        }
        // Place 0, no points, and the outcome where the mark would be — but the
        // athlete keeps their name, student id, grade, class and house, because a
        // sheet that omits them would read as if they never competed.
        for (EventResult result : notPlaced) {
            Student roster = rosters.get(result.getUser().getId());
            placings.add(ChampionsDTO.PlacingDTO.builder()
                    .place(0)
                    .userId(result.getUser().getId())
                    .studentRef(roster != null ? roster.getStudentId() : result.getUser().getUsername())
                    .name(roster != null ? roster.getName() : result.getUser().getFullName())
                    .grade(roster != null && roster.getGrade() != null ? roster.getGrade().name() : null)
                    .className(roster != null ? roster.getClassName() : null)
                    .form(roster != null ? roster.getForm() : null)
                    .house(roster != null ? roster.getHouse() : null)
                    .houseCode(roster != null ? roster.getHouseCode() : null)
                    .mark(null)
                    .unit(null)
                    .displayMark(MarkFormatter.formatOutcome(result.getOutcomeOrDefault()))
                    .points(0)
                    // An absent or disqualified performance can never hold a record.
                    .schoolRecord(false)
                    .build());
        }

        return ChampionsDTO.EventStandingsDTO.builder()
                .eventId(event.getId())
                .eventName(event.getName())
                .eventType(type == null ? null : type.name())
                .eventTypeLabel(type == null ? null : type.getDisplayName())
                .category(event.getCategoryOrDefault().name())
                .categoryLabel(event.getCategoryOrDefault().getLabel())
                .sex(event.getSex() == null ? null : event.getSex().name())
                .sexLabel(event.getSex() == null ? null : event.getSex().getLabel())
                .eventDate(event.getEventDate())
                .scoringStage(stage.name())
                .relay(relay)
                .hasFinal(hasFinal)
                .sheetSize(event.isShortSprint() ? "A5" : "A4")
                .placings(placings)
                .build();
    }

    // ------------------------------------------------------------- totals

    private List<ChampionsDTO.PersonalChampionDTO> buildPersonal(Map<Long, Tally> tallies) {
        List<ChampionsDTO.PersonalChampionDTO> table = new ArrayList<>();
        tallies.forEach((userId, tally) -> table.add(ChampionsDTO.PersonalChampionDTO.builder()
                .userId(userId)
                .studentRef(tally.studentRef)
                .name(tally.name)
                .grade(tally.grade)
                .className(tally.className)
                .form(Student.formOf(tally.className))
                .house(tally.house)
                .houseCode(Student.houseCodeOf(tally.house))
                .points(tally.points)
                .golds(tally.golds)
                .silvers(tally.silvers)
                .bronzes(tally.bronzes)
                .eventsScored(tally.events)
                .build()));

        table.sort(Comparator
                .comparingInt(ChampionsDTO.PersonalChampionDTO::getPoints).reversed()
                .thenComparing(Comparator.comparingInt(ChampionsDTO.PersonalChampionDTO::getGolds).reversed())
                .thenComparing(Comparator.comparingInt(ChampionsDTO.PersonalChampionDTO::getSilvers).reversed())
                .thenComparing(Comparator.comparingInt(ChampionsDTO.PersonalChampionDTO::getBronzes).reversed())
                .thenComparing(entry -> entry.getStudentRef() == null ? "" : entry.getStudentRef()));

        for (int index = 0; index < table.size(); index++) {
            table.get(index).setRank(index + 1);
        }
        return table;
    }

    private List<ChampionsDTO.HouseChampionDTO> buildHouses(Map<String, Tally> tallies) {
        List<ChampionsDTO.HouseChampionDTO> table = new ArrayList<>();
        tallies.forEach((house, tally) -> table.add(ChampionsDTO.HouseChampionDTO.builder()
                .house(house)
                .points(tally.points)
                .golds(tally.golds)
                .silvers(tally.silvers)
                .bronzes(tally.bronzes)
                .athletes(tally.athletes.size())
                .build()));

        table.sort(Comparator
                .comparingInt(ChampionsDTO.HouseChampionDTO::getPoints).reversed()
                .thenComparing(Comparator.comparingInt(ChampionsDTO.HouseChampionDTO::getGolds).reversed())
                .thenComparing(Comparator.comparingInt(ChampionsDTO.HouseChampionDTO::getSilvers).reversed())
                .thenComparing(Comparator.comparingInt(ChampionsDTO.HouseChampionDTO::getBronzes).reversed())
                .thenComparing(entry -> entry.getHouse() == null ? "" : entry.getHouse()));

        for (int index = 0; index < table.size(); index++) {
            table.get(index).setRank(index + 1);
        }
        return table;
    }

    /** Running total for one athlete or one house. */
    private static final class Tally {
        private int points;
        private int golds;
        private int silvers;
        private int bronzes;
        private int events;
        private final java.util.Set<Long> athletes = new java.util.LinkedHashSet<>();
        private String studentRef;
        private String name;
        private String grade;
        private String className;
        private String house;

        void add(ChampionsDTO.PlacingDTO placing, Long userId) {
            points += placing.getPoints();
            events++;
            if (placing.getPlace() == 1) golds++;
            else if (placing.getPlace() == 2) silvers++;
            else if (placing.getPlace() == 3) bronzes++;
            if (userId != null) athletes.add(userId);
            // Remember who we are, for the personal table.
            if (studentRef == null) {
                studentRef = placing.getStudentRef();
                name = placing.getName();
                grade = placing.getGrade();
                className = placing.getClassName();
            }
            if (house == null) {
                house = placing.getHouse();
            }
        }
    }

    private Map<Long, Student> rostersFor(List<EventResult> results) {
        List<Long> userIds = results.stream()
                .filter(result -> result.getUser() != null)
                .map(result -> result.getUser().getId())
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Student> rosters = new HashMap<>();
        for (Student student : studentRepository.findWithUserByUserIdIn(userIds)) {
            if (student.getUser() != null) {
                rosters.put(student.getUser().getId(), student);
            }
        }
        return rosters;
    }
}
