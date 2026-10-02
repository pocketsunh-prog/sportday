-- ---------------------------------------------------------------------------
-- Field attempts, and the short unit a programme writes
--
-- A field event gives every athlete three attempts and counts the best of them,
-- so `event_results` needs somewhere to keep them:
--   attempt_1, attempt_2, attempt_3   the throws / jumps, left NULL for a miss
--
-- `mark` still holds the mark that stands — the best attempt — which is what the
-- placings, the school records and the championships read, so nothing downstream
-- changes. A track event leaves the attempts empty and uses `mark` alone.
--
-- Units are shortened to what a programme prints: M for a field mark, s for a
-- track one. The application also rewrites stored units on start, so this script
-- is only needed where ddl-auto is validate, or to apply the schema ahead of a
-- deploy. It is idempotent.
-- ---------------------------------------------------------------------------

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'event_results'
                  AND COLUMN_NAME = 'attempt_1') > 0,
               'SELECT 1',
               'ALTER TABLE event_results ADD COLUMN attempt_1 DECIMAL(10,3) NULL, '
               'ADD COLUMN attempt_2 DECIMAL(10,3) NULL, '
               'ADD COLUMN attempt_3 DECIMAL(10,3) NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Marks recorded before the units were shortened.
UPDATE event_results r
   JOIN events e ON e.id = r.event_id
   SET r.unit = 'M'
 WHERE e.category = 'FIELD' AND LOWER(r.unit) IN ('metres', 'metre', 'm');

UPDATE event_results r
   JOIN events e ON e.id = r.event_id
   SET r.unit = 's'
 WHERE e.category = 'TRACK' AND LOWER(r.unit) IN ('seconds', 'second', 'sec', 's');
