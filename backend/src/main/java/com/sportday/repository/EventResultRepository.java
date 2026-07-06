package com.sportday.repository;

import com.sportday.entity.EventResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventResultRepository extends JpaRepository<EventResult, Long> {
    List<EventResult> findByEventIdOrderByMarkAsc(Long eventId);
    List<EventResult> findByUserId(Long userId);
    Optional<EventResult> findByUserIdAndEventId(Long userId, Long eventId);
    boolean existsByUserIdAndEventId(Long userId, Long eventId);
}
