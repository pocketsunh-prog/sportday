-- ===========================================================================
--  Sport Day  ·  02-seed.sql
--  The minimum for a working, empty system.
-- ===========================================================================
--
--  RUN 01-schema.sql FIRST.  This file inserts rows; it does not create tables.
--
--  HOW TO RUN IT
--        mysql -h 127.0.0.1 -P 3307 -u sportday -psportday123 sportday < 02-seed.sql
--    or straight into the container:
--        docker exec -i sportday-mysql mysql -usportday -psportday123 sportday < 02-seed.sql
--
--  IDEMPOTENT
--    Both inserts use ON DUPLICATE KEY UPDATE id = id, which is an explicit
--    no-op: if the row is already there it is left exactly as it is, values and
--    all.  Nothing typed into the application is ever overwritten by re-running
--    this file.
--
--  =========================================================================
--  WHAT IS REQUIRED, AND WHAT THE APPLICATION MAKES BY ITSELF
--  =========================================================================
--
--  Everything below in section 1 and 2 is ALSO created by the application on
--  its first boot, by DataInitializer.java:
--
--      settingsService.get()      -> the sport_day_settings row (id = 1)
--      if (!existsByUsername("admin"))  -> the admin user
--
--  So strictly speaking neither row is REQUIRED: point the application at an
--  empty, 01-schema.sql-created database and it will create both.  They are
--  stated here anyway, because a database that is "ready" should not depend on
--  somebody having booted the application once, and because the values are then
--  visible and reviewable in the repository rather than buried in Java.
--
--  THIS IS A DELIBERATE DUPLICATION AND IT CAN DRIFT.  If a default changes in
--  SportDaySettings.defaults(), this file will not follow on its own.  Because
--  the inserts are no-ops when the row exists, an installation that has booted
--  the application once keeps the application's values and this file's are
--  ignored — only a database seeded BEFORE the first boot takes these numbers.
--  When you change a default in Java, change it here too.  (Nothing reads these
--  values at startup, so the worst case is a stale starting value on a fresh
--  install, not a broken one.)
--
--  Section 3 lists what is deliberately NOT seeded.
-- ===========================================================================

SET NAMES utf8mb4;

USE `sportday`;

-- ---------------------------------------------------------------------------
-- 1. sport_day_settings — the school's rules
-- ---------------------------------------------------------------------------
-- One row, id = 1 (SportDaySettings.SINGLETON_ID).  Every value is the
-- documented default from SportDaySettings.defaults() and fillBlanks().
--
-- The school's own name, address and principal are left NULL on purpose: the
-- application falls back to sensible text, and the school should type its own
-- under Settings rather than have a stranger's guess baked into the install.
--
-- sport_day_title is the one piece of text the app does supply, because every
-- marking sheet prints it: 田徑運動會記錄表 / Sport Day Marking Sheet
-- This file is UTF-8; the SET NAMES above is what makes the Chinese survive the
-- import whatever the client's default charset is.
INSERT INTO `sport_day_settings` (
    `id`,
    `school_name`, `school_name_zh`, `address`, `principal`, `sport_day_title`,
    `track_max_entries`, `field_max_entries`,
    `points_first`, `points_second`, `points_third`, `points_top_place`, `points_top`,
    `relay_points_first`, `relay_points_second`, `relay_points_third`, `relay_points_top`,
    `updated_at`
) VALUES (
    1,
    NULL, NULL, NULL, NULL, '田徑運動會記錄表 / Sport Day Marking Sheet',
    2, 1,
    9, 6, 3, 8, 1,
    30, 20, 10, 1,
    NOW(6)
)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- ---------------------------------------------------------------------------
-- 2. users — the administrator account
-- ---------------------------------------------------------------------------
-- Reproduces exactly what DataInitializer creates:
--      username  admin
--      password  BCrypt("admin123")
--      email     admin@sportday.com
--      fullName  System Administrator
--      role      ADMIN
--      enabled   true
--
-- The hash below is a fresh BCrypt hash (cost 10, Spring's
-- BCryptPasswordEncoder, the same encoder SecurityConfig installs) of the
-- application's documented first-run password "admin123".  It was generated for
-- this file and verified to match; it is NOT copied from the school's server.
--
-- >>> CHANGE THIS PASSWORD IMMEDIATELY AFTER THE FIRST LOGIN. <<<
-- A published default administrator password is a published administrator
-- password.  The application has no "must change on first login" step, so this
-- is the installer's job.
INSERT INTO `users` (
    `username`, `password`, `email`, `full_name`,
    `age`, `gender`, `role`, `enabled`,
    `created_at`, `updated_at`
) VALUES (
    'admin',
    '$2a$10$.F5etphkbgylB11eBv9ZF..Q3qvWY9hB/Vaqu1gpDdhZeK/8ZeEcW',
    'admin@sportday.com',
    'System Administrator',
    NULL, NULL, 'ADMIN', b'1',
    NOW(6), NOW(6)
)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- ---------------------------------------------------------------------------
-- 3. Deliberately NOT seeded here
-- ---------------------------------------------------------------------------
--
--  * THE SEASON.  DataInitializer creates the current year's sport day with
--    entries open, dating it to the day the application first boots
--    (or to app.events.seed-event-date when that is set).  Writing one here
--    would mean baking TODAY's date into a file that might be run next year,
--    and it would take the choice away from the application that has to keep it
--    in step with the "current season" logic in SeasonService.  The one
--    exception is 03-sample-data.sql, which is a demonstration and dates
--    itself explicitly.
--
--  * THE EVENT CATALOGUE.  DataInitializer calls EventService.createDefaults()
--    when the events table is empty, creating the whole standard programme —
--    every event type, in both divisions, for each grade that runs it, all
--    enabled, all dated to the season's sport day.  That is around forty rows
--    (fewer if app.events.seed-defaults is false) and roughly forty rows of
--    derived data with a date in them: exactly the sort of thing that goes
--    stale in a checked-in SQL file.  Let the application build it.
--
--    It is safe either way: createDefaults is itself idempotent, skipping any
--    event that already exists for a type, division and grade, so it will not
--    duplicate anything a seed file had created.
--
--  * THE STANDARD DEFAULTS (standard_defaults).  Nothing creates these on first
--    boot — the table is meant to start empty.  An administrator sets them on
--    the Standards page, which writes through StandardDefaultService.
--
--  * STUDENTS, TEACHERS, HELPER ACCOUNTS, EVENTS BY HAND, ENROLMENTS AND MARKS.
--    All of these are the school's own data or the day's work.  For a
--    demonstrable dataset instead, run the optional 03-sample-data.sql.
--
--  * EVENT RECORDS.  A record row is created as results and baselines are
--    entered; event_records.has_previous is NOT NULL with no default, so a
--    hand-written insert would have to supply it correctly too.

-- ---------------------------------------------------------------------------
-- 4. Verify
-- ---------------------------------------------------------------------------
-- Expect 1 and 1.
--
-- SELECT COUNT(*) AS settings_rows FROM sport_day_settings;
-- SELECT username, role, enabled FROM users WHERE username = 'admin';
