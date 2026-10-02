-- ---------------------------------------------------------------------------
-- Records that every event has by default, with a mark an administrator can type
--
-- An event now gets a record row for each grade as soon as it is created, and the
-- mark that stands is the better of:
--   * manual_mark  — typed in by an administrator (last season's best, or a mark
--                    held by a student who has since left), and
--   * the best result recorded in any event of that type and division.
--
-- Two schema changes follow from that:
--   * `mark` must allow NULL, because a record row exists before anything is known;
--   * the hand-entered mark needs its own columns, so recomputing from the results
--     can never overwrite it.
--
-- MySQL has no "ADD COLUMN IF NOT EXISTS", so each step is guarded against
-- information_schema. Idempotent and safe to run repeatedly.
--
-- Only needed where ddl-auto is not `update`: Hibernate adds the new columns but
-- cannot relax the NOT NULL on `mark`.
-- ---------------------------------------------------------------------------

-- `mark` may now be empty: an event with no results and no typed-in record yet.
ALTER TABLE event_records MODIFY COLUMN mark DECIMAL(10,3) NULL;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'event_records'
                  AND COLUMN_NAME = 'manual_mark') > 0,
               'SELECT 1',
               'ALTER TABLE event_records ADD COLUMN manual_mark DECIMAL(10,3) NULL AFTER achieved_on');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'event_records'
                  AND COLUMN_NAME = 'manual_unit') > 0,
               'SELECT 1',
               'ALTER TABLE event_records ADD COLUMN manual_unit VARCHAR(20) NULL AFTER manual_mark');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'event_records'
                  AND COLUMN_NAME = 'manual_holder_name') > 0,
               'SELECT 1',
               'ALTER TABLE event_records ADD COLUMN manual_holder_name VARCHAR(120) NULL AFTER manual_unit');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'event_records'
                  AND COLUMN_NAME = 'manual_achieved_on') > 0,
               'SELECT 1',
               'ALTER TABLE event_records ADD COLUMN manual_achieved_on DATE NULL AFTER manual_holder_name');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- The holder's name in its own right, so a typed-in record and a student's record
-- read the same way. Backfilled from the holder account where there is one.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'event_records'
                  AND COLUMN_NAME = 'holder_name') > 0,
               'SELECT 1',
               'ALTER TABLE event_records ADD COLUMN holder_name VARCHAR(120) NULL AFTER holder_user_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE event_records r
   JOIN users u ON u.id = r.holder_user_id
   SET r.holder_name = COALESCE(u.full_name, u.username)
 WHERE r.holder_name IS NULL AND r.holder_user_id IS NOT NULL;
