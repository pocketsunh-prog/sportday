-- ---------------------------------------------------------------------------
-- Grade eligibility: which grades may enter which events
--
-- Some events are not for everybody. A C grade student — fourteen or under — does
-- not run the 1500M, and only the oldest grade runs the 5000M. Everything else, the
-- sprints, the 800M, the hurdles, the relays and every field event, is open to all
-- three grades.
--
-- Keyed by event TYPE, not by event: Boys 1500M and Girls 1500M are the same race in
-- two divisions, so the school sets the rule once.
--
-- A MISSING ROW MEANS ALLOWED. The application seeds the defaults on start, so this
-- script is only needed where ddl-auto is validate, or to apply the schema ahead of
-- a deploy. It is idempotent.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS event_grade_rules (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    event_type  VARCHAR(40) NOT NULL,
    grade       VARCHAR(4)  NOT NULL,
    allowed     BIT(1)      NOT NULL DEFAULT b'1',
    PRIMARY KEY (id),
    UNIQUE KEY uk_grade_rule_type_grade (event_type, grade)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Every event type is open to every grade to begin with. The event's type column
-- is `type` on `events` and `event_type` here, which is worth watching.
INSERT IGNORE INTO event_grade_rules (event_type, grade, allowed)
SELECT DISTINCT e.type, g.grade, b'1'
  FROM events e
  JOIN (SELECT 'A' AS grade UNION SELECT 'B' UNION SELECT 'C') g
 WHERE e.type <> 'OTHER';

-- ...except the long distances, which the younger grades do not run.
UPDATE event_grade_rules SET allowed = b'0'
 WHERE event_type = 'RUN_1500M' AND grade = 'C';

UPDATE event_grade_rules SET allowed = b'0'
 WHERE event_type = 'RUN_5000M' AND grade IN ('B', 'C');
