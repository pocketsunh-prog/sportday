package com.sportday.service;

import com.sportday.dto.EnrollmentDTO;
import com.sportday.dto.EventGroupDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventGroup;
import com.sportday.entity.EventStage;
import com.sportday.entity.FinalEntry;
import com.sportday.entity.Sex;
import com.sportday.entity.Student;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.FinalEntryRepository;
import com.sportday.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Splits the confirmed entries of an event into heats, and reads the field of a
 * heat or the final.
 *
 * <p>Group size comes from {@link Event#getGroupSize()} — 8 athletes for
 * 60/100/200/400 and 24 for 800 and above (and for the field events) — and an
 * administrator can override it per event. Each group is exactly what a helper
 * receives on one marking sheet.</p>
 *
 * <p>Short sprints gain a second stage: the fastest athletes from the heats form
 * a {@link EventStage#FINAL} group, drawn by
 * {@link FinalQualificationService}. A finalist stays in their heat, so the heat
 * sheets stay intact.</p>
 *
 * <p>Re-running allocation for an event discards the previous heats and rebuilds
 * them from the current entry list, so it is safe to call again after late
 * entries. It also discards the final, because a final drawn from a different set
 * of heats no longer means anything.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventGroupService {

    private final EventGroupRepository groupRepository;
    private final EventRepository eventRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentRepository studentRepository;
    private final FinalEntryRepository finalEntryRepository;
    private final RecordService recordService;
    private final EventResultRepository resultRepository;

    /**
     * Allocates (or re-allocates) the groups of an event.
     *
     * @param shuffle when true the entry order is randomised before chunking,
     *                which is the usual fair way to draw lanes; when false the
     *                athletes are seeded by class then class number so the same
     *                entries always produce the same heats
     */
    @Transactional
    public List<EventGroupDTO> allocateGroups(Long eventId, boolean shuffle) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + eventId));

        int groupSize = event.getGroupSize() == null || event.getGroupSize() <= 0
                ? event.getType().getDefaultGroupSize()
                : event.getGroupSize();

        // 1. Detach every entry from its old group, then drop the old groups.
        //
        //    Re-allocating the heats also drops the final, because a final drawn
        //    from a different set of heats no longer means anything. Heat marks are
        //    untouched; the previous final's marks are cleared by the final service.
        List<Enrollment> entries = enrollmentRepository.findConfirmedWithUserByEvent(
                eventId, Enrollment.EnrollmentStatus.CONFIRMED);
        for (Enrollment entry : entries) {
            entry.setEventGroup(null);
            entry.setLane(null);
        }
        enrollmentRepository.saveAll(entries);

        List<EventGroup> oldGroups = groupRepository.findByEventIdOrderByGroupNumberAsc(eventId);
        if (!oldGroups.isEmpty()) {
            // Final membership keys off the group, so it has to go first.
            for (EventGroup group : oldGroups) {
                if (group.isFinal()) {
                    finalEntryRepository.deleteByGroupId(group.getId());
                }
            }
            finalEntryRepository.flush();
            groupRepository.deleteAll(oldGroups);
            groupRepository.flush();
        }
        // Let any school record go of the final's marks before they are deleted,
        // then rebuild it from the heat marks that are still on file.
        recordService.detachForEvent(eventId);
        resultRepository.deleteByEventIdAndStage(eventId, EventStage.FINAL);
        recordService.recomputeAll();

        if (entries.isEmpty()) {
            log.info("No confirmed entries for event {} — nothing to group", eventId);
            return List.of();
        }

        // 2. Seed the running order.
        List<Enrollment> ordered = new ArrayList<>(entries);
        if (shuffle) {
            java.util.Collections.shuffle(ordered, new Random(eventId * 31L + ordered.size()));
        } else {
            Map<Long, Student> rosters = rosterByUser(ordered);
            ordered.sort(Comparator
                    .comparing((Enrollment e) -> {
                        Student student = rosters.get(e.getUser().getId());
                        return student == null ? "~" : student.getClassName();
                    })
                    .thenComparing(e -> {
                        Student student = rosters.get(e.getUser().getId());
                        return student == null || student.getClassNumber() == null
                                ? Integer.MAX_VALUE : student.getClassNumber();
                    })
                    .thenComparing(e -> e.getUser().getUsername()));
        }

        // 3. Cut the running order into heats of groupSize and assign lanes.
        List<EventGroup> groups = new ArrayList<>();
        int groupNumber = 1;
        for (int start = 0; start < ordered.size(); start += groupSize) {
            int end = Math.min(start + groupSize, ordered.size());
            List<Enrollment> heat = ordered.subList(start, end);

            EventGroup group = EventGroup.builder()
                    .event(event)
                    .groupNumber(groupNumber)
                    .stage(EventStage.HEAT)
                    .capacity(groupSize)
                    .athleteCount(heat.size())
                    .build();
            group = groupRepository.save(group);

            int lane = 1;
            for (Enrollment entry : heat) {
                entry.setEventGroup(group);
                entry.setLane(lane++);
            }
            enrollmentRepository.saveAll(heat);
            groups.add(group);
            groupNumber++;
        }

        log.info("Event {} ({}): {} entries split into {} group(s) of up to {}",
                eventId, event.getType().getDisplayName(), entries.size(), groups.size(), groupSize);
        return describe(groups);
    }

    /** Removes all groups from an event and detaches its entries. */
    @Transactional
    public void clearGroups(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event not found with id: " + eventId);
        }
        List<Enrollment> entries = enrollmentRepository.findConfirmedWithUserByEvent(
                eventId, Enrollment.EnrollmentStatus.CONFIRMED);
        for (Enrollment entry : entries) {
            entry.setEventGroup(null);
            entry.setLane(null);
        }
        enrollmentRepository.saveAll(entries);

        List<EventGroup> groups = groupRepository.findByEventIdOrderByGroupNumberAsc(eventId);
        if (!groups.isEmpty()) {
            for (EventGroup group : groups) {
                if (group.isFinal()) {
                    finalEntryRepository.deleteByGroupId(group.getId());
                }
            }
            finalEntryRepository.flush();
            groupRepository.deleteAll(groups);
        }
        recordService.detachForEvent(eventId);
        resultRepository.deleteByEventIdAndStage(eventId, EventStage.FINAL);
        recordService.recomputeAll();
        log.info("Cleared {} group(s) for event {}", groups.size(), eventId);
    }

    @Transactional(readOnly = true)
    public List<EventGroupDTO> getGroups(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event not found with id: " + eventId);
        }
        return groupsOf(eventId).stream().map(group -> withAthletes(group, false)).toList();
    }

    /** One group including its full roster — used to render a marking sheet. */
    @Transactional(readOnly = true)
    public EventGroupDTO getGroup(Long groupId) {
        EventGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found with id: " + groupId));
        return withAthletes(group, true);
    }

    /** All groups of an event with their rosters, heats first and then the final. */
    @Transactional(readOnly = true)
    public List<EventGroupDTO> getGroupsWithAthletes(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event not found with id: " + eventId);
        }
        return groupsOf(eventId).stream().map(group -> withAthletes(group, true)).toList();
    }

    /**
     * The groups of an event in the order the day is run: Heat 1..N, then the
     * final.
     *
     * <p>Sorted here rather than by {@code ORDER BY group_number}: the final is
     * number 0, so the database would put it before Heat 1 and the print run would
     * lead with the final sheet.</p>
     */
    private List<EventGroup> groupsOf(Long eventId) {
        return groupRepository.findByEventIdOrderByGroupNumberAsc(eventId).stream()
                .sorted(GROUP_ORDER)
                .toList();
    }

    @Transactional(readOnly = true)
    public EventGroup requireGroup(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found with id: " + groupId));
    }

    /**
     * Every group across all matching events, with rosters — used for the
     * whole-school print run.
     */
    @Transactional(readOnly = true)
    public List<EventGroupDTO> getGroupsWithAthletesFiltered(Sex sex, EventCategory category) {
        // Sorted with EVENT_ORDER rather than ORDER BY: the type column is a MySQL
        // ENUM whose declaration order puts RUN_60M last.
        List<Event> events = eventRepository.findAll().stream()
                .filter(e -> sex == null || e.getSex() == sex)
                .filter(e -> category == null || e.getCategoryOrDefault() == category)
                .sorted(EventService.EVENT_ORDER)
                .toList();
        List<EventGroupDTO> result = new ArrayList<>();
        for (Event event : events) {
            result.addAll(getGroupsWithAthletes(event.getId()));
        }
        return result;
    }

    /** Heats in heat order, then the final, whichever group numbers they carry. */
    private static final Comparator<EventGroup> GROUP_ORDER = Comparator
            .comparingInt((EventGroup group) -> group.getStageOrDefault() == EventStage.FINAL ? 1 : 0)
            .thenComparingInt(group -> group.getGroupNumber() == null ? Integer.MAX_VALUE : group.getGroupNumber());

    private List<EventGroupDTO> describe(List<EventGroup> groups) {
        return groups.stream().map(group -> withAthletes(group, false)).toList();
    }

    /** One athlete's place in a group. */
    public record GroupMember(Long userId, Integer lane, Integer seed, BigDecimal seedMark) {
    }

    /**
     * Who competes in a group.
     *
     * <p>A heat's field is its {@link Enrollment}s. The final's field is stored
     * separately in {@link FinalEntry}, because a finalist still belongs to the
     * heat they ran — moving their enrollment would empty the heat sheet.</p>
     */
    @Transactional(readOnly = true)
    public List<GroupMember> membersOf(EventGroup group) {
        if (group.isFinal()) {
            return finalEntryRepository.findByGroupIdOrderByLaneAsc(group.getId()).stream()
                    .filter(entry -> entry.getUser() != null)
                    .map(entry -> new GroupMember(entry.getUser().getId(), entry.getLane(),
                            entry.getSeed(), entry.getSeedMark()))
                    .toList();
        }
        return enrollmentRepository.findByGroupWithUserOrdered(group.getId()).stream()
                .filter(entry -> entry.getUser() != null)
                .map(entry -> new GroupMember(entry.getUser().getId(), entry.getLane(), null, null))
                .toList();
    }

    private EventGroupDTO withAthletes(EventGroup group, boolean includeAthletes) {
        EventGroupDTO dto = EventGroupDTO.from(group);
        if (!includeAthletes) {
            return dto;
        }
        dto.setAthletes(athletesOf(group));
        return dto;
    }

    /**
     * The group's athletes as {@link EnrollmentDTO}s, whichever stage it is.
     *
     * <p>For the final the athlete's own event entry supplies the name, class and
     * house, while the group and lane come from the final itself.</p>
     */
    @Transactional(readOnly = true)
    public List<EnrollmentDTO> athletesOf(EventGroup group) {
        List<GroupMember> members = membersOf(group);
        if (members.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = members.stream().map(GroupMember::userId).distinct().toList();
        Map<Long, Enrollment> entries = new HashMap<>();
        for (Enrollment entry : enrollmentRepository.findWithUserByEventAndUserIds(
                group.getEvent().getId(), userIds)) {
            if (entry.getUser() != null) {
                entries.put(entry.getUser().getId(), entry);
            }
        }
        Map<Long, Student> rosters = rosterByUser(new ArrayList<>(entries.values()));

        List<EnrollmentDTO> athletes = new ArrayList<>(members.size());
        for (GroupMember member : members) {
            Enrollment entry = entries.get(member.userId());
            Student roster = rosters.get(member.userId());
            if (entry == null) {
                continue;
            }
            EnrollmentDTO dto = EnrollmentDTO.from(entry, roster);
            // The final's group and lane win over the athlete's heat placement.
            dto.setGroupId(group.getId());
            dto.setGroupNumber(group.getGroupNumber());
            dto.setGroupLabel(group.getLabel());
            dto.setLane(member.lane());
            athletes.add(dto);
        }
        return athletes;
    }

    /** One query for all the rosters behind a set of entries. */
    private Map<Long, Student> rosterByUser(List<Enrollment> entries) {
        Map<Long, Student> rosters = new HashMap<>();
        if (entries.isEmpty()) {
            return rosters;
        }
        List<Long> userIds = entries.stream()
                .filter(e -> e.getUser() != null)
                .map(e -> e.getUser().getId())
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return rosters;
        }
        for (Student student : studentRepository.findWithUserByUserIdIn(userIds)) {
            if (student.getUser() != null) {
                rosters.put(student.getUser().getId(), student);
            }
        }
        return rosters;
    }
}
