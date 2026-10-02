package com.sportday.repository;

import com.sportday.entity.SportDaySettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SportDaySettingsRepository extends JpaRepository<SportDaySettings, Long> {
}
