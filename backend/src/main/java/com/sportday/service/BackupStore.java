package com.sportday.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportday.dto.SeasonBackupFile.BackupFile;
import com.sportday.dto.SeasonBackupFile.BackupSummary;
import com.sportday.exception.BackupFailedException;
import com.sportday.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The backup directory: writing a season backup into it, getting one back out, and
 * refusing to be talked out of it.
 *
 * <p>Files are named {@code sportday-season-yyyyMMdd-HHmmss.json} so a listing
 * sorts chronologically as plain text, and so a backup identifies itself by name
 * alone. The directory is created if it is missing, and is ignored by git: it
 * holds the school's register data, exactly like {@code db-backup/}.</p>
 *
 * <p>Every name that comes from a caller goes through {@link #resolve(String)}: the
 * name must be a plain file name with the expected prefix and suffix, and the
 * resolved path must be <em>inside</em> the backup directory. {@code ../} and a
 * symlink out of the directory are both refused, not sanitised.</p>
 */
@Slf4j
public class BackupStore {

    /** Prefix of every backup file — the app's name, so a stray file is obvious. */
    public static final String FILE_PREFIX = "sportday-season-";

    /** Suffix of every backup file. */
    public static final String FILE_SUFFIX = ".json";

    /** The sortable timestamp in a file name. */
    public static final DateTimeFormatter NAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Used only to pull the header out of a file that may have been hand-edited. */
    private static final DateTimeFormatter DISPLAY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path root;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BackupStore(Path root, ObjectMapper objectMapper, Clock clock) {
        this.root = root;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** The directory backups are written to. */
    public Path root() {
        return root;
    }

    // ------------------------------------------------------------- writing

    /**
     * Serialises the backup into a new file, and returns the file that was written.
     *
     * <p>Written to a temporary file in the same directory and then moved into
     * place, so a listing never shows a half-written backup and a failure never
     * leaves one behind. Any failure at all — the directory missing and
     * uncreatable, a disk error, a value that will not serialise — comes back as a
     * {@link BackupFailedException}, because the caller's next step is to abandon
     * the reset rather than carry on without a backup.</p>
     */
    public Written write(BackupFile backup) {
        String name = nameFor(LocalDateTime.now(clock));
        Path target = root.resolve(name);
        Path temporary = null;
        try {
            Files.createDirectories(root);
            temporary = Files.createTempFile(root, FILE_PREFIX, ".part");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(out, backup);
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
        } catch (IOException | RuntimeException ex) {
            deleteQuietly(temporary);
            throw new BackupFailedException("The season backup could not be written to " + root
                    + ": " + ex.getMessage() + ". Nothing was reset.", ex);
        }

        long bytes;
        try {
            bytes = Files.size(target);
        } catch (IOException ex) {
            bytes = -1L;
        }
        log.info("Season backup written: {} ({} bytes)", name, bytes);
        return new Written(name, bytes, backup.header().writtenAt());
    }

    /** The file name a backup taken at that moment gets. */
    public static String nameFor(LocalDateTime moment) {
        return FILE_PREFIX + NAME_TIMESTAMP.format(moment) + FILE_SUFFIX;
    }

    // ------------------------------------------------------------- listing

    /**
     * Every backup in the directory, newest first.
     *
     * <p>The counts come out of each file's header, so a listing says what a backup
     * holds without anyone opening it. A file that cannot be parsed is still listed,
     * with {@code problem} saying so — a corrupt backup is exactly the thing an
     * administrator needs to be told about, not hidden.</p>
     */
    public List<BackupSummary> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        && isBackupName(file.getFileName().toString())) {
                    files.add(file);
                }
            }
        } catch (IOException ex) {
            throw new BackupFailedException("The backup directory " + root
                    + " could not be read: " + ex.getMessage(), ex);
        }

        // The name carries a sortable timestamp, so newest first is a name sort.
        files.sort(Comparator.comparing((Path file) -> file.getFileName().toString()).reversed());

        List<BackupSummary> summaries = new ArrayList<>(files.size());
        for (Path file : files) {
            summaries.add(describe(file));
        }
        return summaries;
    }

    /** One file, described — or described as unreadable, without failing the listing. */
    private BackupSummary describe(Path file) {
        String name = file.getFileName().toString();
        long bytes = -1L;
        try {
            bytes = Files.size(file);
        } catch (IOException ignored) {
            // Left as -1: the size is a nicety, not the point of the listing.
        }
        try {
            return new BackupSummary(name, bytes, timestampOf(name), headerCounts(file), null);
        } catch (IOException | RuntimeException ex) {
            log.warn("Backup {} could not be read: {}", name, ex.getMessage());
            return new BackupSummary(name, bytes, timestampOf(name), null, ex.getMessage());
        }
    }

    // ------------------------------------------------------------- reading

    /**
     * The full backup in one file.
     *
     * @throws ResourceNotFoundException when there is no such backup
     */
    public BackupFile read(String name) {
        Path file = resolve(name);
        try {
            return objectMapper.readValue(file.toFile(), BackupFile.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Backup " + file.getFileName()
                    + " could not be read as a season backup: " + ex.getMessage(), ex);
        }
    }

    /**
     * The file a caller named, proved to be inside the backup directory.
     *
     * <p>A caller-supplied name is never joined onto the directory and trusted: it
     * must be a single path segment with the backup prefix and suffix, and the
     * <em>resolved</em> file must sit directly in the resolved directory. That
     * refuses {@code ../} and an absolute path, and also refuses a symbolic link
     * pointing out of the directory, because the check is made on the real path.</p>
     *
     * @throws IllegalArgumentException when the name is not a backup in this directory
     * @throws ResourceNotFoundException when it is a valid name but there is no such file
     */
    public Path resolve(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A backup name is required.");
        }
        String candidate = name.trim();
        if (!isBackupName(candidate)) {
            throw new IllegalArgumentException("Not a season backup name: '" + name
                    + "'. Expected " + FILE_PREFIX + "yyyyMMdd-HHmmss" + FILE_SUFFIX + ".");
        }

        Path base = root.toAbsolutePath().normalize();
        Path file = base.resolve(candidate).normalize();
        boolean contained;
        try {
            contained = Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                    && file.toRealPath().getParent().equals(base.toRealPath());
        } catch (IOException ex) {
            contained = false;
        }
        if (!contained) {
            throw new ResourceNotFoundException("No such season backup: " + candidate);
        }
        return file;
    }

    /**
     * True for a name this store would have written.
     *
     * <p>Rejecting anything with a separator, a {@code ..} segment, a drive letter
     * or a NUL is the first half of the traversal guard; {@link #resolve} proves the
     * second half against the real directory.</p>
     */
    static boolean isBackupName(String name) {
        if (name == null || name.isBlank() || name.length() > 255) {
            return false;
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || name.indexOf('\0') >= 0) {
            return false;
        }
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) {
            return false;
        }
        String stamp = name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
        if (stamp.length() != 15 || stamp.charAt(8) != '-') {
            return false;
        }
        for (int i = 0; i < stamp.length(); i++) {
            char c = stamp.charAt(i);
            if (i == 8) {
                continue;
            }
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------- helpers

    /** The moment in a backup's name, so a listing can show it even unreadably. */
    private static LocalDateTime timestampOf(String name) {
        try {
            String stamp = name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
            return LocalDateTime.parse(stamp, NAME_TIMESTAMP);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * The counts out of a file's header, and nothing else.
     *
     * <p>Only the header is deserialised, so a listing never pays for the body: the
     * parser stops at the end of the {@code counts} object and the arrays of
     * enrollments, marks and records behind it are never turned into objects. That
     * is the point of having a header at all — with one, a backup identifies itself
     * in a few hundred bytes, to this method and to a person reading the file.</p>
     */
    private Map<String, Object> headerCounts(Path file) throws IOException {
        // The mapper's own createParser, so the parser carries the mapper's codec
        // (the Java-time module) and its settings.
        try (var parser = objectMapper.createParser(file.toFile())) {
            JsonNode rootNode = objectMapper.readTree(parser);
            JsonNode counts = rootNode == null ? null : rootNode.path("header").path("counts");
            if (counts == null || !counts.isObject()) {
                return null;
            }
            return objectMapper.convertValue(counts, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("Could not remove the incomplete backup {}: {}", path, ex.getMessage());
        }
    }

    /** How a written backup is reported back to whoever asked for it. */
    public record Written(String name, long bytes, LocalDateTime writtenAt) {

        /** The same facts keyed for the reset's summary. */
        public Map<String, Object> asMap() {
            Map<String, Object> reported = new LinkedHashMap<>();
            reported.put("backupFile", name);
            reported.put("backupBytes", bytes);
            reported.put("backupWrittenAt", writtenAt == null ? null
                    : DISPLAY_TIMESTAMP.format(writtenAt));
            return reported;
        }
    }
}