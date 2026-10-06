-- ---------------------------------------------------------------------------
-- A required standard on the events that qualify against one
--
-- The school's requirement, as confirmed: an event may carry a **required
-- standard** — the qualifying mark an athlete must reach — and only these do:
--
--   * the track races of 400M and over  — RUN_400M, HURDLES_400M, RUN_800M,
--     RUN_1500M, RUN_5000M.  60M, 100M, 200M and the hurdles under 400M do NOT;
--   * every field event — SHOT_PUT, DISCUSSION_THROW, JAVELIN_THROW,
--     HAMMER_THROW, LONG_JUMP, HIGH_JUMP, TRIPLE_JUMP, POLE_VAULT.
--
-- A relay does NOT carry one either: a relay is its own category now (`RELAY`),
-- run and scored by team, not a track event. Which types qualify is answered by
-- one method in the code — `Event.EventType.carriesAStandard()` — and the API
-- refuses a standard on anything else, so this column only ever holds a number
-- on a qualifying event.
--
-- This adds one nullable column:
--
--   standard IS NULL      the event carries no standard, and behaves exactly as
--                         it does today, byte for byte
--   standard IS NOT NULL  that number is the qualifying mark, in the event's own
--                         unit — seconds for a race, metres for a field event
--
-- A time meets the standard at or UNDER it and a distance at or OVER it; that
-- direction is `EventType.isLowerBetter()`, the codebase's existing rule, and is
-- not stored here.
--
-- Null is the whole of the old behaviour, so nothing already on file changes and
-- no row is rewritten or back-filled: a guessed standard would be worse than
-- none, and the school sets its own on the new standards page
-- (`/admin/standards` -> `PUT /api/events/{id}`).
-- ---------------------------------------------------------------------------
-- HOW THIS REACHES THE LIVE DATABASE
--
-- `spring.jpa.hibernate.ddl-auto=update` (backend/src/main/resources/
-- application.yml), and THIS IS THE CASE IT HANDLES: adding a nullable column to
-- an existing table is exactly what ddl-auto=update does by itself, on the next
-- boot of a build containing the new code. Hibernate will add
-- `standard DECIMAL(10,3) NULL` whether or not this script is run.
--
-- This script is therefore the explicit, reviewable record of the change rather
-- than a precondition for it — unlike `relay-category-migration.sql`, which had
-- to widen a MySQL ENUM because ddl-auto=update never widens one, and unlike the
-- back-fill there, which only a script could do. Nothing here needs a manual
-- step: a database that never runs this file is put right by the next start of
-- the application, and a database that runs it first is put right here.
--
-- It has deliberately NOT been run against the school's live programme: the
-- backend serving it is the previous build (its jar holds backend/target/), it
-- has not been restarted, and no live event, relay or student row has been
-- touched by this change. The column appears when the new build is next started,
-- or when an administrator runs this file against that database.
--
-- Idempotent: MySQL has no ADD COLUMN IF NOT EXISTS, so it checks the catalogue
-- first and does nothing on a second run.
-- ---------------------------------------------------------------------------

SET @add_standard := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE events ADD COLUMN standard DECIMAL(10,3) NULL',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'events'
      AND COLUMN_NAME = 'standard'
);

PREPARE add_standard FROM @add_standard;
EXECUTE add_standard;
DEALLOCATE PREPARE add_standard;

-- Nothing else needs a schema change: whether an event *may* carry a standard is
-- decided by its type in the code, and which way round a mark meets it is decided
-- by `isLowerBetter()`. No event, enrollment, heat, final, mark, record, relay
-- team or student row is read or written by this script.
