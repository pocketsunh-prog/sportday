package com.sportday.repository;

import com.sportday.entity.Event;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.StandardDefault;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * The per-grade required-standard defaults.
 *
 * <p>There is deliberately <strong>no</strong> "find the default for this event"
 * query used per event: {@link #findAll()} reads every default in one query — the
 * live programme has 72 qualifying events and at most 13 types &times; 3 grades
 * &times; 2 divisions = 78 possible keys, so the whole table is one small read — and
 * a caller that needs several builds one map from it. That is what keeps the
 * standards page and an "apply now" over the programme from costing a lookup per
 * event.</p>
 */
@Repository
public interface StandardDefaultRepository extends JpaRepository<StandardDefault, Long> {

    /**
     * The default for one key — the type, the grade <em>and</em> the division.
     *
     * <p>The unique constraint on the same three columns means this can never return
     * two rows, which is what makes inheritance a plain map read.</p>
     */
    Optional<StandardDefault> findByTypeAndGradeAndSex(Event.EventType type, Grade grade, Sex sex);

    /** Every default, in a stable order: by type, then grade, then division. */
    List<StandardDefault> findAllByOrderByTypeAscGradeAscSexAsc();
}
