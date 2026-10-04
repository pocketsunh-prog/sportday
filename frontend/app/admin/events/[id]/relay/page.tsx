'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import {
  api,
  EventDTO,
  isRelayEventType,
  RelayEventTeamsDTO,
  RelayTeamDTO,
  StudentDTO,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';

/** The message the server sent, or our own wording when there is none. */
function errorText(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback;
}

/**
 * The form a class belongs to: the leading run of digits of the class name, so
 * `1A`, `1B` and `1C` are all Form 1, and `10B` is Form 10 rather than Form 1.
 * Leading zeros are dropped, so `01A` is Form 1 as well.
 *
 * This is the frontend's copy of the server's `RelayTeamService.formKeyOf`, and
 * it is used only to narrow the *candidate list*: the server is still the one
 * that decides, and refuses an ineligible pick with a message worth showing.
 */
function formKeyOf(className: string | undefined | null): string | null {
  if (!className) return null;
  const trimmed = className.trim();
  let end = 0;
  while (end < trimmed.length && trimmed[end] >= '0' && trimmed[end] <= '9') end += 1;
  if (end === 0) return null;
  const digits = trimmed.slice(0, end);
  let first = 0;
  while (first < digits.length - 1 && digits[first] === '0') first += 1;
  return digits.slice(first);
}

/** A student is shown as `S0440 · Chan Tai Man · 5D 8`. */
function studentLabel(student: StudentDTO): string {
  const klass = student.classLabel || `${student.className} ${student.classNumber}`.trim();
  return `${student.studentId} · ${student.name}${klass ? ` · ${klass}` : ''}`;
}

/**
 * The relay team board for one event.
 *
 * A relay is divided into form or house teams on the event itself; this board is
 * where those teams are derived from the roster, filled and ordered. An event
 * with no kind is **undivided** — a normal state rather than a fault, so it is
 * explained rather than shown as an empty board.
 *
 * The eligibility rules live on the server and are not restated here: the picker
 * only narrows the list to plausible candidates (the event's division and grade,
 * and the team's own form or house), and an ineligible pick is refused with the
 * server's own wording, which is shown as it stands.
 */
export default function AdminEventRelayPage() {
  const params = useParams();
  const router = useRouter();
  const { user, isLoading } = useAuth();
  const { t, label } = useI18n();
  const eventId = Number(params.id);

  const [event, setEvent] = useState<EventDTO | null>(null);
  const [board, setBoard] = useState<RelayEventTeamsDTO | null>(null);
  const [roster, setRoster] = useState<StudentDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /** Whether a derivation should also drop empty teams the roster no longer calls for. */
  const [prune, setPrune] = useState(false);
  /** The student picked for a team's next free leg, keyed by team id. */
  const [picked, setPicked] = useState<Record<number, string>>({});
  /**
   * The running order being edited, keyed by team id. Absent means "the order the
   * server holds"; present means the administrator has moved somebody and the
   * order has not been saved yet.
   */
  const [order, setOrder] = useState<Record<number, number[]>>({});

  const isAdmin = user?.role === 'ADMIN';

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    if (!isAdmin) router.push('/');
  }, [user, isLoading, isAdmin, router]);

  const loadRoster = useCallback(async (target: EventDTO) => {
    if (!target.grade || !target.sex) return;
    const sex = target.sex === 'MALE' ? 'M' : 'F';
    const list = await api
      .getStudents({ grade: target.grade, sex, enabled: true })
      .catch(() => [] as StudentDTO[]);
    setRoster(list);
  }, []);

  /**
   * The board is only fetched for a relay: everything under it answers a 400 for
   * anything else, so a non-relay event is described instead of being asked
   * about.
   */
  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const eventResult = await api.getEvent(eventId);
      setEvent(eventResult);
      setOrder({});
      setPicked({});
      if (isRelayEventType(eventResult.type)) {
        const boardResult = await api.getRelayTeams(eventId);
        setBoard(boardResult);
        await loadRoster(eventResult);
      } else {
        setBoard(null);
      }
    } catch (err) {
      setError(errorText(err, t('relay.loadFailed')));
    } finally {
      setLoading(false);
    }
  }, [eventId, loadRoster, t]);

  useEffect(() => {
    if (Number.isFinite(eventId) && eventId > 0 && isAdmin) load();
    // `load` is rebuilt when the language changes; reloading then is harmless and
    // keeps the server's own wording in the right language where it can be.
  }, [eventId, isAdmin, load]);

  const relay = !!event && isRelayEventType(event.type);
  const teams = board?.teams ?? [];

  /** Every athlete already running in this event, across every team of it. */
  const runningUserIds = useMemo(() => {
    const ids = new Set<number>();
    teams.forEach(team => {
      (team.members ?? []).forEach(member => {
        if (member.userId !== undefined && member.userId !== null) ids.add(member.userId);
      });
    });
    return ids;
  }, [teams]);

  /**
   * The students who could plausibly run for one team: the event's division and
   * grade (already narrowed by the roster request), the team's own form or
   * house, and nobody who already holds a leg of this event.
   */
  const candidatesFor = useCallback(
    (team: RelayTeamDTO): StudentDTO[] => {
      return roster.filter(student => {
        if (runningUserIds.has(student.userId)) return false;
        if (team.kind === 'HOUSE') {
          const house = (student.house ?? '').trim();
          return house !== '' && house.toLowerCase() === (team.teamKey ?? '').trim().toLowerCase();
        }
        const form = formKeyOf(student.className);
        return form !== null && form === (team.teamKey ?? '').trim();
      });
    },
    [roster, runningUserIds]
  );

  /* ---------------- deriving and removing ---------------- */

  const handleDerive = async () => {
    setBusy('derive');
    setError(null);
    setNotice(null);
    try {
      const result = await api.deriveRelayTeams(eventId, prune);
      setBoard(result.board);
      setOrder({});
      setPicked({});
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
    const boardResult = await api.getRelayTeams(eventId);
    setBoard(boardResult);
  }, [eventId]);

  const handleAdd = async (team: RelayTeamDTO) => {
    const raw = picked[team.id];
    const userId = raw ? Number(raw) : NaN;
    if (!Number.isFinite(userId) || userId <= 0) return;
    setBusy(`add-${team.id}`);
    setError(null);
    setNotice(null);
    try {
      await api.addRelayRunner(team.id, userId);
      const student = roster.find(item => item.userId === userId);
      setNotice(t('relay.added', { name: student?.name || userId, team: team.label || '' }));
      setPicked(prev => ({ ...prev, [team.id]: '' }));
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
      await api.removeRelayRunner(team.id, userId);
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
      await api.setRelayLegs(team.id, orderFor(team));
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

  if (isLoading || !user || !isAdmin) {
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
          <Link href={`/admin/events/${eventId}/groups`} className="btn btn-secondary mt-2">
            {t('relay.backToGroups')}
          </Link>
        </div>
      </div>
    );
  }

  const undivided = board !== null && !board.relayTeamKind;

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('relay.boardTitle')}</h1>
        <div className="flex gap-2">
          <Link href={`/admin/events/${eventId}/groups`} className="btn btn-secondary">
            {t('relay.backToGroups')}
          </Link>
          <Link href="/admin/events" className="btn btn-secondary">
            {t('adminEvents.allEvents')}
          </Link>
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

        <div className="pill-actions mt-3">
          <button
            type="button"
            className="btn btn-sm btn-primary"
            disabled={busy === 'derive'}
            onClick={handleDerive}
          >
            {busy === 'derive' ? t('relay.deriving') : t('relay.derive')}
          </button>
          {teams.length > 0 && (
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
        <label className="checkbox-line mt-2">
          <input type="checkbox" checked={prune} onChange={e => setPrune(e.target.checked)} />
          {t('relay.derivePrune')}
        </label>
      </div>

      {undivided && (
        <div className="card">
          <h2>{t('relay.undividedTitle')}</h2>
          <p className="muted mt-2">{t('relay.undividedHint')}</p>
          <div className="pill-actions mt-3">
            <Link href={`/admin/events/${eventId}/edit`} className="btn btn-sm btn-secondary">
              {t('relay.setKind')}
            </Link>
          </div>
        </div>
      )}

      {!undivided && teams.length === 0 && (
        <div className="card text-center">
          <p className="muted">{t('relay.noRunners')}</p>
        </div>
      )}

      {teams.map(team => {
        const members = team.members ?? [];
        const orderIds = orderFor(team);
        const edited = !!order[team.id];
        const candidates = candidatesFor(team);
        const legs = team.legCount ?? board?.legsPerTeam ?? 0;
        const filled = members.filter(member => !member.reserve).length;
        return (
          <div key={team.id} className="card">
            <div className="flex justify-between items-center">
              <div className="flex gap-2 items-center">
                <h3>{team.label}</h3>
                <span className="badge badge-info">
                  {t('relay.legsFilled', { filled, legs })}
                </span>
                <span className={team.complete ? 'badge badge-success' : 'badge badge-warning'}>
                  {team.complete ? t('relay.complete') : t('relay.incomplete')}
                </span>
              </div>
            </div>

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
                          <td>{member.classLabel || member.className || '-'}</td>
                          <td>{member.house || '-'}</td>
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

            {/* Naming a runner. Only plausible candidates are offered; the server
                still decides, and refuses anyone else with its own wording. */}
            <div className="mt-3">
              <h4>{t('relay.addRunner')}</h4>
              <p className="muted">{t('relay.addRunnerHint')}</p>
              {candidates.length === 0 ? (
                <p className="muted">{t('relay.noCandidates')}</p>
              ) : (
                <div className="toolbar mt-2">
                  <div className="form-group" style={{ minWidth: '20rem' }}>
                    <label>{t('relay.pickCandidate')}</label>
                    <select
                      value={picked[team.id] ?? ''}
                      onChange={e => setPicked(prev => ({ ...prev, [team.id]: e.target.value }))}
                    >
                      <option value="">{t('relay.pickCandidate')}</option>
                      {candidates.map(student => (
                        <option key={student.userId} value={student.userId}>
                          {studentLabel(student)}
                        </option>
                      ))}
                    </select>
                  </div>
                  <button
                    type="button"
                    className="btn btn-success"
                    disabled={!picked[team.id] || busy === `add-${team.id}`}
                    onClick={() => handleAdd(team)}
                  >
                    {busy === `add-${team.id}` ? t('relay.adding') : t('relay.add')}
                  </button>
                </div>
              )}
              <p className="muted mt-2">{t('relay.orderHint')}</p>
            </div>
          </div>
        );
      })}
    </div>
  );
}
