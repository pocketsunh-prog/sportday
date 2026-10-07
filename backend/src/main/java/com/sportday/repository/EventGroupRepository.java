package com.sportday.repository;

import com.sportday.entity.EventGroup;
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
public interface EventGroupRepository extends JpaRepository<EventGroup, Long> {

    List<EventGroup> findByEventIdOrderByGroupNumberAsc(Long eventId);

    /** The final of an event, if one has been drawn. */
    Optional<EventGroup> findFirstByEventIdAndStage(Long eventId, EventStage stage);

    long countByEventId(Long eventId);

    long countByEventIdAndStage(Long eventId, EventStage stage);

    /**
     * Adopts groups created before the heat/final split. Hibernate adds the
     * {@code stage} column as nullable, so rows that predate it read as NULL and
     * must be claimed as heats or they would disappear from the heat listings.
     *
     * <p>Carries its own {@code @Transactional} because the first-run bootstrap
     * calls it outside any transaction.</p>
     */
    @Modifying
    @Transactional
    @Query("update EventGroup g set g.stage = :stage where g.stage is null")
    int backfillNullStages(@Param("stage") EventStage stage);

    void deleteByEventId(Long eventId);
}
