package com.sportday.repository;

import com.sportday.entity.Event;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByEnabledTrue();

    /**
     * One event of a type, division and grade — the combination that identifies an
     * event. Used to keep the catalogue seeding idempotent.
     */
    Optional<Event> findFirstByTypeAndSexAndGrade(Event.EventType type, Sex sex, Grade grade);

    // ------------------------------------------------------------- by school year

    long countBySeasonId(Long seasonId);

    /**
     * How many <strong>real</strong> events a year holds — the draft relay events
     * excluded, because a draft is not on the programme and must not make a sport day
     * look bigger than it is. Null reads as "not a draft", so every event written
     * before the flag existed is counted.
     *
     * <p>Written out rather than derived, for two reasons. A derived
     * {@code countBySeasonIdAndDraftIsNot} takes <em>two</em> arguments — the season and
     * the flag — and reading it as one is a boot failure the unit tests cannot see,
     * because no test in this suite builds a Spring context. And {@code draft <> true}
     * is null, therefore false, for every row written before the flag existed, which
     * would report a year with events in it as empty.</p>
     */
    @Query("select count(e) from Event e where e.season.id = :seasonId "
            + "and (e.draft is null or e.draft = false)")
    long countRealEventsInSeason(@Param("seasonId") Long seasonId);

    List<Event> findBySeasonIdOrderByTypeAscSexAscGradeAsc(Long seasonId);

    /** Events created before seasons existed, so the bootstrap can adopt them. */
    List<Event> findBySeasonIsNull();
}
