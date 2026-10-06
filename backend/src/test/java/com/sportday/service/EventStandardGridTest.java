package com.sportday.service;

import com.sportday.dto.MarkRowDTO;
import com.sportday.dto.MarkSheetDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventResult;
import com.sportday.entity.EventStage;
import com.sportday.entity.Grade;
import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamKind;
import com.sportday.entity.RelayTeamMember;
import com.sportday.entity.Sex;
import com.sportday.entity.Student;
import com.sportday.entity.User;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.FinalEntryRepository;
import com.sportday.repository.RelayTeamMemberRepository;
import com.sportday.repository.RelayTeamRepository;
import com.sportday.repository.StudentRepository;
import com.sportday.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The <strong>standard's verdict on a mark-entry row</strong>: the number the grid
 * shows beside the box, and whether the mark fell short of it.
 *
 * <p>Below standard is <strong>automatic</strong>, as the school asked: the grid
 * knows the mark and the standard, so the row says so by itself and the teacher
 * types nothing. The comparison is the server's, on the event's own lower-is-better
 * rule, so the grid cannot disagree with the leaderboard — and this test is where
 * the boundary is pinned: a mark <em>on</em> the standard is met, and one a hair the
 * wrong way is below.</p>
 *
 * <p>Two rows are never below, and both are asserted here: a row with no mark (a
 * blank is not a failure) and an event with no standard (nothing to fall short of).
 * A relay row carries no standard either — a relay is its own category and is run by
 * team — even if a number were somehow left on the event by an older build.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventStandardGridTest {

    private static final long ATHLETE = 61L;
    private static final long RELAY_ID = 30L;
    private static final long TEAM_A = 301L;
    private static final long TEAM_B = 302L;

    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private EventRepository eventRepository;
    @Mock private EventGroupRepository groupRepository;
    @Mock private EventResultRepository resultRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private UserRepository userRepository;
    @Mock private FinalEntryRepository finalEntryRepository;
    @Mock private EventGroupService eventGroupService;
    @Mock private RecordService recordService;
    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;

    private MarkEntryService service;

    // ------------------------------------------------------------- fixtures

    private static Event event(long id, Event.EventType type, String standard) {
        return Event.builder()
                .id(id)
                .name("Boys " + type.getDisplayName() + " · A Grade")
                .type(type)
                .category(type.getCategory())
                .sex(Sex.MALE)
                .grade(Grade.A)
                .standard(standard == null ? null : new BigDecimal(standard))
                .eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512)
                .groupSize(type.getDefaultGroupSize())
                .enabled(true)
                .build();
    }

    private static User user(long id) {
        return User.builder().id(id).username("S00" + id).fullName("Athlete " + id).build();
    }

    private static Student roster(User athlete) {
        return Student.builder()
                .id(athlete.getId())
                .user(athlete)
                .studentId(athlete.getUsername())
                .name("Chan Tai Man")
                .dob(LocalDate.of(2011, 5, 5))
                .sex(Sex.MALE)
                .className("5A")
                .classNumber(1)
                .house("Red")
                .grade(Grade.A)
                .enabled(true)
                .build();
    }

    /** One athlete entered, with the mark they recorded — or none at all. */
    private void entered(Event event, String mark) {
        when(eventRepository.findById(event.getId())).thenReturn(Optional.of(event));
        when(enrollmentRepository.findConfirmedWithUserByEvent(
                eq(event.getId()), eq(Enrollment.EnrollmentStatus.CONFIRMED)))
                .thenReturn(List.of(Enrollment.builder()
                        .user(user(ATHLETE))
                        .event(event)
                        .status(Enrollment.EnrollmentStatus.CONFIRMED)
                        .build()));
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(
                eq(event.getId()), eq(EventStage.HEAT)))
                .thenReturn(mark == null
                        ? List.of()
                        : List.of(EventResult.builder()
                                .id(7L)
                                .user(user(ATHLETE))
                                .event(event)
                                .stage(EventStage.HEAT)
                                .mark(new BigDecimal(mark))
                                .unit(event.getType().getDefaultUnit())
                                .build()));
    }

    @BeforeEach
    void setUp() {
        service = new MarkEntryService(enrollmentRepository, eventRepository, groupRepository,
                resultRepository, studentRepository, userRepository, finalEntryRepository,
                eventGroupService, recordService, relayTeamRepository, relayTeamMemberRepository,
                new RelayReadiness(relayTeamRepository, relayTeamMemberRepository));

        when(recordService.recordResultIds()).thenReturn(Set.of());
        when(resultRepository.findByEventIdAndStageOrderByMarkAsc(anyLong(), any()))
                .thenReturn(List.of());
        when(studentRepository.findWithUserByUserIdIn(any())).thenReturn(List.of());
        when(eventGroupService.finalDrawn(anyLong())).thenReturn(false);
    }

    private MarkRowDTO onlyRow(Event event) {
        MarkSheetDTO sheet = service.getMarkSheet(event.getId(), null, null, EventStage.HEAT);
        assertEquals(1, sheet.getRows().size(), "one athlete, one row: " + sheet.getRows());
        return sheet.getRows().get(0);
    }

    // ------------------------------------------------------ the target itself

    @Test
    @DisplayName("a track row shows the standard in seconds and is below it a hair over")
    void aTrackRowIsBelowAPastTheStandard() {
        Event fourHundred = event(3L, Event.EventType.RUN_400M, "60");
        entered(fourHundred, "60.001");

        MarkRowDTO row = onlyRow(fourHundred);

        assertEquals(0, new BigDecimal("60").compareTo(row.getStandard()),
                "the target travels on the row");
        assertEquals("60 s", row.getStandardLabel(), "in the event's own unit");
        assertTrue(row.getBelowStandard(), "a hair over the standard is below it");
    }

    @Test
    @DisplayName("a track mark exactly on the standard is met — the boundary")
    void aTrackMarkOnTheStandardIsNotBelow() {
        Event fourHundred = event(3L, Event.EventType.RUN_400M, "60");
        entered(fourHundred, "60.000");

        MarkRowDTO row = onlyRow(fourHundred);

        assertFalse(row.getBelowStandard(), "on the standard is met, not below it");
        assertEquals("60 s", row.getStandardLabel());
    }

    @Test
    @DisplayName("a field mark exactly on the standard is met, and a hair under is below")
    void aFieldMarkOnTheStandardIsMetAndUnderItIsBelow() {
        Event shotPut = event(9L, Event.EventType.SHOT_PUT, "12");
        entered(shotPut, "12.000");

        MarkRowDTO onTheLine = onlyRow(shotPut);
        assertFalse(onTheLine.getBelowStandard(), "on the standard is met");
        assertEquals("12 M", onTheLine.getStandardLabel());

        Event under = event(10L, Event.EventType.SHOT_PUT, "12");
        entered(under, "11.999");
        assertTrue(onlyRow(under).getBelowStandard(), "a hair under it is below");
    }

    @Test
    @DisplayName("a distance event reads its standard in minutes and seconds, exactly as its marks do")
    void aDistanceStandardIsShownForAnOverFourHundredRace() {
        Event eightHundred = event(4L, Event.EventType.RUN_800M, "135");
        entered(eightHundred, "135.5");

        MarkRowDTO row = onlyRow(eightHundred);

        assertEquals("135 s", row.getStandardLabel());
        assertTrue(row.getBelowStandard(), "135.5 is over the 135s standard");
        assertEquals(2, row.getMinutes(), "and the mark still reads as a stopped time");
    }

    // ----------------------------------------------------------- never below

    @Test
    @DisplayName("a row with no mark is never below standard, but still shows the target")
    void anUnmarkedRowIsNotBelow() {
        Event shotPut = event(9L, Event.EventType.SHOT_PUT, "12");
        entered(shotPut, null);

        MarkRowDTO row = onlyRow(shotPut);

        assertNull(row.getMark(), "nothing recorded yet");
        assertFalse(row.getBelowStandard(), "a blank is not a failure");
        assertEquals("12 M", row.getStandardLabel(),
                "the target is still shown, which is what a helper needs before the throw");
    }

    @Test
    @DisplayName("an event with no standard judges nothing, however slow the mark")
    void anEventWithNoStandardIsNeverBelow() {
        Event hundred = event(5L, Event.EventType.RUN_100M, null);
        entered(hundred, "99.999");

        MarkRowDTO row = onlyRow(hundred);

        assertNull(row.getStandard(), "no standard on the event, none on the row");
        assertNull(row.getStandardLabel());
        assertFalse(row.getBelowStandard());
        assertEquals(0, new BigDecimal("99.999").compareTo(row.getMark()),
                "and the mark is exactly what it always was");
    }

    // ------------------------------------------------------------- the relay

    private List<RelayTeamMember> fullTeam(RelayTeam team, long firstMemberId, long firstUserId) {
        List<RelayTeamMember> legs = new ArrayList<>(4);
        for (int leg = 1; leg <= 4; leg++) {
            legs.add(RelayTeamMember.builder()
                    .id(firstMemberId + leg - 1)
                    .team(team)
                    .user(user(firstUserId + leg - 1))
                    .leg(leg)
                    .build());
        }
        return legs;
    }

    @Test
    @DisplayName("a relay row carries no standard and is never below one, whatever the row holds")
    void aRelayRowCarriesNoStandard() {
        // A number left on a relay row by an older build: the grid must not publish
        // it, because a relay is its own category, run and scored by team, and only
        // the track races of 400M and over and the field events carry a standard.
        Event relay = Event.builder()
                .id(RELAY_ID)
                .name("Boys 4x100M Relay · A Grade")
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.RELAY)
                .sex(Sex.MALE)
                .grade(Grade.A)
                .standard(new BigDecimal("50"))
                .eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512)
                .groupSize(24)
                .enabled(true)
                .relayTeamKind(RelayTeamKind.FORM)
                .build();
        RelayTeam a = RelayTeam.builder().id(TEAM_A).event(relay).kind(RelayTeamKind.FORM)
                .teamKey("5A").label("5A").build();
        RelayTeam b = RelayTeam.builder().id(TEAM_B).event(relay).kind(RelayTeamKind.FORM)
                .teamKey("5B").label("5B").build();
        List<RelayTeamMember> legs = new ArrayList<>(fullTeam(a, 1L, 61L));
        legs.addAll(fullTeam(b, 5L, 65L));

        when(eventRepository.findById(RELAY_ID)).thenReturn(Optional.of(relay));
        when(relayTeamRepository.findByEventIdOrderByIdAsc(RELAY_ID)).thenReturn(List.of(a, b));
        when(relayTeamMemberRepository.findForEventWithUser(RELAY_ID)).thenReturn(legs);
        when(relayTeamMemberRepository.findByTeamIdOrderByLegAsc(TEAM_A))
                .thenReturn(legs.subList(0, 4));
        when(relayTeamMemberRepository.findByTeamIdOrderByLegAsc(TEAM_B))
                .thenReturn(legs.subList(4, 8));
        when(studentRepository.findWithUserByUserIdIn(any()))
                .thenReturn(legs.stream().map(member -> roster(member.getUser())).toList());

        List<MarkRowDTO> rows = service.getMarkSheet(RELAY_ID, null, null, EventStage.HEAT).getRows();

        assertEquals(2, rows.size(), "one row per team");
        for (MarkRowDTO row : rows) {
            assertNull(row.getStandard(), "a relay row has no standard: " + row.getTeamLabel());
            assertNull(row.getStandardLabel());
            assertFalse(row.getBelowStandard(), "and is never below one");
        }
    }
}
