-- ---------------------------------------------------------------------------
-- DRAFT relay events: the event the school's hand-made relay teams are built on
--
-- The school's requirement, as confirmed:
--
--   "create relay event base on selected relay team."
--
-- The school builds its relay TEAMS first, by hand, and then wants the relay EVENT
-- created around them. A relay team cannot exist without an event: `relay_teams.event_id`
-- is NOT NULL, and both the marking sheet and the mark-entry grid reach a team
-- *through* its event — so a team with no event could hold no mark and print no
-- sheet. A draft event is the honest place for the selections to live: a real
-- `events` row, so every foreign key and every rule holds, that is deliberately not
-- on the programme.
--
-- WHAT THIS SCRIPT DOES
--
--   (a) `events.is_draft` — one new NULLABLE column, true when the event is a draft:
--       a relay event holding teams the school is still building.
--
--       Nullable on purpose, exactly like `events.direct_to_final` and
--       `relay_teams.hand_made`: NULL reads as false through `Event.isDraft()`, so
--       every event already on file — all 112 on the live instance — is a real event
--       and behaves exactly as it did before the column existed. There is no data
--       migration and nothing to backfill: no existing row is a draft.
--
--       It is one boolean and not a status enum on purpose. A draft is not a state an
--       event moves through; it is the absence of one thing (a race the school has
--       entered) and the presence of another (teams being collected). Every place
--       that lists or counts events decides explicitly whether a draft belongs there,
--       and the answer is "no" for the programme, the date picker, the past-events
--       list, the results print run and the year's event count.
--
--   (b) NO new index and NO new unique key. A draft is found by scanning `events`
--       (there are a hundred-odd rows, and an administrator's draft list is not a hot
--       path), so an index would cost a write on every event for nothing.
--
--   (c) NO new constraint linking a draft to the event its teams are moved to. The
--       move is all-or-nothing inside one transaction
--       (`RelayTeamService.moveTeamsToEvent`), which re-checks the destination's name
--       rule, its team size and its division and grade before anything moves; a
--       half-moved board is not a state the schema should have to prevent, because
--       the service never produces one.
--
-- DO NOT run this against a live database without meaning to: `events` holds the
-- school's whole programme. It is idempotent and safe to repeat.
--
-- HIBERNATE'S ddl-auto=update WOULD ADD IT ANYWAY. `spring.jpa.hibernate.ddl-auto` is
-- `update` in `backend/src/main/resources/application.yml`, and this is a NULLABLE
-- column ADD — the one case Hibernate's schema update handles reliably, whatever the
-- dialect's column comparison makes of the rest — so on the next start the column
-- appears by itself, with every existing row NULL, which `Event.isDraft()` reads as
-- false. THIS SCRIPT IS STILL THE RELIABLE PATH: it is explicit about the name and
-- the nullability, it is what has to be run where `ddl-auto` is `validate` or `none`
-- (and on any instance the application does not own the schema of), and it is what
-- the deployment actually applies. Running both is harmless.
-- ---------------------------------------------------------------------------

-- 1. `events.is_draft` — true when the event is a draft relay event holding teams the
--    school is building. MySQL has no "ADD COLUMN IF NOT EXISTS", so it is guarded
--    against the catalogue, which is what makes this script repeatable.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'events'
                  AND COLUMN_NAME = 'is_draft') > 0,
               'SELECT 1',
               'ALTER TABLE events ADD COLUMN is_draft BIT(1) NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- NO part of this script touches the school's data. No row is read, written or
-- backfilled, and after it runs `SELECT COUNT(*) FROM events WHERE is_draft = 1`
-- is zero: the school has no draft until an administrator makes one through
-- POST /api/admin/relay-events/drafts.
-- ---------------------------------------------------------------------------
