package com.sportday.repository;

import com.sportday.entity.Event;
import com.sportday.entity.EventGradeRule;
import com.sportday.entity.Grade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventGradeRuleRepository extends JpaRepository<EventGradeRule, Long> {

    Optional<EventGradeRule> findByEventTypeAndGrade(Event.EventType eventType, Grade grade);

    List<EventGradeRule> findByEventType(Event.EventType eventType);

    List<EventGradeRule> findByEventTypeAndAllowedTrue(Event.EventType eventType);
}
