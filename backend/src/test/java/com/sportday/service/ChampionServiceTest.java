package com.sportday.service;

import com.sportday.dto.ChampionsDTO;
import com.sportday.entity.*;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.StudentRepository;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * The personal and house championships.
 *
 * <p>Requirement: first = 9, second = 6, third = 3, top 8 = 1, relay 30/20/10, with
 * an administrator able to change the scale.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChampionServiceTest {

    private static final Sex SEX = Sex.MALE;

    @Mock private EventRepository eventRepository;
    @Mock private EventGroupRepository groupRepository;
    @Mock private EventResultRepository resultRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private SettingsService settingsService;
    @Mock private RecordService recordService;

    @InjectMocks private ChampionService service;

    private final SportDaySettings settings = SportDaySettings.defaults();
    private final Map<Long, Student> rosters = new java.util.LinkedHashMap<>();

    private Event hundred;
    private Event relay;
    private Event twoHundred;

    // ------------------------------------------------------------- fixtures

    private Event event(long id, Event.EventType type) {
        return Event.builder()
                .id(id)
                .name(type.getDisplayName())
                .type(type)
                .category(type.getCategory())
                .sex(SEX)
                .groupSize(8)
                .eventDate(LocalDate.of(2026, 10, 1))
                .enabled(true)
                .build();
    }

    private Student roster(long userId, String house) {
        User athlete = User.builder().id(userId).username("S000" + userId)
                .fullName("Athlete " + userId).build();
        Student student = Student.builder()
                .id(userId)
                .user(athlete)
                .studentId("S000" + userId)
                .name("Athlete " + userId)
                .grade(Grade.B)
                .className("3A")
                .classNumber((int) userId)
                .house(house)
                .dob(LocalDate.of(2011, 5, 5))
                .sex(SEX)
                .enabled(true)
                .build();
        rosters.put(userId, student);
        return student;
    }

    private EventResult result(Event event, long userId, String mark) {
        return result(event, userId, mark, null);
    }

    /**
     * A result that is a <strong>relay team's</strong> time when a team is named, and
     * the anchor athlete's own mark when it is not. The runner is only the row's
     * anchor — see {@code EventResult#relayTeam}.
     */
    private EventResult result(Event event, long userId, String mark,
                               com.sportday.entity.RelayTeam team) {
        return EventResult.builder()
                .id(userId * 100 + event.getId())
                .user(rosters.get(userId).getUser())
                .event(event)
                .relayTeam(team)
                .stage(EventStage.HEAT)
                .mark(new BigDecimal(mark))
                .unit(event.getType().getDefaultUnit())
                .build();
    }

    @BeforeEach
    void setUp() {
        roster(1, "Red");
        roster(2, "Blue");
        roster(3, "Red");

        hundred = event(1L, Event.EventType.RUN_100M);
        relay = event(2L, Event.EventType.RELAY_4X100M);
        twoHundred = event(3L, Event.EventType.RUN_200M);

        when(eventRepository.findAll()).thenReturn(List.of(hundred, relay, twoHundred));
        when(studentRepository.findWithUserByUserIdIn(any())).thenReturn(new ArrayList<>(rosters.values()));
        when(recordService.recordResultIds()).thenReturn(Set.of());

        // Only the 200M has a final.
        when(groupRepository.findFirstByEventIdAndStage(1L, EventStage.FINAL)).thenReturn(Optional.empty());
        when(groupRepository.findFirstByEventIdAndStage(2L, EventStage.FINAL)).thenReturn(Optional.empty());
        when(groupRepository.findFirstByEventIdAndStage(3L, EventStage.FINAL))
                .thenReturn(Optional.of(EventGroup.builder().id(30L).stage(EventStage.FINAL).build()));

        // 100M heats: 11.5 is the fastest.
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(1L, EventStage.HEAT))
                .thenReturn(List.of(result(hundred, 1, "12.000"), result(hundred, 2, "11.500"),
                        result(hundred, 3, "13.000")));
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(1L, EventStage.FINAL))
                .thenReturn(List.of());

        // Relay heats: 44.0 is the fastest.
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(2L, EventStage.HEAT))
                .thenReturn(List.of(result(relay, 1, "45.000"), result(relay, 2, "44.000")));
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(2L, EventStage.FINAL))
                .thenReturn(List.of());

        // 200M: heats exist, and a final was run.
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(3L, EventStage.HEAT))
                .thenReturn(List.of(result(twoHundred, 1, "30.000"), result(twoHundred, 2, "25.000"),
                        result(twoHundred, 3, "26.000")));
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(3L, EventStage.FINAL))
                .thenReturn(List.of(result(twoHundred, 3, "24.000"), result(twoHundred, 1, "27.000")));

        when(settingsService.get()).thenReturn(settings);
        when(settingsService.pointsForPlace(anyInt(), anyBoolean()))
                .thenAnswer(call -> settings.pointsForPlace(call.getArgument(0), call.getArgument(1)));
    }

    private ChampionsDTO.EventStandingsDTO standingsFor(long eventId) {
        return service.calculate().getEvents().stream()
                .filter(s -> s.getEventId() == eventId)
                .findFirst()
                .orElseThrow();
    }

    private ChampionsDTO.PersonalChampionDTO personal(long userId) {
        return service.calculate().getPersonal().stream()
                .filter(p -> p.getUserId() == userId)
                .findFirst()
                .orElseThrow();
    }

    private ChampionsDTO.HouseChampionDTO house(String name) {
        return service.calculate().getHouses().stream()
                .filter(h -> name.equals(h.getHouse()))
                .findFirst()
                .orElseThrow();
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("an individual event scores 9 / 6 / 3 down the placings")
    void individualPlacingsScore() {
        ChampionsDTO.EventStandingsDTO hundredStandings = standingsFor(1L);

        assertEquals("HEAT", hundredStandings.getScoringStage(), "no final was run");
        assertEquals(3, hundredStandings.getPlacings().size());
        var placings = hundredStandings.getPlacings();
        assertEquals(1, placings.get(0).getPlace());
        assertEquals(2L, placings.get(0).getUserId(), "11.500 is the fastest");
        assertEquals(9, placings.get(0).getPoints());
        assertEquals(1L, placings.get(1).getUserId());
        assertEquals(6, placings.get(1).getPoints());
        assertEquals(3L, placings.get(2).getUserId());
        assertEquals(3, placings.get(2).getPoints());
    }

    @Test
    @DisplayName("where a final was run, the final decides the points and the heats do not")
    void theFinalDecidesThePoints() {
        ChampionsDTO.EventStandingsDTO twoHundredStandings = standingsFor(3L);

        assertEquals("FINAL", twoHundredStandings.getScoringStage());
        assertTrue(twoHundredStandings.isHasFinal());
        var placings = twoHundredStandings.getPlacings();
        assertEquals(2, placings.size(), "only the finalists can score");
        assertEquals(3L, placings.get(0).getUserId(), "24.000 won the final");
        assertEquals(9, placings.get(0).getPoints());
        assertEquals(1L, placings.get(1).getUserId());
        assertEquals(6, placings.get(1).getPoints());

        // Athlete 2 ran 25.000 in the heats — faster than the second-placed final
        // time — but did not qualify, so scores nothing.
        assertTrue(placings.stream().noneMatch(p -> p.getUserId() == 2L),
                "a heat time that did not reach the final scores nothing");
    }

    @Test
    @DisplayName("a relay scores on its own scale")
    void relayScoresOnItsOwnScale() {
        ChampionsDTO.EventStandingsDTO relayStandings = standingsFor(2L);

        assertTrue(relayStandings.isRelay());
        assertEquals(30, relayStandings.getPlacings().get(0).getPoints());
        assertEquals(20, relayStandings.getPlacings().get(1).getPoints());
    }

    // ------------------------------------------------- a relay is named by its team

    @Test
    @DisplayName("a relay placing is named by the team, never by the runner it hangs off")
    void relayPlacingsAreNamedByTheTeam() {
        com.sportday.entity.RelayTeam green = com.sportday.entity.RelayTeam.builder()
                .id(70L).teamKey("Green").label("C Grade Green").build();
        com.sportday.entity.RelayTeam red = com.sportday.entity.RelayTeam.builder()
                .id(71L).teamKey("Red").label("C Grade Red").build();
        // 44.000 is the fastest, and it is the Red team's time — anchored on athlete 2.
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(2L, EventStage.HEAT))
                .thenReturn(List.of(result(relay, 1, "45.000", green),
                        result(relay, 2, "44.000", red)));

        var placings = standingsFor(2L).getPlacings();

        assertEquals("C Grade Red", placings.get(0).getName(), "the team, not the runner");
        assertEquals("C Grade Red", placings.get(0).getTeamLabel());
        assertEquals(71L, placings.get(0).getTeamId());
        assertEquals("C Grade Green", placings.get(1).getName());
        // A team's line has no student id, grade, class or form: those belong to a
        // person, exactly as the marking sheet and the mark grid leave them blank.
        assertNull(placings.get(0).getStudentRef());
        assertNull(placings.get(0).getGrade());
        assertNull(placings.get(0).getClassName());
        assertNull(placings.get(0).getForm());
        // The house is kept: a relay's points count for a house, and always have.
        assertEquals("Blue", placings.get(0).getHouse());
        assertEquals(30, placings.get(0).getPoints());
        assertEquals("0.44.000s", placings.get(0).getDisplayMark(),
                "a relay's time reads in the school's own shape");
    }

    @Test
    @DisplayName("a relay's placings still score for the house, and still not in the personal table")
    void relayTeamPlacingsStillScoreForTheHouse() {
        com.sportday.entity.RelayTeam red = com.sportday.entity.RelayTeam.builder()
                .id(71L).teamKey("Red").label("C Grade Red").build();
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(2L, EventStage.HEAT))
                .thenReturn(List.of(result(relay, 1, "45.000"), result(relay, 2, "44.000", red)));

        assertEquals(39, house("Blue").getPoints(), "9 for the 100M and 30 for the relay");
        assertEquals(9, personal(2L).getPoints(), "the relay's points stay with the house");
    }

    @Test
    @DisplayName("an individual event's placings are completely unchanged")
    void individualPlacingsAreUnchanged() {
        var placings = standingsFor(1L).getPlacings();

        // The athlete's own name, student id, grade and class — and no team anywhere,
        // so nothing that reads a placings table can mistake one for a relay line.
        assertEquals("Athlete 2", placings.get(0).getName());
        assertEquals("S0002", placings.get(0).getStudentRef());
        assertEquals("B", placings.get(0).getGrade());
        assertEquals("3A", placings.get(0).getClassName());
        assertEquals("3", placings.get(0).getForm());
        assertNull(placings.get(0).getTeamId());
        assertNull(placings.get(0).getTeamLabel());
        assertEquals("11.5s", placings.get(0).getDisplayMark(),
                "a sprint reads as the seconds it is, trimmed of trailing zeros");
    }

    @Test
    @DisplayName("relay points count for the house only, so the personal table is individual events")
    void relayPointsGoToTheHouseOnly() {
        // Athlete 2 won the 100M (9) and the relay (30). Only the 9 is personal.
        assertEquals(9, personal(2L).getPoints(),
                "the relay's 30 points are not part of a personal total");
        assertEquals(1, personal(2L).getEventsScored());

        // Houses do get them: Blue = 9 (100M) + 30 (relay) = 39.
        assertEquals(39, house("Blue").getPoints());
    }

    @Test
    @DisplayName("the personal table totals individual events and ranks on points, then medals")
    void personalChampionship() {
        var table = service.calculate().getPersonal();

        // Athlete 3: 3 (100M 3rd) + 9 (200M final 1st) = 12, with a gold.
        // Athlete 1: 6 (100M 2nd) + 6 (200M final 2nd) = 12, no gold.
        // Athlete 2: 9 (100M 1st), relay excluded.
        assertEquals(3, table.size());
        assertEquals(3L, table.get(0).getUserId(), "12 points and a gold leads");
        assertEquals(1L, table.get(1).getUserId(), "12 points but no gold is second");
        assertEquals(2L, table.get(2).getUserId(), "9 points is third");
        assertEquals(12, table.get(0).getPoints());
        assertEquals(12, table.get(1).getPoints());
        assertEquals(9, table.get(2).getPoints());
        assertEquals(1, table.get(0).getGolds());
        assertEquals(1, table.get(0).getBronzes());
    }

    @Test
    @DisplayName("the house table totals every event, relays included")
    void houseChampionship() {
        var table = service.calculate().getHouses();

        // Red: 6 + 3 (100M) + 20 (relay 2nd) + 9 + 6 (200M final) = 44.
        // Blue: 9 (100M) + 30 (relay 1st) = 39.
        assertEquals(2, table.size());
        assertEquals("Red", table.get(0).getHouse());
        assertEquals(44, table.get(0).getPoints());
        assertEquals(1, table.get(0).getRank());
        assertEquals("Blue", table.get(1).getHouse());
        assertEquals(39, table.get(1).getPoints());
        assertEquals(2, table.get(0).getAthletes(), "two Red athletes scored");
        assertEquals(1, table.get(1).getAthletes());
    }

    @Test
    @DisplayName("an administrator's own scale changes the championship")
    void aCustomScaleChangesTheResult() {
        // Make every place worth 100: the house with more scoring places wins.
        settings.setPointsFirst(100);
        settings.setPointsSecond(100);
        settings.setPointsThird(100);
        settings.setPointsTopPlace(8);
        settings.setPointsTop(100);
        settings.setRelayPointsFirst(100);
        settings.setRelayPointsSecond(100);

        var table = service.calculate().getHouses();

        assertEquals(500, table.get(0).getPoints(), "Red scores on five occasions");
        assertEquals(200, table.get(1).getPoints(), "Blue on two");
        assertEquals("Red", table.get(0).getHouse());
    }

    @Test
    @DisplayName("a record-breaking placing is marked on the standings")
    void aRecordPlacingIsFlagged() {
        // The fixture ids are userId * 100 + eventId, so athlete 2's 100M result is 201.
        when(recordService.recordResultIds()).thenReturn(Set.of(201L));

        var placings = standingsFor(1L).getPlacings();

        assertTrue(placings.get(0).isSchoolRecord());
        assertFalse(placings.get(1).isSchoolRecord());
    }

    @Test
    @DisplayName("an event with no results contributes nothing")
    void anEventWithNoResultsIsSkipped() {
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(1L, EventStage.HEAT))
                .thenReturn(List.of());

        assertTrue(service.calculate().getEvents().stream().noneMatch(s -> s.getEventId() == 1L));
    }

    @Test
    @DisplayName("a drawn-but-unrun final does not suppress the heat placings")
    void anUnrunFinalLeavesTheHeatsStanding() {
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(3L, EventStage.FINAL))
                .thenReturn(List.of());

        ChampionsDTO.EventStandingsDTO twoHundredStandings = standingsFor(3L);

        assertEquals("HEAT", twoHundredStandings.getScoringStage(),
                "the final has been drawn but nobody has run it, so the heats count");
        assertTrue(twoHundredStandings.isHasFinal());
        assertEquals(3, twoHundredStandings.getPlacings().size());
    }

    @Test
    @DisplayName("the payload reports the scale it used, so the page can show it")
    void theScaleIsReported() {
        ChampionsDTO champions = service.calculate();

        assertEquals(3, champions.getEventsScored());
        assertNotNull(champions.getSettings());
        assertEquals(9, champions.getSettings().getPointsFirst());
        assertEquals(30, champions.getSettings().getRelayPointsFirst());
        assertEquals(2, champions.getSettings().getTrackMaxEntries());
    }

    // --------------------------------------------- absent and disqualified

    /** A recorded outcome for an athlete who produced no mark at all. */
    private EventResult outcome(Event event, long userId, EventResult.Outcome value) {
        return EventResult.builder()
                .id(userId * 100 + event.getId())
                .user(rosters.get(userId).getUser())
                .event(event)
                .stage(EventStage.HEAT)
                .outcome(value)
                .build();
    }

    @Test
    @DisplayName("an absent or disqualified athlete is not placed and scores nothing")
    void absentAthletesAreNotPlaced() {
        roster(4, "Green");
        roster(5, "Blue");
        when(studentRepository.findWithUserByUserIdIn(any()))
                .thenReturn(new ArrayList<>(rosters.values()));
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(1L, EventStage.HEAT))
                .thenReturn(List.of(
                        result(hundred, 1, "12.000"),
                        result(hundred, 2, "11.500"),
                        outcome(hundred, 4, EventResult.Outcome.ABS),
                        outcome(hundred, 5, EventResult.Outcome.DQ)));

        var placings = standingsFor(1L).getPlacings();

        assertEquals(4, placings.size(), "they are listed, not hidden");
        // The two who ran are placed as they always were.
        assertEquals(1, placings.get(0).getPlace());
        assertEquals(2L, placings.get(0).getUserId());
        assertEquals(9, placings.get(0).getPoints());
        assertEquals(2, placings.get(1).getPlace());
        assertEquals(6, placings.get(1).getPoints());

        // And the two who did not come after them, with no place and no points.
        var absent = placings.get(2);
        assertEquals(0, absent.getPlace(), "no place number");
        assertEquals(0, absent.getPoints(), "and nothing scored");
        assertEquals("ABS", absent.getDisplayMark(), "the outcome stands in for the mark");
        assertNull(absent.getMark());
        assertFalse(absent.isSchoolRecord(), "and it can never be a record");
        assertEquals("Athlete 4", absent.getName(), "the athlete is still named");
        assertEquals("S0004", absent.getStudentRef());
        assertEquals("B", absent.getGrade());
        assertEquals("3A", absent.getClassName());
        assertEquals("Green", absent.getHouse());

        assertEquals("DQ", placings.get(3).getDisplayMark());
        assertEquals(0, placings.get(3).getPlace());
        assertEquals(0, placings.get(3).getPoints());
    }

    @Test
    @DisplayName("an absent athlete never reaches either championship table")
    void absentAthletesScoreNothingInTheTables() {
        roster(4, "Green");
        when(studentRepository.findWithUserByUserIdIn(any()))
                .thenReturn(new ArrayList<>(rosters.values()));
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(1L, EventStage.HEAT))
                .thenReturn(List.of(
                        result(hundred, 1, "12.000"),
                        outcome(hundred, 4, EventResult.Outcome.ABS)));

        ChampionsDTO champions = service.calculate();

        assertTrue(champions.getPersonal().stream().noneMatch(p -> p.getUserId() == 4L),
                "no points means no line in the personal table");
        assertTrue(champions.getHouses().stream().noneMatch(h -> "Green".equals(h.getHouse())),
                "and none in the house table either");
        assertEquals(2, champions.getPersonal().size(), "the two placed athletes are still there");
    }

    @Test
    @DisplayName("an event where everybody was absent has no scoring places at all")
    void anEventOfAbsentAthletesScoresNothing() {
        roster(4, "Green");
        when(studentRepository.findWithUserByUserIdIn(any()))
                .thenReturn(new ArrayList<>(rosters.values()));
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(1L, EventStage.HEAT))
                .thenReturn(List.of(outcome(hundred, 4, EventResult.Outcome.ABS)));

        var placings = standingsFor(1L).getPlacings();

        assertEquals(1, placings.size());
        assertEquals(0, placings.get(0).getPlace());
        assertEquals(0, placings.get(0).getPoints());
        assertEquals("ABS", placings.get(0).getDisplayMark());
    }
}
