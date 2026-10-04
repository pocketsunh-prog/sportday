-- ---------------------------------------------------------------------------
-- ABS and DQ on the mark-entry sheet
--
-- A helper recording a sheet could only ever write a number, so an athlete who
-- did not turn up, or who was disqualified, had nowhere to go. `event_results`
-- gains an `outcome`:
--
--   RESULT   a mark was recorded — `mark` carries it, and this is what every row
--            written before the column existed means;
--   ABS      absent — the athlete did not compete;
--   DQ       disqualified — the performance does not stand.
--
-- Both ABS and DQ mean no mark was produced: `mark` and the three attempts are
-- left NULL, the athlete is not placed, scores no point and can never hold a
-- school record, and the outcome is printed in place of the mark. An empty mark
-- box is unchanged — it still means "nothing recorded yet".
--
-- Two schema changes follow from that:
--   * `outcome` is added and is NULLABLE on purpose. Hibernate's ddl-auto=update
--     adds the column to a table that already holds rows, and a NOT NULL column
--     with no default would leave every one of them unable to map onto the enum.
--   * `mark` must allow NULL as well, because an ABS/DQ row has no number at all.
--     Hibernate adds the new column itself but cannot relax this NOT NULL, so
--     this step is needed even where ddl-auto is `update`.
--
-- No backfill is needed: NULL means RESULT, which is exactly what every existing
-- row is. Readers go through the entity's getOutcomeOrDefault(), so a NULL
-- outcome behaves as RESULT everywhere — a mark that was recorded stays a mark.
--
-- MySQL has no "ADD COLUMN IF NOT EXISTS", so the column is guarded against
-- information_schema. Idempotent and safe to run repeatedly.
-- ---------------------------------------------------------------------------

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'event_results'
                  AND COLUMN_NAME = 'outcome') > 0,
               'SELECT 1',
               'ALTER TABLE event_results ADD COLUMN outcome VARCHAR(10) NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- `mark` may now be empty: an athlete who was absent or disqualified has no mark.
ALTER TABLE event_results MODIFY COLUMN mark DECIMAL(10,3) NULL;
