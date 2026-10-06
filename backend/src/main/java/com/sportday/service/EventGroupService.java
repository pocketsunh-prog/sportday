package com.sportday.service;

import com.sportday.dto.EnrollmentDTO;
import com.sportday.dto.EventGroupDTO;
import com.sportday.dto.EventRecordDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventGroup;
import com.sportday.entity.EventResult;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

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
    private final com.sportday.repository.RelayTeamMemberRepository relayTeamMemberRepository;
    private final RelayReadiness relayReadiness;

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
        beginRender();
        try {
            List<EventGroup> groups = groupsOf(eventId);
            List<String> relayLabels = relayTeamLabelsOf(groups);
            return groups.stream().map(group -> withAthletes(group, false, relayLabels)).toList();
        } finally {
            endRender();
        }
    }

    /** One group including its full roster — used to render a marking sheet. */
    @Transactional(readOnly = true)
    public EventGroupDTO getGroup(Long groupId) {
        EventGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found with id: " + groupId));
        beginRender();
        try {
            return withAthletes(group, true, relayTeamLabelsOf(List.of(group)));
        } finally {
            endRender();
        }
    }

    /** All groups of an event with their rosters, heats first and then the final. */
    @Transactional(readOnly = true)
    public List<EventGroupDTO> getGroupsWithAthletes(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event not found with id: " + eventId);
        }
        beginRender();
        try {
            List<EventGroup> groups = groupsOf(eventId);
            List<String> relayLabels = relayTeamLabelsOf(groups);
            return groups.stream().map(group -> withAthletes(group, true, relayLabels)).toList();
        } finally {
            endRender();
        }
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
     * The final of an event, if one has been drawn.
     *
     * <p>The final is group number 0 of the event, and it exists only once the draw
     * has run — which is why every question of the form "may this be worked on
     * yet?" ends up here. {@link FinalStageGuard} owns the words of the refusal;
     * this is the lookup behind it.</p>
     */
    @Transactional(readOnly = true)
    public Optional<EventGroup> finalOf(Long eventId) {
        return groupRepository.findFirstByEventIdAndStage(eventId, EventStage.FINAL);
    }

    /** True when the event has a final drawn, so its marks and its sheet exist. */
    @Transactional(readOnly = true)
    public boolean finalDrawn(Long eventId) {
        return finalOf(eventId).isPresent();
    }

    /**
     * Every group across all matching events, with rosters — used for the
     * whole-school print run.
     *
     * <p>A draft relay event contributes nothing: it is not on the programme, its
     * teams are still being collected rather than drawn into heats, and the school
     * would not want an unrun race in a whole-school print run.</p>
     *
     * <p>A <strong>relay that is not ready</strong> contributes nothing either, and
     * for the same reason: a race with one team, or with a team still short of its
     * runners, must not appear on a helper's sheet because its marks cannot be entered
     * yet ({@link RelayReadiness}). Skipping it here rather than refusing the whole run
     * is deliberate — a school printing the programme must not be stopped by one
     * half-built relay, and the relay is simply absent from the output. A run that asks
     * for that one relay on purpose is refused with the reason instead, in
     * {@code EventGroupController}.</p>
     */
    @Transactional(readOnly = true)
    public List<EventGroupDTO> getGroupsWithAthletesFiltered(Sex sex, EventCategory category) {
        // Sorted with EVENT_ORDER rather than ORDER BY: the type column is a MySQL
        // ENUM whose declaration order puts RUN_60M last.
        List<Event> events = eventRepository.findAll().stream()
                .filter(e -> !e.isDraft())
                .filter(relayReadiness::isReady)
                .filter(e -> sex == null || e.getSex() == sex)
                .filter(e -> category == null || e.getCategoryOrDefault() == category)
                .sorted(EventService.EVENT_ORDER)
                .toList();
        beginRender();
        try {
            List<EventGroupDTO> result = new ArrayList<>();
            for (Event event : events) {
                result.addAll(getGroupsWithAthletes(event.getId()));
            }
            return result;
        } finally {
            endRender();
        }
    }

    /** Heats in heat order, then the final, whichever group numbers they carry. */
    private static final Comparator<EventGroup> GROUP_ORDER = Comparator
            .comparingInt((EventGroup group) -> group.getStageOrDefault() == EventStage.FINAL ? 1 : 0)
            .thenComparingInt(group -> group.getGroupNumber() == null ? Integer.MAX_VALUE : group.getGroupNumber());

    private List<EventGroupDTO> describe(List<EventGroup> groups) {
        List<String> relayLabels = relayTeamLabelsOf(groups);
        return groups.stream().map(group -> withAthletes(group, false, relayLabels)).toList();
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

    private EventGroupDTO withAthletes(EventGroup group, boolean includeAthletes,
                                       List<String> relayLabels) {
        EventGroupDTO dto = EventGroupDTO.from(group);
        withRecord(dto, group.getEvent());
        withStandard(dto, group.getEvent());
        // The event's own teams travel with every group of a relay, whether or not
        // the roster is carried: they are what the sheet's lines are, and they are
        // not the same list as the entrants. Null for an individual event.
        if (!relayLabels.isEmpty()) {
            dto.setRelayTeamLabels(relayLabels);
        }
        if (!includeAthletes) {
            return dto;
        }
        dto.setAthletes(athletesOf(group));
        return dto;
    }

    /**
     * The relay teams of one event, as the sheet names them — one label per team, in
     * the order the teams were made, which is the order the mark grid lists them in.
     *
     * <p><strong>The teams belong to the event, not to the heat.</strong> They are
     * read here rather than taken off the group's roster, because a relay's teams are
     * one per class (or per house) of the event and the group's roster is the
     * students who entered it: for a form relay those are different sets, and a sheet
     * drawn from the roster printed the entrants' names where the team's name belongs.
     * The whole print run shares one read per event, so a relay with several heats
     * still costs a single query.</p>
     *
     * <p>Empty for an individual event — no query at all — and for a relay with no
     * teams yet, which the sheet draws as it always did (and which the readiness gate
     * refuses to print in any case). A team with no runners is not listed: a team
     * nobody has filled has no label to read from its legs, and a relay cannot be
     * marked until every team is full.</p>
     */
    private List<String> relayTeamLabelsOf(List<EventGroup> groups) {
        if (groups.isEmpty()) {
            return List.of();
        }
        Event event = groups.get(0).getEvent();
        if (event == null || !event.isRelay() || event.getId() == null) {
            return List.of();
        }
        Set<String> labels = new LinkedHashSet<>();
        for (com.sportday.entity.RelayTeamMember member
                : relayTeamMemberRepository.findForEventWithUser(event.getId())) {
            // Ordered by team id and leg, so a team's four legs collapse into its one
            // line and the teams keep the order they were created in.
            String label = member.getTeam() == null ? null : member.getTeam().getLabel();
            if (label != null && !label.isBlank()) {
                labels.add(label.trim());
            }
        }
        return List.copyOf(labels);
    }

    // ------------------------------------ the school record and the required standard

    /**
     * Puts the event's required standard on the sheet's DTO, spelled once.
     *
     * <p>Only the events that carry one get a label — asked through
     * {@link Event.EventType#carriesAStandard()}, the one rule that answers it —
     * and everything else is left null, so the renderer prints no line and nothing
     * already on paper changes. The label reads as the school reads a mark: the
     * number in the event's own unit, {@code 64.123 s} or {@code 12.5 M}.</p>
     */
    private void withStandard(EventGroupDTO dto, Event event) {
        if (event == null || event.getType() == null
                || !event.getType().carriesAStandard()
                || event.getStandard() == null) {
            return;
        }
        dto.setStandardLabel(event.getStandard().stripTrailingZeros().toPlainString()
                + " " + event.getType().getDefaultUnit());
    }

    /**
     * The school record this sheet prints in its header, put on the group as it is
     * built rather than looked up by the PDF renderer.
     *
     * <p>A record belongs to the <em>event</em> — its type, division and grade — so
     * every group of an event carries the same one and the sheet can print it for
     * nothing. The record is read at most once per event per render: a whole-
     * programme print run of every group of every event pays one indexed lookup per
     * event that has a record, never one per sheet and certainly not one per athlete
     * row. An event with no record is a miss rather than a cached blank — that is
     * the cheap case, and it keeps the blank out of the cache.</p>
     *
     * <p>An event with no record carries none, and the sheet leaves the line out
     * rather than printing a dash or the word "none". A record that only exists
     * because of this season's results counts as no record — see below.</p>
     */
    private void withRecord(EventGroupDTO dto, Event event) {
        if (event == null || event.getType() == null || event.getSex() == null || event.getGrade() == null) {
            return;
        }
        EventRecordDTO record = renderRecords.get()
                .computeIfAbsent(event.getType() + "|" + event.getSex() + "|" + event.getGrade(),
                        key -> recordService.record(event.getType(), event.getSex(), event.getGrade()));
        /*
         * Which mark is "the record to beat" — and why it is not `record.getMark()`.
         *
         * `mark` is the STANDING record: the service keeps it equal to the better of
         * the entered baseline and the best result recorded so far. During the meeting
         * that means it is today's leading performance, so printing it under 紀錄 would
         * tell a helper that the school record is a time run twenty minutes ago by
         * someone standing in the next lane — and it would creep upward all afternoon
         * as the results came in.
         *
         * The record a helper needs is the one that stood BEFORE this season's results:
         * the previous mark, or a baseline typed in from the school's own history. With
         * neither there is no record, and the sheet prints no line rather than
         * presenting today's best as one.
         */
        boolean fromPrevious = record != null && record.getPreviousMark() != null;
        BigDecimal mark = fromPrevious
                ? record.getPreviousMark()
                : (record == null ? null : record.getManualMark());
        if (mark == null) {
            return;
        }
        String unit = fromPrevious ? record.getUnit() : record.getManualUnit();
        dto.setRecordDisplayMark(MarkFormatter.formatWithUnit(mark, event.getType(), unit));
        dto.setRecordHolderName(fromPrevious
                ? record.getPreviousHolderName() : record.getManualHolderName());
        dto.setRecordAchievedOn(fromPrevious
                ? record.getPreviousAchievedOn() : record.getManualAchievedOn());
    }

    /**
     * The records already read during one render, so two groups of the same event
     * share a lookup. Held on the thread doing the render and cleared when the
     * outermost read returns, so nothing is cached between requests and a record
     * set while a print run is running is never served stale.
     *
     * <p>An event group service is a singleton and a print run is long, so this is
     * deliberately a thread-local rather than a field.</p>
     */
    private final ThreadLocal<Map<String, EventRecordDTO>> renderRecords =
            ThreadLocal.withInitial(HashMap::new);

    /**
     * Marks the depth of nested reads on this thread; the outermost one clears the
     * record cache as it returns, so the cache lives exactly as long as one render.
     */
    private final ThreadLocal<Integer> renderDepth = ThreadLocal.withInitial(() -> 0);

    private void beginRender() {
        renderDepth.set(renderDepth.get() + 1);
    }

    private void endRender() {
        int depth = renderDepth.get() - 1;
        if (depth <= 0) {
            renderDepth.remove();
            renderRecords.remove();
        } else {
            renderDepth.set(depth);
        }
    }

    /**
     * The group's athletes as {@link EnrollmentDTO}s, whichever stage it is.
     *
     * <p>For the final the athlete's own event entry supplies the name, class and
     * house, while the group and lane come from the final itself — and each athlete
     * also carries the heat they ran to get there, so the final's marking sheet can
     * print that heat record beside the box the final is written in. It takes one
     * extra query for the whole roster, and a heat's own roster has no earlier
     * stage, so it carries none.</p>
     */
    @Transactional(readOnly = true)
    /**
     * Which team each athlete of a relay event runs for, by user id.
     *
     * <p>One query for the whole event rather than one per roster row, so a marking
     * sheet for a sixty-strong relay costs a single read. An athlete who is on no team
     * — a relay that has not been divided yet — simply has no entry, and the sheet
     * leaves its team column blank.</p>
     */
    private Map<Long, String> relayTeamLabelsByUser(Long eventId) {
        Map<Long, String> byUser = new HashMap<>();
        for (com.sportday.entity.RelayTeamMember member
                : relayTeamMemberRepository.findForEventWithUser(eventId)) {
            if (member.getUser() != null && member.getTeam() != null
                    && member.getTeam().getLabel() != null) {
                byUser.put(member.getUser().getId(), member.getTeam().getLabel());
            }
        }
        return byUser;
    }

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
        Map<Long, EventResult> heatResults = group.isFinal()
                ? heatResultsByUser(group.getEvent().getId())
                : Map.of();
        // A relay is run by teams, so a relay sheet says which team each line is on.
        // One query for the event, and none at all for an individual event.
        Map<Long, String> relayTeams = group.getEvent().isRelay()
                ? relayTeamLabelsByUser(group.getEvent().getId())
                : Map.of();

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
            if (group.isFinal()) {
                EventResult heat = heatResults.get(member.userId());
                Event.EventType type = group.getEvent().getType();
                dto.setHeatMark(heat == null ? null : heat.getMark());
                dto.setHeatOutcome(heat == null ? null : heat.getOutcomeOrDefault().name());
                dto.setHeatDisplayMark(MarkFormatter.formatRecord(heat, type,
                        type == null ? null : type.getDefaultUnit()));
            }
            dto.setRelayTeamLabel(relayTeams.get(member.userId()));
            athletes.add(dto);
        }
        return athletes;
    }

    /**
     * The heat results of an event by athlete — one query for a whole roster, so a
     * final sheet never looks a mark up per row. A duplicate row (which the unique
     * key forbids) would keep the first.
     */
    private Map<Long, EventResult> heatResultsByUser(Long eventId) {
        Map<Long, EventResult> byUser = new HashMap<>();
        for (EventResult result : resultRepository.findByEventIdAndStageOrderByMarkAsc(
                eventId, EventStage.HEAT)) {
            if (result.getUser() != null) {
                byUser.putIfAbsent(result.getUser().getId(), result);
            }
        }
        return byUser;
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
