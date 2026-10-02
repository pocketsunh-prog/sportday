-- ---------------------------------------------------------------------------
-- Settings, school records and championships
--
-- Adds two tables:
--   sport_day_settings  one row of editable rules — entry limits and points
--   event_records       one school record per event type + division + grade
--
-- Hibernate's ddl-auto=update creates both on start, so this script is only
-- needed for a database managed with validate, or to apply the schema ahead of
-- a deploy. It is idempotent and safe to run repeatedly.
--
-- The settings row itself is seeded by the application with the documented
-- defaults (2 track / 1 field entries, 9/6/3 points and 1 from 4th to 8th,
-- relay 30/20/10), so this script leaves the table empty on purpose.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS sport_day_settings (
    id                      BIGINT   NOT NULL,
    track_max_entries       INT      NOT NULL DEFAULT 2,
    field_max_entries       INT      NOT NULL DEFAULT 1,
    points_first            INT      NOT NULL DEFAULT 9,
    points_second           INT      NOT NULL DEFAULT 6,
    points_third            INT      NOT NULL DEFAULT 3,
    points_top_place        INT      NOT NULL DEFAULT 8,
    points_top              INT      NOT NULL DEFAULT 1,
    relay_points_first      INT      NOT NULL DEFAULT 30,
    relay_points_second     INT      NOT NULL DEFAULT 20,
    relay_points_third      INT      NOT NULL DEFAULT 10,
    relay_points_top        INT      NOT NULL DEFAULT 1,
    updated_at              DATETIME NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS event_records (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    event_type              VARCHAR(40)  NOT NULL,
    sex                     VARCHAR(10)  NOT NULL,
    grade                   VARCHAR(4)   NOT NULL,
    -- The mark that stands: the better of the hand-entered baseline and the best
    -- result. NULL while an event has neither.
    mark                    DECIMAL(10,3) NULL,
    unit                    VARCHAR(20)  NULL,
    holder_user_id          BIGINT       NULL,
    holder_name             VARCHAR(120) NULL,
    result_id               BIGINT       NULL,
    event_id                BIGINT       NULL,
    achieved_on             DATE         NULL,
    -- The mark an administrator typed in, kept separately so recomputing from the
    -- results never loses it.
    manual_mark             DECIMAL(10,3) NULL,
    manual_unit             VARCHAR(20)  NULL,
    manual_holder_name      VARCHAR(120) NULL,
    manual_achieved_on      DATE         NULL,
    previous_mark           DECIMAL(10,3) NULL,
    previous_holder_name    VARCHAR(120) NULL,
    previous_achieved_on    DATE         NULL,
    has_previous            BIT(1)       NOT NULL DEFAULT b'0',
    updated_at              DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_record_type_sex_grade (event_type, sex, grade),
    KEY idx_record_holder (holder_user_id),
    KEY idx_record_result (result_id),
    KEY idx_record_event (event_id),
    CONSTRAINT fk_record_holder FOREIGN KEY (holder_user_id) REFERENCES users (id),
    CONSTRAINT fk_record_result FOREIGN KEY (result_id) REFERENCES event_results (id),
    CONSTRAINT fk_record_event  FOREIGN KEY (event_id)  REFERENCES events (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
