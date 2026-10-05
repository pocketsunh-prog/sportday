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

/** One group of applicants on the list: their class, or their house team. */
interface ApplicantGroup {
  key: string;
  /** What the group is headed with — `5D` or `Yellow House`. */
  label: string;
  rows: RelayApplicantDTO[];
}

/**
 * The relay team board for one event: the teams, their runners, and the students
 * who applied.
 *
 * A relay is divided into form or house teams on the event itself; this board is
 * where those teams are derived from the roster, filled and ordered — **and where
 * a teacher groups the students who applied to the relay into those teams**. The
 * applicant list is the list of confirmed entrants: with form, class and house
 * (and the house's short code) beside each, and each one saying whether they are
 * already on a team.
 *
 * ## Two ways to make a team, and they are told apart
 *
 * 1. **By hand, from the ticked students** — the primary way, and the one the
 *    school asked for: tick any applicants, **type the team's own name** as free
 *    text, and press create. `POST .../relay-events/{eventId}/teams` with
 *    `{name, userIds}` makes exactly that team in one action — a team that is
 *    not one class's and not one house's, which is why the derived kinds below
 *    can never express it.
 * 2. **Derive the roster's own teams** — kept, because it is still how a `FORM`
 *    or a `HOUSE` relay gets its class or house teams:
 *    `POST .../relay-teams/derive` creates one team per class, or per house of
 *    the event's grade, from the *roster*.
 *
 * Both are on screen, so both say what they do: the derive button is labelled as
 * the roster's class/house teams, and the hand-made form right beside the tick
 * list is labelled as one team out of exactly the students ticked, under a name
 * the teacher types. Nothing about the two is left to guesswork.
 *
 * The server enforces everything that matters about a hand-made squad — four
 * runners and at most one reserve, a name that is neither blank nor over 40
 * characters nor already used in the event, and every runner's grade, division
 * and one-team-per-event rule — and **every message it sends is shown verbatim**
 * beside the create button, never swallowed and never reworded. A refused create
 * writes nothing, so the tick boxes are left exactly as they were to fix the name
 * and retry.
 *
 * A hand-made team comes back with `handMade: true` and carries no kind at all,
 * so a later derive can never match, rename or prune it. Its card says so, with
 * a badge naming it a hand-made team.
 *
 * ## One page, both roles
 *
 * The same page serves a teacher and an administrator: `/api/teacher/**` admits
 * ADMIN and TEACHER alike and runs the same service, so the role only picks which
 * family of endpoints is called (see `api.relayBase`). A teacher sees their own
 * classes' applicants — the students they may place — while the teams themselves
 * are the event's whole set, because a house team spans classes. Only the
 * administrator's extra — removing every team of the event — is held back.
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
  /**
   * Why **the create** was refused, in the server's own words, or our own when it
   * sent none. It is kept apart from the page's general `error` because it is
   * bound to the create button: the teacher pressed that, so that is where the
   * reason belongs. A refused create writes nothing on the server, so the ticks
   * are deliberately left exactly as they were for a retry.
   */
  const [createError, setCreateError] = useState<string | null>(null);
  /** Whether a derivation should also drop empty teams the roster no longer calls for. */
  const [prune, setPrune] = useState(false);
  /**
   * The students ticked on the applicant list, **in the order they were ticked**.
   *
   * The order is the running order: the first ticked student runs leg 1. It is an
   * array rather than a set so that order survives, and unticking removes the
   * student from it without disturbing anybody else's place.
   */
  const [ticked, setTicked] = useState<number[]>([]);
  /**
   * The team name being typed for the team about to be made out of the ticked
   * students. Free text, exactly what the school writes on the sheet.
   */
  const [teamName, setTeamName] = useState('');
  /** Whether the list is narrowed to the applicants nobody has placed yet. */
  const [unplacedOnly, setUnplacedOnly] = useState(false);
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

  /**
   * The board is only fetched for a relay: everything under it answers a 400 for
   * anything else, so a non-relay event is described instead of being asked
   * about.
   */
  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    setCreateError(null);
    try {
      const eventResult = await api.getEvent(eventId);
      setEvent(eventResult);
      setOrder({});
      setNames({});
      setTicked([]);
      if (isRelayEventType(eventResult.type)) {
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
  }, [eventId, role, t]);

  useEffect(() => {
    if (Number.isFinite(eventId) && eventId > 0 && canWork) load();
    // `load` is rebuilt when the language changes; reloading then is harmless and
    // keeps the server's own wording in the right language where it can be.
  }, [eventId, canWork, load]);

  const relay = !!event && isRelayEventType(event.type);
  const teams = useMemo(() => board?.teams ?? [], [board]);
  const applicants = useMemo(() => board?.applicants ?? [], [board]);

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

  const teamById = useMemo(() => {
    const map = new Map<number, RelayTeamDTO>();
    teams.forEach(team => map.set(team.id, team));
    return map;
  }, [teams]);

  /**
   * The **derived** team one applicant would run for: the team they are already
   * on, or else the team of their own class (a form relay) or their own house (a
   * house relay).
   *
   * A **hand-made** team is deliberately never proposed here. It is not one
   * class's and not one house's, so nothing about an applicant's register record
   * points at it — the only way into one is to tick the student and create, or to
   * be added to it by hand from its own card. Its `kind` is null, which is the
   * structural reason it can never be matched here.
   *
   * This is only a *proposal*. The server decides, and refuses a pick that does
   * not belong to the team with its own wording.
   */
  const targetTeamFor = useCallback(
    (applicant: RelayApplicantDTO): RelayTeamDTO | null => {
      if (applicant.teamId !== undefined && applicant.teamId !== null) {
        return teamById.get(applicant.teamId) ?? null;
      }
      const className = (applicant.className ?? '').trim().toUpperCase();
      const form = applicant.form ?? formKeyOf(applicant.className);
      const house = (applicant.house ?? '').trim();
      const match = teams.find(team => {
        // A hand-made team belongs to no class and no house: skip it explicitly.
        if (team.handMade || !team.kind) return false;
        const key = (team.teamKey ?? '').trim();
        if (!key) return false;
        if (team.kind === 'HOUSE') return house !== '' && key.toLowerCase() === house.toLowerCase();
        // A form relay is a class relay: the key is the class name, and a team
        // keyed with a bare form number is one derived before the class split.
        if (className && key.toUpperCase() === className) return true;
        return !!form && key === form;
      });
      return match ?? null;
    },
    [teamById, teams]
  );

  /** The applicants on screen: all of them, or only those still unplaced. */
  const visibleApplicants = useMemo(
    () => (unplacedOnly ? applicants.filter(item => !item.placed) : applicants),
    [applicants, unplacedOnly]
  );

  /**
   * The applicant list grouped by class — the order the server returns, which is
   * form numerically (Form 2 before Form 10), then class, then class number, then
   * name. Grouping by class is what a teacher ticks down, and the groups come out
   * of the server's own order rather than a second sort here.
   */
  const groups = useMemo<ApplicantGroup[]>(() => {
    const built: ApplicantGroup[] = [];
    let lastKey = '';
    visibleApplicants.forEach(applicant => {
      const classLabel = classText(applicant);
      const key = classLabel || '—';
      if (key !== lastKey || built.length === 0) {
        built.push({ key, label: key, rows: [] });
        lastKey = key;
      }
      built[built.length - 1].rows.push(applicant);
    });
    return built;
  }, [visibleApplicants]);

  /** The applicants by account, for naming one the tick list is warning about. */
  const applicantById = useMemo(() => {
    const map = new Map<number, RelayApplicantDTO>();
    applicants.forEach(item => map.set(item.userId, item));
    return map;
  }, [applicants]);

  /**
   * The students ticked, in the order they were ticked — which is the leg order
   * the create sends, so the first student ticked runs leg 1.
   */
  const selectedIds = ticked;

  /**
   * The ticked students the server is certain to refuse, with the reason: an
   * applicant who is already on another team of this event. They are shown here
   * *before* the create is pressed, and their tick box is disabled rather than
   * left to fail, because the refusal — one leg per athlete per event — is not
   * something the teacher can fix by trying again.
   */
  const tickConflicts = useMemo(
    () =>
      selectedIds
        .map(userId => applicantById.get(userId))
        .filter((item): item is RelayApplicantDTO => !!item)
        .filter(item => !!item.placed || runningUserIds.has(item.userId)),
    [selectedIds, applicantById, runningUserIds]
  );

  /**
   * Whether the ticks already name more runners than one team may hold — the
   * event's own cap, four legs and at most one reserve. The server refuses it with
   * its own wording; saying so here means the teacher is not left to discover it
   * from a refusal.
   */
  const memberCap = board?.memberCap ?? event?.relayMemberCap ?? null;
  const overCap = memberCap !== null && selectedIds.length > memberCap;

  /**
   * Whether a student may be ticked into the team about to be made.
   *
   * A student who already runs in **another team of this event** may not: the
   * server refuses it — one leg per athlete per event — and there is nothing a
   * teacher can do about it from here, so the tick box is disabled with the
   * reason on screen beside it rather than left to fail on the create.
   */
  const canTick = (applicant: RelayApplicantDTO): boolean =>
    !runningUserIds.has(applicant.userId);

  /** Why a student cannot be ticked, in the board's own words. */
  const tickBlockedReason = (applicant: RelayApplicantDTO): string | null => {
    if (!runningUserIds.has(applicant.userId)) return null;
    if (applicant.placed) {
      return t('relay.tickBlockedPlaced', {
        team: applicant.teamLabel || t('relay.placed'),
      });
    }
    // On a team the server has not linked back to this applicant yet — the board
    // is a moment stale, but the rule is the same and so is the reason.
    return t('relay.tickBlockedRunning');
  };

  const setTick = (userId: number, on: boolean) => {
    setTicked(prev => {
      if (!on) return prev.filter(id => id !== userId);
      // Appended, not sorted: the tick order is the running order.
      return prev.includes(userId) ? prev : [...prev, userId];
    });
    setCreateError(null);
    setNotice(null);
  };

  /** Ticks or unticks a whole group — one class at a time, which is the usual job. */
  const setGroup = (rows: RelayApplicantDTO[], on: boolean) => {
    setTicked(prev => {
      if (!on) {
        const drop = new Set(rows.map(row => row.userId));
        return prev.filter(id => !drop.has(id));
      }
      const held = new Set(prev);
      return [...prev, ...rows.map(row => row.userId).filter(id => !held.has(id))];
    });
    setCreateError(null);
    setNotice(null);
  };

  const clearSelection = () => {
    setTicked([]);
    setCreateError(null);
  };

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

  /* ---------------- making a team out of the ticked students ---------------- */

  /**
   * Creates **one team** out of exactly the students ticked, under the name typed.
   *
   * This is the school's own flow and the primary way a team is made: tick any
   * applicants, type the team's name — `1A`, `B Grade Yellow`, whatever goes on
   * the sheet — and press create. The team is not required to be one class's or
   * one house's, which is exactly what a derived team cannot be.
   *
   * The tick order is the leg order: the first student ticked runs leg 1. Fewer
   * than four is allowed and the team is reported incomplete; the server refuses
   * anything past the event's own cap, a blank or over-long or already-used name,
   * and any runner who is in the wrong grade or division or already running.
   *
   * On a refusal the ticks are **not** touched: nothing was written on the server,
   * so the teacher fixes the name and presses create again without re-ticking. The
   * server's message is put on screen word for word beside this button.
   */
  const handleCreateTeam = async () => {
    const name = teamName.trim();
    if (name === '') {
      // The server refuses a blank name too, but there is no reason to make a
      // round trip for something the page can see.
      setCreateError(t('relay.createNeedsName'));
      return;
    }
    if (selectedIds.length === 0) {
      setCreateError(t('relay.createNeedsTicks'));
      return;
    }
    setBusy('create');
    setCreateError(null);
    setError(null);
    setNotice(null);
    try {
      // `selectedIds` is the tick order, which the server takes as the leg order.
      const created = await api.createRelayTeam(eventId, name, selectedIds, role);
      const made = created.label || name;
      const runCount = created.memberCount ?? selectedIds.length;
      setTeamName('');
      setTicked([]);
      setOrder({});
      await refreshBoard();
      setNotice(t('relay.createDone', { team: made, count: runCount }));
    } catch (err) {
      // The server's own wording, verbatim: a blank name, one over 40 characters,
      // one this event already holds, a sixth runner, a runner from the wrong
      // grade or division, a runner already on another team of this event. The
      // ticks stay as they are so the name can be fixed and the create retried.
      setCreateError(errorText(err, t('relay.createFailed')));
    } finally {
      setBusy(null);
    }
  };

  /* ---------------- runners ---------------- */

  const refreshBoard = useCallback(async () => {
    const boardResult = await api.getRelayTeams(eventId, role);
    setBoard(boardResult);
  }, [eventId, role]);

  const handleAddOne = async (team: RelayTeamDTO, userId: number) => {
    setBusy(`add-${team.id}`);
    setError(null);
    setNotice(null);
    try {
      await api.addRelayRunner(team.id, userId, null, role);
      const applicant = applicants.find(item => item.userId === userId);
      setNotice(
        t('relay.added', { name: applicant?.name || userId, team: team.label || '' })
      );
      setTick(userId, false);
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
          {isAdmin ? (
            <Link href={`/admin/events/${eventId}/groups`} className="btn btn-secondary mt-2">
              {t('relay.backToGroups')}
            </Link>
          ) : (
            <Link href="/teacher" className="btn btn-secondary mt-2">
              {t('relay.backToTeacher')}
            </Link>
          )}
        </div>
      </div>
    );
  }

  const undivided = board !== null && !board.relayTeamKind;
  const placedCount = board?.placedCount ?? 0;
  const unplacedCount = board?.unplacedCount ?? 0;
  /** A team short of its legs — what the board has to warn about, never hide. */
  const shortTeams = teams.filter(team => !team.complete);

  return (
    <div>
      <div className="flex justify-between items-center">
        <h1 className="page-title">{t('relay.boardTitle')}</h1>
        <div className="flex gap-2">
          {isAdmin ? (
            <Link href={`/admin/events/${eventId}/groups`} className="btn btn-secondary">
              {t('relay.backToGroups')}
            </Link>
          ) : (
            <Link href="/teacher" className="btn btn-secondary">
              {t('relay.backToTeacher')}
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
          The **derive** control. This is not how a team is normally made any more:
          it exists for a FORM or HOUSE relay, whose class or house teams come out
          of the roster itself. It is labelled as that, against the hand-made form
          further down, so the two are never mistaken for one another.
        */}
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
      </div>

      {undivided && (
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

      {/* ---------------- who applied ---------------- */}

      <div className="card">
        <div className="flex justify-between items-center">
          <h2>{t('relay.applicantsTitle')}</h2>
          <div className="pill-actions">
            <span className="badge badge-info">
              {t('relay.applicantCount', { count: board?.applicantCount ?? 0 })}
            </span>
            <span className={unplacedCount > 0 ? 'badge badge-warning' : 'badge badge-success'}>
              {t('relay.unplacedCount', { count: unplacedCount })}
            </span>
            <span className="badge badge-info">
              {t('relay.placedCount', { count: placedCount })}
            </span>
          </div>
        </div>
        <p className="muted mt-2">{t('relay.applicantsHint')}</p>

        {applicants.length === 0 ? (
          /* "Nobody applied" and "no applicant is yours to place" are different
             answers, and a teacher with no classes at all is refused everybody. */
          <p className="muted mt-2">{isAdmin ? t('relay.noApplicants') : t('relay.noApplicantsMine')}</p>
        ) : (
          <>
            <div className="pill-actions mt-3">
              <label className="checkbox-line">
                <input
                  type="checkbox"
                  checked={unplacedOnly}
                  onChange={e => setUnplacedOnly(e.target.checked)}
                />
                {t('relay.unplacedOnly')}
              </label>
            </div>

            {/*
              The **hand-made** team form: the primary way a team is made. Tick any
              applicants, type the name, press create. It sits directly above the
              tick list, and says what it does in its own words, so it cannot be
              confused with the derive control in the card above.
            */}
            <div className="card mt-3">
              <div className="flex justify-between items-center">
                <h4>{t('relay.createTitle')}</h4>
                <span
                  className={selectedIds.length > 0 ? 'badge badge-info' : 'badge badge-warning'}
                >
                  {/* How many are ticked — what the create is about to make. */}
                  {t('relay.tickedCount', { count: selectedIds.length })}
                </span>
              </div>
              <p className="muted mt-2">{t('relay.createFlow')}</p>

              <div className="toolbar mt-2">
                <div className="form-group" style={{ minWidth: '20rem' }}>
                  <label htmlFor="relay-new-team-name">{t('relay.createNameLabel')}</label>
                  <input
                    id="relay-new-team-name"
                    type="text"
                    maxLength={40}
                    placeholder={t('relay.createNamePlaceholder')}
                    value={teamName}
                    onChange={e => {
                      setTeamName(e.target.value);
                      setCreateError(null);
                    }}
                  />
                </div>
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={
                    busy === 'create' || selectedIds.length === 0 || teamName.trim() === ''
                  }
                  onClick={handleCreateTeam}
                >
                  {busy === 'create' ? t('relay.creating') : t('relay.createTeam')}
                </button>
                <button
                  type="button"
                  className="btn btn-secondary"
                  disabled={selectedIds.length === 0}
                  onClick={clearSelection}
                >
                  {t('relay.clearSelection')}
                </button>
              </div>

              {selectedIds.length > 0 && (
                <p className="muted mt-2">{t('relay.createOrderHint')}</p>
              )}

              {overCap && (
                <div className="alert alert-warning mt-2">
                  {t('relay.createOverCap', {
                    count: selectedIds.length,
                    cap: memberCap ?? 0,
                    legs: board?.legsPerTeam ?? event.relayTeamSize ?? 0,
                  })}
                </div>
              )}

              {tickConflicts.length > 0 && (
                <div className="alert alert-warning mt-2">
                  {t('relay.createConflict', {
                    names: tickConflicts
                      .map(item => item.name || item.studentRef || item.userId)
                      .join(', '),
                  })}
                </div>
              )}

              {/*
                Every refusal the server sent for **this** create, in its own words
                and bound to the button that failed. A refused create writes
                nothing, so the ticks above are untouched and the name can be fixed
                and the create pressed again.
              */}
              {createError && (
                <div className="alert alert-error mt-2">
                  <strong>{t('relay.createRefused')}</strong>
                  <p>{createError}</p>
                  <p className="muted">{t('relay.createTicksKept')}</p>
                </div>
              )}
            </div>

            {groups.length === 0 ? (
              <p className="muted mt-2">{t('relay.noUnplaced')}</p>
            ) : (
              groups.map(group => (
                <div key={group.key} className="mt-3">
                  <div className="flex justify-between items-center">
                    <h4>
                      {group.label} <span className="muted">({group.rows.length})</span>
                    </h4>
                    <div className="pill-actions">
                      <button
                        type="button"
                        className="btn btn-sm btn-secondary"
                        disabled={!group.rows.some(canTick)}
                        onClick={() => setGroup(group.rows.filter(canTick), true)}
                      >
                        {t('relay.tickGroup')}
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm btn-secondary"
                        onClick={() => setGroup(group.rows, false)}
                      >
                        {t('relay.untickGroup')}
                      </button>
                    </div>
                  </div>
                  <div className="table-wrap mt-2">
                    <table>
                      <thead>
                        <tr>
                          <th className="col-narrow">{t('relay.tick')}</th>
                          <th>{t('students.colId')}</th>
                          <th>{t('students.colName')}</th>
                          <th>{t('relay.form')}</th>
                          <th>{t('marks.class')}</th>
                          <th>{t('students.colHouse')}</th>
                          <th>{t('relay.team')}</th>
                          <th>{t('common.actions')}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {group.rows.map(applicant => {
                          const team = targetTeamFor(applicant);
                          const tickable = canTick(applicant);
                          const blocked = tickable ? null : tickBlockedReason(applicant);
                          return (
                            <tr key={applicant.userId}>
                              <td>
                                {/* A student already on a team of this event cannot
                                    be ticked into another: the server refuses it
                                    and the reason is on screen beside the box. */}
                                <input
                                  type="checkbox"
                                  aria-label={studentLabel(applicant)}
                                  disabled={!tickable}
                                  checked={selectedIds.includes(applicant.userId)}
                                  onChange={e => setTick(applicant.userId, e.target.checked)}
                                />
                              </td>
                              <td>{applicant.studentRef || '-'}</td>
                              <td>{applicant.name || '-'}</td>
                              <td>{formText(applicant, t)}</td>
                              <td>{classText(applicant)}</td>
                              <td>{houseText(applicant)}</td>
                              <td>
                                {applicant.placed ? (
                                  <span className="badge badge-success">
                                    {applicant.teamLabel || t('relay.placed')}
                                  </span>
                                ) : team ? (
                                  /* Where this student would go in a *derived*
                                     team — their own class or house. The server
                                     still decides. */
                                  <span className="badge badge-info">{team.label}</span>
                                ) : (
                                  <span className="badge badge-warning">{t('relay.noTeamYet')}</span>
                                )}
                                {blocked && <div className="muted">{blocked}</div>}
                              </td>
                              <td>
                                {tickable && team && (
                                  <button
                                    type="button"
                                    className="btn btn-sm btn-success"
                                    disabled={busy === `add-${team.id}`}
                                    onClick={() => handleAddOne(team, applicant.userId)}
                                  >
                                    {t('relay.add')}
                                  </button>
                                )}
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                </div>
              ))
            )}
          </>
        )}
      </div>

      {/* ---------------- the teams ---------------- */}

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

      {teams.map(team => {
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

            {/* Adding one student to this team by hand, for the case the tick list
                does not cover. Only the applicants still unplaced are offered, and
                only the team the derivation rule points them at — a hand-made team
                is no class's and no house's, so nothing points at it and it is
                filled through the tick list and the create form above instead. */}
            {(() => {
              const addable = applicants.filter(
                item => !item.placed && !runningUserIds.has(item.userId) && targetTeamFor(item)?.id === team.id
              );
              if (addable.length === 0) {
                return (
                  <div className="mt-3">
                    <h4>{t('relay.addRunner')}</h4>
                    <p className="muted">{t('relay.noCandidates')}</p>
                  </div>
                );
              }
              return (
                <div className="mt-3">
                  <h4>{t('relay.addRunner')}</h4>
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
                        {addable.map(applicant => {
                          return (
                            <tr key={applicant.userId}>
                              <td>{studentLabel(applicant)}</td>
                              <td>{formText(applicant, t)}</td>
                              <td>{classText(applicant)}</td>
                              <td>{houseText(applicant)}</td>
                              <td>
                                <button
                                  type="button"
                                  className="btn btn-sm btn-success"
                                  disabled={busy === `add-${team.id}`}
                                  onClick={() => handleAddOne(team, applicant.userId)}
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
