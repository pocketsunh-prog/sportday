-- ---------------------------------------------------------------------------
-- Direct to final
--
-- An event is decided by its own run unless the school asks for heats and a final,
-- which only 60M / 100M / 200M / 400M may be. `direct_to_final` records that; NULL
-- reads as TRUE, so an event written before the column existed behaves as a
-- direct final rather than silently acquiring one.
--
-- The backfill preserves what each event is already doing: a short sprint that has
-- heats drawn keeps them and its final, everything else runs straight to a final.
--
-- The application does the same backfill on start, so this script is only needed
-- where ddl-auto is validate, or to apply the schema ahead of a deploy.
-- Idempotent.
-- ---------------------------------------------------------------------------

-- The column is added WITHOUT a default on purpose. `ADD COLUMN ... DEFAULT b'1'`
-- would fill every existing row with 1 immediately, so the backfill below — which
-- keys on IS NULL — would never match and every short sprint that already has
-- heats would silently become direct to a final. The default is applied at the end,
-- once the existing rows have been decided.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'events'
                  AND COLUMN_NAME = 'direct_to_final') > 0,
               'SELECT 1',
               'ALTER TABLE events ADD COLUMN direct_to_final BIT(1) NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- A 60/100/200/400 with heats already drawn was being run as heats and a final.
UPDATE events e
   SET e.direct_to_final = b'0'
 WHERE e.direct_to_final IS NULL
   AND e.type IN ('RUN_60M', 'RUN_100M', 'RUN_200M', 'RUN_400M')
   AND EXISTS (SELECT 1 FROM event_groups g WHERE g.event_id = e.id);

-- Everything else, including a distance event split into several sheets, is
-- decided by its own run.
UPDATE events
   SET direct_to_final = b'1'
 WHERE direct_to_final IS NULL;

-- Only now, so a new row gets the default the school expects.
ALTER TABLE events ALTER COLUMN direct_to_final SET DEFAULT b'1';
