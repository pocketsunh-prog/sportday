'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import {
  api,
  EventDTO,
  isRelayEventType,
  RelayApplicantDTO,
  RelayEventTeamsDTO,
  RelayTeamDTO,
  Role,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';
import { classText, formText, houseText } from '@/lib/students';
import RelayMarkEntry from '@/components/RelayMarkEntry';

/** The message the server sent, or our own wording when there is none. */
function errorText(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback;
}

/** A student is shown as `S0440 · Chan Tai Man · 5D 8 · Red (R)`. */
function studentLabel(student: {
  studentRef?: string | null;
  studentId?: string | null;
  name?: string | null;
  className?: string | null;
  classNumber?: number | null;
  classLabel?: string | null;
  house?: string | null;
  houseCode?: string | null;
}): string {
  const parts = [
    student.studentRef || student.studentId || '',
    student.name || '',
    classText(student),
    houseText(student),
  ].filter(part => part && part !== '-');
  return parts.join(' · ');
}

/**
 * The relay team board for one event: the teams, their runners and their order —
 * **and the relay's marks, one time per team.**
 *
 * A relay is divided into form or house teams on the event itself; this board is
 * where those teams are **derived from the roster**, filled, renamed and ordered
 * — a form relay's first two classes, or one team per house of a grade relay —
 * and where the one time each team runs is keyed in and saved, because a relay is
 * run and scored by *team* and its own page is where its paper is printed from.
 *
 * ## One page, four roles, each offered exactly what the endpoints allow
 *
 * The two halves of this page answer to two different sets of endpoints, and the
 * page reads each half with the role that may use it:
 *
 * <ul>
 *   <li>the <strong>team board</strong> — the teams, their runners, the derive and
 *       the removal of every team — is served by
 *       {@code /api/admin/events/{id}/relay-teams} (ADMIN) and its
 *       {@code /api/teacher/**} twin (ADMIN or TEACHER), so it is read and shown
 *       to an <strong>administrator and a teacher</strong>;</li>
 *   <li>the <strong>mark entry</strong> — one time per team — is served by
 *       {@code GET/POST /api/events/{id}/marks}, which admits
 *       <strong>ADMIN, MANAGER and HELPER</strong> ({@code MarkEntryController}
 *       and the request rules in {@code SecurityConfig}). So it is offered to
 *       exactly those three, and a teacher — who may place runners but may not
 *       key a mark — is told so plainly rather than offered a control whose only
 *       outcome is a 403.</li>
 * </ul>
 *
 * A manager or an input helper therefore reaches this page for the one thing they
 * are for, and the lines they mark come from the endpoint itself: the relay's
 * teams, named as the school names them.
 *
 * ## Filling a team
 *
 * Every team's own card carries the register's students for that team's group —
 * its class, or its house — who are not already running in this event. The list
 * is the server's own pool (`RelayTeamDTO.candidates`), read from the same place
 * a derive reads it, so a runner is added out of the register the team is
 * actually drawn from **and a runner who has just been removed comes straight
 * back into it**. Every refusal the server sends is shown verbatim.
 *
 * A **hand-made** team — built out of chosen students under a typed name — is not
 * one class's and not one house's, so the register offers it nobody: its card
 * carries no add list, and it is drawn with a badge saying it is the school's own
 * team rather than the roster's. Such a team is made through the relay team API
 * (`POST .../teams`), which still supports it; this page no longer carries the
 * tick-list form that used to make one from the students who entered.
 */
export default function AdminEventRelayPage() {
  const params = useParams();
  const router = useRouter();
  const { user, isLoading } = useAuth();
  const { t, label } = useI18n();
  const eventId = Number(params.id);

  const [event, setEvent] = useState<EventDTO | null>(null);
  const [board, setBoard] = useState<RelayEventTeamsDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /** Whether a derivation should also drop empty teams the roster no longer calls for. */
  const [prune, setPrune] = useState(false);
  /** The team name being typed, keyed by team id. */
  const [names, setNames] = useState<Record<number, string>>({});
  /** Which team's name field is open, if any. */
  const [renaming, setRenaming] = useState<number | null>(null);
  /**
   * The running order being edited, keyed by team id. Absent means "the order the
   * server holds"; present means the runner order has not been saved yet.
   */
  const [order, setOrder] = useState<Record<number, number[]>>({});

  const isAdmin = user?.role === 'ADMIN';
  /**
   * Who may read the team board: the two roles its endpoints admit. The board is
   * requested only for them — a manager or a helper asking for it would only be
   * refused, and the lines they are here for come from the mark sheet.
   */
  const canReadBoard = isAdmin || user?.role === 'TEACHER';
  /**
   * Who may key a relay's marks: exactly the roles the mark-entry endpoints admit
   * (`ADMIN`, `MANAGER`, `HELPER`). Nothing is narrowed here and nothing widened.
   */
  const canKeyMarks =
    isAdmin || user?.role === 'MANAGER' || user?.role === 'HELPER';
  /** Whether this reader has anything to do on this page at all. */
  const canWork = canReadBoard || canKeyMarks;
  /** The endpoint family this caller may use: `/teacher/**` for a teacher. */
  const role: Role = isAdmin ? 'ADMIN' : 'TEACHER';

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    if (!canWork) router.push('/');
  }, [user, isLoading, canWork, router]);

  /**
   * The board is only fetched for a relay, and only by a caller the board
   * endpoints admit: everything under it answers a 400 for anything else, so a
   * non-relay event is described instead of being asked about.
   */
  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const eventResult = await api.getEvent(eventId);
      setEvent(eventResult);
      setOrder({});
      setNames({});
      if (isRelayEventType(eventResult.type) && canReadBoard) {
        const boardResult = await api.getRelayTeams(eventId, role);
        setBoard(boardResult);
      } else {
        setBoard(null);
      }
    } catch (err) {
      setError(errorText(err, t('relay.loadFailed')));
    } finally {
      setLoading(false);
    }
  }, [eventId, role, canReadBoard, t]);

  useEffect(() => {
    if (Number.isFinite(eventId) && eventId > 0 && canWork) load();
    // `load` is rebuilt when the language changes; reloading then is harmless and
    // keeps the server's own wording in the right language where it can be.
  }, [eventId, canWork, load]);

  const relay = !!event && isRelayEventType(event.type);
  const teams = useMemo(() => board?.teams ?? [], [board]);

  /* ---------------- the board: derive, remove ---------------- */

  const handleDerive = async () => {
    setBusy('derive');
    setError(null);
    setNotice(null);
    try {
      const result = await api.deriveRelayTeams(eventId, prune, role);
      setBoard(result.board);
      setOrder({});
      setNotice(
        `${t('relay.derived', {
          created: result.created,
          kept: result.kept,
          pruned: result.pruned,
          eligible: result.eligibleStudents,
        })}${
          result.keptWithRunners > 0
            ? t('relay.derivedKeptWithRunners', { count: result.keptWithRunners })
            : ''
        }`
      );
    } catch (err) {
      // A relay that has not been divided yet is refused with a 409 whose message
      // names the way out: show it as it stands.
      setError(errorText(err, t('relay.deriveFailed')));
    } finally {
      setBusy(null);
    }
  };

  const handleRemoveAll = async () => {
    if (!confirm(t('relay.removeAllConfirm'))) return;
    setBusy('remove-all');
    setError(null);
    setNotice(null);
    try {
      const result = await api.removeRelayTeams(eventId);
      setNotice(t('relay.removedAll', { count: result.teamsRemoved }));
      await load();
    } catch (err) {
      setError(errorText(err, t('relay.removeAllFailed')));
    } finally {
      setBusy(null);
    }
  };

  /* ---------------- runners ---------------- */

  const refreshBoard = useCallback(async () => {
    const boardResult = await api.getRelayTeams(eventId, role);
    setBoard(boardResult);
  }, [eventId, role]);

  /**
   * Adds one student to a team, out of the list that team's own card offers — the
   * register's students for its class or house. The candidate is handed in rather
   * than looked up, so the notice can name them without the page holding a second
   * copy of the pool.
   */
  const handleAddOne = async (team: RelayTeamDTO, candidate: RelayApplicantDTO) => {
    const userId = candidate.userId;
    setBusy(`add-${team.id}`);
    setError(null);
    setNotice(null);
    try {
      await api.addRelayRunner(team.id, userId, null, role);
      setNotice(
        t('relay.added', { name: candidate.name || userId, team: team.label || '' })
      );
      setOrder(prev => {
        const next = { ...prev };
        delete next[team.id];
        return next;
      });
      await refreshBoard();
    } catch (err) {
      // An ineligible pick is refused with a 409 naming the athlete and why.
      setError(errorText(err, t('relay.addFailed')));
    } finally {
      setBusy(null);
    }
  };

  const handleRemove = async (team: RelayTeamDTO, userId: number, name: string) => {
    if (!confirm(t('relay.removeConfirm', { name, team: team.label || '' }))) return;
    setBusy(`remove-${team.id}-${userId}`);
    setError(null);
    setNotice(null);
    try {
      await api.removeRelayRunner(team.id, userId, role);
      setNotice(t('relay.removed', { name, team: team.label || '' }));
      setOrder(prev => {
        const next = { ...prev };
        delete next[team.id];
        return next;
      });
      await refreshBoard();
    } catch (err) {
      setError(errorText(err, t('relay.removeFailed')));
    } finally {
      setBusy(null);
    }
  };

  /* ---------------- the team name ---------------- */

  const handleRename = async (team: RelayTeamDTO) => {
    const typed = (names[team.id] ?? '').trim();
    setBusy(`rename-${team.id}`);
    setError(null);
    setNotice(null);
    try {
      const updated = await api.renameRelayTeam(team.id, typed, role);
      setNotice(t('relay.renamed', { team: updated.label || typed }));
      setRenaming(null);
      await refreshBoard();
    } catch (err) {
      // A blank name, a name over 40 characters and a name another team of this
      // event already holds are each refused with their own wording.
      setError(errorText(err, t('relay.renameFailed')));
    } finally {
      setBusy(null);
    }
  };

  /* ---------------- the running order ---------------- */

  /** The order on screen for a team: the edited one, or the server's. */
  const orderFor = (team: RelayTeamDTO): number[] => {
    const edited = order[team.id];
    if (edited) return edited;
    return (team.members ?? [])
      .map(member => member.userId)
      .filter((id): id is number => id !== undefined);
  };

  const move = (team: RelayTeamDTO, index: number, delta: number) => {
    const current = [...orderFor(team)];
    const target = index + delta;
    if (target < 0 || target >= current.length) return;
    const swap = current[index];
    current[index] = current[target];
    current[target] = swap;
    setOrder(prev => ({ ...prev, [team.id]: current }));
    setNotice(null);
  };

  const handleSaveOrder = async (team: RelayTeamDTO) => {
    setBusy(`order-${team.id}`);
    setError(null);
    setNotice(null);
    try {
      await api.setRelayLegs(team.id, orderFor(team), role);
      setNotice(t('relay.orderSaved', { team: team.label || '' }));
      setOrder(prev => {
        const next = { ...prev };
        delete next[team.id];
        return next;
      });
      await refreshBoard();
    } catch (err) {
      setError(errorText(err, t('relay.orderFailed')));
    } finally {
      setBusy(null);
    }
  };

  /* ---------------- rendering ---------------- */

  if (isLoading || !user || !canWork) {
    return <div>{t('common.loading')}</div>;
  }

  if (loading) {
    return <div>{t('relay.loading')}</div>;
  }

  if (!event || !relay) {
    return (
      <div>
        {error && <div className="alert alert-error">{error}</div>}
        <div className="card text-center">
          <p>{t('relay.notRelay')}</p>
          {user?.role === 'TEACHER' ? (
            <Link href="/teacher" className="btn btn-secondary mt-2">
              {t('relay.backToTeacher')}
            </Link>
          ) : (
            <Link href={`/admin/events/${eventId}/groups`} className="btn btn-secondary mt-2">
              {t('relay.backToGroups')}
            </Link>
          )}
        </div>
      </div>
    );
  }

  const undivided = board !== null && !board.relayTeamKind;
  /** A team short of its legs — what the board has to warn about, never hide. */
  const shortTeams = teams.filter(team => !team.complete);

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('relay.boardTitle')}</h1>
        <div className="flex gap-2">
          {user?.role === 'TEACHER' ? (
            <Link href="/teacher" className="btn btn-secondary">
              {t('relay.backToTeacher')}
            </Link>
          ) : (
            /*
             * Back to the relay programme, not to the heats page. A relay is
             * divided into TEAMS — a form relay's first two classes, or one per
             * grade and house — so there is nothing to shuffle and no heats to
             * allocate: its lines on the sheet and its rows in the mark grid are
             * teams, not athletes. Sending the reader to the heat page from here
             * only offered a control that does nothing for a relay.
             */
            <Link href="/admin/relay-events/form" className="btn btn-secondary">
              {t('relay.backToRelays')}
            </Link>
          )}
          {isAdmin && (
            <Link href="/admin/events" className="btn btn-secondary">
              {t('adminEvents.allEvents')}
            </Link>
          )}
        </div>
      </div>
      <p className="muted">{t('relay.subtitle')}</p>

      {error && <div className="alert alert-error">{error}</div>}
      {notice && <div className="alert alert-success">{notice}</div>}

      <div className="card">
        <h2>{event.name}</h2>
        <div className="flex gap-2 mt-2">
          <span className="badge badge-info">{event.typeLabel}</span>
          <span className="badge badge-info" title={label('grade', event.grade)}>
            {label('grade.short', event.grade)}
          </span>
          <span className="badge badge-info">{label('sex', event.sex)}</span>
          {board?.relayTeamKind && (
            <span className="badge badge-success">
              {board.relayTeamKind === 'FORM' ? t('relay.kindForm') : t('relay.kindHouse')}
            </span>
          )}
          {undivided && <span className="badge badge-warning">{t('relay.kindUndivided')}</span>}
        </div>

        <div className="event-meta mt-3">
          <div>
            <strong>{t('relay.legsPerTeam')}:</strong> {board?.legsPerTeam ?? event.relayTeamSize ?? '-'}
          </div>
          <div>
            <strong>{t('relay.reservesAllowed')}:</strong>{' '}
            {board?.reservesAllowed ?? event.relayReservesAllowed
              ? t('admin.active')
              : t('common.none')}
          </div>
          <div>
            <strong>{t('relay.runners')}:</strong> {board?.runnerCount ?? 0} / {board?.memberCap ?? '-'}
          </div>
          <div>
            <strong>{t('relay.kind')}:</strong>{' '}
            {board?.relayTeamKind
              ? board.relayTeamKind === 'FORM'
                ? t('relay.kindForm')
                : t('relay.kindHouse')
              : t('relay.kindUndivided')}
          </div>
        </div>

        {/*
          The **derive** control: it makes a FORM or HOUSE relay's class or house
          teams out of the roster itself — a form relay's first two classes, or one
          per house — and is the way this board's teams come into being. Each one is
          then filled from its own card below. It belongs to the two roles the
          relay-team endpoints admit, so a manager or a helper is not shown it at
          all rather than shown a control that would only be refused.
        */}
        {canReadBoard && (
          <div className="mt-3">
            <h4>{t('relay.deriveTitle')}</h4>
            <div className="pill-actions mt-2">
              <button
                type="button"
                className="btn btn-sm btn-secondary"
                disabled={busy === 'derive'}
                onClick={handleDerive}
              >
                {busy === 'derive' ? t('relay.deriving') : t('relay.derive')}
              </button>
              {isAdmin && teams.length > 0 && (
                <button
                  type="button"
                  className="btn btn-sm btn-danger"
                  disabled={busy === 'remove-all'}
                  onClick={handleRemoveAll}
                >
                  {busy === 'remove-all' ? t('common.processing') : t('relay.removeAll')}
                </button>
              )}
            </div>
            <p className="muted mt-2">{t('relay.deriveExplanation')}</p>
            <label className="checkbox-line mt-2">
              <input type="checkbox" checked={prune} onChange={e => setPrune(e.target.checked)} />
              {t('relay.derivePrune')}
            </label>
          </div>
        )}
      </div>

      {/*
        **The relay's marks, where the relay is.** One line per team, one time
        written for the four runners together — the grid itself is
        `RelayMarkEntry`, which reads and saves through `GET`/`POST
        /api/events/{id}/marks`, the endpoint a relay's sheet has always answered
        on. It is here, on the event's own board, and nowhere else: it is offered
        once, beside the teams it marks, rather than on each card of the relay
        list as well.

        The roles are the endpoint's own: ADMIN, MANAGER and HELPER may key a
        relay's time, and a teacher — who may place the runners but may not record
        the mark — is told so plainly.
      */}
      {canKeyMarks ? (
        <RelayMarkEntry
          eventId={eventId}
          onSaved={canReadBoard ? refreshBoard : undefined}
        />
      ) : (
        <div className="card">
          <h2>{t('relayMark.title')}</h2>
          <p className="muted mt-2">{t('relayMark.teacherLimit')}</p>
        </div>
      )}

      {/*
        What a manager or an input helper cannot see, said rather than left blank:
        the teams and their runners are read by the relay-team endpoints, which
        admit an administrator and a teacher only. The lines they mark come from
        the mark sheet above, so nothing they are here for is missing.
      */}
      {!canReadBoard && (
        <div className="card">
          <h2>{t('relay.teamsTitle')}</h2>
          <p className="muted mt-2">{t('relayMark.boardNotYours')}</p>
        </div>
      )}

      {canReadBoard && undivided && (
        <div className="card">
          <h2>{t('relay.undividedTitle')}</h2>
          <p className="muted mt-2">{t('relay.undividedHint')}</p>
          {isAdmin && (
            <div className="pill-actions mt-3">
              <Link href={`/admin/events/${eventId}/edit`} className="btn btn-sm btn-secondary">
                {t('relay.setKind')}
              </Link>
            </div>
          )}
          {!isAdmin && <p className="muted mt-2">{t('relay.undividedTeacherHint')}</p>}
        </div>
      )}

      {/* ---------------- the teams ---------------- */}

      {canReadBoard && (
      <div className="card">
        <div className="flex justify-between items-center">
          <h2>{t('relay.teamsTitle')}</h2>
          <div className="pill-actions">
            <span className="badge badge-info">{t('relay.teamCount', { count: teams.length })}</span>
            {shortTeams.length > 0 ? (
              <span className="badge badge-warning">
                {t('relay.shortTeams', { count: shortTeams.length })}
              </span>
            ) : (
              teams.length > 0 && <span className="badge badge-success">{t('relay.allComplete')}</span>
            )}
          </div>
        </div>
        {shortTeams.length > 0 && (
          <div className="alert alert-error mt-2">
            {t('relay.shortTeamsWarn', {
              count: shortTeams.length,
              names: shortTeams.map(team => team.label || team.id).join(', '),
            })}
          </div>
        )}

        {!undivided && teams.length === 0 && (
          <p className="muted mt-2">{t('relay.noTeams')}</p>
        )}
        {undivided && <p className="muted mt-2">{t('relay.undividedTeamsHint')}</p>}
      </div>
      )}

      {canReadBoard && teams.map(team => {
        const members = team.members ?? [];
        const orderIds = orderFor(team);
        const edited = !!order[team.id];
        const legs = team.legCount ?? board?.legsPerTeam ?? 0;
        const filled = members.filter(member => !member.reserve).length;
        const reserves = members.length - filled;
        return (
          <div key={team.id} className="card">
            <div className="flex justify-between items-center">
              <div className="flex gap-2 items-center">
                {/* The team's name is what the marking sheet and the mark grid are
                    keyed on, so it is the heading, not a detail. */}
                <h3>{team.label || t('relay.unnamed')}</h3>
                <span className="badge badge-info">
                  {t('relay.legsFilled', { filled, legs })}
                </span>
                {reserves > 0 && (
                  <span className="badge badge-info">
                    {t('relay.reserveCount', { count: reserves })}
                  </span>
                )}
                <span className={team.complete ? 'badge badge-success' : 'badge badge-warning'}>
                  {team.complete
                    ? t('relay.complete')
                    : t('relay.shortOfLegs', { missing: Math.max(legs - filled, 0) })}
                </span>
                {/*
                  The marker that says this team is not the roster's. A hand-made
                  team is no class's and no house's, so no later derive will ever
                  match, rename or prune it — which a teacher re-deriving a FORM or
                  HOUSE relay needs to be able to see at a glance.
                */}
                {team.handMade ? (
                  <span className="badge badge-success">{t('relay.handMade')}</span>
                ) : (
                  team.kind && (
                    <span className="badge badge-info">
                      {team.kind === 'HOUSE' ? t('relay.derivedHouse') : t('relay.derivedForm')}
                    </span>
                  )
                )}
                {team.nameOverridden && (
                  <span className="badge badge-info">{t('relay.named')}</span>
                )}
              </div>
              <div className="pill-actions">
                <button
                  type="button"
                  className="btn btn-sm btn-secondary"
                  onClick={() => {
                    setRenaming(renaming === team.id ? null : team.id);
                    setNames(prev => ({ ...prev, [team.id]: prev[team.id] ?? team.label ?? '' }));
                  }}
                >
                  {t('relay.rename')}
                </button>
              </div>
            </div>

            {!team.complete && (
              <p className="muted mt-2">
                {t('relay.incompleteWarn', { legs, filled })}
              </p>
            )}

            {team.handMade && <p className="muted mt-2">{t('relay.handMadeHint')}</p>}

            {renaming === team.id && (
              <div className="toolbar mt-2">
                <div className="form-group" style={{ minWidth: '20rem' }}>
                  <label htmlFor={`relay-name-${team.id}`}>{t('relay.teamName')}</label>
                  <input
                    id={`relay-name-${team.id}`}
                    type="text"
                    maxLength={40}
                    value={names[team.id] ?? team.label ?? ''}
                    onChange={e =>
                      setNames(prev => ({ ...prev, [team.id]: e.target.value }))
                    }
                  />
                </div>
                <button
                  type="button"
                  className="btn btn-sm btn-primary"
                  disabled={busy === `rename-${team.id}`}
                  onClick={() => handleRename(team)}
                >
                  {busy === `rename-${team.id}` ? t('common.saving') : t('relay.saveName')}
                </button>
                <button
                  type="button"
                  className="btn btn-sm btn-secondary"
                  onClick={() => setRenaming(null)}
                >
                  {t('common.cancel')}
                </button>
              </div>
            )}
            {renaming === team.id && <p className="muted mt-2">{t('relay.renameHint')}</p>}

            {members.length === 0 ? (
              <p className="muted mt-2">{t('relay.noRunners')}</p>
            ) : (
              <div className="table-wrap mt-2">
                <table>
                  <thead>
                    <tr>
                      <th>{t('relay.leg')}</th>
                      <th>{t('students.colId')}</th>
                      <th>{t('students.colName')}</th>
                      <th>{t('relay.form')}</th>
                      <th>{t('marks.class')}</th>
                      <th>{t('students.colHouse')}</th>
                      <th>{t('common.actions')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {members.map(member => {
                      const position = orderIds.indexOf(member.userId ?? -1);
                      const name = member.name || member.studentId || String(member.userId ?? '');
                      return (
                        <tr key={member.id}>
                          <td>
                            {member.leg}
                            {member.reserve && (
                              <span className="badge badge-warning" style={{ marginLeft: '0.4rem' }}>
                                {t('relay.reserve')}
                              </span>
                            )}
                          </td>
                          <td>{member.studentId || '-'}</td>
                          <td>{name}</td>
                          <td>{formText(member, t)}</td>
                          <td>{classText(member)}</td>
                          <td>{houseText(member)}</td>
                          <td>
                            <div className="pill-actions">
                              <button
                                type="button"
                                className="btn btn-sm btn-secondary"
                                disabled={position <= 0}
                                onClick={() => move(team, position, -1)}
                              >
                                {t('relay.moveUp')}
                              </button>
                              <button
                                type="button"
                                className="btn btn-sm btn-secondary"
                                disabled={position < 0 || position >= orderIds.length - 1}
                                onClick={() => move(team, position, 1)}
                              >
                                {t('relay.moveDown')}
                              </button>
                              <button
                                type="button"
                                className="btn btn-sm btn-danger"
                                disabled={
                                  member.userId === undefined ||
                                  busy === `remove-${team.id}-${member.userId}`
                                }
                                onClick={() =>
                                  member.userId !== undefined &&
                                  handleRemove(team, member.userId, name)
                                }
                              >
                                {t('relay.remove')}
                              </button>
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}

            {edited && (
              <div className="pill-actions mt-2">
                <span className="badge badge-warning">{t('relay.orderUnsaved')}</span>
                <button
                  type="button"
                  className="btn btn-sm btn-primary"
                  disabled={busy === `order-${team.id}`}
                  onClick={() => handleSaveOrder(team)}
                >
                  {busy === `order-${team.id}` ? t('relay.savingOrder') : t('relay.saveOrder')}
                </button>
              </div>
            )}

            <p className="muted mt-2">{t('relay.orderHint')}</p>

            {/* Adding one student to this team by hand, out of the register the
                team's own group offers — its class on a form relay, its house on a
                house one — with everyone already running in this event left out.

                The server decides that pool (`team.candidates`), so the list is the
                same one a re-derive would place and a runner who has just been
                **removed** from a team comes back here, which is what the remove
                button is for. A team made by hand is no class's and no house's and
                has no such pool at all, so it is drawn without this table, and the
                note under its badge says it is not the roster's. */}
            {!team.handMade && (() => {
              const addable = team.candidates ?? [];
              if (addable.length === 0) {
                return (
                  <div className="mt-3">
                    <h4>{t('relay.addRunner')}</h4>
                    <p className="muted">{t('relay.addRunnerHint')}</p>
                    <p className="muted">{t('relay.noCandidates')}</p>
                  </div>
                );
              }
              return (
                <div className="mt-3">
                  <h4>{t('relay.addRunner')}</h4>
                  <p className="muted">{t('relay.addRunnerHint')}</p>
                  <div className="table-wrap mt-2">
                    <table>
                      <thead>
                        <tr>
                          <th>{t('students.colName')}</th>
                          <th>{t('relay.form')}</th>
                          <th>{t('marks.class')}</th>
                          <th>{t('students.colHouse')}</th>
                          <th>{t('common.actions')}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {addable.map(candidate => {
                          return (
                            <tr key={candidate.userId}>
                              <td>{studentLabel(candidate)}</td>
                              <td>{formText(candidate, t)}</td>
                              <td>{classText(candidate)}</td>
                              <td>{houseText(candidate)}</td>
                              <td>
                                <button
                                  type="button"
                                  className="btn btn-sm btn-success"
                                  disabled={busy === `add-${team.id}`}
                                  onClick={() => handleAddOne(team, candidate)}
                                >
                                  {busy === `add-${team.id}` ? t('relay.adding') : t('relay.add')}
                                </button>
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                </div>
              );
            })()}
          </div>
        );
      })}
    </div>
  );
}
