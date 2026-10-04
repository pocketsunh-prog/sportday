-- ---------------------------------------------------------------------------
-- Events belong to one grade, and the grade-assignment feature is gone
--
-- An event used to be per type x division ("Boys 100M") and mixed the grades: A,
-- B and C athletes were entered, heated, marked and ranked together. An event now
-- belongs to exactly ONE grade, so "Boys 100M - A Grade", "Boys 100M - B Grade"
-- and "Boys 100M - C Grade" are three separate events, each with its own heats,
-- marking sheets, results and placings.
--
-- Two consequences:
--
--   * `events` needs a `grade` column, which the application treats as required;
--
--   * the grade-eligibility feature is deleted. Whether a grade runs an event is
--     now simply whether that event exists, so `event_grade_rules` is dropped
--     along with the page that edited it. Note that Hibernate will NOT drop a
--     table for you: `ddl-auto=update` creates tables and columns and never
--     removes either, so this DROP is the only thing that takes the table away.
--
-- THE CATALOGUE RESTRUCTURES, SO THE APPLICATION RESEEDS IT.
--
-- This script deliberately does NOT try to split the existing grade-mixed events
-- into per-grade ones. It cannot be done honestly: today's "Boys 1500M" may hold
-- both A and B grade entries, heats drawn across both, and marks recorded against
-- them, and there is no way to tell from the data which athletes the school
-- intends to keep in which grade's race — nor what to do with an entry in a grade
-- that does not run that event at all. Splitting it would be guesswork on results
-- that the school is about to publish.
--
-- The clean path is a WIPE: empty the programme tables (below), then let the
-- application rebuild the catalogue, which seeds 112 events — every type in both
-- divisions for each grade that runs it, which is three events for most types and
-- fewer for the 1500M, the 5000M and the 110M hurdles. Either restart the backend
-- (which seeds the catalogue when `events` is empty) or call
-- `POST /api/events/defaults` as an administrator.
--
-- MySQL has no "ADD COLUMN IF NOT EXISTS", so the column is guarded against
-- information_schema. The script is idempotent and safe to run repeatedly.
--
-- Only needed where ddl-auto is not `update`, or to apply the schema ahead of a
-- deploy: Hibernate adds the `grade` column itself, but it cannot make an
-- existing row's grade meaningful.
-- ---------------------------------------------------------------------------

-- 1. `events.grade` — the one grade that competes in the event.
--
-- Added NULLable on purpose. A fresh database gets the NOT NULL from Hibernate;
-- an existing one cannot, because every row already there is grade-mixed and
-- there is no correct value to backfill (see the note above). Once the catalogue
-- is reseeded every row has a grade, and the application refuses to create or
-- update an event without one.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'events'
                  AND COLUMN_NAME = 'grade') > 0,
               'SELECT 1',
               'ALTER TABLE events ADD COLUMN grade VARCHAR(4) NULL AFTER sex');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. The grade-eligibility table is gone with the feature it served.
--
-- Hibernate never drops a table on its own, so without this the dead table (and
-- the rules a school once set on the deleted page) would linger for ever.
DROP TABLE IF EXISTS event_grade_rules;

-- 3. (Optional, destructive, left inert on purpose.)
--
-- The wipe the note above describes. Uncomment, run it, and let the application
-- reseed the programme — the grade-mixed events cannot be carried over, and any
-- entries, heats and marks hanging off them go with them. `event_records` and the
-- hand-entered baselines on them are kept: a record spans every edition of an
-- event, so it does not belong to one year's events.
--
-- DELETE FROM final_entries;
-- DELETE FROM event_results;
-- DELETE FROM event_groups;
-- DELETE FROM enrollments;
-- UPDATE event_records SET result_id = NULL, event_id = NULL;
-- DELETE FROM events;
