package com.sportday.repository;

import com.sportday.entity.EventResult;
import com.sportday.entity.EventStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventResultRepository extends JpaRepository<EventResult, Long> {

    List<EventResult> findByEventIdOrderByMarkAsc(Long eventId);

    /** Every mark recorded at one stage of an event, best first for a timed event. */
    List<EventResult> findByEventIdAndStageOrderByMarkAsc(Long eventId, EventStage stage);

    List<EventResult> findByUserId(Long userId);

    Optional<EventResult> findByUserIdAndEventId(Long userId, Long eventId);

    /** A mark is unique per athlete, event <em>and stage</em>. */
    Optional<EventResult> findByUserIdAndEventIdAndStage(Long userId, Long eventId, EventStage stage);

    boolean existsByUserIdAndEventId(Long userId, Long eventId);

    long countByEventIdAndStage(Long eventId, EventStage stage);

    /**
     * Adopts marks recorded before the heat/final split. Hibernate adds the
     * {@code stage} column as nullable, so rows that predate it read as NULL and
     * must be claimed as heats or the heat leaderboard would come back empty.
     *
     * <p>Carries its own {@code @Transactional} because the first-run bootstrap
     * calls it outside any transaction.</p>
     */
    @Modifying
    @Transactional
    @Query("update EventResult r set r.stage = :stage where r.stage is null")
    int backfillNullStages(@Param("stage") EventStage stage);

    /**
     * Rewrites stored units to the short form a programme uses — {@code M} for a
     * field event, {@code s} for a track one. Marks recorded before the units were
     * shortened say "metres" or "seconds", and a sheet should read one way.
     */
    @Modifying
    @Transactional
    @Query("update EventResult r set r.unit = :to "
            + "where r.event.category = :category and lower(r.unit) in :from")
    int normaliseUnits(@Param("category") com.sportday.entity.EventCategory category,
                       @Param("from") java.util.Collection<String> from,
                       @Param("to") String to);

    /**
     * Every mark ever recorded in events of one type, division <em>and grade</em>. A
     * school record spans every edition of the event, not just today's, so it is
     * rebuilt from these rather than from a single event. The grade is the event's
     * own — an event belongs to exactly one grade — so the marks that count towards
     * a record are exactly those run in that grade's event.
     */
    @Query("""
           select r from EventResult r
           join fetch r.user u
           join fetch r.event e
           where e.type = :type and e.sex = :sex and e.grade = :grade
           """)
    List<EventResult> findByEventTypeAndSexAndGrade(
            @Param("type") com.sportday.entity.Event.EventType type,
            @Param("sex") com.sportday.entity.Sex sex,
            @Param("grade") com.sportday.entity.Grade grade);

    /** Used when an event is deleted, so its results go with it. */
    @Modifying
    @Query("delete from EventResult r where r.event.id = :eventId")
    int deleteByEventId(Long eventId);

    /** Used when the final is cleared, so only the heat marks remain. */
    @Modifying
    @Query("delete from EventResult r where r.event.id = :eventId and r.stage = :stage")
    int deleteByEventIdAndStage(Long eventId, EventStage stage);

    /** Used when a student account is removed. */
    @Modifying
    @Query("delete from EventResult r where r.user.id = :userId")
    int deleteByUserId(Long userId);
}
