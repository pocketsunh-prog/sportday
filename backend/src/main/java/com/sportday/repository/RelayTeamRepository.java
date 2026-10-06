package com.sportday.repository;

import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Relay teams, one row per form or per house of a relay event.
 *
 * <p>The runners are read from {@link RelayTeamMemberRepository} rather than fetched
 * through the team's collection: the board wants every member of every team in one
 * query, and the service passes the rows in so a team is never described from a
 * collection that a write has just left stale.</p>
 */
@Repository
public interface RelayTeamRepository extends JpaRepository<RelayTeam, Long> {

    List<RelayTeam> findByEventIdOrderByIdAsc(Long eventId);

    /**
     * The teams of <strong>several</strong> events at once, in the same order
     * {@link #findByEventIdOrderByIdAsc(Long)} gives one event — team id ascending, so
     * grouping the result by event reproduces exactly what the single-event lookup
     * returns.
     *
     * <p>It exists for the event <em>list</em>: a programme is over a hundred events
     * with a handful of relays, and asking each relay on its own would be one query
     * per relay. Pooled here, the whole list's team sizes cost a single query
     * however many relays are on it, and an event that is not a relay is never in
     * the list at all.</p>
     *
     * <p>The caller passes at least one id: an empty {@code IN ()} is refused by the
     * database, so a list with no relay in it must not reach this method.</p>
     */
    @Query("select t from RelayTeam t where t.event.id in :eventIds order by t.id asc")
    List<RelayTeam> findForEvents(@Param("eventIds") Collection<Long> eventIds);

    /** How many teams an event has — the check before its relay kind is changed. */
    long countByEventId(Long eventId);

    /** The one team of a form or a house: what makes deriving the teams idempotent. */
    Optional<RelayTeam> findFirstByEventIdAndKindAndTeamKey(
            Long eventId, RelayTeamKind kind, String teamKey);

    void deleteByEventId(Long eventId);
}
