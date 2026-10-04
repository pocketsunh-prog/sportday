-- ---------------------------------------------------------------------------
-- The 100M hurdles, for the C grade
--
-- The C grade — fourteen or under — hurdles over the shorter distance, so the
-- 100M hurdles joins the programme and the 110M hurdles becomes an A and B event.
--
-- Two things have to change in an existing database:
--
--   1. `events.type` is a MySQL ENUM, and Hibernate emits its values in
--      alphabetical order. A new value has to be added to the column, or every
--      insert of a 100M hurdles fails with "Data truncated for column 'type'".
--   2. The two events themselves, and the grade rules that decide who may enter
--      them. The application creates the events only when it seeds a catalogue,
--      which it does not do for a school that already has one.
--
-- The ENUM is rewritten in full rather than appended to, so this is safe to run on
-- a database whose column order differs. Stored values are names, so reordering
-- them changes nothing. Idempotent.
-- ---------------------------------------------------------------------------

ALTER TABLE events MODIFY COLUMN type ENUM(
    'DISCUSSION_THROW', 'HAMMER_THROW', 'HIGH_JUMP', 'HURDLES_100M', 'HURDLES_110M',
    'HURDLES_400M', 'JAVELIN_THROW', 'LONG_JUMP', 'OTHER', 'POLE_VAULT',
    'RELAY_4X100M', 'RELAY_4X400M', 'RUN_100M', 'RUN_1500M', 'RUN_200M', 'RUN_400M',
    'RUN_5000M', 'RUN_800M', 'RUN_60M', 'SHOT_PUT', 'TRIPLE_JUMP'
) NOT NULL;

-- The C grade runs the 100M hurdles instead of the 110M.
UPDATE event_grade_rules
   SET allowed = b'1'
 WHERE event_type = 'HURDLES_100M';

UPDATE event_grade_rules
   SET allowed = b'0'
 WHERE event_type = 'HURDLES_110M' AND grade = 'C';

INSERT IGNORE INTO event_grade_rules (event_type, grade, allowed)
SELECT 'HURDLES_100M', g.grade, b'1'
  FROM (SELECT 'A' AS grade UNION SELECT 'B' UNION SELECT 'C') g;

INSERT IGNORE INTO event_grade_rules (event_type, grade, allowed)
SELECT 'HURDLES_110M' AS event_type, g.grade,
       CASE WHEN g.grade = 'C' THEN b'0' ELSE b'1' END
  FROM (SELECT 'A' AS grade UNION SELECT 'B' UNION SELECT 'C') g;

-- One 100M hurdles per division, alongside the rest of the catalogue, in the
-- current school year. Matches what the application seeds: 24 to a group, enabled,
-- running straight to a final.
INSERT INTO events (created_at, description, enabled, event_date, location,
                    max_participants, name, type, category, group_size, sex,
                    season_id, direct_to_final, direct_to_final_auto)
SELECT NOW(6), '徑項 Track 100M Hurdles', b'1',
       COALESCE(s.sport_day_date, CURDATE()), NULL,
       0, CONCAT(IF(e.sex = 'MALE', 'Boys ', 'Girls '), '100M Hurdles'),
       'HURDLES_100M', 'TRACK', 24, e.sex,
       s.id, b'1', b'0'
  FROM (SELECT 'MALE' AS sex UNION SELECT 'FEMALE') e
  LEFT JOIN seasons s ON s.enrollment_open = b'1'
 WHERE NOT EXISTS (
        SELECT 1 FROM events x
         WHERE x.type = 'HURDLES_100M' AND x.sex = e.sex
       );
