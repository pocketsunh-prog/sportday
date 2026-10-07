package com.sportday.service;

import com.sportday.dto.RelayEventTeamsDTO;
import com.sportday.dto.RelayTeamDTO;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Grade;
import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamKind;
import com.sportday.entity.RelayTeamMember;
import com.sportday.entity.Sex;
import com.sportday.entity.Student;
import com.sportday.entity.User;
import com.sportday.repository.EventRepository;
import com.sportday.repository.RelayTeamMemberRepository;
import com.sportday.repository.RelayTeamRepository;
import com.sportday.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A form relay, once it is a <strong>class</strong> relay: the teams a derive makes,
 * a runner taken from the class the team is named for, and the 4 + 1 squad the school
 * asked for.
 *
 * <p>This is deliberately a second, focused test class beside
 * {@code RelayTeamServiceTest}. That class still builds its teams by hand with the
 * old form keys — {@code team("1")} — so it cannot notice the one thing this file
 * exists for: a team a <em>derive</em> made is keyed with a <strong>class name</strong>
 * ({@code 1A}), and a runner has to be accepted into <em>that</em> team. A suite that
 * only ever names runners into hand-built form teams stays green while no real class
 * team can be filled at all.</p>
 *
 * <p>The repositories are backed by in-memory lists, as in the other relay tests, so
 * the derive, the selection and the re-derive run against real read-then-write
 * behaviour rather than a scripted sequence of mock answers.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayTeamClassTeamTest {

    private static final Long EVENT_ID = 42L;

    @Mock private RelayTeamRepository relayTeamRepository;
    @Mock private RelayTeamMemberRepository relayTeamMemberRepository;
    @Mock private EventRepository eventRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private TeacherClassService teacherClassService;
    /** The event's entries: no confirmed entry, so the board has no applicants. */
    @Mock private com.sportday.repository.EnrollmentRepository enrollmentRepository;

    private RelayTeamService service;

    private final List<RelayTeam> teams = new ArrayList<>();
    private final List<RelayTeamMember> members = new ArrayList<>();
    private final Map<Long, Student> students = new LinkedHashMap<>();
    private long nextTeamId = 1;
    private long nextMemberId = 1;
    private long nextStudentId = 100;

    private Event event;

    @BeforeEach
    void setUp() {
        service = new RelayTeamService(relayTeamRepository, relayTeamMemberRepository,
                eventRepository, studentRepository, teacherClassService, enrollmentRepository);
        teams.clear();
        members.clear();
        students.clear();
        nextTeamId = 1;
        nextMemberId = 1;
        nextStudentId = 100;

        // The caller is an administrator for the rename paths; the selection paths
        // never consult the class rule here, because requireMayHelp is a no-op stub.
        when(teacherClassService.isAdmin()).thenReturn(true);

        // 1A runs two athletes, 1B one, 2A one, 10B one — and 10B is the trap a plain
        // string order falls into ("10B" sorts before "1A").
        student(1L, "1A", "Red");
        student(2L, "1A", "Blue");
        student(3L, "1B", "Blue");
        student(4L, "2A", "Green");
        student(5L, "10B", "Yellow");

        event = Event.builder()
                .id(EVENT_ID)
                .name("Boys 4x100M Relay · B Grade")
                .type(Event.EventType.RELAY_4X100M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .grade(Grade.B)
                .eventDate(LocalDate.of(2026, 10, 1))
                .maxParticipants(512)
                .groupSize(24)
                .enabled(true)
                .relayTeamKind(RelayTeamKind.FORM)
                .build();

        wireRepositories();
    }

    // ============================================================== fixtures

    private Student student(long userId, String className, String house) {
        User user = User.builder().id(userId).username("S%04d".formatted(userId))
                .fullName("Athlete " + userId).role(User.Role.STUDENT).enabled(true).build();
        Student roster = Student.builder()
                .id(userId)
                .user(user)
                .studentId("S%04d".formatted(userId))
                .name("Athlete " + userId)
                .dob(LocalDate.of(2011, 5, 5))
                .sex(Sex.MALE)
                .className(className)
                .classNumber(1)
                .house(house)
                .grade(Grade.B)
                .enabled(true)
                .build();
        students.put(userId, roster);
        return roster;
    }

    /** One more athlete of a class, for filling a team past its four legs. */
    private Student classmate(String className, String house) {
        return student(nextStudentId++, className, house);
    }

    private void wireRepositories() {
        when(eventRepository.findById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (EVENT_ID.equals(id)) {
                return Optional.of(event);
            }
            return teams.stream().map(RelayTeam::getEvent)
                    .filter(candidate -> candidate != null && id.equals(candidate.getId()))
                    .findFirst();
        });

        when(relayTeamRepository.findById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return teams.stream().filter(team -> id.equals(team.getId())).findFirst();
        });
        when(relayTeamRepository.findByEventIdOrderByIdAsc(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return teams.stream()
                    .filter(team -> team.getEvent() != null && id.equals(team.getEvent().getId()))
                    .collect(Collectors.toList());
        });
        when(relayTeamRepository.save(any())).thenAnswer(invocation -> {
            RelayTeam saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(nextTeamId++);
            }
            if (!teams.contains(saved)) {
                teams.add(saved);
            }
            return saved;
        });
        when(relayTeamRepository.countByEventId(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return teams.stream()
                    .filter(team -> team.getEvent() != null && id.equals(team.getEvent().getId()))
                    .count();
        });

        when(relayTeamMemberRepository.save(any())).thenAnswer(invocation -> {
            RelayTeamMember saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(nextMemberId++);
            }
            if (!members.contains(saved)) {
                members.add(saved);
            }
            return saved;
        });
        when(relayTeamMemberRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<RelayTeamMember> saved = invocation.getArgument(0);
            for (RelayTeamMember member : saved) {
                if (member.getId() == null) {
                    member.setId(nextMemberId++);
                }
                if (!members.contains(member)) {
                    members.add(member);
                }
            }
            return saved;
        });
        when(relayTeamMemberRepository.findByTeamIdOrderByLegAsc(anyLong()))
                .thenAnswer(invocation -> membersOf(invocation.getArgument(0)));
        when(relayTeamMemberRepository.countByTeamId(anyLong()))
                .thenAnswer(invocation -> (long) membersOf(invocation.getArgument(0)).size());
        when(relayTeamMemberRepository.existsByTeamIdAndLeg(anyLong(), any()))
                .thenAnswer(invocation -> membersOf(invocation.getArgument(0)).stream()
                        .anyMatch(member -> member.getLeg().equals(invocation.getArgument(1))));
        when(relayTeamMemberRepository.findByTeamIdAndUserId(anyLong(), anyLong()))
                .thenAnswer(invocation -> membersOf(invocation.getArgument(0)).stream()
                        .filter(member -> member.getUser() != null
                                && member.getUser().getId().equals(invocation.getArgument(1)))
                        .findFirst());
        when(relayTeamMemberRepository.findForEventAndUser(anyLong(), anyLong()))
                .thenAnswer(invocation -> membersOfEvent(invocation.getArgument(0)).stream()
                        .filter(member -> member.getUser() != null
                                && member.getUser().getId().equals(invocation.getArgument(1)))
                        .collect(Collectors.toList()));
        when(relayTeamMemberRepository.findForEventWithUser(anyLong()))
                .thenAnswer(invocation -> membersOfEvent(invocation.getArgument(0)));
        doAnswer(invocation -> {
            members.remove(invocation.getArgument(0));
            return null;
        }).when(relayTeamMemberRepository).delete(any());

        when(studentRepository.findWithUserByUserId(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(students.get(invocation.getArgument(0))));
        when(studentRepository.findWithUserByUserIdIn(any())).thenAnswer(invocation -> {
            List<Long> ids = new ArrayList<>(invocation.getArgument(0));
            return ids.stream().map(students::get).filter(Objects::nonNull)
                    .collect(Collectors.toList());
        });
        when(studentRepository.findActiveBySexAndGrade(any(), any())).thenAnswer(invocation -> {
            Sex sex = invocation.getArgument(0);
            Grade grade = invocation.getArgument(1);
            return students.values().stream()
                    .filter(student -> student.getSex() == sex && student.getGrade() == grade)
                    .filter(student -> Boolean.TRUE.equals(student.getEnabled()))
                    .collect(Collectors.toList());
        });
    }

    private List<RelayTeamMember> membersOf(Long teamId) {
        return members.stream()
                .filter(member -> member.getTeam() != null && teamId.equals(member.getTeam().getId()))
                .sorted(Comparator.comparingInt(member -> member.getLeg() == null
                        ? Integer.MAX_VALUE : member.getLeg()))
                .collect(Collectors.toList());
    }

    private List<RelayTeamMember> membersOfEvent(Long eventId) {
        return members.stream()
                .filter(member -> member.getTeam() != null
                        && member.getTeam().getEvent() != null
                        && eventId.equals(member.getTeam().getEvent().getId()))
                .collect(Collectors.toList());
    }

    private RelayEventTeamsDTO board() {
        return service.getBoard(EVENT_ID);
    }

    private RelayTeamDTO teamNamed(String name) {
        return board().getTeams().stream()
                .filter(team -> name.equals(team.getLabel()) || name.equals(team.getTeamKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no team named " + name + " in "
                        + board().getTeams().stream().map(RelayTeamDTO::getLabel).toList()));
    }

    private List<String> teamKeys() {
        return board().getTeams().stream().map(RelayTeamDTO::getTeamKey)
                .collect(Collectors.toList());
    }

    // ================================================= derivation by class

    @Test
    @DisplayName("a class relay scoped by grade derives one team per class — the older rule, "
            + "unchanged where there is no form")
    void derivesOneTeamPerClassInSchoolOrder() {
        // The event carries no form, so it is scoped by its grade and keeps one team per
        // class of it. A relay scoped to a FORM makes two teams instead — see
        // RelayTeamFormRelayTwoTeamsTest.
        service.deriveTeams(EVENT_ID, false);

        assertEquals(List.of("1A", "1B", "2A", "10B"), teamKeys());
        assertEquals(List.of("1A", "1B", "2A", "10B"),
                board().getTeams().stream().map(RelayTeamDTO::getLabel).toList());
    }

    @Test
    @DisplayName("a runner may be named into the class team a derive made — the class is the team")
    void aDerivedClassTeamTakesItsOwnClass() {
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDTO classOneA = teamNamed("1A");

        RelayTeamDTO filled = service.addRunner(classOneA.getId(), 1L, null);

        assertEquals(1, filled.getMemberCount().intValue());
        assertEquals("S0001", filled.getMembers().get(0).getStudentId());
        assertEquals(1, filled.getMembers().get(0).getLeg().intValue());
        assertFalse(filled.getMembers().get(0).getReserve());
    }

    @Test
    @DisplayName("a class team refuses a runner from another class, and the refusal names both")
    void aDerivedClassTeamRefusesAnotherClass() {
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDTO classOneA = teamNamed("1A");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(classOneA.getId(), 4L, null));

        assertTrue(error.getMessage().contains("1A"), error.getMessage());
        assertTrue(error.getMessage().contains("2A"), error.getMessage());
        assertTrue(membersOf(classOneA.getId()).isEmpty());
    }

    @Test
    @DisplayName("a house relay derives one team per house, named with the event's grade")
    void derivesOneTeamPerHouseWithItsGrade() {
        event.setRelayTeamKind(RelayTeamKind.HOUSE);

        service.deriveTeams(EVENT_ID, false);

        // The keys stay the register's house names — that is what a runner is matched
        // on — and the names the school reads carry the grade.
        assertEquals(List.of("Blue", "Green", "Red", "Yellow"), teamKeys());
        assertEquals(List.of("B Grade Blue", "B Grade Green", "B Grade Red", "B Grade Yellow"),
                board().getTeams().stream().map(RelayTeamDTO::getLabel).toList());
    }

    // ================================================= four runners, one reserve

    @Test
    @DisplayName("the fifth runner is the reserve and there is no sixth")
    void fourRunnersAndOneReserve() {
        event.setRelayReservesAllowed(true);
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDTO classOneA = teamNamed("1A");
        List<Long> squad = new ArrayList<>();
        squad.add(1L);
        squad.add(2L);
        for (int i = 0; i < 4; i++) {
            squad.add(classmate("1A", "Red").getUser().getId());
        }

        for (Long userId : squad.subList(0, 4)) {
            service.addRunner(classOneA.getId(), userId, null);
        }
        RelayTeamDTO four = teamNamed("1A");
        assertTrue(four.getComplete(), "four runners is a complete team");
        assertEquals(4, four.getMemberCount().intValue());
        assertTrue(four.getMembers().stream()
                .noneMatch(member -> Boolean.TRUE.equals(member.getReserve())));
        assertEquals(5, four.getMemberCap().intValue(), "four legs and one backup");

        RelayTeamDTO five = service.addRunner(classOneA.getId(), squad.get(4), null);
        assertEquals(5, five.getMemberCount().intValue());
        assertTrue(five.getMembers().get(4).getReserve(), "the fifth runner is the reserve");
        assertTrue(five.getComplete(), "a reserve does not make a team incomplete");

        Student sixth = classmate("1A", "Red");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.addRunner(classOneA.getId(), sixth.getUser().getId(), null));
        assertTrue(error.getMessage().contains("full squad of 5"), error.getMessage());
    }

    @Test
    @DisplayName("an under-filled team is saved, not refused, and reported incomplete")
    void anUnderFilledTeamIsReportedIncomplete() {
        event.setRelayReservesAllowed(true);
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDTO empty = teamNamed("1A");

        // A newly derived team has nobody in it at all, and that is a normal state:
        // the runners are still being collected.
        assertEquals(0, empty.getMemberCount().intValue());
        assertFalse(empty.getComplete());
        assertTrue(empty.getMembers().isEmpty());

        service.addRunner(empty.getId(), 1L, null);
        service.addRunner(empty.getId(), 2L, null);
        RelayTeamDTO two = teamNamed("1A");
        assertEquals(2, two.getMemberCount().intValue());
        assertFalse(two.getComplete());

        Long third = classmate("1A", "Red").getUser().getId();
        service.addRunner(empty.getId(), third, null);
        RelayTeamDTO three = teamNamed("1A");
        assertEquals(3, three.getMemberCount().intValue());
        assertFalse(three.getComplete(), "three of four runners is incomplete, not refused");

        // Taking one away again leaves three, and is still allowed: a helper may
        // legitimately correct a partial team while the runners are collected.
        RelayTeamDTO afterRemoval = service.removeRunner(empty.getId(), third);
        assertEquals(2, afterRemoval.getMemberCount().intValue());
        assertFalse(afterRemoval.getComplete());

        service.addRunner(empty.getId(), classmate("1A", "Red").getUser().getId(), null);
        service.addRunner(empty.getId(), classmate("1A", "Red").getUser().getId(), null);
        assertTrue(teamNamed("1A").getComplete(), "four runners completes it");
    }

    // ============================================================ renaming

    @Test
    @DisplayName("an administrator renames a team, and a re-derive leaves that name alone")
    void reDerivingKeepsARenamedTeam() {
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDTO classOneA = teamNamed("1A");
        service.addRunner(classOneA.getId(), 1L, null);

        RelayTeamDTO renamed = service.renameTeam(classOneA.getId(), "  1A Boys  ");

        assertEquals("1A Boys", renamed.getLabel());
        assertEquals("1A", renamed.getTeamKey(), "the key is the identity, not the name");
        assertTrue(renamed.getNameOverridden());
        assertEquals(1, renamed.getMemberCount().intValue(), "a rename does not disturb the runners");

        service.deriveTeams(EVENT_ID, false);

        RelayTeamDTO after = teamNamed("1A");
        assertEquals("1A Boys", after.getLabel(), "a typed name survives a re-derive");
        assertEquals("1A", after.getTeamKey());
        assertEquals(1, after.getMemberCount().intValue(), "and the runners are still there");
    }

    @Test
    @DisplayName("a name nobody typed is still refreshed to the name the roster gives")
    void reDerivingRefreshesALabelNobodyHasRenamed() {
        service.deriveTeams(EVENT_ID, false);
        RelayTeam classOneA = teams.stream()
                .filter(team -> "1A".equals(team.getTeamKey())).findFirst().orElseThrow();
        classOneA.setLabel("stale label");

        service.deriveTeams(EVENT_ID, false);

        assertEquals("1A", teamNamed("1A").getLabel());
        assertFalse(teamNamed("1A").getNameOverridden());
    }

    @Test
    @DisplayName("two teams of one race cannot share a name")
    void aDuplicateNameIsRefused() {
        service.deriveTeams(EVENT_ID, false);
        RelayTeamDTO classOneA = teamNamed("1A");
        RelayTeamDTO classOneB = teamNamed("1B");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.renameTeam(classOneB.getId(), "1a"));

        assertTrue(error.getMessage().contains("already the name of another team"),
                error.getMessage());
        assertEquals("1B", teamNamed("1B").getLabel(), "the refused rename changed nothing");

        // The same name as its own, in another case, is not a collision with itself.
        assertEquals("1a", service.renameTeam(classOneA.getId(), "1a").getLabel());
    }
}
