-- ===========================================================================
--  Sport Day  ·  03-sample-data.sql
--
--  *** OPTIONAL — DEMONSTRATION DATA ONLY.  DO NOT RUN ON A REAL INSTALL. ***
-- ===========================================================================
--
--  WHAT THIS IS
--    A small, wholly synthetic dataset so somebody can start the application,
--    log in and see it working without the school's real register:
--
--      * one season  — 2026, dated 2026-11-06, with entries open
--      * four events — two track, one field, one relay, all 2026 / A Grade
--      * twelve students in six classes across grades A, B and C,
--        four houses, both sexes
--
--  WHAT THIS IS NOT
--    It is NOT a dump of the school's database.  It contains no real student
--    name, username, password, email or mark.  Every student here is called
--    "Demo Student NN", has the id DEMO00NN, and is marked with the import
--    batch 'SAMPLE-DEMO-2026' so it can be found and removed in one query.
--    The live database holds hundreds of real pupils; none of them are here.
--
--  RUN 01-schema.sql AND 02-seed.sql FIRST.
--
--        mysql -h 127.0.0.1 -P 3307 -u sportday -psportday123 sportday < 03-sample-data.sql
--    or:
--        docker exec -i sportday-mysql mysql -usportday -psportday123 sportday < 03-sample-data.sql
--
--  ---------------------------------------------------------------------------
--  READ THIS BEFORE RUNNING IT ON ANYTHING YOU CARE ABOUT
--  ---------------------------------------------------------------------------
--  DataInitializer only builds the standard event programme when the events
--  table is EMPTY:
--
--      if (seedDefaults && eventRepository.count() == 0) {
--          eventService.createDefaults(seedDate, true);
--
--  This file fills the events table first.  So if you run it BEFORE the
--  application has ever booted, the automatic programme never appears and the
--  system has exactly these four events.  That is the point for a demo, and a
--  nasty surprise otherwise.  Either boot the application first (letting it
--  build all ~40 events, after which this file adds only the season and the
--  students, because it will not duplicate an event by name), or run this file
--  only on a throwaway database.
--
--  IDEMPOTENT
--    Every insert is guarded: the season and the students go through
--    ON DUPLICATE KEY UPDATE, which is an explicit no-op, and the events go
--    through WHERE NOT EXISTS on (name, season) because the events table has no
--    unique key to collide on.  Running it twice adds nothing.
--
--  LOGINS IT CREATES
--    The twelve student accounts use the application's own documented rule —
--    password = yyyyMMdd(date of birth) + class + class number, see
--    StudentPasswordPolicy — so each one's password is written as a comment
--    next to it.  For example DEMO0001 is born 2013-04-18, is in 1A and is
--    number 1, so its password is 201304181A1.  They are real BCrypt hashes of
--    those passwords, generated for this file, so these accounts really do log
--    in.
-- ===========================================================================

SET NAMES utf8mb4;

USE `sportday`;

-- ---------------------------------------------------------------------------
-- 1. The season
-- ---------------------------------------------------------------------------
-- enrollment_open is set to 1 only when no season is already open.  The
-- application's rule is that exactly one year may accept entries
-- (SeasonService.closeOthers), so a second open season here would break that
-- invariant.  If your database already has an open year, this sample season is
-- created closed and simply sits alongside it as a demonstration.
INSERT INTO `seasons` (`year`, `name`, `sport_day_date`, `enrollment_open`, `notes`, `created_at`, `updated_at`)
SELECT 2026,
       '2026 Sports Day (sample data)',
       '2026-11-06',
       IF(EXISTS(SELECT 1 FROM `seasons` WHERE `enrollment_open` = b'1'), b'0', b'1'),
       'Synthetic sample season written by deploy/sql/03-sample-data.sql. Contains no real school data.',
       NOW(6),
       NOW(6)
FROM DUAL
ON DUPLICATE KEY UPDATE `seasons`.`id` = `seasons`.`id`;

SET @sample_season_id := (SELECT `id` FROM `seasons` WHERE `year` = 2026);

-- ---------------------------------------------------------------------------
-- 2. Four events
-- ---------------------------------------------------------------------------
-- Every column matches what EventService.createDefaults() would have written
-- for the same type, division and grade, so these rows are indistinguishable
-- from real ones apart from their date and their names being in this file:
--
--   * name            EventService.defaultName() — "Boys 100M · A Grade"
--   * description     EventCategory.getLabel() + " " + EventType.getDisplayName()
--   * category        derived from the type (and re-derived by the entity anyway)
--   * max_participants 512, the entity's DEFAULT_MAX_PARTICIPANTS
--   * group_size      8 for 60/100/200/400, 24 for everything else
--   * direct_to_final true, and direct_to_final_auto true only for a short sprint
--   * is_draft        b'0' — these are real events, not relay drafts
--   * standard        NULL — no standard_defaults row exists to inherit from
--   * form            NULL — these are grade-scoped, not form-scoped
--
-- The relay is a HOUSE relay: its teams are one per house within A Grade
-- (RelayTeamKind.HOUSE).  It has no form, which is the ordinary case.
INSERT INTO `events` (
    `name`, `description`, `type`, `category`, `sex`, `grade`,
    `event_date`, `location`, `max_participants`, `group_size`, `enabled`,
    `direct_to_final`, `direct_to_final_auto`, `is_draft`, `form`,
    `relay_team_kind`, `relay_team_size`, `relay_reserves_allowed`,
    `standard`, `standard_is_default`, `season_id`, `created_at`, `updated_at`
)
SELECT v.name, v.description, v.type, v.category, v.sex, v.grade,
       '2026-11-06', 'Main Sports Ground', 512, v.group_size, b'1',
       b'1', v.direct_to_final_auto, b'0', NULL,
       v.relay_team_kind, v.relay_team_size, v.relay_reserves_allowed,
       NULL, NULL, @sample_season_id, NOW(6), NOW(6)
FROM (
              SELECT 'Boys 100M · A Grade'          AS name,
                     '徑項 Track 100M'               AS description,
                     'RUN_100M'                      AS type,
                     'TRACK'                         AS category,
                     'MALE'                          AS sex,
                     'A'                             AS grade,
                     8                               AS group_size,
                     b'1'                            AS direct_to_final_auto,
                     NULL                            AS relay_team_kind,
                     NULL                            AS relay_team_size,
                     NULL                            AS relay_reserves_allowed
    UNION ALL SELECT 'Girls 100M · A Grade',  '徑項 Track 100M',  'RUN_100M',    'TRACK', 'FEMALE', 'A', 8,  b'1', NULL,      NULL, NULL
    UNION ALL SELECT 'Boys Long Jump · A Grade', '田項 Field Long Jump', 'LONG_JUMP', 'FIELD', 'MALE', 'A', 24, b'0', NULL, NULL, NULL
    UNION ALL SELECT 'Boys 4x100M Relay · A Grade', '接力 Relay 4x100M Relay', 'RELAY_4X100M', 'RELAY', 'MALE', 'A', 24, b'0', 'HOUSE', 4, b'0'
) v
WHERE NOT EXISTS (
    SELECT 1 FROM `events` e
     WHERE e.`name` = v.name AND e.`season_id` = @sample_season_id
);

-- ---------------------------------------------------------------------------
-- 3. Twelve student login accounts
-- ---------------------------------------------------------------------------
-- One users row per student, built exactly as StudentService.upsert() builds
-- one: username = student id, full_name = the student's name, age computed from
-- the date of birth, gender = 'M'/'F' (Sex.getCode()), role = STUDENT, and no
-- email — imported student accounts never have one, which is why users.email is
-- nullable.
INSERT INTO `users` (`username`, `password`, `email`, `full_name`, `age`, `gender`, `role`, `enabled`, `created_at`, `updated_at`)
SELECT v.student_id,
       v.password_hash,
       NULL,
       v.full_name,
       TIMESTAMPDIFF(YEAR, DATE(v.dob), CURDATE()),
       v.sex_code,
       'STUDENT',
       b'1',
       NOW(6),
       NOW(6)
FROM (
    -- student_id | full_name        | dob        | sex_code | password      | bcrypt hash of that password
              SELECT 'DEMO0001' AS student_id, 'Demo Student 01' AS full_name, '2013-04-18' AS dob, 'M' AS sex_code, '$2a$10$1esVetapt63wU9ZFVL3Xb.FgvmRUZA7whTN3KHPIwhquGN2VgmDP6' AS password_hash
    UNION ALL SELECT 'DEMO0002', 'Demo Student 02', '2013-04-18', 'F', '$2a$10$4EAarYItuMIaA2Y6AjxV8.kmzOOabFA4hZKa55yqZmJQ.A8KyGBhW'
    UNION ALL SELECT 'DEMO0003', 'Demo Student 03', '2013-04-18', 'M', '$2a$10$cejr.1FeHP2Psz5J4gD//uvaK6e8QKJSANKDA83o6pibdxuMRi10i'
    UNION ALL SELECT 'DEMO0004', 'Demo Student 04', '2013-04-18', 'F', '$2a$10$ML94hKjGgMfARcnoHpBmeO6eAUS.pt.BQQxLlaYzIuUoKqqoCBhQ.'
    UNION ALL SELECT 'DEMO0005', 'Demo Student 05', '2011-06-09', 'F', '$2a$10$cyLCvg6zNtXaz/Oi0I.rP.3M4bA/Ijl4GtEjAq86xLR1SkqHWv0DG'
    UNION ALL SELECT 'DEMO0006', 'Demo Student 06', '2011-06-09', 'M', '$2a$10$i/xHwpBCJ5lVv3XRTNdYnO40jYf7aGGdyoyD1QB06ddxkJX3Jk4ee'
    UNION ALL SELECT 'DEMO0007', 'Demo Student 07', '2011-06-09', 'F', '$2a$10$fv/OLTSlpTbkVoWPifj2vOVNNbBSqXpSrvpgcyw2MuhISLRFha.Oy'
    UNION ALL SELECT 'DEMO0008', 'Demo Student 08', '2011-06-09', 'M', '$2a$10$/BKkhVmxY.gQL8vlt8CmXekH0DCmitzo3zg79fPO.UcskwoCGDUce'
    UNION ALL SELECT 'DEMO0009', 'Demo Student 09', '2009-02-27', 'M', '$2a$10$mX5TvmrZuDQVfMit8o35mOMRjk9uFAFMSHMZK0/oKjd5kTMIT6UqK'
    UNION ALL SELECT 'DEMO0010', 'Demo Student 10', '2009-02-27', 'F', '$2a$10$zL4qNwUKSUbNAMcZJ8jrCOf0CTdnSKnHTxXAPKD2eezcBGzBzDgLC'
    UNION ALL SELECT 'DEMO0011', 'Demo Student 11', '2009-02-27', 'M', '$2a$10$JHbTdRc7Hky.BCjXRJHs2uQbndeS09XbmziaJ/0pT7cY3BxKF8ut2'
    UNION ALL SELECT 'DEMO0012', 'Demo Student 12', '2009-02-27', 'F', '$2a$10$HqakMD7q0oY0oux0c4658OFCF90tTsPYvzuGVo4LYXS4a.YnJVXcC'
) v
ON DUPLICATE KEY UPDATE `users`.`id` = `users`.`id`;

-- ---------------------------------------------------------------------------
-- 4. Twelve student records
-- ---------------------------------------------------------------------------
-- Grades follow GradeCalculator's bands at the dates chosen: 14 or below is C,
-- 15-16 is B, 17 or above is A.  On the sample sport day (2026-11-06) the three
-- dates of birth give ages 13, 15 and 17, so the stored grade is the grade the
-- application recomputes from the date of birth — the register and the grade
-- filter will agree.
--
-- Note that the application always derives a displayed grade from the date of
-- birth rather than trusting this column, so if this file is run years from now
-- the demo students will show the grade they have aged into.  Cosmetic only;
-- it is sample data.
--
-- class_number restarts at 1 in each class, which is the register rule, and
-- house names are the four the application knows (Red, Blue, Green, Yellow),
-- so house codes R/B/G/Y resolve.
INSERT INTO `students` (`user_id`, `student_id`, `name`, `dob`, `sex`, `class_name`, `class_number`, `house`, `grade`, `enabled`, `import_batch`, `created_at`, `updated_at`)
SELECT u.`id`, v.student_id, v.full_name, v.dob, v.sex, v.class_name, v.class_number, v.house, v.grade, b'1', 'SAMPLE-DEMO-2026', NOW(6), NOW(6)
FROM (
    -- student_id | name             | dob          | sex    | class | no | house  | grade | password
              SELECT 'DEMO0001' AS student_id, 'Demo Student 01' AS full_name, '2013-04-18' AS dob, 'MALE' AS sex, '1A' AS class_name, 1 AS class_number, 'Red' AS house, 'C' AS grade
    UNION ALL SELECT 'DEMO0002', 'Demo Student 02', '2013-04-18', 'FEMALE', '1A', 2, 'Blue',   'C'   -- 201304181A2
    UNION ALL SELECT 'DEMO0003', 'Demo Student 03', '2013-04-18', 'MALE',   '1B', 1, 'Green',  'C'   -- 201304181B1
    UNION ALL SELECT 'DEMO0004', 'Demo Student 04', '2013-04-18', 'FEMALE', '1B', 2, 'Yellow', 'C'   -- 201304181B2
    UNION ALL SELECT 'DEMO0005', 'Demo Student 05', '2011-06-09', 'FEMALE', '3A', 1, 'Red',    'B'   -- 201106093A1
    UNION ALL SELECT 'DEMO0006', 'Demo Student 06', '2011-06-09', 'MALE',   '3A', 2, 'Blue',   'B'   -- 201106093A2
    UNION ALL SELECT 'DEMO0007', 'Demo Student 07', '2011-06-09', 'FEMALE', '3B', 1, 'Green',  'B'   -- 201106093B1
    UNION ALL SELECT 'DEMO0008', 'Demo Student 08', '2011-06-09', 'MALE',   '3B', 2, 'Yellow', 'B'   -- 201106093B2
    UNION ALL SELECT 'DEMO0009', 'Demo Student 09', '2009-02-27', 'MALE',   '5A', 1, 'Red',    'A'   -- 200902275A1
    UNION ALL SELECT 'DEMO0010', 'Demo Student 10', '2009-02-27', 'FEMALE', '5A', 2, 'Blue',   'A'   -- 200902275A2
    UNION ALL SELECT 'DEMO0011', 'Demo Student 11', '2009-02-27', 'MALE',   '5B', 1, 'Green',  'A'   -- 200902275B1
    UNION ALL SELECT 'DEMO0012', 'Demo Student 12', '2009-02-27', 'FEMALE', '5B', 2, 'Yellow', 'A'   -- 200902275B2
) v
JOIN `users` u ON u.`username` = v.student_id
ON DUPLICATE KEY UPDATE `students`.`id` = `students`.`id`;

-- ---------------------------------------------------------------------------
-- 5. Deliberately NOT seeded
-- ---------------------------------------------------------------------------
-- No enrolments, no heats, no marks, no results and no records.  Those are the
-- school's day's work, and every one of them goes through a service that
-- enforces rules the schema cannot — the track/field entry limits per student,
-- group allocation and lane assignment, the direct-to-final decision, the
-- relay team derivation, and the records engine.  Hand-written rows would
-- bypass all of it and could leave the demo in a state no screen expects.
-- Enter a few through the application instead; that is the point of the demo.
--
-- No standard_defaults either: an administrator sets those on the Standards
-- page, and 02-seed.sql explains why they start empty.

-- ---------------------------------------------------------------------------
-- 6. Verify
-- ---------------------------------------------------------------------------
-- Expect 1 season, 4 events, 12 users, 12 students (plus the admin from
-- 02-seed.sql, so 13 users in total on a fresh install).
--
-- SELECT (SELECT COUNT(*) FROM seasons  WHERE year = 2026)          AS sample_seasons,
--        (SELECT COUNT(*) FROM events   WHERE season_id = @sample_season_id) AS sample_events,
--        (SELECT COUNT(*) FROM students WHERE import_batch = 'SAMPLE-DEMO-2026') AS sample_students;
--
-- SELECT s.student_id, s.name, s.class_name, s.class_number, s.house, s.grade
--   FROM students s WHERE s.import_batch = 'SAMPLE-DEMO-2026' ORDER BY s.student_id;

-- ---------------------------------------------------------------------------
-- 7. Removing the sample data again
-- ---------------------------------------------------------------------------
-- The application is the right tool once the demo has been used (the Seasons
-- page resets a year and everything under it; the Students page deletes a
-- batch).  For a database where the sample has NOT been used — no enrolments
-- and no marks entered against it — this raw delete is enough, in this order,
-- because students reference users and events reference the season:
--
-- DELETE FROM `students` WHERE `import_batch` = 'SAMPLE-DEMO-2026';
-- DELETE FROM `users`    WHERE `username` LIKE 'DEMO%';
-- DELETE FROM `events`   WHERE `season_id` = (SELECT `id` FROM `seasons` WHERE `year` = 2026);
-- DELETE FROM `seasons`  WHERE `year` = 2026;
