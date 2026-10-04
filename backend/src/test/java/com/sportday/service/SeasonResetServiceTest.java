package com.sportday.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportday.dto.SeasonBackupFile;
import com.sportday.entity.Enrollment;
import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.EventGroup;
import com.sportday.entity.EventRecord;
import com.sportday.entity.EventResult;
import com.sportday.entity.EventStage;
import com.sportday.entity.FinalEntry;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.User;
import com.sportday.exception.BackupFailedException;
import com.sportday.repository.EnrollmentRepository;
import com.sportday.repository.EventGroupRepository;
import com.sportday.repository.EventRecordRepository;
import com.sportday.repository.EventRepository;
import com.sportday.repository.EventResultRepository;
import com.sportday.repository.FinalEntryRepository;
import com.sportday.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A season reset must back the season up <strong>first</strong>, and must refuse to
 * run at all if it cannot.
 *
 * <p>Requirement: "can backup record before reset record". Two things follow from
 * it, and both are tested here rather than argued: the file is on disk before the
 * first delete, and a backup that cannot be written stops the reset with the data
 * still in place.</p>
 *
 * <p>These are mock tests, so they cannot demonstrate a database rollback. What they
 * demonstrate is the guard itself: {@code writeSeasonBackup()} runs before the first
 * delete, so a failure there means no delete is ever issued. In production the
 * method is {@code @Transactional} as well, so the transaction the failed call was
 * inside rolls back regardless.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeasonResetServiceTest {

    @Mock private RecordService recordService;
    @Mock private EventService eventService;
    @Mock private UserRepository userRepository;
    @Mock private EntityManager entityManager;

    private InMemoryRepo<Enrollment> enrollments;
    private InMemoryRepo<EventGroup> groups;
    private InMemoryRepo<EventResult> results;
    private InMemoryRepo<FinalEntry> finalEntries;
    private InMemoryRepo<EventRecord> records;
    private InMemoryRepo<Event> events;

    private ObjectMapper objectMapper;
    private Path directory;
    private BackupStore store;
    private SeasonBackupService backupService;
    private SeasonResetService resetService;

    // ------------------------------------------------------------------ setup

    @BeforeEach
    void setUp() throws IOException {
        ObjectMapper apiMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        objectMapper = com.sportday.config.BackupConfig.backupMapper();
        directory = Files.createTempDirectory("sportday-season-reset");
        store = new BackupStore(directory, objectMapper, Clock.systemDefaultZone());

        enrollments = new InMemoryRepo<>(Enrollment::getId, Enrollment::setId);
        groups = new InMemoryRepo<>(EventGroup::getId, EventGroup::setId);
        results = new InMemoryRepo<>(EventResult::getId, EventResult::setId);
        finalEntries = new InMemoryRepo<>(FinalEntry::getId, FinalEntry::setId);
        records = new InMemoryRepo<>(EventRecord::getId, EventRecord::setId);
        events = new InMemoryRepo<>(Event::getId, Event::setId);

        backupService = new SeasonBackupService(
                enrollments.repository(EnrollmentRepository.class),
                groups.repository(EventGroupRepository.class),
                results.repository(EventResultRepository.class),
                finalEntries.repository(FinalEntryRepository.class),
                records.repository(EventRecordRepository.class),
                userRepository,
                events.repository(EventRepository.class),
                recordService, eventService, store, entityManager);

        resetService = new SeasonResetService(
                enrollments.repository(EnrollmentRepository.class),
                groups.repository(EventGroupRepository.class),
                results.repository(EventResultRepository.class),
                finalEntries.repository(FinalEntryRepository.class),
                records.repository(EventRecordRepository.class),
                recordService, eventService, backupService);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ---------------------------------------------------------------- fixtures

    /** A student account, so a restored row has somebody to belong to. */
    private User student(long id) {
        return User.builder().id(id).username("S" + id).fullName("Athlete " + id).build();
    }

    private Event event(long id) {
        return Event.builder()
                .id(id)
                .name("Boys 100M · B Grade")
                .type(Event.EventType.RUN_100M)
                .category(EventCategory.TRACK)
                .sex(Sex.MALE)
                .grade(Grade.B)
                .groupSize(8)
                .eventDate(LocalDate.of(2026, 10, 1))
                .enabled(true)
                .build();
    }

    /**
     * A populated season: two events with a heat and a final each, entries in both,
     * a final place, three marks — one of them a final — and one school record whose
     * standing mark is held by a result.
     */
    private void givenASeason() {
        Event hundred = event(2L);
        Event twoHundred = event(3L);
        events.add(hundred);
        events.add(twoHundred);

        EventGroup heat = EventGroup.builder().id(7L).event(hundred).groupNumber(1)
                .stage(EventStage.HEAT).capacity(8).athleteCount(2).build();
        EventGroup finalGroup = EventGroup.builder().id(8L).event(hundred).groupNumber(0)
                .stage(EventStage.FINAL).capacity(8).athleteCount(1).build();
        groups.add(heat);
        groups.add(finalGroup);

        enrollments.add(Enrollment.builder().id(10L).user(student(11L)).event(hundred)
                .eventGroup(heat).lane(3).status(Enrollment.EnrollmentStatus.CONFIRMED)
                .enrolledAt(LocalDateTime.of(2026, 9, 20, 9, 0)).build());
        enrollments.add(Enrollment.builder().id(11L).user(student(12L)).event(hundred)
                .eventGroup(heat).lane(4).status(Enrollment.EnrollmentStatus.CONFIRMED).build());
        enrollments.add(Enrollment.builder().id(12L).user(student(11L)).event(twoHundred)
                .status(Enrollment.EnrollmentStatus.CONFIRMED).build());

        finalEntries.add(FinalEntry.builder().id(20L).group(finalGroup).user(student(11L))
                .lane(1).seed(1).seedMark(new BigDecimal("10.100")).seedUnit("s").build());

        EventResult heatMark = EventResult.builder().id(30L).user(student(11L)).event(hundred)
                .stage(EventStage.HEAT).mark(new BigDecimal("10.100")).unit("s")
                .attempt1(new BigDecimal("10.100")).notes("PB").build();
        EventResult finalMark = EventResult.builder().id(31L).user(student(11L)).event(hundred)
                .stage(EventStage.FINAL).mark(new BigDecimal("10.240")).unit("s").build();
        EventResult other = EventResult.builder().id(32L).user(student(12L)).event(twoHundred)
                .stage(EventStage.HEAT).mark(new BigDecimal("22.500")).unit("s").build();
        results.add(heatMark);
        results.add(finalMark);
        results.add(other);

        records.add(EventRecord.builder().id(40L)
                .eventType(Event.EventType.RUN_100M).sex(Sex.MALE).grade(Grade.B)
                .manualMark(new BigDecimal("11.500")).manualUnit("s")
                .manualHolderName("Chan Tai Man").manualAchievedOn(LocalDate.of(2018, 5, 1))
                .mark(new BigDecimal("10.100")).unit("s").holderName("Athlete 11")
                .holder(student(11L)).result(heatMark).event(hundred)
                .achievedOn(LocalDate.of(2026, 10, 1)).hasPrevious(false)
                .build());

        // The lookups a restore makes, and the recompute that follows it. These are
        // stubs on the Mockito services only: the repositories are the in-memory
        // fakes above, so a Mockito matcher must never be handed to one of them or
        // the two mechanisms would fight over the last invocation.
        when(userRepository.findAllById(any())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            List<User> found = new ArrayList<>();
            if (ids != null) {
                for (Long id : ids) {
                    found.add(student(id));
                }
            }
            return found;
        });
        when(recordService.recomputeAll()).thenReturn(records.all().size());
        // Put each record back at its typed-in baseline and then rebuild it from the
        // results that have just been restored, exactly as RecordService does.
        doAnswer(invocation -> {
            EventRecord record = invocation.getArgument(0);
            record.setMark(record.getManualMark());
            record.setUnit(record.getManualUnit());
            record.setHolder(null);
            record.setResult(null);
            record.setEvent(null);
            return null;
        }).when(recordService).recomputeFor(any(), any(), any());
        when(eventService.reapplyFinalFormat()).thenReturn(2);
    }

    // ----------------------------------------------------- the backup comes first

    @Test
    @DisplayName("the backup file is written before anything is deleted")
    void theBackupIsWrittenBeforeAnythingIsDeleted() throws IOException {
        givenASeason();

        Map<String, Object> summary = resetService.resetSeason();

        // The file is there, and it holds what the reset is about to destroy.
        String name = String.valueOf(summary.get("backupFile"));
        assertTrue(BackupStore.isBackupName(name), "the response names the backup: " + name);
        Path file = directory.resolve(name);
        assertTrue(Files.isRegularFile(file), "and the file is on disk");
        assertTrue(((Number) summary.get("backupBytes")).longValue() > 0,
                "and its size is reported");

        JsonNode root = objectMapper.readTree(file.toFile());
        assertEquals(SeasonBackupFile.APP_NAME, root.path("app").asText());
        assertEquals(3, root.path("header").path("counts").path("enrollments").asInt());
        assertEquals(1, root.path("header").path("counts").path("finalEntries").asInt());
        assertEquals(2, root.path("header").path("counts").path("groups").asInt());
        assertEquals(3, root.path("header").path("counts").path("results").asInt());
        assertEquals(1, root.path("header").path("counts").path("records").asInt());
        assertEquals(3, root.path("enrollments").size());
        assertEquals(2, root.path("groups").size());
        assertEquals(1, root.path("finalEntries").size());
        assertEquals(3, root.path("results").size());
        assertEquals(1, root.path("records").size());

        // And the reset did what it says on the tin, afterwards.
        assertTrue(enrollments.all().isEmpty());
        assertTrue(groups.all().isEmpty());
        assertTrue(results.all().isEmpty());
        assertTrue(finalEntries.all().isEmpty());
        assertEquals(3L, summary.get("enrollmentsRemoved"));
        assertEquals(2L, summary.get("groupsRemoved"));
        assertEquals(3L, summary.get("resultsRemoved"));
        assertEquals(1L, summary.get("finalPlacesRemoved"));
    }

    // ---------------------------------------------------- a failed backup aborts

    @Test
    @DisplayName("a backup that cannot be written aborts the reset, and nothing is deleted")
    void aFailedBackupAbortsTheReset() throws IOException {
        givenASeason();
        // A file where the backup directory should be: the write cannot succeed.
        Path inTheWay = Files.createFile(directory.resolve("in-the-way"));
        SeasonBackupService brokenBackupService = new SeasonBackupService(
                enrollments.repository(EnrollmentRepository.class),
                groups.repository(EventGroupRepository.class),
                results.repository(EventResultRepository.class),
                finalEntries.repository(FinalEntryRepository.class),
                records.repository(EventRecordRepository.class),
                userRepository,
                events.repository(EventRepository.class),
                recordService, eventService,
                new BackupStore(inTheWay, objectMapper, Clock.systemDefaultZone()),
                entityManager);
        SeasonResetService refusing = new SeasonResetService(
                enrollments.repository(EnrollmentRepository.class),
                groups.repository(EventGroupRepository.class),
                results.repository(EventResultRepository.class),
                finalEntries.repository(FinalEntryRepository.class),
                records.repository(EventRecordRepository.class),
                recordService, eventService, brokenBackupService);

        BackupFailedException failed = assertThrows(BackupFailedException.class,
                () -> refusing.resetSeason());

        assertTrue(failed.getMessage().contains("Nothing was reset"),
                "the caller is told the reset did not run: " + failed.getMessage());

        // Not one delete was issued. The repositories here are in-memory fakes, not
        // Mockito mocks, so they are asked what they did rather than verified.
        assertEquals(0, enrollments.deletes(), "no entry was deleted");
        assertEquals(0, groups.deletes(), "no heat or final was deleted");
        assertEquals(0, results.deletes(), "no mark was deleted");
        assertEquals(0, finalEntries.deletes(), "no final place was deleted");
        verify(recordService, never()).detachAll();
        verify(recordService, never()).recomputeAll();
        verify(eventService, never()).reapplyFinalFormat();

        // And the data is all still there, untouched.
        assertEquals(3, enrollments.all().size());
        assertEquals(2, groups.all().size());
        assertEquals(3, results.all().size());
        assertEquals(1, finalEntries.all().size());
        assertEquals(1, records.all().size());
        assertEquals(new BigDecimal("10.100"),
                results.all().stream().filter(r -> r.getId().equals(30L)).findFirst()
                        .orElseThrow().getMark(),
                "and the sampled mark never went anywhere");
    }

    // ---------------------------------------------------------------- round trip

    @Test
    @DisplayName("take a backup, reset, restore — the counts and a sampled mark come back")
    void aRoundTripComesBack() {
        givenASeason();

        Map<String, Object> reset = resetService.resetSeason();
        String name = String.valueOf(reset.get("backupFile"));
        assertTrue(enrollments.all().isEmpty(), "the reset cleared the season");

        Map<String, Object> restored = backupService.restore(name);

        assertEquals(name, restored.get("restoredFrom"));
        assertEquals(2L, restored.get("groupsRestored"));
        assertEquals(3L, restored.get("enrollmentsRestored"));
        assertEquals(1L, restored.get("finalEntriesRestored"));
        assertEquals(3L, restored.get("resultsRestored"));
        assertEquals(1L, restored.get("recordsRestored"));
        assertEquals(0L, ((Map<?, ?>) restored.get("skipped")).values().stream()
                .mapToLong(value -> ((Number) value).longValue()).sum(),
                "nothing had to be skipped: every student and event is still here");

        assertEquals(3, enrollments.all().size());
        assertEquals(2, groups.all().size());
        assertEquals(3, results.all().size());
        assertEquals(1, finalEntries.all().size());
        assertEquals(1, records.all().size());

        // A sampled mark, at a stage, for one athlete — the thing a reset destroys.
        EventResult sampled = results.all().stream()
                .filter(r -> r.getUser().getId().equals(11L))
                .filter(r -> r.getStageOrDefault() == EventStage.HEAT)
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("10.100"), sampled.getMark());
        assertEquals("s", sampled.getUnit());
        assertEquals(new BigDecimal("10.100"), sampled.getAttempt1());
        assertEquals("PB", sampled.getNotes());

        // The final's own mark is separate and came back separately.
        assertTrue(results.all().stream()
                        .anyMatch(r -> r.getStageOrDefault() == EventStage.FINAL
                                && new BigDecimal("10.240").equals(r.getMark())),
                "heat and final marks are still two rows");

        // The entry kept its heat and lane, and the final kept its place.
        Enrollment entry = enrollments.all().stream()
                .filter(e -> e.getId() != null && e.getLane() != null && e.getLane() == 3)
                .findFirst().orElseThrow();
        assertEquals(3, entry.getLane());
        assertTrue(entry.getEventGroup() != null && entry.getEventGroup().isFinal() == false,
                "the entry is back in its heat, not the final");
        assertEquals(1, finalEntries.all().get(0).getLane());
        assertEquals(new BigDecimal("10.100"), finalEntries.all().get(0).getSeedMark());

        // The school record's typed-in baseline survived, which is the part a reset
        // keeps and which cannot be rebuilt from anything else.
        EventRecord record = records.all().get(0);
        assertEquals(new BigDecimal("11.500"), record.getManualMark());
        assertEquals("Chan Tai Man", record.getManualHolderName());
        assertEquals(LocalDate.of(2018, 5, 1), record.getManualAchievedOn());
        verify(recordService, times(2)).recomputeAll();
    }

    @Test
    @DisplayName("a restored row whose student is gone is skipped and counted, not invented")
    void aRowWithNoStudentIsSkipped() {
        givenASeason();
        Map<String, Object> reset = resetService.resetSeason();
        String name = String.valueOf(reset.get("backupFile"));

        // Only one of the two students still exists — the register moved on.
        when(userRepository.findAllById(any())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            List<User> found = new ArrayList<>();
            for (Long id : ids) {
                if (id != null && id.equals(11L)) {
                    found.add(student(id));
                }
            }
            return found;
        });

        Map<String, Object> restored = backupService.restore(name);

        assertEquals(2L, restored.get("enrollmentsRestored"),
                "the rows belonging to the student who is still here come back");
        assertEquals(2L, restored.get("resultsRestored"));
        assertEquals(1L, restored.get("finalEntriesRestored"));
        assertEquals(1L, ((Map<?, ?>) restored.get("skipped")).get("enrollments"),
                "the one entry belonging to the student who is gone");
        assertEquals(1L, ((Map<?, ?>) restored.get("skipped")).get("results"));
        assertEquals(2, enrollments.all().size());
        assertEquals(2, results.all().size());
    }

    // -------------------------------------------------------------- deletions order

    @Test
    @DisplayName("the restore deletes children before parents and inserts parents first")
    void theRestoreOrdersItsDeletesAndInserts() {
        givenASeason();
        Map<String, Object> reset = resetService.resetSeason();
        String name = String.valueOf(reset.get("backupFile"));

        backupService.restore(name);
        List<Long> firstIds = results.all().stream().map(EventResult::getId).sorted().toList();
        List<Long> firstEnrollmentIds =
                enrollments.all().stream().map(Enrollment::getId).sorted().toList();

        // A second restore, from a file whose ids are all stale, is the case the
        // ordering exists for: every group, entry, final place and mark in the
        // database has to be pointed at an id generated by this run.
        Map<String, Object> again = backupService.restore(name);

        assertEquals(2L, again.get("groupsRestored"));
        assertEquals(3L, again.get("enrollmentsRestored"));
        assertTrue(groups.all().stream().allMatch(g -> g.getEvent() != null),
                "every restored heat still names its event");
        assertTrue(enrollments.all().stream().allMatch(e ->
                        e.getEvent() != null && e.getUser() != null),
                "every restored entry names its event and its student");
        assertTrue(enrollments.all().stream()
                        .filter(e -> e.getLane() != null)
                        .allMatch(e -> e.getEventGroup() != null),
                "an entry that had a heat still has one, on the id this run generated");
        assertTrue(finalEntries.all().stream().allMatch(f ->
                        f.getGroup() != null && f.getGroup().isFinal() && f.getUser() != null),
                "every restored final place is in the final and names its athlete");
        assertTrue(results.all().stream().allMatch(r ->
                        r.getEvent() != null && r.getUser() != null),
                "every restored mark names its event and its student");

        // Replace, not append: three marks, as the file has, with ids minted by the
        // second restore rather than the first.
        assertEquals(3, results.all().size(), "a restore replaces the season, it does not add to it");
        assertEquals(3, enrollments.all().size());
        assertNotEquals(firstIds,
                results.all().stream().map(EventResult::getId).sorted().toList(),
                "the rows were re-inserted, so the records had to be re-pointed");
        assertNotEquals(firstEnrollmentIds,
                enrollments.all().stream().map(Enrollment::getId).sorted().toList());
    }

    // ----------------------------------------------------------------- fakes

    /**
     * A JPA repository backed by a list, so the test can see what a delete actually
     * did and what an insert actually left behind.
     *
     * <p>{@code deleteAllInBatch} empties the list and {@code save} assigns an id the
     * way the database would, because the restore's whole job is to re-point rows at
     * ids that were generated on insert.</p>
     */
    private static final class InMemoryRepo<T> {

        private final List<T> rows = new ArrayList<>();
        private final Function<T, Long> idOf;
        private final java.util.function.BiConsumer<T, Long> setId;
        private long sequence = 1000L;
        private int deletes;

        InMemoryRepo(Function<T, Long> idOf, java.util.function.BiConsumer<T, Long> setId) {
            this.idOf = idOf;
            this.setId = setId;
        }

        void add(T row) {
            rows.add(row);
        }

        List<T> all() {
            return List.copyOf(rows);
        }

        /** How many times a delete was asked for — zero means nothing was touched. */
        int deletes() {
            return deletes;
        }

        T save(T row) {
            Long id = idOf.apply(row);
            if (id == null) {
                setId.accept(row, sequence++);
                rows.add(row);
            } else if (rows.stream().noneMatch(existing -> id.equals(idOf.apply(existing)))) {
                rows.add(row);
            }
            return row;
        }

        /** The repository interface, backed by the list above. */
        @SuppressWarnings("unchecked")
        <R> R repository(Class<R> type) {
            final Object[] proxy = new Object[1];
            proxy[0] = java.lang.reflect.Proxy.newProxyInstance(
                    type.getClassLoader(), new Class<?>[]{type},
                    (instance, method, args) -> handle(instance, method, args));
            return (R) proxy[0];
        }

        private Object handle(Object instance, java.lang.reflect.Method method, Object[] args) {
            switch (method.getName()) {
                case "findAll":
                    return new ArrayList<>(rows);
                case "findAllById":
                    List<Long> ids = args == null ? List.of() : (List<Long>) args[0];
                    return ids == null ? List.of()
                            : rows.stream().filter(row -> ids.contains(idOf.apply(row))).toList();
                case "findByEventTypeAndSexAndGrade":
                    // The query a record's rebuild makes: it wants the one record row for
                    // that event type, division and grade, if there is one.
                    return rows.stream().findFirst();
                case "findAllWithHolder":
                    return new ArrayList<>(rows);
                case "save":
                    return save((T) args[0]);
                case "deleteAllInBatch":
                case "deleteAll":
                    deletes++;
                    rows.clear();
                    return null;
                case "count":
                    return (long) rows.size();
                case "toString":
                    return "InMemoryRepo";
                case "hashCode":
                    return System.identityHashCode(this);
                case "equals":
                    return instance == args[0];
                default:
                    throw new UnsupportedOperationException(
                            "The test's in-memory repository does not implement " + method.getName());
            }
        }
    }
}
