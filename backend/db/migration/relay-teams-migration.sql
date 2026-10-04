-- ---------------------------------------------------------------------------
-- Relay teams: a form relay and a house relay, and who runs in them
--
-- Requirement 3: the form relay is one team per form (中一 to 中六) of the event's
-- own grade, and the house relay is one team per house within that grade. A relay
-- event says which of the two it is, and a teacher (or an administrator) picks the
-- students who run each leg.
--
-- Adds:
--   relay_teams                    one team per form, or per house, of a relay event
--   relay_team_members             the runners — one row per leg or reserve
--   events.relay_team_kind         FORM or HOUSE; NULL means the relay is undivided
--   events.relay_team_size         legs in a team: 4 for a 4x100M (NULL = the type's default)
--   events.relay_reserves_allowed  whether a team may name reserves past its legs
--
-- The three `events` columns are NULLable on purpose, exactly like
-- `direct_to_final` and `outcome` before them: Hibernate's ddl-auto=update adds a
-- column to a table that already holds rows, and every relay event already in the
-- programme must stay UNDIVIDED rather than being given a kind it never asked for.
-- NULL reads as: no kind, the type's own four legs, and no reserves — which is
-- exactly how those events behave today.
--
-- Hibernate creates all of this on start where ddl-auto is `update`, so the script
-- is only needed where ddl-auto is `validate`, or to apply the schema ahead of a
-- deploy. It is idempotent and safe to run repeatedly. DO NOT run it against a live
-- database without meaning to — nothing here is destructive, but the tables are the
-- school's relay selections once they exist.
-- ---------------------------------------------------------------------------

-- 1. The teams themselves.
CREATE TABLE IF NOT EXISTS relay_teams (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    event_id    BIGINT      NOT NULL,
    kind        ENUM('FORM','HOUSE') NOT NULL,
    team_key    VARCHAR(40) NOT NULL,
    label       VARCHAR(80) NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NULL,
    PRIMARY KEY (id),
    -- One team per form, or per house, of an event: re-deriving the teams can never
    -- produce a second Form 1.
    UNIQUE KEY uk_relay_team_event_kind_key (event_id, kind, team_key),
    KEY idx_relay_team_event (event_id),
    CONSTRAINT fk_relay_team_event FOREIGN KEY (event_id) REFERENCES events (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 2. The runners. Two keys keep a team honest: one athlete cannot hold two legs of
--    one team, and two athletes cannot both be down for leg 3.
CREATE TABLE IF NOT EXISTS relay_team_members (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    team_id     BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    leg         INT         NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_relay_member_team_user (team_id, user_id),
    UNIQUE KEY uk_relay_member_team_leg (team_id, leg),
    KEY idx_relay_member_user (user_id),
    CONSTRAINT fk_relay_member_team FOREIGN KEY (team_id) REFERENCES relay_teams (id),
    CONSTRAINT fk_relay_member_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 3. `events` — what kind of relay this is, how big a team is, and whether reserves
--    are allowed. MySQL has no "ADD COLUMN IF NOT EXISTS", so each column is
--    guarded against information_schema, one at a time, so a database that already
--    has some of them (a half-applied run, or a Hibernate-created relay_team_kind)
--    is completed rather than skipped.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'events'
                  AND COLUMN_NAME = 'relay_team_kind') > 0,
               'SELECT 1',
               'ALTER TABLE events ADD COLUMN relay_team_kind ENUM(''FORM'',''HOUSE'') NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'events'
                  AND COLUMN_NAME = 'relay_team_size') > 0,
               'SELECT 1',
               'ALTER TABLE events ADD COLUMN relay_team_size INT NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'events'
                  AND COLUMN_NAME = 'relay_reserves_allowed') > 0,
               'SELECT 1',
               'ALTER TABLE events ADD COLUMN relay_reserves_allowed BIT(1) NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
