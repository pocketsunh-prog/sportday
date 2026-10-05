-- ---------------------------------------------------------------------------
-- Relay teams: one team per CLASS, four runners and one reserve, and a name
-- somebody can type
--
-- The school's requirement, as confirmed:
--
--   1. a form relay is one team per CLASS of the event's grade — 1A, 1B, 1C, 1D,
--      2A — and the team is named after the class;
--   2. a house relay stays one team per grade x house, named "C Grade Yellow";
--   3. a team is four runners, with at most one reserve (four or five members);
--   4. an administrator may rename any team, a teacher only one belonging to
--      their own classes.
--
-- WHAT THIS SCRIPT DOES — and, more usefully, what it does NOT have to do.
--
--   (a) The class split needs NO schema change. `relay_teams.team_key` is already
--       VARCHAR(40) and already holds the thing the team is matched on, so it holds
--       "1A" exactly as well as it held "1". A re-derive makes the class teams; see
--       the note at the bottom about the FORM teams those replace.
--
--   (b) The 4-runners-plus-1-reserve rule needs NO schema change either. A runner's
--       `leg` was already 1-based with anything past the race's own legs read as a
--       reserve, and the ceiling is computed from the event
--       (`Event.getRelayMemberCap()` = legs + 1 when reserves are allowed), so the
--       sixth runner is refused in the service with nothing new to store.
--
--   (c) A name typed by hand needs ONE new nullable column, `name_overridden`. The
--       label itself already exists; the flag is what tells a re-derive to leave a
--       typed name alone instead of putting the derived one back over it.
--
--   (d) `event_results.relay_team_id` — a relay's mark held against the team rather
--       than an athlete — is NOT part of this round. It is written up in
--       `relay-teams-results-migration.sql` beside this file by the round that adds
--       team marks to the grid and the sheet.
--
-- Hibernate's ddl-auto=update adds the column on start where the application owns the
-- schema, so this script is only needed where ddl-auto is `validate`, or to apply the
-- schema ahead of a deploy. It is idempotent and safe to run repeatedly. DO NOT run it
-- against a live database without meaning to — nothing here is destructive, but
-- `relay_teams` holds the school's relay selections once they exist.
-- ---------------------------------------------------------------------------

-- 1. `relay_teams.name_overridden` — true when the team's name was typed by hand.
--    Nullable on purpose: NULL reads as false through
--    `RelayTeam.isNameOverridden()`, so every team already on file is the roster's to
--    label, exactly as it was before the column existed. MySQL has no
--    "ADD COLUMN IF NOT EXISTS", so the column is guarded against the catalogue.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'relay_teams'
                  AND COLUMN_NAME = 'name_overridden') > 0,
               'SELECT 1',
               'ALTER TABLE relay_teams ADD COLUMN name_overridden BIT(1) NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- The one piece of DATA this round changes its meaning for: a FORM relay's existing
-- teams.
--
-- A FORM team derived before this round is keyed with a bare form number — "1", "2",
-- "10" — because the old rule was one team per form. The new rule keys a form team
-- with the whole class, so the next derive creates "1A", "1B" ... beside them. The
-- old teams are not deleted and their runners are not moved: a team somebody runs in
-- is never taken away by a derive, empty ones are only dropped when the caller asks
-- for `prune=true`, and the service still accepts a runner into a legacy form-keyed
-- team (a team keyed "1" takes the runners of Form 1), so no live team is left unable
-- to be filled.
--
-- Nothing is done to them here on purpose. Moving a runner from a form team to a class
-- team means deciding which class each runner is in, which is the school's selection
-- and not a migration's to make. The intended path is: derive the class teams, move
-- the runners across on the relay screen, then `POST
-- /api/admin/events/{id}/relay-teams/derive?prune=true` to drop the now-empty legacy
-- teams. An administrator who would rather not wait can delete every team of the event
-- with `DELETE /api/admin/events/{id}/relay-teams` and derive afresh.
--
-- If a school does want the empty legacy teams gone in one statement, this is the
-- shape of it — deliberately NOT executed here, and deliberately limited to teams with
-- NO runners, because a team with selections is not a script's to delete:
--
--   DELETE t FROM relay_teams t
--    WHERE t.kind = 'FORM'
--      AND t.team_key REGEXP '^[0-9]+$'          -- a bare form number, so pre-split
--      AND NOT EXISTS (SELECT 1 FROM relay_team_members m WHERE m.team_id = t.id);
-- ---------------------------------------------------------------------------

-- 2. A re-derive must be idempotent for a team that already exists. The uniqueness
--    that makes it so — one team per event, kind and key — is the constraint the
--    table already has; asserted here so a database where it is missing is put right
--    rather than left to produce a duplicate team on the next derive.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'relay_teams'
                  AND INDEX_NAME = 'uk_relay_team_event_kind_key') > 0,
               'SELECT 1',
               'ALTER TABLE relay_teams ADD UNIQUE KEY uk_relay_team_event_kind_key (event_id, kind, team_key)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
