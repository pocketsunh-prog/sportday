-- ---------------------------------------------------------------------------
-- A form relay is scoped to a form, not a grade
--
-- A "Form 1 4x100M" has the teams 1A, 1B, 1C and 1D, drawn from every class in
-- Form 1 whatever grade its students are; Form 2 is a separate event. An event was
-- scoped only by grade, so it could not span grades at all.
--
-- This adds one nullable column:
--
--   form IS NULL      the event is scoped by grade, exactly as every event is today
--   form IS NOT NULL  the event is scoped to that form, and a runner is judged by
--                     their form and the event's division instead of by grade
--
-- Null is the whole of the old behaviour, so nothing already on file changes and no
-- row is rewritten. Idempotent: MySQL has no ADD COLUMN IF NOT EXISTS, so it checks
-- the catalogue first.
-- ---------------------------------------------------------------------------

SET @add_form := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE events ADD COLUMN form VARCHAR(4) NULL',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'events'
      AND COLUMN_NAME = 'form'
);

PREPARE add_form FROM @add_form;
EXECUTE add_form;
DEALLOCATE PREPARE add_form;
