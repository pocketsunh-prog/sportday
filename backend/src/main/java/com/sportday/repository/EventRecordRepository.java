package com.sportday.repository;

import com.sportday.entity.Event;
import com.sportday.entity.EventRecord;
import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventRecordRepository extends JpaRepository<EventRecord, Long> {

    Optional<EventRecord> findByEventTypeAndSexAndGrade(Event.EventType eventType, Sex sex, Grade grade);

    /** Every record, for the records page. */
    @Query("""
           select r from EventRecord r
           left join fetch r.holder
           left join fetch r.event
           order by r.eventType asc, r.sex asc, r.grade asc
           """)
    List<EventRecord> findAllWithHolder();

    @Query("select r.result.id from EventRecord r where r.result is not null")
    List<Long> findResultIds();

    /**
     * Lets go of the results and the event that are about to be deleted.
     *
     * <p>A record is not owned by one event — it spans every edition of that event
     * — so the reference is cleared rather than the row removed, and the record is
     * rebuilt from whatever is left. Clearing rather than deleting also keeps a
     * baseline an administrator typed in.</p>
     */
    @Modifying
    @Transactional
    @Query("update EventRecord r set r.result = null, r.event = null where r.event.id = :eventId")
    int detachResultsForEvent(Long eventId);

    /** As {@link #detachResultsForEvent}, for a single result. */
    @Modifying
    @Transactional
    @Query("update EventRecord r set r.result = null where r.result.id = :resultId")
    int detachResult(Long resultId);

    /**
     * Lets go of every result at once, ready for a season reset. The baselines are
     * untouched, so a record an administrator entered survives the reset.
     */
    @Modifying
    @Transactional
    @Query("update EventRecord r set r.result = null, r.event = null "
            + "where r.result is not null or r.event is not null")
    int detachAllResults();
}
