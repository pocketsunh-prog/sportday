package com.sportday.service;

import com.sportday.dto.SportDaySettingsDTO;
import com.sportday.entity.EventCategory;
import com.sportday.entity.SportDaySettings;
import com.sportday.repository.SportDaySettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the school's rules.
 *
 * <p>There is one settings row. It is created with the documented defaults the
 * first time anybody asks for it, so a fresh database behaves exactly as it did
 * before these settings existed.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final SportDaySettingsRepository settingsRepository;

    /** The settings, created with defaults if this is the first time. */
    @Transactional
    public SportDaySettings get() {
        return settingsRepository.findById(SportDaySettings.SINGLETON_ID)
                .map(settings -> {
                    settings.fillBlanks();
                    return settings;
                })
                .orElseGet(() -> {
                    SportDaySettings created = settingsRepository.save(SportDaySettings.defaults());
                    log.info("Created the sport day settings with the default entry limits and points");
                    return created;
                });
    }

    @Transactional
    public SportDaySettings update(SportDaySettingsDTO payload) {
        SportDaySettings settings = get();
        payload.applyTo(settings);
        SportDaySettings saved = settingsRepository.save(settings);
        log.info("Sport day settings updated: {} track / {} field entries, points {}/{}/{}/{} (to {})",
                saved.getTrackMaxEntries(), saved.getFieldMaxEntries(), saved.getPointsFirst(),
                saved.getPointsSecond(), saved.getPointsThird(), saved.getPointsTop(),
                saved.getPointsTopPlace());
        return saved;
    }

    /** Restores the documented defaults, e.g. after a season has been set up. */
    @Transactional
    public SportDaySettings resetToDefaults() {
        return settingsRepository.save(SportDaySettings.defaults());
    }

    /** How many events of this category one student may enter. */
    @Transactional
    public int maxEntriesFor(EventCategory category) {
        SportDaySettings settings = get();
        return category == EventCategory.FIELD
                ? settings.getFieldMaxEntries()
                : settings.getTrackMaxEntries();
    }

    /** Points for a placing, on the individual or the relay scale. */
    @Transactional
    public int pointsForPlace(int place, boolean relay) {
        return get().pointsForPlace(place, relay);
    }
}
