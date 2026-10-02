-- ============================================================================
-- SportDay revamp — migration for a database created by the PREVIOUS version.
--
-- A brand new database needs none of this: `spring.jpa.hibernate.ddl-auto=update`
-- creates every table and column correctly on first start.
--
-- Run this once, against an existing `sportday` schema, BEFORE starting the
-- revamped backend:
--
--   docker exec -i sportday-mysql mysql -usportday -psportday123 -D sportday < this-file.sql
--
-- Why it is needed: Hibernate's schema *update* adds new tables and columns, but
-- it never widens an existing MySQL ENUM. The revamp adds a new role and a new
-- event type, and student accounts have no email address, so three columns have
-- to be adjusted by hand.
-- ============================================================================

-- 1. Student logins use the new STUDENT role.
ALTER TABLE users MODIFY COLUMN role ENUM('ADMIN', 'MANAGER', 'USER', 'STUDENT') NOT NULL;

-- 2. Imported student accounts have no email address.
ALTER TABLE users MODIFY COLUMN email VARCHAR(255) NULL;

-- 3. The 60M event was added to the catalogue.
ALTER TABLE events MODIFY COLUMN type ENUM(
    'DISCUSSION_THROW', 'HAMMER_THROW', 'HIGH_JUMP', 'HURDLES_110M', 'HURDLES_400M',
    'JAVELIN_THROW', 'LONG_JUMP', 'OTHER', 'POLE_VAULT', 'RELAY_4X100M', 'RELAY_4X400M',
    'RUN_100M', 'RUN_1500M', 'RUN_200M', 'RUN_400M', 'RUN_5000M', 'RUN_800M',
    'RUN_60M', 'SHOT_PUT', 'TRIPLE_JUMP'
) NOT NULL;

-- 4. `events`, `students` and `event_groups` columns, plus the new
--    `enrollments.event_group_id` / `enrollments.lane`, are created automatically
--    by ddl-auto=update on the next start. Legacy event rows are then backfilled
--    with a category, sex division and group size by DataInitializer.
--
--    Legacy events are assigned to the boys' division, because the old schema had
--    no notion of divisions. Re-assign them from the admin event page if needed.
