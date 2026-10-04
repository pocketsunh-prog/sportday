package com.sportday.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportday.config.BackupConfig;
import com.sportday.dto.SeasonBackupFile;
import com.sportday.dto.SeasonBackupFile.BackupFile;
import com.sportday.dto.SeasonBackupFile.BackupSummary;
import com.sportday.dto.SeasonBackupFile.Counts;
import com.sportday.dto.SeasonBackupFile.EnrollmentLine;
import com.sportday.dto.SeasonBackupFile.Header;
import com.sportday.exception.BackupFailedException;
import com.sportday.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The backup directory itself: what a file is called, what a listing says without
 * opening the body, and — the part that matters — that a name from a caller can
 * never reach outside the directory.
 *
 * <p>Requirement: back up before a reset, and get the backup back. A backup
 * endpoint that can be asked for {@code ../../application.yml} would turn "download
 * a backup" into "read any file on the server", which is why the guard is tested
 * rather than assumed.</p>
 */
class BackupStoreTest {

    /** 2026-10-04 16:21:35, so file names are the ones the design documents. */
    private static final Clock FIXED = Clock.fixed(
            LocalDateTime.of(2026, 10, 4, 16, 21, 35)
                    .atZone(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault());

    private ObjectMapper objectMapper;
    private Path directory;
    private BackupStore store;

    @BeforeEach
    void setUp() throws IOException {
        ObjectMapper apiMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        // The same mapper the application hands the store: dates as ISO-8601 text.
        objectMapper = BackupConfig.backupMapper();
        directory = Files.createTempDirectory("sportday-backup-store");
        store = new BackupStore(directory, objectMapper, FIXED);
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

    // --------------------------------------------------------------- fixtures

    private BackupFile backup(Counts counts) {
        return new BackupFile(SeasonBackupFile.APP_NAME, SeasonBackupFile.FORMAT_VERSION,
                new Header(SeasonBackupFile.APP_NAME, SeasonBackupFile.FORMAT_VERSION,
                        LocalDateTime.now(FIXED), counts, "written by a test"),
                List.of(),
                List.of(new EnrollmentLine(5L, 11L, 2L, "CONFIRMED", 7L, 3, null)),
                List.of(), List.of(), List.of());
    }

    // ------------------------------------------------------------ the file name

    @Test
    @DisplayName("a backup is named so it sorts chronologically, and it identifies itself")
    void aBackupIsNamedChronologically() throws IOException {
        store.write(backup(new Counts(1, 0, 0, 0, 0)));

        List<String> names = listFileNames();
        assertEquals(List.of("sportday-season-20261004-162135.json"), names,
                "sportday-season-yyyyMMdd-HHmmss.json sorts as plain text, newest last");
        assertTrue(Files.isRegularFile(directory.resolve(names.get(0))));
    }

    @Test
    @DisplayName("the file leads with a header that can be read without the body")
    void theFileLeadsWithAReadableHeader() throws IOException {
        store.write(backup(new Counts(4, 3, 2, 5, 1)));

        JsonNode root = objectMapper.readTree(directory.resolve(listFileNames().get(0)).toFile());
        assertEquals(SeasonBackupFile.APP_NAME, root.path("app").asText());
        assertEquals(SeasonBackupFile.FORMAT_VERSION, root.path("version").asInt());
        assertEquals(SeasonBackupFile.APP_NAME, root.path("header").path("app").asText());
        assertTrue(root.path("header").path("writtenAt").asText()
                        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"),
                "the header says when it was taken, as readable text: "
                        + root.path("header").path("writtenAt"));
        assertEquals(4, root.path("header").path("counts").path("enrollments").asInt());
        assertEquals(3, root.path("header").path("counts").path("finalEntries").asInt());
        assertEquals(2, root.path("header").path("counts").path("groups").asInt());
        assertEquals(5, root.path("header").path("counts").path("results").asInt());
        assertEquals(1, root.path("header").path("counts").path("records").asInt());
        assertEquals(1, root.path("enrollments").size(), "and the body follows it");
    }

    @Test
    @DisplayName("a written backup reads back as the same file")
    void aWrittenBackupReadsBack() throws IOException {
        store.write(backup(new Counts(1, 0, 0, 0, 0)));

        String name = listFileNames().get(0);
        BackupFile read = store.read(name);

        assertEquals(SeasonBackupFile.APP_NAME, read.app());
        assertEquals(1, read.enrollments().size());
        EnrollmentLine line = read.enrollments().get(0);
        assertEquals(11L, line.userId());
        assertEquals(2L, line.eventId());
        assertEquals("CONFIRMED", line.status());
        assertEquals(7L, line.groupId());
        assertEquals(3, line.lane());
    }

    // ------------------------------------------------------------- the listing

    @Test
    @DisplayName("a listing is newest first and carries the counts out of the header")
    void aListingIsNewestFirst() throws IOException {
        store.write(backup(new Counts(1, 0, 0, 0, 0)));
        Files.createFile(directory.resolve("sportday-season-20261003-090000.json"));

        List<BackupSummary> summaries = store.list();

        assertEquals(2, summaries.size());
        assertEquals("sportday-season-20261004-162135.json", summaries.get(0).name(),
                "the newest backup comes first");
        assertEquals("sportday-season-20261003-090000.json", summaries.get(1).name());
        // Jackson reads a small JSON number as an Integer, so a Long in the header
        // and an Integer out of the parser are compared as numbers.
        assertEquals(1L, ((Number) summaries.get(0).counts().get("enrollments")).longValue());
        assertTrue(summaries.get(0).bytes() > 0, "a listing says how big the file is");
        assertNotNull(summaries.get(0).writtenAt());
        assertNull(summaries.get(0).problem());
    }

    @Test
    @DisplayName("a file left in the directory that is not a backup is ignored")
    void aStrayFileIsIgnored() throws IOException {
        Files.writeString(directory.resolve("notes.txt"), "not a backup");

        assertTrue(store.list().isEmpty());
    }

    @Test
    @DisplayName("a corrupt backup is still listed, saying what is wrong with it")
    void aCorruptBackupIsListedAsSuspect() throws IOException {
        Files.writeString(directory.resolve("sportday-season-20261001-120000.json"), "{ this is not json");

        List<BackupSummary> summaries = store.list();

        assertEquals(1, summaries.size());
        assertNull(summaries.get(0).counts());
        assertNotNull(summaries.get(0).problem(),
                "an administrator needs to be told a backup is unreadable, not have it hidden");
    }

    // ---------------------------------------------------- the traversal guard

    @Test
    @DisplayName("a name that would leave the backup directory is refused")
    void aPathTraversalIsRefused() {
        for (String name : List.of(
                "../sportday-season-20261004-162135.json",
                "../../application.yml",
                "..\\sportday-season-20261004-162135.json",
                "sub/sportday-season-20261004-162135.json",
                "sub\\sportday-season-20261004-162135.json",
                "/etc/passwd",
                "C:\\Windows\\win.ini",
                "sportday-season-20261004-162135.json\0.png")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> store.resolve(name),
                    "a caller-supplied name must never be joined on and trusted: " + name);
            assertTrue(refused.getMessage().contains("Not a season backup name"),
                    refused.getMessage());
        }
    }

    @Test
    @DisplayName("a name that is not a backup name at all is refused")
    void aForeignNameIsRefused() {
        for (String name : List.of("application.yml", "sportday-season.json",
                "sportday-season-notatimestamp.json", "sportday-season-2026100-162135.json",
                "other-app-20261004-162135.json", "")) {
            assertThrows(IllegalArgumentException.class, () -> store.resolve(name), name);
        }
    }

    @Test
    @DisplayName("a backup name with no file behind it is a 404, not a 400")
    void aMissingBackupIsANotFound() {
        assertThrows(ResourceNotFoundException.class,
                () -> store.resolve("sportday-season-20261004-162135.json"));
    }

    @Test
    @DisplayName("a well-formed name inside the directory resolves")
    void aRealBackupResolves() throws IOException {
        store.write(backup(new Counts(0, 0, 0, 0, 0)));

        Path resolved = store.resolve("sportday-season-20261004-162135.json");

        assertTrue(Files.isRegularFile(resolved));
        assertEquals(directory.toRealPath(), resolved.toRealPath().getParent(),
                "and it is in the backup directory, which is the point of the guard");
    }

    // ------------------------------------------------------------- the writing

    @Test
    @DisplayName("a backup that cannot be written says so, and leaves no half-written file")
    void aFailedWriteSaysSo() throws IOException {
        Path inTheWay = Files.createFile(directory.resolve("in-the-way"));
        BackupStore broken = new BackupStore(inTheWay, objectMapper, FIXED);

        BackupFailedException failed = assertThrows(BackupFailedException.class,
                () -> broken.write(backup(new Counts(0, 0, 0, 0, 0))));

        assertTrue(failed.getMessage().contains("Nothing was reset"),
                "the message has to say the reset did not run: " + failed.getMessage());
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(List.of("in-the-way"), files.map(p -> p.getFileName().toString()).toList(),
                    "no temporary file is left behind by the failure");
        }
    }

    @Test
    @DisplayName("the directory is created if it is missing")
    void theDirectoryIsCreated() {
        Path nested = directory.resolve("does/not/exist/yet");
        BackupStore creating = new BackupStore(nested, objectMapper, FIXED);

        creating.write(backup(new Counts(0, 0, 0, 0, 0)));

        assertTrue(Files.isDirectory(nested));
        assertEquals(1, creating.list().size());
    }

    @Test
    @DisplayName("an Instant-derived name is stable for a fixed clock")
    void theNameComesFromTheClock() {
        assertEquals("sportday-season-20261004-162135.json",
                BackupStore.nameFor(LocalDateTime.ofInstant(Instant.parse("2026-10-04T16:21:35Z"),
                        ZoneId.of("UTC"))));
    }

    private List<String> listFileNames() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
