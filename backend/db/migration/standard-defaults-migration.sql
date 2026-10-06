-- ---------------------------------------------------------------------------
-- A default required standard per event type, grade and sex division
--
-- The school's requirement: "base on each grade update standard record for
-- default". A required standard used to be typed once per qualifying event —
-- 72 boxes on the live programme — so setting the 400M for a new grade meant
-- finding every 400M event again. It is now set once per key and inherited:
--
--   "400M, A grade, boys = 64.0 s"  ->  every Boys 400M · A Grade event
--
-- THE KEY IS type x grade x SEX. The sex division is part of the key on
-- purpose, and this is stated so it can be corrected if the school disagrees:
-- a default keyed on grade alone would give the boys' 400M and the girls' 400M
-- the same qualifying time, and the live programme holds a Boys 400M and a
-- Girls 400M at every grade. To key on grade alone, drop the `sex` column from
-- the unique key below; no other part of the design moves.
--
-- WHAT IS CREATED
--
--   1. a new table `standard_defaults`, one row per key:
--
--        type      the event type, e.g. RUN_400M — only types that carry a
--                  standard (Event.EventType.carriesAStandard(): the track
--                  races of 400M and over, and the field events). A relay and
--                  a short sprint are refused by the API, so no row exists for
--                  them and none can be offered.
--        grade     A, B or C
--        sex       MALE or FEMALE — part of the key
--        standard  DECIMAL(10,3) NULL, the qualifying mark in the type's own
--                  unit (seconds on the track, metres in the field)
--
--      UNIQUE (type, grade, sex), so a lookup for one key can never return two
--      rows and the inheritance is a map read rather than a "which wins"
--      question.
--
--   2. one new nullable column on `events`:
--
--        standard_is_default  TINYINT(1) NULL
--
--      true  the event's standard came from the default above
--      false a person typed it on this event
--      NULL  a standard set before this column existed — read as FALSE, i.e.
--            hand-set, and therefore never quietly overwritten by an apply
--
--      This one flag is what makes updating a default safe: "apply now"
--      re-points the events that FOLLOW a default and leaves the exceptions
--      alone, and without it there is no way to tell an inherited number from
--      a chosen one — they are the same column.
--
-- Nothing here is back-filled and no existing row is rewritten. Every event
-- keeps the standard it has and reads as hand-set, so the first "apply" leaves
-- it alone and says so; the 72 qualifying events on the live programme all hold
-- no standard today, so an apply is what fills them.
--
-- DIRECTION: which way round a mark meets a standard is
-- `EventType.isLowerBetter()` / `meetsStandard`, the codebase's existing rule,
-- and is deliberately not restated or stored here.
-- ---------------------------------------------------------------------------
-- HOW THIS REACHES THE LIVE DATABASE
--
-- `spring.jpa.hibernate.ddl-auto=update` (backend/src/main/resources/
-- application.yml), and BOTH changes are the case it handles by itself: adding
-- a nullable column to an existing table, and creating a table that does not
-- exist. Hibernate will do both on the next boot of a build containing the new
-- code, whether or not this script is run.
--
-- This script is therefore the explicit, reviewable record of the change rather
-- than a precondition for it. It needs no manual step, and it is safe to run
-- before the new build: an old build ignores a table and a column it does not
-- map.
--
-- ORDER OF DEPLOYMENT — is the app broken in between? NO, in either order:
--
--   * script first, then the new build: the columns and table are already
--     there, Hibernate finds them and changes nothing. The previous build,
--     still running, does not know about either and is unaffected.
--   * new build first, with no script: Hibernate creates the table and the
--     column on boot. This is the same code path the previous features used.
--
-- There is no window in which the application fails to start or a request
-- fails: nothing reads the new table on a path that could run before the schema
-- exists, because the only reader is the new code, and the new code is what
-- creates it.
--
-- It has deliberately NOT been run against the school's live programme, and the
-- live data has not been touched: the backend serving it is the previous build,
-- it has not been restarted, and no live event, relay or student row is read or
-- written by this script.
--
-- Idempotent: MySQL has no ADD COLUMN IF NOT EXISTS and no CREATE TABLE IF NOT
-- EXISTS worth relying on for this, so the catalogue is checked first and a
-- second run does nothing.
-- ---------------------------------------------------------------------------

-- 1. The defaults table, one row per type x grade x sex.

CREATE TABLE IF NOT EXISTS standard_defaults (
    id       BIGINT       NOT NULL AUTO_INCREMENT,
    type     VARCHAR(32)  NOT NULL,
    grade    VARCHAR(4)   NOT NULL,
    sex      VARCHAR(8)   NOT NULL,
    standard DECIMAL(10,3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_standard_default_type_grade_sex UNIQUE (type, grade, sex),
    INDEX idx_standard_defaults_type (type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 2. Where an event's standard came from. Nullable, so every row already on
--    file reads as hand-set and is never overwritten by an apply.

SET @add_standard_is_default := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE events ADD COLUMN standard_is_default TINYINT(1) NULL',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'events'
      AND COLUMN_NAME = 'standard_is_default'
);

PREPARE add_standard_is_default FROM @add_standard_is_default;
EXECUTE add_standard_is_default;
DEALLOCATE PREPARE add_standard_is_default;

-- Nothing else needs a schema change: whether an event *may* carry a standard
-- is decided by its type in the code, and which way round a mark meets one is
-- decided by `isLowerBetter()`. No event, enrollment, heat, final, mark, record,
-- relay team or student row is read or written by this script.
