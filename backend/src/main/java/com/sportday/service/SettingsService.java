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

    /**
     * How many events of this category one student may enter.
     *
     * <p>This is the number the entry itself is refused against, and a
     * {@link EventCategory#RELAY} deliberately takes the <strong>track</strong>
     * allowance rather than the field's single entry. The school's rule is that a
     * student may hold a leg in a <em>house</em> relay and a leg in a
     * <em>class</em> relay — the two ways a relay is divided — and those are two
     * events; a relay capped at the field's one would forbid exactly that.</p>
     *
     * <p>The count is the relay's own even though the allowance is the track's: an
     * entry is counted against the event's own category
     * ({@code Event.getCategoryOrDefault()}), so a relay is counted as
     * {@code RELAY} and no longer uses up one of the student's two individual
     * track entries.</p>
     */
    @Transactional
    public int maxEntriesFor(EventCategory category) {
        SportDaySettings settings = get();
        if (category == EventCategory.FIELD) {
            return settings.getFieldMaxEntries();
        }
        return settings.getTrackMaxEntries();
    }

    /** Points for a placing, on the individual or the relay scale. */
    @Transactional
    public int pointsForPlace(int place, boolean relay) {
        return get().pointsForPlace(place, relay);
    }
}
