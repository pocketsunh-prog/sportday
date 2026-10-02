package com.sportday.repository;

import com.sportday.entity.Season;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SeasonRepository extends JpaRepository<Season, Long> {

    Optional<Season> findByYear(Integer year);

    /** Every year, most recent first — what the season picker offers. */
    List<Season> findAllByOrderByYearDesc();

    /** The season students may enter, if any. */
    Optional<Season> findFirstByEnrollmentOpenTrueOrderByYearDesc();

    Optional<Season> findFirstByOrderByYearDesc();
}
