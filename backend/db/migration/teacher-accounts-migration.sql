-- ---------------------------------------------------------------------------
-- Teacher accounts, and the classes a teacher may help
--
-- The school asked for two things:
--
--   1. an administrator can upload teacher accounts;
--
--   2. a teacher can help a student enter or withdraw from events — but only a
--      student in one of the classes assigned to that teacher. An administrator
--      may still help any student.
--
-- This script does the two things Hibernate cannot do for itself:
--
--   * it adds TEACHER to the `users.role` column. That column is a MySQL ENUM,
--     and `ddl-auto=update` never widens an existing ENUM — the same reason
--     revamp-migration.sql rewrites `role` to add STUDENT. Without this, signing
--     in as a teacher (or creating one) fails on an existing database.
--
--   * it creates `teacher_classes`, the assignment table. Hibernate WILL create
--     this table itself on the next start, so on a database running with
--     ddl-auto=update this part is a convenience: it lets the schema be applied
--     ahead of a deploy, or on a database where ddl-auto is `validate`/`none`.
--
-- The table is a row per (teacher, class) rather than a comma-separated string
-- on the account, so a class can be queried, the pair is unique by construction,
-- and re-uploading a teacher replaces the set instead of editing a string nobody
-- can validate. A teacher with no rows here can help NOBODY — that is the
-- documented refusal, not a silent allow.
--
-- Run it once, against an existing `sportday` schema:
--
--   docker exec -i sportday-mysql mysql -usportday -psportday123 -D sportday \
--     < backend/db/migration/teacher-accounts-migration.sql
--
-- It is idempotent: the ENUM rewrite states the full set of values it wants, and
-- the table is only created when it is missing, so running it twice — or after
-- Hibernate has already created the table — changes nothing the second time.
-- ---------------------------------------------------------------------------

-- 1. `users.role` gains TEACHER.
--
-- The complete list, not a partial one: MySQL has no "ADD ENUM VALUE", so the
-- column is restated. Every value the application can write must be here or the
-- insert is truncated with a warning and the account becomes a USER.
ALTER TABLE users MODIFY COLUMN role
    ENUM('ADMIN', 'MANAGER', 'USER', 'STUDENT', 'TEACHER') NOT NULL;

-- 2. `teacher_classes` — one row per class a teacher may help in.
--
-- `class_name` is stored exactly as `students.class_name` is (upper-cased, no
-- whitespace), which is what makes the class check an exact comparison. The
-- unique key is on (user_id, class_name), so a teacher cannot be assigned the
-- same class twice and a re-upload can replace the set safely.
--
-- The teacher's account is deleted with them: an assignment has no meaning
-- without the account it was made for, so ON DELETE CASCADE is right here —
-- unlike the school's history, which is never cascaded away.
CREATE TABLE IF NOT EXISTS teacher_classes (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    class_name  VARCHAR(20)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_teacher_class (user_id, class_name),
    KEY idx_teacher_classes_class (class_name),
    CONSTRAINT fk_teacher_classes_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 3. (Optional, informational.) Who teaches what, once the administrator has
--    uploaded the staff list. Left commented out because it is a report, not a
--    change, and the upload is what fills the table:
--
-- SELECT u.username, u.full_name, tc.class_name
--   FROM teacher_classes tc
--   JOIN users u ON u.id = tc.user_id
--  ORDER BY u.username, tc.class_name;
