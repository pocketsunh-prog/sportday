package com.sportday.repository;

import com.sportday.entity.RelayTeam;
import com.sportday.entity.RelayTeamKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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

    /** How many teams an event has — the check before its relay kind is changed. */
    long countByEventId(Long eventId);

    /** The one team of a form or a house: what makes deriving the teams idempotent. */
    Optional<RelayTeam> findFirstByEventIdAndKindAndTeamKey(
            Long eventId, RelayTeamKind kind, String teamKey);

    void deleteByEventId(Long eventId);
}
