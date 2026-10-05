'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  api,
  EventDTO,
  isRelayEventType,
  RelayEventTeamsDTO,
  RelayTeamDerivationDTO,
  RelayTeamKind,
  Role,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';

/** The message the server sent, or our own wording when there is none. */
function errorText(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback;
}

/** Turns a school-side event name into something safe for a filename. */
function slug(value: string): string {
  const cleaned = value.replace(/[^\w.-]+/g, '-').replace(/^-+|-+$/g, '');
  return cleaned || 'relay-event';
}

/**
 * The message the server sent for one attempt, **verbatim and untranslated** —
 * the re-kind refusal ("… already has 8 relay team(s) with their runners. Remove
 * them before changing what kind of relay it is."), the "not divided into form or
 * house teams" refusal a derive answers with, a 403 for a role an endpoint
 * refuses, or a download failure. It is never replaced with a generic "failed",
 * and it is shown under the button that was pressed.
 */
interface EventFailure {
  error: string;
  /** What the attempt was trying to do, so the message can name it. */
  kind: RelayTeamKind | null;
}

/** What one successful derive reported, with the teams it left standing. */
interface EventOutcome {
  derived: RelayTeamDerivationDTO;
  /** Teams removed immediately before it, when the reader freed the event first. */
  removed: number | null;
}

/** How a board could not be read, when it could not be. */
interface BoardFailure {
  error: string;
}

/** One event type's relays, so the two runs of the programme read as two lists. */
interface EventGroup {
  key: string;
  label: string;
  events: EventDTO[];
}

/**
 * **Every relay event of the programme, and the one click that makes its teams.**
 *
 * A relay is divided into teams on the event itself: FORM means one team per
 * **class** of the event's grade (5A, 5B, ... — what the school calls form and
 * class), and HOUSE means one team per **grade x house** (C Grade Yellow). Those
 * are the only two divisions the server knows, and they are the two values of the
 * event's relayTeamKind.
 *
 * This page is the index the per-event board at /admin/events/[id]/relay never
 * had: that board shows the teams of **one** event, so seeing which of the
 * programme's relays is still undivided meant opening twelve pages.
 *
 * ## Setting the kind, then deriving — two calls, in that order
 *
 * Deriving reads the kind **the event already holds**: the derive endpoint takes
 * no kind at all. Calling it on an event whose kind is not the one the button
 * names would therefore make the wrong teams and report success. The kind is a
 * field of the event, set by PUT on the event itself. So "make the teams by grade
 * and house" is **two calls: set the kind, then derive**, and the derive is only
 * sent once the kind is settled. If the first call is refused the derive is **not**
 * sent at all — there is nothing to derive into, and sending it would either write
 * the wrong teams or fail a second time for a reason that hides the first.
 *
 * ## The refusal that must be shown, not swallowed
 *
 * The server refuses to change an event's kind while it already has teams
 * (EventService.requireNoTeamsToReKind): those teams hold real selections, so they
 * are not thrown away to make room for another division. Pressing "make the teams
 * by grade and house" on an event that already has class teams is therefore
 * **refused with a 409**, and that refusal is shown here in the server's own
 * words, under the button that caused it. It is offered a way out rather than left
 * as a dead end: a second, deliberate step — *remove this event's teams and make
 * them by ...* — which asks for confirmation, calls the administrator's own
 * DELETE on the event's relay teams (**ADMIN only**) and then runs the original
 * two calls. Nothing is cleared without that confirmation.
 *
 * ## One page, both roles
 *
 * The teacher endpoint family admits ADMIN and TEACHER alike and runs the same
 * services, so the role only picks which family is called (see api.relayBase). The
 * two calls that change an event are **not** shared: PUT on an event is
 * hasAnyRole('ADMIN','MANAGER'), so a teacher may not set a kind, and the marking
 * sheet PDF of an event is ADMIN, MANAGER or HELPER, so a teacher may not print
 * either. A teacher is therefore **told that plainly, up front** — the same
 * treatment the per-event board gives the kind it holds back — rather than being
 * offered a control whose only outcome is a 403. What a teacher *can* do from here
 * is derive an event that is already divided, and open its board to place their
 * own classes' applicants.
 *
 * ## An event with no teams is the normal starting state
 *
 * Every relay of the live programme starts undivided and holds no teams. That is
 * how a relay begins, so it is described as a starting point — with the two
 * buttons right beside it — and never rendered as a failure or an empty table.
 */
export default function AdminRelayEventsPage() {
  const router = useRouter();
  const { user, isLoading } = useAuth();
  const { t, label } = useI18n();

  const [events, setEvents] = useState<EventDTO[]>([]);
  /**
   * Each relay's board, keyed by event id. A board that could not be read holds
   * its failure rather than being left out, because "we could not ask" and "this
   * event has no teams" are different answers.
   */
  const [boards, setBoards] = useState<Record<number, RelayEventTeamsDTO | BoardFailure>>({});
  const [loading, setLoading] = useState(true);
  const [boardsLoading, setBoardsLoading] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** The last outcome per event, keyed by event id. */
  const [outcomes, setOutcomes] = useState<Record<number, EventOutcome>>({});
  /** The last refusal per event, keyed by event id. */
  const [failures, setFailures] = useState<Record<number, EventFailure>>({});
  /** A sheet saved for an event, keyed by event id. */
  const [saved, setSaved] = useState<Record<number, string>>({});

  const isAdmin = user?.role === 'ADMIN';
  const canWork = isAdmin || user?.role === 'TEACHER';
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

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // Disabled events are included: a relay closed to new entries still needs
      // its teams and its marking sheets.
      const all = await api.getEvents({ onlyEnabled: false });
      setEvents(all.filter(event => isRelayEventType(event.type)));
    } catch (err) {
      setError(errorText(err, t('relayEvents.loadFailed')));
      setEvents([]);
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    if (canWork) load();
    // `load` is rebuilt when the language changes; reloading then is harmless.
  }, [canWork, load]);

  /**
   * Reads every relay's board once, to answer the question this page exists to
   * answer: **which relays are undivided, and which already hold teams?**
   *
   * `GET /api/events` carries the kind on the event itself, so the division is
   * free. The **team count is not** — no list response carries it — so it costs
   * one request per relay. That is twelve requests for the live programme, made
   * **once**, in parallel, only for the relays that were just listed, and never
   * again per render or per keystroke. It buys the two answers this page would
   * otherwise be unable to give.
   *
   * A board that fails is recorded as failed rather than retried or hidden: a
   * caller the board endpoint refuses sees which events could not be read instead
   * of an invented zero.
   */
  const loadBoards = useCallback(
    async (list: EventDTO[]) => {
      if (list.length === 0) {
        setBoards({});
        setBoardsLoading(false);
        return;
      }
      setBoardsLoading(true);
      const fetched = await Promise.all(
        list.map(async event => {
          try {
            const board = await api.getRelayTeams(event.id, role);
            return [event.id, board] as const;
          } catch (err) {
            const failure: BoardFailure = {
              error: errorText(err, t('relayEvents.boardFailed')),
            };
            return [event.id, failure] as const;
          }
        })
      );
      setBoards(previous => {
        const next = { ...previous };
        fetched.forEach(pair => {
          next[pair[0]] = pair[1];
        });
        return next;
      });
      setBoardsLoading(false);
    },
    [role, t]
  );

  useEffect(() => {
    if (canWork && events.length > 0) loadBoards(events);
  }, [canWork, events, loadBoards]);

  const boardOf = (eventId: number): RelayEventTeamsDTO | null => {
    const board = boards[eventId];
    if (!board) return null;
    if ('error' in board) return null;
    return board;
  };

  const boardFailureOf = (eventId: number): string | null => {
    const board = boards[eventId];
    if (!board) return null;
    if (!('error' in board)) return null;
    return board.error;
  };

  /** What kind an event is divided by, read from the board and the event alike. */
  const kindOf = (event: EventDTO): RelayTeamKind | '' => {
    const board = boardOf(event.id);
    const kind = board ? board.relayTeamKind : event.relayTeamKind;
    if (kind === 'FORM') return 'FORM';
    if (kind === 'HOUSE') return 'HOUSE';
    return '';
  };

  /** Replaces one event's board with a freshly read one. */
  const refreshBoard = async (eventId: number) => {
    try {
      const board = await api.getRelayTeams(eventId, role);
      setBoards(previous => ({ ...previous, [eventId]: board }));
    } catch {
      // A stale count is not worth an error banner: whatever went wrong is
      // already reported by the call that prompted the refresh.
    }
  };

  /**
   * The whole of one button: **set the kind if it is not already the one asked
   * for, then derive.**
   *
   * The order is the point. Deriving reads the kind the event holds, so the
   * derive is only sent once the kind is settled — and if setting it is refused,
   * the derive is never sent. That refusal is kept as the event's failure, with
   * the server's own wording untouched.
   */
  const makeTeams = useCallback(
    async (event: EventDTO, kind: RelayTeamKind) => {
      setBusy(`make-${event.id}`);
      setError(null);
      setFailures(previous => {
        const next = { ...previous };
        delete next[event.id];
        return next;
      });
      setSaved(previous => {
        const next = { ...previous };
        delete next[event.id];
        return next;
      });
      try {
        if (kindOf(event) !== kind) {
          /*
           * Step one. This is the call the server refuses with a 409 while the
           * event already has teams, and that refusal is the reader's answer: it
           * names the event and the count and says what to do. It is shown as it
           * stands and the derive below is not attempted.
           */
          await api.updateEvent(event.id, { relayTeamKind: kind });
        }
        // Step two, reached only when the kind now stands.
        const derived = await api.deriveRelayTeams(event.id, false, role);
        setOutcomes(previous => ({ ...previous, [event.id]: { derived, removed: null } }));
        await refreshBoard(event.id);
      } catch (err) {
        const failure: EventFailure = {
          error: errorText(err, t('relayEvents.makeFailed')),
          kind,
        };
        setFailures(previous => ({ ...previous, [event.id]: failure }));
      } finally {
        setBusy(null);
      }
    },
    // `kindOf` reads the boards, which the list below covers.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [boards, role, t]
  );

  /**
   * The way out of the refusal: remove this event's teams, then make them by the
   * rule that was refused. **Two deliberate steps, never one** — the teams hold
   * real selections, so this asks for confirmation and is offered only to an
   * administrator, who is the only role the endpoint admits.
   */
  const clearAndMake = async (event: EventDTO, kind: RelayTeamKind) => {
    if (!confirm(t('relayEvents.clearConfirm', { name: event.name }))) return;
    setBusy(`clear-${event.id}`);
    setError(null);
    try {
      const removed = await api.removeRelayTeams(event.id);
      setFailures(previous => {
        const next = { ...previous };
        delete next[event.id];
        return next;
      });
      await makeTeams(event, kind);
      // The removal is reported with the derivation that followed it, so both
      // halves of the two-step press are visible.
      setOutcomes(previous => {
        const outcome = previous[event.id];
        if (!outcome) return previous;
        return { ...previous, [event.id]: { derived: outcome.derived, removed: removed.teamsRemoved } };
      });
    } catch (err) {
      const failure: EventFailure = {
        error: errorText(err, t('relayEvents.clearFailed')),
        kind,
      };
      setFailures(previous => ({ ...previous, [event.id]: failure }));
    } finally {
      setBusy(null);
    }
  };

  /**
   * Prints an event's marking sheets: every page the server renders for it, one
   * per group, including the relay teams' own sheets. **ADMIN, MANAGER or
   * HELPER** — a teacher is refused by the endpoint, so the control is not
   * offered to them and the reason is on screen instead. The whole print run
   * belongs to `/admin/print`, which this page links to; this is the one-event
   * shortcut to the same file.
   */
  const printSheets = async (event: EventDTO) => {
    setBusy(`print-${event.id}`);
    setError(null);
    try {
      const filename = await api.downloadEventSheets(
        event.id,
        `${slug(event.name)}-marking-sheets.pdf`
      );
      setSaved(previous => ({ ...previous, [event.id]: filename }));
    } catch (err) {
      const failure: EventFailure = {
        error: errorText(err, t('relayEvents.printFailed')),
        kind: null,
      };
      setFailures(previous => ({ ...previous, [event.id]: failure }));
    } finally {
      setBusy(null);
    }
  };

  const relayEvents = events;

  /** The events grouped by event type, so the two runs read as two lists. */
  const groups = useMemo<EventGroup[]>(() => {
    const built: EventGroup[] = [];
    relayEvents.forEach(event => {
      const existing = built.find(group => group.key === event.type);
      if (existing) existing.events.push(event);
      else built.push({ key: event.type, label: event.typeLabel, events: [event] });
    });
    return built;
  }, [relayEvents]);

  /** The rule one press applies, named as the press it will be. */
  const ruleLabel = (kind: RelayTeamKind | '', asked: RelayTeamKind): string => {
    const refresh = kind === asked;
    if (asked === 'FORM') {
      return refresh ? t('relayEvents.refreshForm') : t('relayEvents.makeForm');
    }
    return refresh ? t('relayEvents.refreshHouse') : t('relayEvents.makeHouse');
  };

  /**
   * What the last attempt on one event produced: the derivation's own counts and
   * the teams it left standing, the sheet it saved, or the server's refusal in
   * its own words. This is the whole of the feedback the page gives per event.
   */
  const outcomeFor = (event: EventDTO): ReactNode => {
    const failure = failures[event.id];
    const downloaded = saved[event.id];
    const outcome = outcomes[event.id];

    if (failure) {
      const kindText =
        failure.kind === 'FORM' ? t('relay.kindForm') : t('relay.kindHouse');
      return (
        <div className="alert alert-error">
          <strong>{t('relayEvents.refused')}</strong>
          <p>{failure.error}</p>
          {failure.kind !== null && <p className="muted">{t('relayEvents.refusedWhat', { kind: kindText })}</p>}
        </div>
      );
    }

    if (downloaded) {
      return (
        <div className="alert alert-success">
          {t('common.downloaded', { filename: downloaded })}
        </div>
      );
    }

    if (!outcome) return null;

    const derived = outcome.derived;
    const derivedKind = derived.kind === 'FORM' ? t('relay.kindForm') : t('relay.kindHouse');
    const kept = derived.keptWithRunners;
    const teams = derived.board.teams;
    return (
      <div className="alert alert-success">
        <strong>{t('relayEvents.done', { kind: derivedKind })}</strong>
        <p>
          {t('relay.derived', {
            created: derived.created,
            kept: derived.kept,
            pruned: derived.pruned,
            eligible: derived.eligibleStudents,
          })}
          {kept > 0 ? t('relay.derivedKeptWithRunners', { count: kept }) : ''}
        </p>
        {outcome.removed !== null && (
          <p className="muted">{t('relayEvents.clearedFirst', { count: outcome.removed })}</p>
        )}
        {teams.length > 0 && (
          <div className="mt-2">
            <p className="muted">{t('relayEvents.teamsMade')}</p>
            <div className="pill-actions mt-1">
              {teams.map(team => (
                <span key={team.id} className="badge badge-success">
                  {team.label || t('relay.unnamed')}
                </span>
              ))}
            </div>
          </div>
        )}
      </div>
    );
  };

  /** The two rule buttons, the way out of a refusal, and each event's own links. */
  const actionsFor = (event: EventDTO) => {
    const board = boardOf(event.id);
    const failure = failures[event.id];
    const kind = kindOf(event);
    const undivided = kind === '';
    const teamCount = board ? board.teamCount ?? 0 : null;
    const working = busy === `make-${event.id}`;
    const formLabel = working ? t('relayEvents.working') : ruleLabel(kind, 'FORM');
    const houseLabel = working ? t('relayEvents.working') : ruleLabel(kind, 'HOUSE');
    const teacherNote = undivided
      ? t('relayEvents.teacherUndivided')
      : t('relayEvents.teacherDeriveOnly');
    const clearable = isAdmin && !!failure && failure.kind !== null && !!teamCount && teamCount > 0;

    return (
      <div className="mt-3">
        <h4>{t('relayEvents.actionsTitle')}</h4>
        <div className="pill-actions mt-2">
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy !== null || !isAdmin}
            title={isAdmin ? undefined : t('relayEvents.teacherLimits')}
            onClick={() => makeTeams(event, 'FORM')}
          >
            {formLabel}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy !== null || !isAdmin}
            title={isAdmin ? undefined : t('relayEvents.teacherLimits')}
            onClick={() => makeTeams(event, 'HOUSE')}
          >
            {houseLabel}
          </button>
        </div>

        {!isAdmin && <p className="muted mt-2">{teacherNote}</p>}
        {isAdmin && undivided && <p className="muted mt-2">{t('relayEvents.noTeamsYet')}</p>}
        {isAdmin && undivided && <p className="muted">{t('relayEvents.kindWillBeSet')}</p>}
        {isAdmin && !undivided && <p className="muted mt-2">{t('relayEvents.kindAlreadySet')}</p>}

        {clearable && !!failure && failure.kind !== null && (
          <div className="alert alert-warning mt-2">
            <p>{t('relayEvents.clearOffer')}</p>
            <button
              type="button"
              className="btn btn-sm btn-danger"
              disabled={busy !== null}
              onClick={() => clearAndMake(event, failure.kind === 'HOUSE' ? 'HOUSE' : 'FORM')}
            >
              {busy === `clear-${event.id}`
                ? t('common.processing')
                : t('relayEvents.clearAndMake', {
                    kind: failure.kind === 'FORM' ? t('relay.kindForm') : t('relay.kindHouse'),
                  })}
            </button>
          </div>
        )}

        <div className="pill-actions mt-3">
          <Link href={`/admin/events/${event.id}/relay`} className="btn btn-sm btn-secondary">
            {t('relay.openBoard')}
          </Link>
          {isAdmin ? (
            <button
              type="button"
              className="btn btn-sm btn-secondary"
              disabled={busy !== null}
              onClick={() => printSheets(event)}
            >
              {busy === `print-${event.id}`
                ? t('common.downloading')
                : t('relayEvents.printSheets')}
            </button>
          ) : (
            <span className="muted">{t('relayEvents.teacherPrintLimit')}</span>
          )}
          {isAdmin && (
            <Link href="/admin/print" className="btn btn-sm btn-secondary">
              {t('relayEvents.printRun')}
            </Link>
          )}
        </div>
      </div>
    );
  };

  /** What an event is, and what it already holds. */
  const factsFor = (event: EventDTO) => {
    const board = boardOf(event.id);
    const boardFailure = boardFailureOf(event.id);
    const kind = kindOf(event);
    const undivided = kind === '';
    const teamCount = board ? board.teamCount ?? 0 : null;
    const kindText = kind === 'HOUSE' ? t('relay.kindHouse') : t('relay.kindForm');

    let teamCountText = t('common.loading');
    if (boardFailure) teamCountText = t('relayEvents.countUnknown');
    else if (teamCount !== null) teamCountText = t('relay.teamCount', { count: teamCount });

    return (
      <>
        <div className="event-meta mt-2">
          <div>
            <strong>{t('relay.kind')}:</strong>{' '}
            {undivided ? (
              <span className="badge badge-warning">{t('relay.kindUndivided')}</span>
            ) : (
              <span className="badge badge-success">{kindText}</span>
            )}
          </div>
          <div>
            <strong>{t('relay.teamsTitle')}:</strong> {teamCountText}
          </div>
          <div>
            <strong>{t('relay.runners')}:</strong>{' '}
            {board ? board.runnerCount ?? 0 : t('relayEvents.countUnknown')}
          </div>
          <div>
            <strong>{t('relay.applicantsTitle')}:</strong>{' '}
            {board ? board.applicantCount ?? 0 : t('relayEvents.countUnknown')}
          </div>
          <div>
            <strong>{t('relay.legsPerTeam')}:</strong>{' '}
            {board?.legsPerTeam ?? event.relayTeamSize ?? '-'}
          </div>
        </div>
        {boardFailure && (
          <div className="alert alert-error mt-2">
            <strong>{t('relayEvents.boardFailedTitle')}</strong>
            <p>{boardFailure}</p>
          </div>
        )}
      </>
    );
  };

  /** The teams an event already holds, by name. */
  const teamsFor = (event: EventDTO): ReactNode => {
    const board = boardOf(event.id);
    if (!board) return null;
    if (kindOf(event) === '') return null;
    const names = board.teams.map(team => team.label || String(team.id));
    if (names.length === 0) return <p className="muted mt-2">{t('relay.noTeams')}</p>;
    return (
      <div className="mt-3">
        <h4>{t('relayEvents.currentTeams')}</h4>
        <div className="pill-actions mt-1">
          {names.map(name => (
            <span key={name} className="badge badge-info">
              {name}
            </span>
          ))}
        </div>
      </div>
    );
  };

  /** One event's card, assembled from the pieces above. */
  const eventCard = (event: EventDTO) => {
    const card = (
      <div key={event.id} className="card">
        <div className="flex justify-between items-center">
          <h3>{event.name}</h3>
          <div className="pill-actions">
            <span className="badge badge-info">{event.typeLabel}</span>
            <span className="badge badge-info" title={label('grade', event.grade)}>
              {label('grade.short', event.grade)}
            </span>
            <span className="badge badge-info">{label('sex', event.sex)}</span>
            {event.enabled === false && (
              <span className="badge badge-warning">{t('adminEvents.disabled')}</span>
            )}
          </div>
        </div>
        {factsFor(event)}
        {actionsFor(event)}
        <div className="mt-3">{outcomeFor(event)}</div>
        {teamsFor(event)}
      </div>
    );
    return card;
  };

  if (isLoading || !user || !canWork) {
    return <div>{t('common.loading')}</div>;
  }

  if (loading) {
    return <div>{t('relayEvents.loading')}</div>;
  }

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('relayEvents.title')}</h1>
        <div className="flex gap-2">
          {isAdmin && (
            <Link href="/admin/print" className="btn btn-secondary">
              {t('nav.print')}
            </Link>
          )}
          {isAdmin ? (
            <Link href="/admin" className="btn btn-secondary">
              {t('common.backToAdmin')}
            </Link>
          ) : (
            <Link href="/teacher" className="btn btn-secondary">
              {t('relay.backToTeacher')}
            </Link>
          )}
        </div>
      </div>
      <p className="muted">{t('relayEvents.subtitle')}</p>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="card">
        <h2>{t('relayEvents.rulesTitle')}</h2>
        <p className="muted mt-2">{t('relayEvents.rulesHint')}</p>
        <p className="mt-2">
          <span className="badge badge-info">{t('relay.kindForm')}</span>{' '}
          <span className="muted">{t('relayEvents.formRule')}</span>
        </p>
        <p>
          <span className="badge badge-info">{t('relay.kindHouse')}</span>{' '}
          <span className="muted">{t('relayEvents.houseRule')}</span>
        </p>
        <p className="muted mt-2">{t('relayEvents.kindIsStoredOnTheEvent')}</p>
        {isAdmin && <p className="muted mt-2">{t('relayEvents.adminClearHint')}</p>}
        {!isAdmin && <p className="muted mt-2">{t('relayEvents.teacherLimits')}</p>}
      </div>

      <div className="card">
        <div className="flex justify-between items-center">
          <h2>{t('relayEvents.listTitle')}</h2>
          <div className="pill-actions">
            <span className="badge badge-info">
              {t('relayEvents.eventCount', { count: relayEvents.length })}
            </span>
            {boardsLoading && <span className="muted">{t('relayEvents.readingBoards')}</span>}
          </div>
        </div>
        <p className="muted mt-2">{t('relayEvents.listHint')}</p>
      </div>

      {relayEvents.length === 0 && (
        <div className="card">
          <p className="muted">{t('teacher.relayEmpty')}</p>
        </div>
      )}

      {groups.map(group => (
        <div key={group.key} className="mt-3">
          <h2>{group.label}</h2>
          {group.events.map(eventCard)}
        </div>
      ))}
    </div>
  );
}
