-- ===========================================================================
--  Sport Day  ·  01-schema.sql
--  Complete schema for a FRESH install — all 14 tables.
-- ===========================================================================
--
--  WHAT THIS IS
--    The whole database: the schema Hibernate (`spring.jpa.hibernate.ddl-auto:
--    update`) builds for the application, plus the hand-written columns that
--    were added to the school's database by backend/db/migration/*.sql.
--
--    It was taken from the live database with
--        mysqldump --no-data --routines --triggers --databases sportday
--    and then checked, column by column, against the JPA entity classes in
--    backend/src/main/java/com/sportday/entity/.  See README.md, "How the
--    schema was derived", for what the dump could not settle and how it was
--    resolved.
--
--    The live database has no triggers, no stored routines, no views, no
--    scheduled events and no CHECK constraints, so none are created here.
--
--  HOW TO RUN IT
--        mysql -h 127.0.0.1 -P 3307 -u root -proot123 < 01-schema.sql
--    or straight into the container:
--        docker exec -i sportday-mysql mysql -uroot -proot123 < 01-schema.sql
--
--  IDEMPOTENT
--    Every statement is safe to run again:
--      * CREATE DATABASE IF NOT EXISTS  — keeps an existing database.
--      * ALTER DATABASE ...             — re-states the charset, changes no data.
--      * CREATE USER IF NOT EXISTS      — an existing account is left alone,
--                                         password and all (MySQL emits a
--                                         warning and does not reset it).
--      * GRANT                          — idempotent; grants what is missing.
--      * CREATE TABLE IF NOT EXISTS     — an existing table is left exactly as
--                                         it is, data and all.
--    Running it twice therefore changes nothing, and running it against the
--    school's live database would not drop a single row or table.
--
--    The one thing IF NOT EXISTS cannot do is reconcile a table that already
--    exists with a DIFFERENT shape: the existing definition wins and no warning
--    is raised.  That is the intended trade — never destroy live data.
--
--  PRIVILEGES
--    Steps 1-5 create the database, the application account and its grant, so
--    they need an administrative account (root).  Where those already exist —
--    which is the case for the docker-compose stack, whose MYSQL_DATABASE /
--    MYSQL_USER / MYSQL_PASSWORD create them at first container start — the
--    block is a no-op and you may comment it out and run the rest as
--    `sportday` itself if you prefer.
--
--  AUTO_INCREMENT
--    Deliberately not set.  A fresh install starts every table at 1; the large
--    values on the school's server are a record of how much data it holds and
--    are none of a new installation's business.
-- ===========================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1. The database
-- ---------------------------------------------------------------------------
-- utf8mb4 is required and not negotiable: the application stores Chinese event
-- and category names (徑項, 田項, 接力) and Chinese student names.
CREATE DATABASE IF NOT EXISTS `sportday`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- Stated again so an existing database with a different charset is corrected
-- without being recreated.  Touches no table and no row.
ALTER DATABASE `sportday` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 2. The application account
-- ---------------------------------------------------------------------------
-- Matches docker-compose.yml (MYSQL_USER / MYSQL_PASSWORD) and
-- backend/src/main/resources/application.yml (spring.datasource.username /
-- password).  '%' is right for the Docker case: the application connects from
-- the host through the published 3307 port, so it arrives from the Docker
-- bridge address, not from localhost.  It also covers socket connections —
-- MySQL matches 'localhost' against '%'.
CREATE USER IF NOT EXISTS 'sportday'@'%' IDENTIFIED BY 'sportday123';

-- An account that already exists is deliberately LEFT ALONE — name, host and
-- password all unchanged (MySQL raises a warning and does not reset it).  This
-- script creates what is missing; it does not rotate credentials.  If you have
-- changed the application's password, change it in application.yml and
-- docker-compose.yml as well, or reset it by hand with
--     ALTER USER 'sportday'@'%' IDENTIFIED BY '<new password>';
GRANT ALL PRIVILEGES ON `sportday`.* TO 'sportday'@'%';

-- No FLUSH PRIVILEGES is needed: CREATE USER and GRANT reload the grant tables
-- themselves.  It is only required after editing them by hand.

-- ---------------------------------------------------------------------------
-- 3. Tables
-- ---------------------------------------------------------------------------
-- Created in foreign-key dependency order, so the script needs no
-- FOREIGN_KEY_CHECKS=0 around it:
--     users, seasons, sport_day_settings, standard_defaults   (no parents)
--     teacher_classes, students                               -> users
--     events                                                  -> seasons
--     event_groups                                            -> events
--     relay_teams                                             -> events
--     relay_team_members                                      -> relay_teams, users
--     enrollments                                             -> users, events, event_groups
--     event_results                                           -> users, events, relay_teams
--     final_entries                                           -> event_groups, users
--     event_records                                           -> events, event_results, users

USE `sportday`;

-- ---------------------------------------------------------------------------
-- 3.1  users — every login: staff, helpers, teachers and students
-- ---------------------------------------------------------------------------
-- `role` is a native ENUM.  Hibernate generates ENUM(...) with the values in
-- alphabetical order for @Enumerated(EnumType.STRING) on MySQL, and the
-- hand-written migrations widen it with ALTER TABLE ... MODIFY COLUMN when a
-- role is added (see backend/db/migration/helper-role-migration.sql).  The six
-- values below are exactly User.Role's six constants.
--
-- `email` is UNIQUE and NULLABLE on purpose: imported student accounts have no
-- email address, and MySQL permits any number of NULLs in a unique index.
CREATE TABLE IF NOT EXISTS `users` (
  `id`         bigint       NOT NULL AUTO_INCREMENT,
  `age`        int          DEFAULT NULL,
  `created_at` datetime(6)  NOT NULL,
  `email`      varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `enabled`    bit(1)       NOT NULL,
  `full_name`  varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `gender`     varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `password`   varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `role`       enum('ADMIN','HELPER','MANAGER','STUDENT','TEACHER','USER') COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6)  DEFAULT NULL,
  `username`   varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKr43af9ap4edm43mmtq01oddj6` (`username`),
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3.2  seasons — one school year, one sport day
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `seasons` (
  `id`              bigint       NOT NULL AUTO_INCREMENT,
  `created_at`      datetime(6)  NOT NULL,
  `enrollment_open` bit(1)       NOT NULL,
  `name`            varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `notes`           varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sport_day_date`  date         DEFAULT NULL,
  `updated_at`      datetime(6)  DEFAULT NULL,
  `year`            int          NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_season_year` (`year`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3.3  sport_day_settings — the school's rules, a single row with id = 1
-- ---------------------------------------------------------------------------
-- No AUTO_INCREMENT: the id is assigned by the application
-- (SportDaySettings.SINGLETON_ID = 1), not generated by the database.
-- A settings row is written by DataInitializer on first boot; see 02-seed.sql.
CREATE TABLE IF NOT EXISTS `sport_day_settings` (
  `id`                 bigint       NOT NULL,
  `address`            varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `field_max_entries`  int          NOT NULL,
  `points_first`       int          NOT NULL,
  `points_second`      int          NOT NULL,
  `points_third`       int          NOT NULL,
  `points_top`         int          NOT NULL,
  `points_top_place`   int          NOT NULL,
  `principal`          varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `relay_points_first` int          NOT NULL,
  `relay_points_second` int         NOT NULL,
  `relay_points_third` int          NOT NULL,
  `relay_points_top`   int          NOT NULL,
  `school_name`        varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `school_name_zh`     varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sport_day_title`    varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `track_max_entries`  int          NOT NULL,
  `updated_at`         datetime(6)  DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3.4  standard_defaults — the qualifying mark a grade and division starts on
-- ---------------------------------------------------------------------------
-- Empty on purpose: nothing on first boot fills it.  The standards page in the
-- application creates a row per event type / grade / division as an
-- administrator sets one, through StandardDefaultService.
CREATE TABLE IF NOT EXISTS `standard_defaults` (
  `id`       bigint NOT NULL AUTO_INCREMENT,
  `grade`    enum('A','B','C') COLLATE utf8mb4_unicode_ci NOT NULL,
  `sex`      enum('FEMALE','MALE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `standard` decimal(10,3) DEFAULT NULL,
  `type`     enum('DISCUSSION_THROW','HAMMER_THROW','HIGH_JUMP','HURDLES_100M','HURDLES_110M','HURDLES_400M','JAVELIN_THROW','LONG_JUMP','OTHER','POLE_VAULT','RELAY_4X100M','RELAY_4X400M','RUN_100M','RUN_1500M','RUN_200M','RUN_400M','RUN_5000M','RUN_60M','RUN_800M','SHOT_PUT','TRIPLE_JUMP') COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_standard_default_type_grade_sex` (`type`,`grade`,`sex`),
  KEY `idx_standard_defaults_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3.5  teacher_classes — the classes one teacher may help
-- ---------------------------------------------------------------------------
-- NOTE ON COLLATION — read this before "fixing" it.
-- This is the only table in the schema whose default collation is
-- utf8mb4_0900_ai_ci (MySQL 8's server default) rather than
-- utf8mb4_unicode_ci.  That is not a typo: backend/db/migration/
-- teacher-accounts-migration.sql declares
--     ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
-- with no COLLATE, so the table inherited the server default, and the live
-- database shows exactly that.  It is reproduced here so that a fresh install
-- behaves identically to the school's database.
--
-- Consequence: `class_name` here is utf8mb4_0900_ai_ci while
-- `students.class_name` is utf8mb4_unicode_ci, so a future SQL JOIN or
-- comparison between the two would raise "Illegal mix of collations".  Today it
-- does not: every query against either column is a single-table JPQL query
-- (TeacherClassRepository, StudentRepository), and the two values are only ever
-- compared in Java.
--
-- To normalise a fresh install instead, change the COLLATE on the last line of
-- this statement to utf8mb4_unicode_ci before running it.  Nothing in the
-- application depends on the difference.
CREATE TABLE IF NOT EXISTS `teacher_classes` (
  `id`         bigint      NOT NULL AUTO_INCREMENT,
  `user_id`    bigint      NOT NULL,
  `class_name` varchar(20) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_teacher_class` (`user_id`,`class_name`),
  KEY `idx_teacher_classes_class` (`class_name`),
  CONSTRAINT `fk_teacher_classes_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 3.6  students — the imported register, one row per student
-- ---------------------------------------------------------------------------
-- One-to-one with users: students.user_id is UNIQUE.  The student's password is
-- never stored here — it is derived from dob + class + class number and only
-- its BCrypt hash lives on the linked users row.
CREATE TABLE IF NOT EXISTS `students` (
  `id`           bigint       NOT NULL AUTO_INCREMENT,
  `class_name`   varchar(20)  COLLATE utf8mb4_unicode_ci NOT NULL,
  `class_number` int          NOT NULL,
  `created_at`   datetime(6)  NOT NULL,
  `dob`          date         NOT NULL,
  `enabled`      bit(1)       NOT NULL,
  `grade`        enum('A','B','C') COLLATE utf8mb4_unicode_ci NOT NULL,
  `house`        varchar(40)  COLLATE utf8mb4_unicode_ci NOT NULL,
  `import_batch` varchar(60)  COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name`         varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sex`          enum('FEMALE','MALE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `student_id`   varchar(40)  COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at`   datetime(6)  DEFAULT NULL,
  `user_id`      bigint       NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK5mbus2m1tm2acucrp6t627jmx` (`student_id`),
  UNIQUE KEY `UKg4fwvutq09fjdlb4bb0byp7t` (`user_id`),
  KEY `idx_students_class` (`class_name`),
  KEY `idx_students_grade` (`grade`),
  KEY `idx_students_house` (`house`),
  CONSTRAINT `FKdt1cjx5ve5bdabmuuf3ibrwaq` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3.7  events — one race or field event, for one division and one grade
-- ---------------------------------------------------------------------------
-- `type` holds all 21 Event.EventType constants; `category` 徑項/田項/接力;
-- `grade` is NOT NULL because an event that mixed grades would rank a grade
-- against another, which the school does not want.
--
-- Columns added after the table was first created (season_id onwards) come from
-- backend/db/migration/, and that is why the declaration order here is not
-- alphabetical: the first block is Hibernate's own, the tail is history.
CREATE TABLE IF NOT EXISTS `events` (
  `id`                     bigint       NOT NULL AUTO_INCREMENT,
  `category`               enum('FIELD','RELAY','TRACK') COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at`             datetime(6)  NOT NULL,
  `description`            varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `direct_to_final`        bit(1)       DEFAULT NULL,
  `direct_to_final_auto`   bit(1)       DEFAULT NULL,
  `enabled`                bit(1)       NOT NULL,
  `event_date`             date         NOT NULL,
  `grade`                  enum('A','B','C') COLLATE utf8mb4_unicode_ci NOT NULL,
  `group_size`             int          NOT NULL,
  `location`               varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `max_participants`       int          NOT NULL,
  `name`                   varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sex`                    enum('FEMALE','MALE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `type`                   enum('DISCUSSION_THROW','HAMMER_THROW','HIGH_JUMP','HURDLES_100M','HURDLES_110M','HURDLES_400M','JAVELIN_THROW','LONG_JUMP','OTHER','POLE_VAULT','RELAY_4X100M','RELAY_4X400M','RUN_100M','RUN_1500M','RUN_200M','RUN_400M','RUN_5000M','RUN_60M','RUN_800M','SHOT_PUT','TRIPLE_JUMP') COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at`             datetime(6)  DEFAULT NULL,
  `season_id`              bigint       DEFAULT NULL,
  `relay_team_kind`        enum('FORM','HOUSE') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `relay_team_size`        int          DEFAULT NULL,
  `relay_reserves_allowed` bit(1)       DEFAULT NULL,
  `is_draft`               bit(1)       DEFAULT NULL,
  `form`                   varchar(4)   COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `standard`               decimal(10,3) DEFAULT NULL,
  `standard_is_default`    bit(1)       DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_events_enabled` (`enabled`),
  KEY `idx_events_category_sex` (`category`,`sex`),
  KEY `FKpvt0a432cu2669eyv34fswc5o` (`season_id`),
  CONSTRAINT `FKpvt0a432cu2669eyv34fswc5o` FOREIGN KEY (`season_id`) REFERENCES `seasons` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3.8  event_groups — the heats of an event, and its final
-- ---------------------------------------------------------------------------
-- group_number 0 is reserved for the final (EventGroup.FINAL_GROUP_NUMBER);
-- `stage` says which, and is NULLABLE because rows written before the
-- heat/final split have none.  DataInitializer adopts those as HEAT on boot.
CREATE TABLE IF NOT EXISTS `event_groups` (
  `id`            bigint      NOT NULL AUTO_INCREMENT,
  `athlete_count` int         NOT NULL,
  `capacity`      int         NOT NULL,
  `created_at`    datetime(6) NOT NULL,
  `group_number`  int         NOT NULL,
  `stage`         enum('FINAL','HEAT') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `event_id`      bigint      NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_group_number` (`event_id`,`group_number`),
  CONSTRAINT `FKk4e4tvlp60add7vxm56digfrg` FOREIGN KEY (`event_id`) REFERENCES `events` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3.9  relay_teams — one team inside a relay event
-- ---------------------------------------------------------------------------
-- `kind` is NULLABLE: a relay with no kind is simply undivided, which is how
-- the relays already in the programme behave.
CREATE TABLE IF NOT EXISTS `relay_teams` (
  `id`             bigint      NOT NULL AUTO_INCREMENT,
  `event_id`       bigint      NOT NULL,
  `kind`           enum('FORM','HOUSE') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `team_key`       varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `label`          varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at`     datetime(6) NOT NULL,
  `updated_at`     datetime(6) DEFAULT NULL,
  `name_overridden` bit(1)     DEFAULT NULL,
  `hand_made`      bit(1)      DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_relay_team_event_kind_key` (`event_id`,`kind`,`team_key`),
  KEY `idx_relay_team_event` (`event_id`),
  CONSTRAINT `fk_relay_team_event` FOREIGN KEY (`event_id`) REFERENCES `events` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3.10  relay_team_members — a runner's leg in a team
-- ---------------------------------------------------------------------------
-- Two unique keys, deliberately: one runner is in a team once, and one leg has
-- one runner.  A `leg` past the race's own size is a reserve.
CREATE TABLE IF NOT EXISTS `relay_team_members` (
  `id`         bigint      NOT NULL AUTO_INCREMENT,
  `team_id`    bigint      NOT NULL,
  `user_id`    bigint      NOT NULL,
  `leg`        int         NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_relay_member_team_user` (`team_id`,`user_id`),
  UNIQUE KEY `uk_relay_member_team_leg` (`team_id`,`leg`),
  KEY `idx_relay_member_user` (`user_id`),
  CONSTRAINT `fk_relay_member_team` FOREIGN KEY (`team_id`) REFERENCES `relay_teams` (`id`),
  CONSTRAINT `fk_relay_member_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3.11  enrollments — one student's entry into one event
-- ---------------------------------------------------------------------------
-- The unique key (user_id, event_id) is the rule "a student enters an event
-- once"; the entry-limit rules (track/field counts) are enforced in
-- EnrollmentService, not by the schema.
CREATE TABLE IF NOT EXISTS `enrollments` (
  `id`             bigint      NOT NULL AUTO_INCREMENT,
  `enrolled_at`    datetime(6) NOT NULL,
  `lane`           int         DEFAULT NULL,
  `status`         enum('CANCELLED','CONFIRMED','PENDING') COLLATE utf8mb4_unicode_ci NOT NULL,
  `event_id`       bigint      NOT NULL,
  `event_group_id` bigint      DEFAULT NULL,
  `user_id`        bigint      NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKn0vrnxjkb4wd19qdxh6j6iq8a` (`user_id`,`event_id`),
  KEY `FKlih6fb6gc52jvgbtbyuenm24f` (`event_id`),
  KEY `FK51191s3kofv939kwhe893cf8t` (`event_group_id`),
  CONSTRAINT `FK3hjx6rcnbmfw368sxigrpfpx0` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FK51191s3kofv939kwhe893cf8t` FOREIGN KEY (`event_group_id`) REFERENCES `event_groups` (`id`),
  CONSTRAINT `FKlih6fb6gc52jvgbtbyuenm24f` FOREIGN KEY (`event_id`) REFERENCES `events` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3.12  event_results — a mark, or an absence or disqualification
-- ---------------------------------------------------------------------------
-- `outcome` is VARCHAR(10), NOT a native ENUM, and that is the one place this
-- schema departs from the other enum columns.  It was added by
-- backend/db/migration/mark-outcome-migration.sql as
--     ADD COLUMN outcome VARCHAR(10) NULL
-- and is written here exactly as the live database has it.
--
-- The entity declares @Enumerated(EnumType.STRING) with length 10, and the app
-- reads NULL through getOutcomeOrDefault() as Outcome.RESULT.
--
-- Verified, not assumed: the application was booted against a database built by
-- this file and Hibernate left this column alone.  It did NOT convert it to
-- enum('ABS','DQ','RESULT'); it stayed varchar(10), exactly as the school's
-- database has it.  Writing varchar(10) here therefore does not invite a
-- first-boot ALTER, and a fresh install ends up with the same column as the
-- live one.  (See README.md, "ddl-auto: update versus this script".)
--
-- `mark` is NULLABLE because an ABS or DQ row has no number at all.
CREATE TABLE IF NOT EXISTS `event_results` (
  `id`           bigint       NOT NULL AUTO_INCREMENT,
  `attempt_1`    decimal(10,3) DEFAULT NULL,
  `attempt_2`    decimal(10,3) DEFAULT NULL,
  `attempt_3`    decimal(10,3) DEFAULT NULL,
  `mark`         decimal(10,3) DEFAULT NULL,
  `notes`        varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `recorded_at`  datetime(6)  NOT NULL,
  `stage`        enum('FINAL','HEAT') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `unit`         varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `event_id`     bigint       NOT NULL,
  `user_id`      bigint       NOT NULL,
  `outcome`      varchar(10)  COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `relay_team_id` bigint      DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_result_user_event_stage` (`user_id`,`event_id`,`stage`),
  UNIQUE KEY `uk_event_results_relay_team` (`relay_team_id`,`event_id`,`stage`),
  KEY `FKrmqwb942jaoagmman4oulcqy9` (`event_id`),
  CONSTRAINT `fk_event_results_relay_team` FOREIGN KEY (`relay_team_id`) REFERENCES `relay_teams` (`id`) ON DELETE SET NULL,
  CONSTRAINT `FKeepo6rbm00rltmsfayc8u1p6s` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKrmqwb942jaoagmman4oulcqy9` FOREIGN KEY (`event_id`) REFERENCES `events` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3.13  final_entries — a place on the start list of a final
-- ---------------------------------------------------------------------------
-- `seed` is not a reserved word in MySQL 8 (SEED is only a keyword in some
-- storage-engine contexts) and is a legal column name; it is back-quoted here
-- purely for consistency with the rest of the file.
CREATE TABLE IF NOT EXISTS `final_entries` (
  `id`         bigint      NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `lane`       int         NOT NULL,
  `seed`       int         NOT NULL,
  `seed_mark`  decimal(10,3) DEFAULT NULL,
  `seed_unit`  varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `group_id`   bigint      NOT NULL,
  `user_id`    bigint      NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_final_entry_group_user` (`group_id`,`user_id`),
  KEY `FKcf10w1d1oesi9d3twexft5mta` (`user_id`),
  CONSTRAINT `FKcf10w1d1oesi9d3twexft5mta` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKkfvy37089ljpime5aesax6s8q` FOREIGN KEY (`group_id`) REFERENCES `event_groups` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3.14  event_records — the school record standing for one type, division, grade
-- ---------------------------------------------------------------------------
-- One row per (event_type, sex, grade) — the unique key says so — holding the
-- standing mark, the administrator's manual baseline, and what the standing
-- mark beat so the "new record" notices can be drawn.
--
-- `has_previous` is NOT NULL with no default.  That is what the live database
-- has and what Hibernate generates for a `Boolean` declared @Column(nullable =
-- false); the entity sets it itself in @PrePersist/@PreUpdate.  It is therefore
-- listed here as a required column, not an oversight — a hand-written INSERT
-- into this table must supply it.
CREATE TABLE IF NOT EXISTS `event_records` (
  `id`                   bigint       NOT NULL AUTO_INCREMENT,
  `achieved_on`          date         DEFAULT NULL,
  `event_type`           enum('DISCUSSION_THROW','HAMMER_THROW','HIGH_JUMP','HURDLES_100M','HURDLES_110M','HURDLES_400M','JAVELIN_THROW','LONG_JUMP','OTHER','POLE_VAULT','RELAY_4X100M','RELAY_4X400M','RUN_100M','RUN_1500M','RUN_200M','RUN_400M','RUN_5000M','RUN_60M','RUN_800M','SHOT_PUT','TRIPLE_JUMP') COLLATE utf8mb4_unicode_ci NOT NULL,
  `grade`                enum('A','B','C') COLLATE utf8mb4_unicode_ci NOT NULL,
  `has_previous`         bit(1)       NOT NULL,
  `holder_name`          varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `manual_achieved_on`   date         DEFAULT NULL,
  `manual_holder_name`   varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `manual_mark`          decimal(10,3) DEFAULT NULL,
  `manual_unit`          varchar(20)  COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `mark`                 decimal(10,3) DEFAULT NULL,
  `previous_achieved_on` date         DEFAULT NULL,
  `previous_holder_name` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `previous_mark`        decimal(10,3) DEFAULT NULL,
  `sex`                  enum('FEMALE','MALE') COLLATE utf8mb4_unicode_ci NOT NULL,
  `unit`                 varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at`           datetime(6)  DEFAULT NULL,
  `event_id`             bigint       DEFAULT NULL,
  `holder_user_id`       bigint       DEFAULT NULL,
  `result_id`            bigint       DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_record_type_sex_grade` (`event_type`,`sex`,`grade`),
  KEY `FKiekgk4jbxhxiqendjwy5hv0yf` (`event_id`),
  KEY `FKl7xevgk4e29apmhxl9n3a1wjm` (`holder_user_id`),
  KEY `FKjwi4pn5gepttg9fumv4voowx7` (`result_id`),
  CONSTRAINT `FKiekgk4jbxhxiqendjwy5hv0yf` FOREIGN KEY (`event_id`) REFERENCES `events` (`id`),
  CONSTRAINT `FKjwi4pn5gepttg9fumv4voowx7` FOREIGN KEY (`result_id`) REFERENCES `event_results` (`id`),
  CONSTRAINT `FKl7xevgk4e29apmhxl9n3a1wjm` FOREIGN KEY (`holder_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 4. Verify
-- ---------------------------------------------------------------------------
-- Expect 14.  Uncomment to check by hand after running this file.
--
-- SELECT COUNT(*) AS tables_created FROM information_schema.TABLES
--  WHERE TABLE_SCHEMA = 'sportday' AND TABLE_TYPE = 'BASE TABLE';
--
-- SELECT TABLE_NAME, TABLE_COLLATION FROM information_schema.TABLES
--  WHERE TABLE_SCHEMA = 'sportday' ORDER BY TABLE_NAME;
