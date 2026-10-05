-- ---------------------------------------------------------------------------
-- Relay teams made BY HAND: a team out of chosen students, under a name the
-- school typed
--
-- The school's requirement, as confirmed:
--
--   "teacher can select student who applied relay event and create a relay team.
--    create relay event base on selected relay team."
--
-- A teacher ticks any students who applied and creates a team from them, typing
-- the team's own name as free text — `1A`, `B Grade Yellow`, anything. The team
-- is NOT required to be one class or one house, which is exactly what the derived
-- FORM and HOUSE teams cannot express.
--
-- WHAT THIS SCRIPT DOES — and why it is needed at all, when the class split
-- needed nothing.
--
--   (a) `relay_teams.hand_made` — one new NULLABLE column, true when the team was
--       built by hand rather than derived from the register. Null reads as false
--       through `RelayTeam.isHandMade()`, so every team already on file was
--       derived and behaves exactly as it did before the column existed.
--
--   (b) `relay_teams.kind` MUST BECOME NULLABLE. This is the important one, and
--       it is the opposite of the class split: `relay-teams-migration.sql`
--       created the column as
--
--           kind ENUM('FORM','HOUSE') NOT NULL
--
--       and the live database has exactly that (verified against
--       information_schema on the running instance). A hand-made team is
--       deliberately of NO kind — that is the structural half of the guarantee
--       that a later derive can never match, rename, re-key or prune it, because
--       a derive's key set is built from class names (FORM) or house names
--       (HOUSE) and a team with no kind can never be in it. So a hand-made team's
--       row has `kind IS NULL`, and against the current schema the INSERT is
--       refused with "Column 'kind' cannot be null".
--
--       Adding `RelayTeamKind.MANUAL` instead — a third enum value to mean "not
--       derived" — was considered and rejected: it would put a value into the
--       column that `RelayTeamKind` is documented to hold only FORM or HOUSE,
--       every existing `kind == RelayTeamKind.FORM` / `== HOUSE` test would need
--       a third branch, and `Event.relayTeamKind` shares the same enum and would
--       have to be taught to refuse it. Null is the honest value: this team
--       belongs to no division of the roster at all.
--
--       Nullable kind also needs NO new unique key. `(event_id, kind, team_key)`
--       already holds, and in MySQL a NULL in a unique key is distinct from every
--       other NULL, so hand-made teams cannot collide with each other or with a
--       derived team on this key. Their real identity — one team of a given name
--       per event — is enforced in the service
--       (`RelayTeamService.requireNameIsFree`), on the event and the trimmed
--       label, exactly as it already is for a rename.
--
-- DO NOT run this against a live database without meaning to: `relay_teams`
-- holds the school's relay selections. It is idempotent and safe to repeat.
--
-- Hibernate's ddl-auto=update adds `hand_made` on start where the application
-- owns the schema. Whether it will also RELAX `kind` to NULL depends on the
-- dialect's column comparison, so this script is the reliable path and is what
-- should be run before the application is restarted with hand-made teams in use.
-- It is also required where ddl-auto is `validate`.
-- ---------------------------------------------------------------------------

-- 1. `relay_teams.hand_made` — true when the team's name and runners were chosen
--    by hand rather than derived from the roster. Nullable on purpose (NULL reads
--    as false). MySQL has no "ADD COLUMN IF NOT EXISTS", so it is guarded against
--    the catalogue.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'relay_teams'
                  AND COLUMN_NAME = 'hand_made') > 0,
               'SELECT 1',
               'ALTER TABLE relay_teams ADD COLUMN hand_made BIT(1) NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. `relay_teams.kind` — from ENUM('FORM','HOUSE') NOT NULL to the same enum,
--    nullable, so a team built by hand can belong to no kind. Guarded on
--    IS_NULLABLE so it is only done once. `MODIFY COLUMN` keeps the existing
--    values, so every derived team keeps its kind untouched.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'relay_teams'
                  AND COLUMN_NAME = 'kind' AND IS_NULLABLE = 'NO') > 0,
               'ALTER TABLE relay_teams MODIFY COLUMN kind ENUM(''FORM'',''HOUSE'') NULL',
               'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. Asserted, not re-created: `(event_id, kind, team_key)` is what makes a
--    re-derive idempotent and what keeps a hand-made team and a derived team
--    apart (NULL kind vs FORM/HOUSE). A database where it is missing is put
--    right rather than left to produce a duplicate team on the next derive.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'relay_teams'
                  AND INDEX_NAME = 'uk_relay_team_event_kind_key') > 0,
               'SELECT 1',
               'ALTER TABLE relay_teams ADD UNIQUE KEY uk_relay_team_event_kind_key (event_id, kind, team_key)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- NO part of this script touches the school's data. There is no data migration,
-- and there is nothing to migrate: no team on file is hand-made, so every
-- existing row keeps `hand_made = NULL` and its own `kind`. The live instance
-- currently holds ZERO relay teams (verified read-only), so this is schema only.
-- ---------------------------------------------------------------------------
