package com.sportday.repository;

import com.sportday.entity.Enrollment;
import com.sportday.entity.EventCategory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    List<Enrollment> findByUserId(Long userId);

    List<Enrollment> findByEventId(Long eventId);

    Optional<Enrollment> findByUserIdAndEventId(Long userId, Long eventId);

    boolean existsByUserIdAndEventId(Long userId, Long eventId);

    long countByEventIdAndStatus(Long eventId, Enrollment.EnrollmentStatus status);

    List<Enrollment> findByUserIdAndStatus(Long userId, Enrollment.EnrollmentStatus status);

    List<Enrollment> findByEventIdAndStatus(Long eventId, Enrollment.EnrollmentStatus status);

    @EntityGraph(attributePaths = {"event", "eventGroup"})
    @Query("select e from Enrollment e where e.user.id = :userId and e.status = :status order by e.enrolledAt asc")
    List<Enrollment> findMineWithEvent(Long userId, Enrollment.EnrollmentStatus status);

    /**
     * How many events in the given category (徑項 / 田項) the student is
     * currently entered in. This is what enforces the "two track, one field"
     * limit.
     */
    @Query("""
           select count(e) from Enrollment e
           where e.user.id = :userId
             and e.status = :status
             and e.event.category = :category
           """)
    long countByUserAndCategory(Long userId, Enrollment.EnrollmentStatus status, EventCategory category);

    @Query("""
           select e from Enrollment e
           join fetch e.user u
           where e.event.id = :eventId and e.status = :status
           order by u.username asc
           """)
    List<Enrollment> findConfirmedWithUserByEvent(Long eventId, Enrollment.EnrollmentStatus status);

    @Query("""
           select e from Enrollment e
           join fetch e.user u
           where e.eventGroup.id = :groupId
           order by e.lane asc, u.username asc
           """)
    List<Enrollment> findByGroupWithUserOrdered(Long groupId);

    /**
     * The entries of a set of athletes in one event, in a single query.
     *
     * <p>Used for the final, whose field is stored separately from the heat
     * groups: the athletes' event entries are still needed to render their names,
     * classes and houses.</p>
     */
    @Query("""
           select e from Enrollment e
           join fetch e.user u
           where e.event.id = :eventId and u.id in :userIds
           """)
    List<Enrollment> findWithUserByEventAndUserIds(@Param("eventId") Long eventId,
                                                   @Param("userIds") Collection<Long> userIds);

    @Query("select count(e) from Enrollment e where e.event.id = :eventId and e.eventGroup is null and e.status = :status")
    long countUngroupedByEvent(Long eventId, Enrollment.EnrollmentStatus status);

    @Modifying
    @Query("update Enrollment e set e.eventGroup = null, e.lane = null where e.event.id = :eventId")
    int clearGroupAssignments(Long eventId);
}
