-- ---------------------------------------------------------------------------
-- School years, and the school's own details
--
-- Adds:
--   seasons             one row per school year (one sport day each)
--   events.season_id    the year an event belongs to
--   sport_day_settings  the school's name, address, principal and sheet title
--
-- Hibernate's ddl-auto=update creates all of these on start, so this script is
-- only needed where ddl-auto is validate, or to apply the schema ahead of a
-- deploy. It is idempotent.
--
-- The application also adopts any event with no year into the year of its own
-- date on the next start, so existing programmes appear in the year picker.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS seasons (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    year            INT          NOT NULL,
    name            VARCHAR(120) NULL,
    sport_day_date  DATE         NULL,
    enrollment_open BIT(1)       NOT NULL DEFAULT b'0',
    notes           VARCHAR(500) NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_season_year (year)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'events'
                  AND COLUMN_NAME = 'season_id') > 0,
               'SELECT 1',
               'ALTER TABLE events ADD COLUMN season_id BIGINT NULL, '
               'ADD KEY idx_event_season (season_id), '
               'ADD CONSTRAINT fk_event_season FOREIGN KEY (season_id) REFERENCES seasons (id)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sport_day_settings'
                  AND COLUMN_NAME = 'school_name') > 0,
               'SELECT 1',
               'ALTER TABLE sport_day_settings ADD COLUMN school_name VARCHAR(160) NULL, '
               'ADD COLUMN school_name_zh VARCHAR(160) NULL, '
               'ADD COLUMN address VARCHAR(255) NULL, '
               'ADD COLUMN principal VARCHAR(120) NULL, '
               'ADD COLUMN sport_day_title VARCHAR(160) NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
