package com.sportday.service;

import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventGroup;
import com.sportday.entity.EventResult;
import com.sportday.entity.EventStage;
import com.sportday.entity.FinalEntry;
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
import java.util.Set;

/**
 * Draws the final of an event from the heat results.
 *
 * <p>Short sprints — 60/100/200/400 — are run in two stages. Everyone runs a
 * heat, the marks are recorded, and the best eight go through to a final. This
 * service ranks the heat marks, creates the {@link EventStage#FINAL} group and
 * records who qualified and with what performance.</p>
 *
 * <p>Ranking follows the event: a <strong>track</strong> event is ordered by
 * fastest first, a <strong>field</strong> event by longest or highest first. Ties
 * are broken by student id so the same marks always produce the same final.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinalQualificationService {

    /** The final holds eight athletes unless the event says otherwise. */
    public static final int DEFAULT_FINAL_SIZE = 8;

    private final EventRepository eventRepository;
    private final EventGroupRepository groupRepository;
    private final EventResultRepository resultRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentRepository studentRepository;
    private final FinalEntryRepository finalEntryRepository;
    private final RecordService recordService;
    private final FinalStageGuard finalStageGuard;

    /**
     * One athlete in the qualification ranking.
     *
     * @param rank position by heat performance, 1-based — the best mark, which on a
     *             track is the fastest. This is also the final's <em>seed</em>: how
     *             the athlete got there, which is not the same number as the lane.
     * @param lane the lane the school's draw gives that rank in the final, or 0 for a
     *             rank that does not go through. {@link #drawnInto} fills it in from
     *             {@link #laneForRank(int)} for the athletes who qualify, so a client
     *             drawing a final is shown the lane it will actually use.
     */
    public record Qualifier(
            int rank,
            int lane,
            Long userId,
            String studentRef,
            String name,
            String grade,
            String className,
            Integer classNumber,
            String house,
            BigDecimal heatMark,
            String unit) {

        /** The same athlete, drawn into {@code lane} for the final. */
        Qualifier inLane(int lane) {
            return new Qualifier(rank, lane, userId, studentRef, name, grade, className,
                    classNumber, house, heatMark, unit);
        }
    }

    /** The final, and who is in it. */
    public record FinalSummary(
            Long eventId,
            String eventName,
            String eventTypeLabel,
            String category,
            String sheetSize,
            boolean shortSprint,
            int finalSize,
            int rankedAthletes,
            int qualified,
            boolean drawn,
            Long groupId,
            String groupLabel,
            /** Final marks removed because the final was redrawn. */
            int clearedFinalMarks,
            List<Qualifier> qualifiers,
            String note) {
    }

    // ------------------------------------------------------------- preview

    /** Who would go through right now, without changing anything. */
    @Transactional(readOnly = true)
    public FinalSummary preview(Long eventId, Integer limit) {
        Event event = requireEvent(eventId);
        requireAFinalIsPossible(event);
        int size = resolveSize(event, limit);
        List<Qualifier> ranked = rank(event);
        List<Qualifier> qualifiers = drawnInto(ranked, size);
        EventGroup existing = groupRepository.findFirstByEventIdAndStage(eventId, EventStage.FINAL).orElse(null);

        return describe(event, size, ranked.size(), qualifiers, existing != null, existing, 0, null);
    }

    // ------------------------------------------------------------ generate

    /**
     * Creates (or re-draws) the final from the current heat marks.
     *
     * <p>Re-drawing discards the previous final and any marks recorded in it,
     * because those belong to a field that no longer exists. Heat marks are never
     * touched.</p>
     */
    @Transactional
    public FinalSummary generate(Long eventId, Integer limit) {
        Event event = requireEvent(eventId);
        requireAFinalIsPossible(event);
        int size = resolveSize(event, limit);
        List<Qualifier> ranked = rank(event);
        if (ranked.isEmpty()) {
            throw new IllegalStateException(
                    "No heat results have been recorded for " + event.getName()
                            + " yet — enter the heat marks first, then draw the final.");
        }
        List<Qualifier> qualifiers = drawnInto(ranked, size);

        // Drop the previous final: its group, its field, and any marks recorded in it.
        int clearedMarks = clearFinal(eventId);

        EventGroup finalGroup = groupRepository.save(EventGroup.builder()
                .event(event)
                .groupNumber(EventGroup.FINAL_GROUP_NUMBER)
                .stage(EventStage.FINAL)
                .capacity(size)
                .athleteCount(qualifiers.size())
                .build());

        Map<Long, Student> rosters = rostersFor(qualifiers);
        List<FinalEntry> entries = new ArrayList<>(qualifiers.size());
        for (Qualifier qualifier : qualifiers) {
            entries.add(FinalEntry.builder()
                    .group(finalGroup)
                    .user(enrollmentUser(eventId, qualifier.userId()))
                    .lane(qualifier.lane())
                    // The seed is the entry order — how the athlete got here — while
                    // the lane is the draw. They are different numbers.
                    .seed(qualifier.rank())
                    .seedMark(qualifier.heatMark())
                    .seedUnit(qualifier.unit())
                    .build());
        }
        finalEntryRepository.saveAll(entries);

        log.info("Event {} '{}': final drawn with {} of {} ranked athletes (limit {}){}",
                eventId, event.getName(), qualifiers.size(), ranked.size(), size,
                clearedMarks > 0 ? ", clearing " + clearedMarks + " previous final mark(s)" : "");

        String note = qualifiers.size() < size
                ? "Only " + qualifiers.size() + " athlete(s) had a heat result, so the final is not full."
                : null;
        return describe(event, size, ranked.size(), qualifiers, true, finalGroup, clearedMarks, note);
    }

    /** Removes the final of an event, including any marks recorded in it. */
    @Transactional
    public int clearFinal(Long eventId) {
        // A school record may point at one of the final's marks. The reference has
        // to be let go before the rows can be deleted, and the record rebuilt
        // afterwards — it usually survives, because the heat mark that earned the
        // place is still on file.
        recordService.detachForEvent(eventId);
        int clearedMarks = resultRepository.deleteByEventIdAndStage(eventId, EventStage.FINAL);
        Optional<EventGroup> finalGroup = groupRepository.findFirstByEventIdAndStage(eventId, EventStage.FINAL);
        if (finalGroup.isPresent()) {
            EventGroup group = finalGroup.get();
            finalEntryRepository.deleteByGroupId(group.getId());
            finalEntryRepository.flush();
            groupRepository.delete(group);
            // Flush the DELETE before anything inserts the replacement final.
            // Hibernate orders inserts ahead of deletes within a flush, so without
            // this the new group (event_id, 0) collides with the old one still in
            // the table and the unique key rejects it.
            groupRepository.flush();
        }
        if (clearedMarks > 0) {
            recordService.recomputeAll();
        }
        return clearedMarks;
    }

    // ------------------------------------------------------------- ranking

    /**
     * The heat results in performance order. Only athletes still entered are
     * considered, so someone who withdrew cannot qualify — and neither can an
     * athlete who was absent or disqualified, because they have no performance to
     * rank.
     */
    private List<Qualifier> rank(Event event) {
        List<EventResult> heatResults = resultRepository
                .findByEventIdAndStageOrderByMarkAsc(event.getId(), EventStage.HEAT);
        if (heatResults.isEmpty()) {
            return List.of();
        }

        Set<Long> stillEntered = new LinkedHashSet<>();
        for (Enrollment enrollment : enrollmentRepository.findConfirmedWithUserByEvent(
                event.getId(), Enrollment.EnrollmentStatus.CONFIRMED)) {
            if (enrollment.getUser() != null) {
                stillEntered.add(enrollment.getUser().getId());
            }
        }

        // A track event is won by the smallest mark (a time); a field event by the
        // largest (a distance or height).
        Comparator<EventResult> performance = event.getCategoryOrDefault() == EventCategory.FIELD
                ? Comparator.comparing(EventResult::getMark).reversed()
                : Comparator.comparing(EventResult::getMark);

        List<EventResult> ordered = heatResults.stream()
                .filter(result -> result.getUser() != null && stillEntered.contains(result.getUser().getId()))
                // An athlete who was absent or disqualified did not produce a
                // performance, so they cannot be ranked and cannot qualify for the
                // final. A row with no mark at all is unrankable for the same reason.
                .filter(result -> !result.isAbsentOrDisqualified())
                .filter(result -> result.getMark() != null)
                // Ties are broken by user id, which needs no extra query and keeps
                // the same marks producing the same final every time.
                .sorted(performance.thenComparing(result -> result.getUser().getId()))
                .toList();

        Map<Long, Student> rosters = new HashMap<>();
        List<Long> userIds = ordered.stream().map(result -> result.getUser().getId()).distinct().toList();
        if (!userIds.isEmpty()) {
            for (Student student : studentRepository.findWithUserByUserIdIn(userIds)) {
                if (student.getUser() != null) {
                    rosters.put(student.getUser().getId(), student);
                }
            }
        }

        List<Qualifier> ranked = new ArrayList<>(ordered.size());
        int rank = 1;
        for (EventResult result : ordered) {
            Long userId = result.getUser().getId();
            Student roster = rosters.get(userId);
            ranked.add(new Qualifier(
                    rank++,
                    // The lane is the draw and belongs only to the athletes who go
                    // through; `drawnInto` puts it on the top `size` of this list.
                    0,
                    userId,
                    roster != null ? roster.getStudentId() : result.getUser().getUsername(),
                    roster != null ? roster.getName() : result.getUser().getFullName(),
                    roster != null && roster.getGrade() != null ? roster.getGrade().name() : null,
                    roster != null ? roster.getClassName() : null,
                    roster != null ? roster.getClassNumber() : null,
                    roster != null ? roster.getHouse() : null,
                    result.getMark(),
                    result.getUnit()));
        }
        return ranked;
    }

    // ------------------------------------------------------------- helpers

    /**
     * The lane a finalist runs in, drawn from their heat rank.
     *
     * <p>The school's draw puts the fastest qualifier in the <strong>middle</strong> of
     * the track rather than in lane 1, and works outward from there:</p>
     *
     * <pre>
     *   初賽成績 Heat rank   1  2  3  4  5  6  7  8
     *   線道     Lane        3  4  5  6  7  8  2  1
     * </pre>
     *
     * <p>So ranks 1–6 take lanes 3 to 8 in order, and the two slowest qualifiers are
     * put on the outside — lane 2, then lane 1. With fewer than eight qualifiers the
     * same rule simply stops early: five runners take lanes 3, 4, 5, 6 and 7, and no
     * two of them ever share a lane.</p>
     *
     * <p>A rank past eight has no lane left to draw from a standard eight-lane track.
     * Rather than invent one, the lane falls back to the rank so nothing is lost —
     * an event with more than eight qualifiers is not a lane race the school runs.</p>
     */
    static int laneForRank(int rank) {
        if (rank < 1 || rank > LANES) {
            return rank;
        }
        // Ranks 1-6 fill the middle lanes outwards; 7 and 8 go to the outside.
        return rank <= 6 ? rank + 2 : 9 - rank;
    }

    /** The lanes a standard track has; the school's draw works within them. */
    static final int LANES = 8;

    /**
     * The athletes who go through — the best {@code size} of the ranking — each with
     * the lane the school's draw gives their rank.
     *
     * <p>This is the one place the draw is applied on the way out, so the lane a
     * preview shows an athlete is the lane the drawn final puts them in.</p>
     */
    private static List<Qualifier> drawnInto(List<Qualifier> ranked, int size) {
        List<Qualifier> through = ranked.size() > size ? ranked.subList(0, size) : ranked;
        return through.stream()
                .map(qualifier -> qualifier.inLane(laneForRank(qualifier.rank())))
                .toList();
    }

    private int resolveSize(Event event, Integer limit) {
        if (limit != null && limit > 0) {
            return limit;
        }
        Integer groupSize = event.getGroupSize();
        if (groupSize != null && groupSize > 0) {
            return groupSize;
        }
        return DEFAULT_FINAL_SIZE;
    }

    private FinalSummary describe(Event event, int size, int rankedAthletes,
                                  List<Qualifier> qualifiers, boolean drawn,
                                  EventGroup group, int clearedMarks, String note) {
        Event.EventType type = event.getType();
        return new FinalSummary(
                event.getId(),
                event.getName(),
                type == null ? null : type.getDisplayName(),
                event.getCategoryOrDefault().name(),
                event.isShortSprint() ? "A5" : "A4",
                event.isShortSprint(),
                size,
                rankedAthletes,
                qualifiers.size(),
                drawn,
                group == null ? null : group.getId(),
                group == null ? "Final" : group.getLabel(),
                clearedMarks,
                List.copyOf(qualifiers),
                note);
    }

    /**
     * Keeps a sprint's format in step with how many have entered.
     *
     * <p>A final with only a group's worth of entries would be the same athletes as
     * the heat, so such an event runs straight to a final. When entries rise again
     * the final comes back — but only if the system was the one that took it away; a
     * format the school chose is never undone behind its back.</p>
     *
     * @return true when the event's format was changed
     */
    @Transactional
    public boolean syncFinalFormat(Long eventId) {
        return syncFinalFormat(requireEvent(eventId));
    }

    @Transactional
    public boolean syncFinalFormat(Event event) {
        if (event == null || event.getId() == null || !event.mayHaveFinal()) {
            return false;
        }
        int finalSize = resolveSize(event, null);
        long entered = enrollmentRepository.countByEventIdAndStatus(
                event.getId(), Enrollment.EnrollmentStatus.CONFIRMED);
        boolean tooFewToSplit = entered <= finalSize;

        if (tooFewToSplit && !event.isDirectToFinal()) {
            event.setDirectToFinal(true);
            event.setDirectToFinalAuto(true);
            eventRepository.save(event);
            log.info("{} has {} entrant(s), which fits a final — running it straight to a final",
                    event.getName(), entered);
            return true;
        }
        if (!tooFewToSplit && event.isDirectToFinal()
                && Boolean.TRUE.equals(event.getDirectToFinalAuto())) {
            // The system closed it, so the system opens it again.
            event.setDirectToFinal(false);
            event.setDirectToFinalAuto(false);
            eventRepository.save(event);
            log.info("{} now has {} entrants, so heats and a final are back on",
                    event.getName(), entered);
            return true;
        }
        return false;
    }

    private Event requireEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + eventId));
    }

    /**
     * An event that is run straight to a final has no final to draw: everyone
     * competes once and that is the result. Only 60M/100M/200M/400M can be run as
     * heats and a final, and only once the school has asked for one.
     *
     * <p>The rule itself lives in {@link FinalStageGuard}, because the same rule
     * seen from the other end stops a helper entering final marks or printing a
     * final sheet before this draw has run. Both ends state it once, so they cannot
     * disagree.</p>
     */
    private void requireAFinalIsPossible(Event event) {
        finalStageGuard.requireAFinalIsPossible(event);
    }

    private com.sportday.entity.User enrollmentUser(Long eventId, Long userId) {
        return enrollmentRepository.findByUserIdAndEventId(userId, eventId)
                .map(Enrollment::getUser)
                .orElseThrow(() -> new IllegalStateException(
                        "Athlete " + userId + " is no longer entered in event " + eventId));
    }

    private Map<Long, Student> rostersFor(List<Qualifier> qualifiers) {
        Map<Long, Student> rosters = new HashMap<>();
        List<Long> userIds = qualifiers.stream().map(Qualifier::userId).toList();
        if (!userIds.isEmpty()) {
            for (Student student : studentRepository.findWithUserByUserIdIn(userIds)) {
                if (student.getUser() != null) {
                    rosters.put(student.getUser().getId(), student);
                }
            }
        }
        return rosters;
    }
}
