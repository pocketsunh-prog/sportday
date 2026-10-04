package com.sportday.repository;

import com.sportday.entity.Grade;
import com.sportday.entity.Sex;
import com.sportday.entity.Student;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByStudentId(String studentId);

    Optional<Student> findByUserId(Long userId);

    boolean existsByStudentId(String studentId);

    /** Students plus their login account, in student-id order. */
    @EntityGraph(attributePaths = "user")
    @Query("select s from Student s order by s.studentId asc")
    List<Student> findAllWithUser();

    @EntityGraph(attributePaths = "user")
    @Query("select s from Student s where s.className = :className order by s.classNumber asc")
    List<Student> findWithUserByClassName(String className);

    @EntityGraph(attributePaths = "user")
    @Query("select s from Student s where s.sex = :sex order by s.studentId asc")
    List<Student> findWithUserBySex(Sex sex);

    @EntityGraph(attributePaths = "user")
    @Query("select s from Student s where s.user.id = :userId")
    Optional<Student> findWithUserByUserId(Long userId);

    /** Roster lookup for a whole heat's worth of entries in one query. */
    @Query("select s from Student s join fetch s.user u where u.id in :userIds")
    List<Student> findWithUserByUserIdIn(@Param("userIds") Collection<Long> userIds);

    /**
     * The students who may run in an event: the event's own division and grade, and
     * on this year's list. This is what a relay team is derived from — one team per
     * form, or one per house, among exactly these students — and the same filter the
     * entry rules apply when they judge an athlete eligible for the event.
     *
     * <p>A locked student is left out: they are not on this year's list and may not
     * be entered, so a team derived only for them would be a team nobody could fill.</p>
     */
    @EntityGraph(attributePaths = "user")
    @Query("select s from Student s where s.sex = :sex and s.grade = :grade and s.enabled = true "
            + "order by s.className asc, s.classNumber asc, s.studentId asc")
    List<Student> findActiveBySexAndGrade(@Param("sex") Sex sex, @Param("grade") Grade grade);

    long countByGrade(Grade grade);

    long countBySex(Sex sex);

    long countByHouse(String house);

    @Query("select distinct s.className from Student s order by s.className asc")
    List<String> findDistinctClassNames();

    @Query("select distinct s.house from Student s order by s.house asc")
    List<String> findDistinctHouses();

    List<Student> findByImportBatch(String importBatch);

    /**
     * The import batches in recency order, so the most recent upload can be
     * identified — students not in it are the ones a roster lock targets.
     */
    @Query("select s.importBatch from Student s "
            + "where s.importBatch is not null and s.updatedAt is not null "
            + "order by s.updatedAt desc")
    List<String> findBatchesByRecency();
}
