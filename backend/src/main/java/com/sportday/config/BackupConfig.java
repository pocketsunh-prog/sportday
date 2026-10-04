package com.sportday.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sportday.service.BackupStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;

/**
 * Where season backups are written, and how they are written.
 *
 * <p>{@code app.backup.dir} defaults to {@code backups/} relative to the working
 * directory — the directory the application was started from — and is created when
 * a backup is first written. The directory holds register data and is ignored by
 * git, exactly like {@code db-backup/}.</p>
 *
 * <p>The writer is Spring Boot's own {@link ObjectMapper} with one change: dates are
 * written as ISO-8601 text rather than as arrays of numbers. A backup is meant to be
 * readable — an administrator should be able to open it and see when it was taken
 * and what each mark was — and {@code [2026,10,4,16,21,35]} is not. Everything else
 * about the API's JSON (its modules, and
 * {@code jackson.default-property-inclusion: non_null} dropping absent values) is
 * kept.</p>
 */
@Slf4j
@Configuration
public class BackupConfig {

    /** The directory backups go in, relative to the working directory unless absolute. */
    @Value("${app.backup.dir:backups/}")
    private String backupDir;

    @Bean
    public BackupStore backupStore() {
        Path root = Paths.get(backupDir).toAbsolutePath().normalize();
        log.info("Season backups will be written to {}", root);
        return new BackupStore(root, backupMapper(), Clock.systemDefaultZone());
    }

    /**
     * The mapper a backup is written with, built here rather than injected.
     *
     * <p>Spring Boot 4 auto-configures <strong>Jackson 3</strong>'s mapper for the web
     * layer, so there is no {@code com.fasterxml.jackson.databind.ObjectMapper} bean to
     * inject — and asking for one stops the application from starting. Every DTO in
     * this codebase is annotated with Jackson 2 annotations, and so is this writer, so
     * building the mapper keeps the backup file's format independent of whichever
     * Jackson the web layer happens to use.</p>
     *
     * <p>Dates are written as ISO-8601 text rather than arrays of numbers, because a
     * backup is meant to be readable: an administrator should be able to open it and
     * see when it was taken and what each mark was, and {@code [2026,10,4,16,21,35]}
     * is not. Absent values are dropped, matching
     * {@code jackson.default-property-inclusion: non_null}.</p>
     */
    public static ObjectMapper backupMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
