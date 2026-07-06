package com.sportday.repository;

import com.sportday.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {
    List<Event> findByEnabledTrue();
    List<Event> findByEnabledTrueAndEventDateAfter(LocalDate date);
    List<Event> findByTypeAndEnabledTrue(Event.EventType type);
}
