package com.sportday.controller;

import com.sportday.dto.SeasonBackupFile.BackupSummary;
import com.sportday.exception.ResourceNotFoundException;
import com.sportday.service.BackupStore;
import com.sportday.service.SeasonBackupService;
import com.sportday.service.SeasonResetService;
import com.sportday.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The ADMIN backup endpoints: listing, downloading and restoring.
 *
 * <p>The traversal guard lives in {@link BackupStore} and is proved there against a
 * real directory. What is checked here is that the endpoint does not get in front of
 * it: a name it will not resolve comes back as a 400 rather than as a file, and a
 * download is served with the file's own name on it.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminControllerBackupTest {

    private static final String NAME = "sportday-season-20261004-162135.json";

    @Mock private UserService userService;
    @Mock private SeasonResetService seasonResetService;
    @Mock private SeasonBackupService seasonBackupService;
    @Mock private BackupStore backupStore;

    @InjectMocks private AdminController controller;

    @Test
    @DisplayName("listing returns whatever the directory holds, newest first")
    void listingReturnsTheDirectory() {
        BackupSummary summary = new BackupSummary(NAME, 4096L,
                LocalDateTime.of(2026, 10, 4, 16, 21, 35),
                Map.of("enrollments", 1038), null);
        when(backupStore.list()).thenReturn(List.of(summary));

        ResponseEntity<List<BackupSummary>> response = controller.listBackups();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(summary), response.getBody());
    }

    @Test
    @DisplayName("downloading one serves the file, named, as JSON")
    void downloadingServesTheFile() {
        Path file = Path.of("backups", NAME);
        when(backupStore.resolve(NAME)).thenReturn(file);

        ResponseEntity<FileSystemResource> response = controller.downloadBackup(NAME);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(file.normalize(), Path.of(response.getBody().getPath()).normalize());
        assertTrue(String.valueOf(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains(NAME), "the download is named after the backup");
        assertEquals("application/json", String.valueOf(response.getHeaders().getContentType()));
    }

    @Test
    @DisplayName("a name that would leave the backup directory is refused, not served")
    void aTraversalNameIsRefused() {
        when(backupStore.resolve(anyString())).thenThrow(
                new IllegalArgumentException("Not a season backup name: '../application.yml'."));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> controller.downloadBackup("../application.yml"));

        assertTrue(refused.getMessage().contains("Not a season backup name"),
                "the store's refusal reaches the caller, which the handler maps to 400");
        verify(backupStore, never()).list();
    }

    @Test
    @DisplayName("a backup name with no file behind it is a 404")
    void aMissingBackupIsANotFound() {
        when(backupStore.resolve(NAME))
                .thenThrow(new ResourceNotFoundException("No such season backup: " + NAME));

        assertThrows(ResourceNotFoundException.class, () -> controller.downloadBackup(NAME));
    }

    @Test
    @DisplayName("restoring reports what the file put back")
    void restoringReportsWhatCameBack() {
        when(seasonBackupService.restore(NAME)).thenReturn(Map.of(
                "restoredFrom", NAME,
                "enrollmentsRestored", 1038,
                "resultsRestored", 1206));

        ResponseEntity<Map<String, Object>> response = controller.restoreBackup(NAME);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(NAME, response.getBody().get("restoredFrom"));
        assertEquals(1038, response.getBody().get("enrollmentsRestored"));
        verify(seasonBackupService).restore(NAME);
    }

    @Test
    @DisplayName("the reset endpoint answers with the service's summary")
    void theResetAnswersWithTheSummary() {
        when(seasonResetService.resetSeason()).thenReturn(Map.of("enrollmentsRemoved", 1038L));

        ResponseEntity<Map<String, Object>> response = controller.resetSeason();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1038L, response.getBody().get("enrollmentsRemoved"));
    }
}
