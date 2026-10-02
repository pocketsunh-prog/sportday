package com.sportday.repository;

import com.sportday.entity.Event;
import com.sportday.entity.EventCategory;
import com.sportday.entity.Sex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByEnabledTrue();

    List<Event> findByEnabledTrueAndEventDateAfter(LocalDate date);

    List<Event> findByTypeAndEnabledTrue(Event.EventType type);

    List<Event> findByEnabledTrueAndSex(Sex sex);

    List<Event> findByEnabledTrueAndCategory(Sex sex, EventCategory category);

    List<Event> findByEnabledTrueAndCategoryOrderByTypeAsc(EventCategory category);

    List<Event> findAllByOrderByCategoryAscTypeAscSexAsc();

    Optional<Event> findFirstByTypeAndSex(Event.EventType type, Sex sex);

    Optional<Event> findFirstByTypeAndSexAndEventDate(Event.EventType type, Sex sex, LocalDate eventDate);

    long countByEnabledTrue();

    // ------------------------------------------------------------- by school year

    long countBySeasonId(Long seasonId);

    List<Event> findBySeasonIdOrderByTypeAscSexAsc(Long seasonId);

    /** Events created before seasons existed, so the bootstrap can adopt them. */
    List<Event> findBySeasonIsNull();

    /** The first event of its type and division in a given year. */
    Optional<Event> findFirstByTypeAndSexAndSeasonId(Event.EventType type, Sex sex, Long seasonId);
}
