-- ---------------------------------------------------------------------------
-- A relay is its own category, not a track event
--
-- Until now a relay was a TRACK event: `events.category` holds a MySQL ENUM, and
-- every 4x100M and 4x400M row on file carries 'TRACK'. A relay is run and scored
-- by TEAM and never by an individual, so it is now its own family, `RELAY`, and
-- the two relay event types declare it instead of TRACK. The places that must go
-- on reading a relay as a race rather than as a field event are settled in the
-- code; this script settles the two that live in the database:
--
--   1. `events.category` must be able to STORE 'RELAY';
--   2. the relay rows already on file must BE 'RELAY', because they were written
--      when the only two values there meant that a relay was a track event.
--
-- ---------------------------------------------------------------------------
-- WHAT IS BROKEN BETWEEN DEPLOYING THE NEW BUILD AND RUNNING THIS SCRIPT
--
-- Both halves — and Hibernate's ddl-auto=update fixes neither:
--
--   * IT CANNOT STORE THE NEW VALUE. `category` is an ENUM and ddl-auto=update
--     never widens one; it only adds columns and tables. Writing 'RELAY' into it
--     fails with "Data truncated for column 'category'", so a new relay event
--     cannot be saved and an existing relay cannot be re-saved. The 100M hurdles
--     hit exactly this wall on `events.type`; see `hurdles-100m-migration.sql`,
--     which rewrites that ENUM for the same reason.
--
--   * THE ROWS ALREADY ON FILE KEEP THE OLD VALUE. Every live 4x100M/4x400M row
--     still says category='TRACK', and nothing in the build rewrites stored rows.
--     A relay read from such a row is a TRACK event: it is counted against the
--     student's two TRACK entries rather than against the relay's own allowance,
--     it is listed under 徑項 Track on the programme and in the class relay
--     listings, and `?category=RELAY` does not find it. Its mark is still read as
--     a time (a TRACK mark is a time either way) and its sheet still prints one
--     line per team, so this half is wrong rather than dangerous — but it is
--     wrong, and only this script puts it right.
--
-- Order matters: the ENUM is widened first, then the rows are moved. Running the
-- UPDATE against an ENUM that does not yet hold 'RELAY' would truncate every relay
-- row — to the empty string, or to an error under strict mode.
--
-- Idempotent and safe to run repeatedly: the ENUM is rewritten only when 'RELAY'
-- is not already one of its values, and the UPDATE only touches rows still saying
-- 'TRACK' that are a relay type, so a second run changes nothing.
--
-- Run it with a client that is not in MySQL's "safe updates" mode, or turn it off
-- for the session first (`SET SQL_SAFE_UPDATES = 0;`): the UPDATE is guarded by the
-- event's type and category rather than by its primary key, which a client with
-- safe updates on refuses with error 1175 even though the statement is fine.
--
-- It is deliberately NOT run by the application, and it was NOT run for this
-- change: the database it touches is the school's live programme.
-- ---------------------------------------------------------------------------

-- 1. Widen the ENUM so 'RELAY' can be stored at all.
--
--    The values are rewritten in full rather than appended to, so a database whose
--    column order differs is put right rather than left alone; stored values are
--    names, so the order means nothing to a row. The order used is Hibernate's own
--    — alphabetical, which is why the live column reads ('FIELD','TRACK') and why
--    `events.type` reads the way it does in `hurdles-100m-migration.sql` — so a
--    database migrated by this script and one created fresh by Hibernate agree.
--
--    The nullability is taken from the column as it is found rather than assumed:
--    `Event.category` is `@Column(nullable = false)`, but a database that predates
--    that annotation may allow NULL (`Event.getCategoryOrDefault()` reads one as
--    TRACK), and a migration has no business tightening or relaxing a constraint
--    the live school data is already stored under. Only the list of legal values
--    changes.
SET @has_category := (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'events'
       AND COLUMN_NAME = 'category'
);

SET @has_relay_value := (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'events'
       AND COLUMN_NAME = 'category'
       AND COLUMN_TYPE LIKE '%''RELAY''%'
);

SET @category_nullability := (
    SELECT IF(IS_NULLABLE = 'YES', 'NULL', 'NOT NULL')
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'events'
       AND COLUMN_NAME = 'category'
);

SET @widen_category := IF(
    @has_category = 1 AND @has_relay_value = 0,
    CONCAT('ALTER TABLE events MODIFY COLUMN category ENUM(''FIELD'',''RELAY'',''TRACK'') ',
           @category_nullability),
    'SELECT 1'
);
PREPARE widen_category FROM @widen_category;
EXECUTE widen_category;
DEALLOCATE PREPARE widen_category;

-- 2. Move the relay rows onto the new value. Only a 4x100M or a 4x400M is a relay
--    (`Event.EventType.isRelay()`), and only a row still saying 'TRACK' needs
--    moving, so a second run updates nothing.
--
--    Whether 'RELAY' is now legal is re-read AFTER the widening above, because
--    that is what makes it a legal value to write: where the column could not be
--    widened (it is missing, or an administrator means to widen it another way)
--    the UPDATE is skipped rather than allowed to truncate the school's relay rows.
SET @has_relay_value := (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'events'
       AND COLUMN_NAME = 'category'
       AND COLUMN_TYPE LIKE '%''RELAY''%'
);

SET @move_relays := IF(
    @has_relay_value = 1,
    'UPDATE events SET category = ''RELAY'' WHERE type IN (''RELAY_4X100M'',''RELAY_4X400M'') AND category <> ''RELAY''',
    'SELECT 1'
);
PREPARE move_relays FROM @move_relays;
EXECUTE move_relays;
DEALLOCATE PREPARE move_relays;

-- Nothing else needs a schema change, and nothing else in the data is touched. A
-- relay's allowance, its mark being a time, and its one-line-per-team sheet are
-- decided by the code, not by a column: RELAY is a value of a column that already
-- exists. No relay team, runner, leg, mark, record or name is read or written by
-- this script.
