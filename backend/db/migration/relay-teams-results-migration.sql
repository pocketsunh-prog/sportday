-- ---------------------------------------------------------------------------
-- A relay's mark belongs to a team, not to an athlete
--
-- A 4x100M is scored by team: one time for the four runners together. The result
-- tables were built per athlete, so `event_results` gains a nullable `relay_team_id`:
--
--   relay_team_id IS NULL      the row is an athlete's own mark, as before
--   relay_team_id IS NOT NULL  the row is that team's time
--
-- `user_id` deliberately stays NOT NULL. Every existing query — standings, school
-- records, the results PDF, the season backup and restore — joins on it, and making
-- it nullable would turn one schema change into a review of all of them. A relay row
-- therefore names the team's FIRST RUNNER as an anchor. The time belongs to the team;
-- the user column only says which row to find it on.
--
-- This is the compromise recorded in EventResult#relayTeam, and it is the reason a
-- relay time counts for school records and shows up in the results at all.
--
-- Idempotent: MySQL has no ADD COLUMN IF NOT EXISTS, so this is written to be safe
-- to re-run by checking the catalogue first.
-- ---------------------------------------------------------------------------

SET @add_relay_team := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE event_results ADD COLUMN relay_team_id BIGINT NULL, ADD CONSTRAINT fk_event_results_relay_team FOREIGN KEY (relay_team_id) REFERENCES relay_teams (id) ON DELETE SET NULL',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'event_results'
      AND COLUMN_NAME = 'relay_team_id'
);

PREPARE add_relay_team FROM @add_relay_team;
EXECUTE add_relay_team;
DEALLOCATE PREPARE add_relay_team;

-- One mark per team per event per stage: a team cannot have two times in the same race.
SET @add_relay_unique := (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE UNIQUE INDEX uk_event_results_relay_team ON event_results (relay_team_id, event_id, stage)',
        'SELECT 1'
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'event_results'
      AND INDEX_NAME = 'uk_event_results_relay_team'
);

PREPARE add_relay_unique FROM @add_relay_unique;
EXECUTE add_relay_unique;
DEALLOCATE PREPARE add_relay_unique;
