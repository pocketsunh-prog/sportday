package com.sportday.repository;

import com.sportday.entity.RelayTeamMember;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The runners in a relay team — one row per leg or reserve.
 *
 * <p>The service passes these rows to the DTO rather than describing a team from its
 * own {@code members} collection, so a team is never reported from a collection that
 * a write has just left stale.</p>
 */
@Repository
public interface RelayTeamMemberRepository extends JpaRepository<RelayTeamMember, Long> {

    /** A team's runners in leg order. */
    @EntityGraph(attributePaths = "user")
    List<RelayTeamMember> findByTeamIdOrderByLegAsc(Long teamId);

    /** Every runner in every team of an event, in one query, team by team, leg by leg. */
    @EntityGraph(attributePaths = "user")
    @Query("select m from RelayTeamMember m where m.team.event.id = :eventId "
            + "order by m.team.id asc, m.leg asc")
    List<RelayTeamMember> findForEventWithUser(@Param("eventId") Long eventId);

    /**
     * The team of every relay leg of <strong>several</strong> events — one row per
     * runner, so a whole programme's team sizes are counted in one query.
     *
     * <p>Only the team's id is selected: the rule judges a team by how many runners
     * it holds, so the runners themselves and the athlete behind each leg are not
     * read. The same shape as {@link #findForEventWithUser(Long)} otherwise, and the
     * caller counts the rows per team — see {@code RelayReadiness.shortfallsOf}.</p>
     */
    @Query("select m.team.id from RelayTeamMember m where m.team.event.id in :eventIds")
    List<Long> findTeamIdsForEvents(@Param("eventIds") Collection<Long> eventIds);

    /** The row for one athlete in one team, if they are in it. */
    Optional<RelayTeamMember> findByTeamIdAndUserId(Long teamId, Long userId);

    /** How many runners a team has — what the team's size limit is checked against. */
    long countByTeamId(Long teamId);

    /** Whether a leg is already taken, so two athletes cannot share leg 3. */
    boolean existsByTeamIdAndLeg(Long teamId, Integer leg);

    /**
     * Every leg this athlete holds in an event. One athlete may hold only one leg of
     * an event, so this is what stops them being named twice across two teams of the
     * same race — a check the per-team unique keys cannot make, because they cannot
     * see across teams.
     */
    @Query("select m from RelayTeamMember m where m.team.event.id = :eventId and m.user.id = :userId")
    List<RelayTeamMember> findForEventAndUser(@Param("eventId") Long eventId,
                                              @Param("userId") Long userId);

    /** Every relay leg an athlete holds, across every event. */
    @Query("select m from RelayTeamMember m where m.user.id = :userId")
    List<RelayTeamMember> findByUserId(@Param("userId") Long userId);

    void deleteByTeamId(Long teamId);
}
