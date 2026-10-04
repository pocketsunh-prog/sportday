-- ---------------------------------------------------------------------------
-- A sprint with only a group's worth of entries runs straight to a final
--
-- `direct_to_final_auto` records that the SYSTEM switched `direct_to_final` on,
-- because a final with eight or fewer entrants would be the same athletes as the
-- heat. When entries rise again the system puts the final back — but only if it was
-- the one that took it away. A format the school chose is never undone behind its
-- back, so an explicit choice clears this flag.
--
-- No backfill is needed: an event that already exists keeps the format it has, and
-- NULL is exactly right for "the school's own setting".
--
-- Hibernate's ddl-auto=update adds the column itself, so this script is only needed
-- where ddl-auto is validate, or to apply the schema ahead of a deploy. Idempotent.
-- ---------------------------------------------------------------------------

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'events'
                  AND COLUMN_NAME = 'direct_to_final_auto') > 0,
               'SELECT 1',
               'ALTER TABLE events ADD COLUMN direct_to_final_auto BIT(1) NULL DEFAULT b''0''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
