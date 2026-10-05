package com.sportday.service;

import com.sportday.dto.BulkMarkRequest;
import com.sportday.dto.EventResultDTO;
import com.sportday.dto.MarkRowDTO;
import com.sportday.dto.MarkSheetDTO;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventResult;
import com.sportday.entity.EventStage;
import com.sportday.entity.Grade;
import com.sportday.entity.Student;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.FinalEntryRepository;
import com.sportday.repository.StudentRepository;
import com.sportday.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The mark-entry grid.
 *
 * <p>{@link #getMarkSheet} returns every athlete competing in a stage of an event,
 * together with the heat and lane they were drawn into and any mark already
 * recorded, and can be narrowed to one group and one grade band. {@link #saveMarks}
 * takes the whole grid back in one request and upserts it.</p>
 *
 * <p>Heats and the final are separate grids: marks are stored per stage, so a
 * final time never overwrites the heat time that earned the athlete their place.
 * Which stage a grid covers is decided by the group it is showing, or by the
 * {@code stage} on the request.</p>
 *
 * <p>A <strong>final</strong> grid also carries the heat each finalist ran
 * ({@link MarkRowDTO#getHeatMark() heatMark} / {@link MarkRowDTO#getHeatDisplayMark()
 * heatDisplayMark}), because the final is drawn from those heat marks and the person
 * writing the final down needs to see what earned the place. A heat grid carries
 * none, having no earlier stage to show.</p>
 *
 * <p>A <strong>relay</strong> grid is one row per <em>team</em>, and it is refused
 * with the reason — a 409 — while the relay is not ready: fewer than two teams, or a
 * team still short of its runners. The rule and its wording live in
 * {@link RelayReadiness}, so the grid and the marking sheets refuse a half-built
 * relay in exactly the same words. An individual event is never gated, and neither is
 * a relay's team builder: a short team is still saved.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkEntryService {

    /** Anything larger than this is a typo rather than a result. */
    private static final BigDecimal MAX_PLAUSIBLE_MARK = new BigDecimal("100000");

    private final EnrollmentRepository enrollmentRepository;
    private final EventRepository eventRepository;
    private final EventGroupRepository groupRepository;
    private final EventResultRepository resultRepository;
    private final StudentRepository studentRepository;
    private final UserRepository userRepository;
    private final FinalEntryRepository finalEntryRepository;
    private final EventGroupService eventGroupService;
    private final RecordService recordService;
    private final com.sportday.repository.RelayTeamRepository relayTeamRepository;
    private final com.sportday.repository.RelayTeamMemberRepository relayTeamMemberRepository;
    private final RelayReadiness relayReadiness;

    /**
     * The team a row records a time for, or null for an athlete's own mark.
     *
     * <p>A reference rather than a fetch: the row already carries the team's id and
     * nothing here reads the team, only points the result at it.</p>
     */
    private com.sportday.entity.RelayTeam relayTeamOf(BulkMarkRequest.Entry row) {
        return row == null || row.getTeamId() == null
                ? null
                : relayTeamRepository.getReferenceById(row.getTeamId());
    }

    /**
     * The teams of a relay event, or none for anything else — an individual event, or a
     * relay nobody has divided yet.
     *
     * <p>An individual event keeps the athlete-per-row grid. A relay with no teams is
     * <em>not</em> an athlete grid any more: it is not ready, and the readiness rule
     * refuses it before this list is used — see {@link RelayReadiness}.</p>
     */
    private List<com.sportday.entity.RelayTeam> relayTeamsOf(Event event) {
        if (event == null || !event.isRelay() || event.getId() == null) {
            return List.of();
        }
        return relayTeamRepository.findByEventIdOrderByIdAsc(event.getId());
    }

    // ------------------------------------------------------------- reading

    /**
     * @param groupId only athletes in this group; null for the whole stage
     * @param grade   only athletes in this grade band; null for all grades
     * @param stage   {@code HEAT} (default) or {@code FINAL}; ignored when a
     *                group is named, because the group already says which it is
     */
    @Transactional(readOnly = true)
    public MarkSheetDTO getMarkSheet(Long eventId, Long groupId, Grade grade, EventStage stage) {
        Event event = requireEvent(eventId);

        EventStage effectiveStage = stage == null ? EventStage.HEAT : stage;
        if (groupId != null) {
            effectiveStage = eventGroupService.requireGroup(groupId).getStageOrDefault();
        }
        requireFinalIsReady(event, effectiveStage);

        /*
         * The teams of a relay, and the one readiness rule over them — asked before
         * anything is read for the grid. A relay is marked by team, and it may not be
         * marked at all until it has at least two teams and every team holds its four
         * runners: until then the grid is refused with the reason rather than served
         * empty, which would look like a race with nothing in it. An individual event
         * has no teams and passes straight through — see RelayReadiness.
         */
        List<com.sportday.entity.RelayTeam> relayTeams = relayTeamsOf(event);
        RelayReadiness.requireRelayIsReadyToMark(event,
                relayReadiness.statesOf(event.getId(), relayTeams));

        // Who is competing: the named group, or everyone in the stage.
        List<MarkSheetDTO.GroupOption> groupOptions = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();

        /*
         * A relay is scored by TEAM — one time for the four runners together, not four
         * times — so its grid lists teams and every other event lists athletes. A ready
         * relay always has teams to list: the readiness rule above refuses a relay with
         * fewer than two of them, so the grid never falls through to the athlete-per-row
         * shape for a relay.
         *
         * The row IS the team: it carries the team's id and the team's name, and it
         * deliberately does not carry who is running for it. The school's requirement is
         * that a relay line is read by the team's name and not by the students', so the
         * runners are not put on the grid at all — the relay board is where they are
         * listed, leg by leg, and it is the only place that needs them.
         */
        if (!relayTeams.isEmpty()) {
            for (com.sportday.entity.RelayTeam team : relayTeams) {
                List<com.sportday.entity.RelayTeamMember> squad =
                        relayTeamMemberRepository.findByTeamIdOrderByLegAsc(team.getId());
                // The row hangs off the team's first runner: event_results.user_id is not
                // nullable and every reader of a result — standings, records, the results
                // PDF, the season backup — joins on it. See EventResult#relayTeam.
                Long anchor = squad.stream()
                        .filter(member -> member.getUser() != null)
                        .map(member -> member.getUser().getId())
                        .findFirst().orElse(null);
                if (anchor == null) {
                    continue;
                }
                candidates.add(new Candidate(anchor, null, null, team.getLabel(), null,
                        team.getId(), team.getLabel()));
            }
        } else if (groupId != null) {
            var group = eventGroupService.requireGroup(groupId);
            groupOptions.add(MarkSheetDTO.GroupOption.builder()
                    .id(group.getId())
                    .groupNumber(group.getGroupNumber())
                    .label(group.getLabel())
                    .athleteCount(group.getAthleteCount())
                    .build());
            for (var member : eventGroupService.membersOf(group)) {
                candidates.add(new Candidate(member.userId(), group.getId(), group.getGroupNumber(),
                        group.getLabel(), member.lane(), null, null));
            }
        } else if (effectiveStage == EventStage.FINAL) {
            groupRepository.findFirstByEventIdAndStage(eventId, EventStage.FINAL)
                    .ifPresent(finalGroup -> {
                        groupOptions.add(MarkSheetDTO.GroupOption.builder()
                                .id(finalGroup.getId())
                                .groupNumber(finalGroup.getGroupNumber())
                                .label(finalGroup.getLabel())
                                .athleteCount(finalGroup.getAthleteCount())
                                .build());
                        for (var entry : finalEntryRepository.findByGroupIdOrderByLaneAsc(finalGroup.getId())) {
                            if (entry.getUser() != null) {
                                candidates.add(new Candidate(entry.getUser().getId(), finalGroup.getId(),
                                        finalGroup.getGroupNumber(), finalGroup.getLabel(),
                                        entry.getLane(), null, null));
                            }
                        }
                    });
        } else {
            List<Enrollment> entries = enrollmentRepository.findConfirmedWithUserByEvent(
                    eventId, Enrollment.EnrollmentStatus.CONFIRMED);
            Map<Long, MarkSheetDTO.GroupOption> byGroup = new LinkedHashMap<>();
            for (Enrollment entry : entries) {
                Long userId = entry.getUser() == null ? null : entry.getUser().getId();
                if (userId == null) {
                    continue;
                }
                Long gid = entry.getEventGroup() == null ? null : entry.getEventGroup().getId();
                Integer number = entry.getEventGroup() == null ? null : entry.getEventGroup().getGroupNumber();
                String label = entry.getEventGroup() == null ? null : entry.getEventGroup().getLabel();
                if (entry.getEventGroup() != null) {
                    byGroup.computeIfAbsent(gid, key -> MarkSheetDTO.GroupOption.builder()
                            .id(gid)
                            .groupNumber(number)
                            .label(label)
                            .athleteCount(entry.getEventGroup().getAthleteCount())
                            .build());
                }
                candidates.add(new Candidate(userId, gid, number, label, entry.getLane(),
                        null, null));
            }
            groupOptions.addAll(byGroup.values());
            groupOptions.sort(Comparator.comparingInt(
                    option -> option.getGroupNumber() == null ? Integer.MAX_VALUE : option.getGroupNumber()));
        }

        // Rosters and recorded marks.
        List<Long> userIds = candidates.stream().map(Candidate::userId).distinct().toList();
        Map<Long, Student> rosters = rostersFor(userIds);
        Map<Long, EventResult> results = resultsFor(eventId, effectiveStage);
        // A relay's marks are the team's, found by team rather than by athlete. Read off
        // the results already in hand, so a relay grid costs no extra query.
        Map<Long, EventResult> teamResults = new LinkedHashMap<>();
        for (EventResult recorded : results.values()) {
            if (recorded.getRelayTeam() != null) {
                teamResults.putIfAbsent(recorded.getRelayTeam().getId(), recorded);
            }
        }
        // A final's grid shows what each finalist ran in their heat, because that
        // is the performance that put them in the final. One query for the whole
        // sheet — and none at all for a heat sheet, which has no earlier stage.
        Map<Long, EventResult> heatResults = effectiveStage == EventStage.FINAL
                ? resultsFor(eventId, EventStage.HEAT)
                : Map.of();
        // One lookup, so a record-breaking row can be badged as it is entered.
        Set<Long> recordHolders = recordService.recordResultIds();

        Event.EventType type = event.getType();
        String defaultUnit = type == null ? null : type.getDefaultUnit();

        List<MarkRowDTO> rows = new ArrayList<>();
        Set<String> grades = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            Student roster = rosters.get(candidate.userId());
            if (roster != null && roster.getGrade() != null) {
                grades.add(roster.getGrade().name());
            }
            if (grade != null && (roster == null || roster.getGrade() != grade)) {
                continue;
            }
            EventResult result = candidate.teamId() != null
                    ? teamResults.get(candidate.teamId())
                    : results.get(candidate.userId());
            EventResult heat = heatResults.get(candidate.userId());
            rows.add(MarkRowDTO.builder()
                    .userId(candidate.userId())
                    .teamId(candidate.teamId())
                    .teamLabel(candidate.teamLabel())
                    .studentRef(roster != null ? roster.getStudentId() : String.valueOf(candidate.userId()))
                    .name(roster != null ? roster.getName() : null)
                    .grade(roster != null && roster.getGrade() != null ? roster.getGrade().name() : null)
                    .className(roster != null ? roster.getClassName() : null)
                    .classNumber(roster != null ? roster.getClassNumber() : null)
                    .form(roster != null ? roster.getForm() : null)
                    .house(roster != null ? roster.getHouse() : null)
                    .houseCode(roster != null ? roster.getHouseCode() : null)
                    .groupId(candidate.groupId())
                    .groupNumber(candidate.groupNumber())
                    .groupLabel(candidate.groupLabel())
                    .lane(candidate.lane())
                    .resultId(result != null ? result.getId() : null)
                    .mark(result != null ? result.getMark() : null)
                    .unit(result != null ? result.getUnit() : null)
                    .notes(result != null ? result.getNotes() : null)
                    .outcome(result != null ? result.getOutcomeOrDefault().name() : null)
                    .newRecord(result != null && recordHolders.contains(result.getId()))
                    .attempts(result != null && result.hasAttempts()
                            ? new ArrayList<>(result.getAttempts()) : null)
                    .minutes(minutesOf(result == null ? null : result.getMark()))
                    .seconds(secondsOf(result == null ? null : result.getMark()))
                    .heatMark(heat == null ? null : heat.getMark())
                    .heatOutcome(heat == null ? null : heat.getOutcomeOrDefault().name())
                    .heatDisplayMark(MarkFormatter.formatRecord(heat, type, defaultUnit))
                    .build());
        }

        rows.sort(ROW_ORDER);
        int marked = (int) rows.stream().filter(row -> row.getMark() != null).count();

        boolean finalDrawn = eventGroupService.finalDrawn(eventId);
        FinalStageGuard.FinalState finalState = FinalStageGuard.state(event, finalDrawn);
        boolean field = event.getCategoryOrDefault() == EventCategory.FIELD;
        return MarkSheetDTO.builder()
                .eventId(event.getId())
                .eventName(event.getName())
                .eventType(type == null ? null : type.name())
                .eventTypeLabel(type == null ? null : type.getDisplayName())
                .category(event.getCategoryOrDefault().name())
                .categoryLabel(event.getCategoryOrDefault().getLabel())
                .sex(event.getSex() == null ? null : event.getSex().name())
                .sexLabel(event.getSex() == null ? null : event.getSex().getLabel())
                .eventDate(event.getEventDate())
                .location(event.getLocation())
                .groupSize(event.getGroupSize())
                .sheetSize(event.isShortSprint() ? "A5" : "A4")
                // A field athlete gets three attempts; a track athlete one time.
                .attemptCount(field ? Event.EventType.FIELD_ATTEMPTS : 1)
                .fieldEvent(field)
                // A race over 400M is typed as minutes and seconds.
                .timeInMinutes(event.usesMinutesAndSeconds())
                .stage(effectiveStage.name())
                .stageLabel(effectiveStage.getLabelEn() + " " + effectiveStage.getLabelZh())
                .finalDrawn(finalDrawn)
                // finalDrawn cannot tell "this event has no final" from "not drawn
                // yet"; the state can, so a client can gate the final grid on it.
                .finalState(finalState.name())
                .finalStateLabel(finalStateLabelOf(finalState))
                .finalSize(event.getGroupSize())
                .defaultUnit(type == null ? null : type.getDefaultUnit())
                .groups(groupOptions)
                .grades(new ArrayList<>(grades))
                .totalAthletes(rows.size())
                .markedCount(marked)
                .rows(rows)
                .build();
    }

    /** The final state as the grid shows it, in the product's English/Chinese style. */
    private static String finalStateLabelOf(FinalStageGuard.FinalState state) {
        return switch (state) {
            case NONE -> "No final 不設決賽";
            case DIRECT -> "Direct to final 直接決賽";
            case NOT_DRAWN -> "Final not drawn yet 決賽未抽籤";
            case DRAWN -> "Final drawn 已抽決賽";
        };
    }

    /**
     * Which group an athlete competes in at a stage — or, on a relay, which team.
     *
     * <p>A relay candidate carries its team's id and name and no member list: the grid
     * line is the team's, so the runners are not carried onto it. See
     * {@link MarkRowDTO#getTeamMembers()}.</p>
     */
    private record Candidate(Long userId, Long groupId, Integer groupNumber, String groupLabel,
                             Integer lane, Long teamId, String teamLabel) {
    }

    /** Heats first, then lane, then grade and class, then student id. */
    private static final Comparator<MarkRowDTO> ROW_ORDER = Comparator
            .comparingInt((MarkRowDTO row) -> row.getGroupNumber() == null ? Integer.MAX_VALUE : row.getGroupNumber())
            .thenComparingInt(row -> row.getLane() == null ? Integer.MAX_VALUE : row.getLane())
            .thenComparing(row -> row.getGrade() == null ? "~" : row.getGrade())
            .thenComparing(row -> row.getClassName() == null ? "~" : row.getClassName())
            .thenComparingInt(row -> row.getClassNumber() == null ? Integer.MAX_VALUE : row.getClassNumber())
            .thenComparing(row -> row.getStudentRef() == null ? "" : row.getStudentRef());

    // ------------------------------------------------------------- writing

    /**
     * Saves the whole grid in one transaction. Rows are independent, so one bad
     * athlete never costs the rest of the batch.
     *
     * <p>Marks are written at the stage named on the request — {@code HEAT} unless
     * the grid is the final — so the two stages never overwrite one another.</p>
     *
     * <p>A row may carry an {@code outcome} instead of a number: <strong>ABS</strong>
     * or <strong>DQ</strong> records that the athlete was absent or disqualified,
     * which clears the mark and the attempts and skips every mark rule. A row with
     * neither a mark nor a value to check is left exactly as it was, as it always
     * has been.</p>
     */
    @Transactional
    public BulkMarkRequest.Result saveMarks(Long eventId, BulkMarkRequest request) {
        Event event = requireEvent(eventId);
        EventStage stage = request == null || request.getStage() == null
                ? EventStage.HEAT
                : EventStage.fromCode(request.getStage());
        if (stage == null) {
            throw new IllegalArgumentException("Unknown stage: " + request.getStage()
                    + " — use HEAT or FINAL.");
        }
        requireFinalIsReady(event, stage);
        // Mark entry is held back with the grid: a relay that is not ready has no grid
        // to have been filled in, so a save aimed straight at the endpoint is refused
        // in the same words rather than storing a time for a race still being built.
        // An individual event is never gated. See RelayReadiness.
        relayReadiness.requireRelayIsReadyToMark(event);

        String defaultUnit = event.getType() == null ? null : event.getType().getDefaultUnit();
        Set<Long> allowed = stage == EventStage.FINAL ? finalists(eventId) : entered(eventId);
        Long finalGroupId = stage == EventStage.FINAL
                ? groupRepository.findFirstByEventIdAndStage(eventId, EventStage.FINAL)
                        .map(group -> group.getId()).orElse(null)
                : null;

        BulkMarkRequest.Result outcome = BulkMarkRequest.Result.builder()
                .eventId(eventId)
                .eventName(event.getName())
                .stage(stage.name())
                .build();

        List<BulkMarkRequest.Entry> rows = request == null || request.getRows() == null
                ? List.of() : request.getRows();
        Set<Long> seen = new HashSet<>();
        // A field event is decided by the best of three attempts.
        boolean field = event.getCategoryOrDefault() == EventCategory.FIELD;
        // Marks whose event record may have changed, so the records can be rebuilt
        // once the batch has been flushed.
        Map<Long, EventResult> touched = new LinkedHashMap<>();
        // A stored mark replaced by an ABS/DQ: the record can only stand on a
        // performance, so it has to be rebuilt without this one.
        boolean releasedRecord = false;

        for (BulkMarkRequest.Entry row : rows) {
            if (row == null || row.getUserId() == null) {
                outcome.setFailed(outcome.getFailed() + 1);
                outcome.addError(null, "Row has no athlete id.");
                continue;
            }
            Long userId = row.getUserId();
            if (!seen.add(userId)) {
                outcome.setFailed(outcome.getFailed() + 1);
                outcome.addError(userId, "This athlete appears twice in the same save.");
                continue;
            }
            if (!allowed.contains(userId)) {
                outcome.setFailed(outcome.getFailed() + 1);
                outcome.addError(userId, stage == EventStage.FINAL
                        ? "The athlete did not qualify for the final."
                        : "The athlete is not entered in this event.");
                continue;
            }

            EventResult existing = resultRepository
                    .findByUserIdAndEventIdAndStage(userId, eventId, stage)
                    .orElse(null);

            // What the helper wrote in place of a number, if anything. Refused
            // outright — rather than stored as a mark — when the value is not one
            // of the three the sheet understands.
            EventResult.Outcome rowOutcome = outcomeOf(row);

            if (Boolean.TRUE.equals(row.getClear())) {
                if (existing != null) {
                    // Let a school record go of this mark before deleting it, or the
                    // record's foreign key blocks the delete.
                    recordService.detachForResult(existing.getId());
                    touched.put(existing.getId(), existing);
                    resultRepository.delete(existing);
                    outcome.setCleared(outcome.getCleared() + 1);
                } else {
                    outcome.setSkipped(outcome.getSkipped() + 1);
                }
                continue;
            }

            if (rowOutcome.isNoMark()) {
                // ABS or DQ: there is no number to store and none to check, so the
                // plausibility and stopwatch rules are skipped. The mark and the
                // attempts go, and the outcome is the whole result. Re-saving the
                // same row as a real mark puts them back.
                if (existing != null && existing.getMark() != null) {
                    // The performance is gone, so a record built on it cannot stand.
                    // The outcome itself has no say in the record — this is the mark
                    // being removed, exactly as clearing it would be.
                    releasedRecord = true;
                }
                if (existing == null) {
                    existing = EventResult.builder()
                            .user(userRepository.getReferenceById(userId))
                            .event(event)
                            .stage(stage)
                            .relayTeam(relayTeamOf(row))
                            .outcome(rowOutcome)
                            .notes(row.getNotes())
                            .build();
                } else {
                    existing.setRelayTeam(relayTeamOf(row));
                    existing.setOutcome(rowOutcome);
                    existing.setMark(null);
                    existing.setAttempts(null);
                    existing.setNotes(row.getNotes());
                }
                resultRepository.save(existing);
                touched.put(existing.getId(), existing);
                outcome.setSaved(outcome.getSaved() + 1);
                continue;
            }

            // A field event gives three attempts and counts the best of them; a race
            // over 400M is typed as minutes and seconds; anything else is one mark.
            List<BigDecimal> attempts = field ? fieldAttempts(row) : null;
            BigDecimal value;
            if (field) {
                value = bestAttempt(attempts);
            } else if (event.usesMinutesAndSeconds()
                    && (row.getMinutes() != null || row.getSeconds() != null)) {
                BigDecimal asSeconds = totalSeconds(row.getMinutes(), row.getSeconds());
                if (asSeconds == null) {
                    outcome.setFailed(outcome.getFailed() + 1);
                    outcome.addError(userId, "The seconds part of a time must be under 60 — "
                            + "write 2 minutes 15 seconds as 2 and 15, not as 1 and 75.");
                    continue;
                }
                value = asSeconds;
            } else {
                value = row.getMark();
            }

            if (value == null) {
                // Nothing typed for this athlete: leave whatever is stored alone.
                outcome.setSkipped(outcome.getSkipped() + 1);
                continue;
            }
            BigDecimal implausible = implausibleAmong(field ? attempts : List.of(value));
            if (implausible != null) {
                outcome.setFailed(outcome.getFailed() + 1);
                outcome.addError(userId, "Implausible mark: " + implausible.toPlainString());
                continue;
            }

            String unit = event.getType() == null
                    ? (row.getUnit() == null || row.getUnit().isBlank() ? defaultUnit : row.getUnit().trim())
                    : event.getType().normaliseUnit(row.getUnit());

            if (existing == null) {
                existing = EventResult.builder()
                        .user(userRepository.getReferenceById(userId))
                        .event(event)
                        .stage(stage)
                        .relayTeam(relayTeamOf(row))
                        // A mark was produced, so the row is a result — which is also
                        // what clears an ABS/DQ previously recorded for this athlete.
                        .outcome(EventResult.Outcome.RESULT)
                        .mark(value)
                        .unit(unit)
                        .notes(row.getNotes())
                        .build();
            } else {
                existing.setRelayTeam(relayTeamOf(row));
                existing.setOutcome(EventResult.Outcome.RESULT);
                existing.setMark(value);
                existing.setUnit(unit);
                existing.setNotes(row.getNotes());
            }
            if (field) {
                existing.setAttempts(attempts);
            }
            resultRepository.save(existing);
            touched.put(existing.getId(), existing);
            outcome.setSaved(outcome.getSaved() + 1);
        }

        resultRepository.flush();

        // School records are derived from the results, so they are rebuilt once the
        // whole batch is written. That way a corrected or cleared mark can never
        // leave a record pointing at a performance that no longer exists.
        for (EventResult result : touched.values()) {
            recordService.considerResult(result);
        }
        if (releasedRecord) {
            // Rebuild the record without the performance that has just been taken
            // away, so a disqualified mark cannot stand as the school's best.
            recordService.recomputeFor(event.getType(), event.getSex(), event.getGrade());
        }

        outcome.setResults(getResultsByEvent(eventId, stage));
        log.info("Event {} {} marks saved: {} saved, {} cleared, {} skipped, {} failed",
                eventId, stage, outcome.getSaved(), outcome.getCleared(),
                outcome.getSkipped(), outcome.getFailed());
        if (finalGroupId != null) {
            log.debug("Final marks for event {} belong to group {}", eventId, finalGroupId);
        }
        return outcome;
    }

    @Transactional(readOnly = true)
    public List<EventResultDTO> getResultsByEvent(Long eventId, EventStage stage) {
        // One lookup for every record holder, so the grid can badge a record row.
        Set<Long> recordHolders = recordService.recordResultIds();
        return resultRepository.findByEventIdAndStageOrderByMarkAsc(eventId, stage).stream()
                // An athlete who was absent or disqualified still appears, but after
                // the performances: they have no mark, so they have no place.
                .sorted(Comparator.comparing((EventResult result) -> result.isAbsentOrDisqualified()))
                .map(result -> {
                    EventResultDTO dto = EventResultDTO.from(result);
                    dto.setNewRecord(recordHolders.contains(result.getId()));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------- helpers

    /**
     * The outcome a row asked for. An omitted or blank value is
     * {@link EventResult.Outcome#RESULT}, the meaning the grid has always had; a
     * value that is none of the three is refused with the value named, because
     * silently storing it as a mark would record a performance nobody entered.
     */
    private static EventResult.Outcome outcomeOf(BulkMarkRequest.Entry row) {
        String raw = row.getOutcome();
        if (raw == null || raw.isBlank()) {
            return EventResult.Outcome.RESULT;
        }
        EventResult.Outcome parsed = EventResult.Outcome.fromCode(raw);
        if (parsed == null) {
            throw new IllegalArgumentException("Unknown outcome: " + raw + " — use RESULT, ABS or DQ.");
        }
        return parsed;
    }

    /**
     * A field row's attempts, always three of them. A helper may send just the
     * attempts that happened, or a single {@code mark}, so a missing attempt is
     * simply left empty rather than treated as zero.
     */
    private static List<BigDecimal> fieldAttempts(BulkMarkRequest.Entry row) {
        List<BigDecimal> sent = row.getAttempts();
        if (sent == null || sent.isEmpty()) {
            sent = row.getMark() == null ? List.of() : List.of(row.getMark());
        }
        List<BigDecimal> attempts = new ArrayList<>(Event.EventType.FIELD_ATTEMPTS);
        for (int i = 0; i < Event.EventType.FIELD_ATTEMPTS; i++) {
            attempts.add(i < sent.size() ? sent.get(i) : null);
        }
        return attempts;
    }

    /** The attempt that counts: a field event is won by the best of the three. */
    private static BigDecimal bestAttempt(List<BigDecimal> attempts) {
        if (attempts == null) {
            return null;
        }
        return attempts.stream()
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /** The first value that could not be a real mark, or null when all are fine. */
    private static BigDecimal implausibleAmong(List<BigDecimal> values) {
        for (BigDecimal value : values) {
            if (value != null
                    && (value.signum() < 0 || value.abs().compareTo(MAX_PLAUSIBLE_MARK) > 0)) {
                return value;
            }
        }
        return null;
    }

    /**
     * A stopwatch time as a total in seconds. Returns null when the seconds part is
     * not a real part — 1 minute 75 seconds is how a helper mistypes 2:15.
     */
    static BigDecimal totalSeconds(Integer minutes, BigDecimal seconds) {
        int whole = minutes == null ? 0 : minutes;
        if (whole < 0) {
            return null;
        }
        BigDecimal part = seconds == null ? BigDecimal.ZERO : seconds;
        if (part.signum() < 0 || part.compareTo(BigDecimal.valueOf(60)) >= 0) {
            return null;
        }
        return BigDecimal.valueOf(whole).multiply(BigDecimal.valueOf(60)).add(part);
    }

    /** The whole minutes in a mark, for a race timed on a stopwatch. */
    static Integer minutesOf(BigDecimal mark) {
        return mark == null ? null
                : mark.divideToIntegralValue(BigDecimal.valueOf(60)).intValue();
    }

    /** The seconds left over after the whole minutes. */
    static BigDecimal secondsOf(BigDecimal mark) {
        return mark == null ? null : mark.remainder(BigDecimal.valueOf(60));
    }

    /**
     * A final cannot be worked on until it exists.
     *
     * <p>The final is drawn <em>from</em> the heat results, so until the heats are in
     * and the draw has been run there is nothing to record in the final grid. Refusing
     * here rather than letting the screen show an empty final is the difference
     * between "not yet" and "nothing to do", and it is the same rule
     * {@link FinalQualificationService} applies when it refuses to draw without heat
     * marks — both ends ask {@link FinalStageGuard}, so neither can drift from the
     * other.</p>
     *
     * <p>The three refusals are distinct and deliberate: an event that cannot be split
     * has no final stage at all, an event set to run straight to a final has one it is
     * not using, and an event running heats simply has not drawn it yet.</p>
     *
     * @throws IllegalStateException with the reason, which becomes a 409
     */
    private void requireFinalIsReady(Event event, EventStage stage) {
        if (stage == EventStage.FINAL) {
            FinalStageGuard.requireDrawnFinal(event, eventGroupService.finalDrawn(event.getId()));
        }
    }

    private Event requireEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + eventId));
    }

    /** Everyone still entered in the event. */
    private Set<Long> entered(Long eventId) {
        return enrollmentRepository.findConfirmedWithUserByEvent(
                        eventId, Enrollment.EnrollmentStatus.CONFIRMED).stream()
                .filter(entry -> entry.getUser() != null)
                .map(entry -> entry.getUser().getId())
                .collect(Collectors.toSet());
    }

    /** Everyone who qualified for the final. */
    private Set<Long> finalists(Long eventId) {
        return groupRepository.findFirstByEventIdAndStage(eventId, EventStage.FINAL)
                .map(group -> finalEntryRepository.findByGroupIdOrderByLaneAsc(group.getId()).stream()
                        .filter(entry -> entry.getUser() != null)
                        .map(entry -> entry.getUser().getId())
                        .collect(Collectors.toSet()))
                .orElseGet(Set::of);
    }

    private Map<Long, EventResult> resultsFor(Long eventId, EventStage stage) {
        Map<Long, EventResult> results = new HashMap<>();
        for (EventResult result : resultRepository.findByEventIdAndStageOrderByMarkAsc(eventId, stage)) {
            if (result.getUser() != null) {
                results.putIfAbsent(result.getUser().getId(), result);
            }
        }
        return results;
    }

    private Map<Long, Student> rostersFor(List<Long> userIds) {
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
