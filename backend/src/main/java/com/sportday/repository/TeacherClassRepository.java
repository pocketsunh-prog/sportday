package com.sportday.repository;

import com.sportday.entity.TeacherClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface TeacherClassRepository extends JpaRepository<TeacherClass, Long> {

    /** The classes assigned to one teacher. */
    List<TeacherClass> findByUserId(Long userId);

    /**
     * The class names assigned to one teacher, in a stable, sorted order.
     *
     * <p>The sort is stated in the query itself rather than left to the database's
     * own ordering, because callers compare and print these lists; the method name
     * says so as well, so a caller cannot miss the guarantee. The explicit
     * {@code @Query} is kept instead of a derived {@code OrderBy} query: the return
     * type is a single {@code String} column and the explicit JPQL projection is
     * what makes that unambiguous.</p>
     */
    @Query("select t.className from TeacherClass t where t.user.id = :userId order by t.className asc")
    List<String> findClassNamesByUserIdOrderByClassNameAsc(Long userId);

    /** Every class assignment held by any teacher — for the "who teaches 1A?" view. */
    @Query("select t from TeacherClass t where t.className in :classNames")
    List<TeacherClass> findByClassNameIn(Collection<String> classNames);

    /** Whether this teacher may help this class at all. */
    boolean existsByUserIdAndClassName(Long userId, String className);

    /** Used when replacing a teacher's assignment list on re-upload. */
    void deleteByUserId(Long userId);
}
