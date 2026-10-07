'use client';

import { useCallback, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  api,
  EventDTO,
  EventSex,
  Grade,
  GRADES,
  isRelayEventType,
  RelayEventTeamsDTO,
  RelayTeamKind,
  Role,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';
import type { MessageKey } from '@/lib/i18n';

/** The two families of relay, one page each: class teams, or grade x house teams. */
export type RelayFamily = RelayTeamKind;

/** The six forms the school's class relays are run by, in order. */
const RELAY_FORMS: ReadonlyArray<string> = ['1', '2', '3', '4', '5', '6'];

/** The two divisions a class relay is run in: a boys relay and a girls relay. */
const RELAY_DIVISIONS: ReadonlyArray<EventSex> = ['MALE', 'FEMALE'];

/**
 * The two distances the school runs its relays over — **at form level and at house
 * level alike** — and therefore the two grids each page holds: the 4x100M and the
 * 4x400M, for every form (twenty-four class relays) and for every grade (twelve
 * house relays), boys and girls.
 *
 * One grid per distance, because a grid for "a relay on each form class" or "a relay
 * on each grade" would be half a programme: whichever distance that group happened
 * to have would answer for both, and the other would never be offered. It is also
 * what a grid **matches** a relay on, so a house page holding one grid of an unknown
 * distance could not see the house relays standing in its cells at all.
 */
const RELAY_TYPES: ReadonlyArray<string> = ['RELAY_4X100M', 'RELAY_4X400M'];

/** The fewest teams a relay can be run and scored with: one team is not a relay. */
const TEAMS_NEEDED = 2;

/**
 * The **scope a stored relay name carries at its end**, in the school's own words.
 * Live names come in two shapes and both are matched here: `Boys 4x400M Relay -
 * Form 3` (a seed relay, named before it was scoped) and `Girls 4x400M Relay · C
 * Grade` (one the school named itself, before the relay was re-scoped to a form).
 *
 * The stored name is data rather than UI text: the school typed it, and the server
 * holds it in English whatever language the page is being read in.
 */
const NAME_SCOPE_AT_END = /\s*[-–—·.]\s*(Form\s*[0-9]+|[A-Za-z]{1,2}\s*Grade|Grade\s*[A-Za-z]{1,2})\s*$/;

/**
 * The grade a form's students are in, read from the school's own age bands: A is 17
 * and over, B is 15-16 and C is 14 or below, so Forms 1 and 2 are C, Forms 3 and 4
 * are B, and Forms 5 and 6 are A. Used **only** when a form has no relay at all to
 * copy a grade from — a grade the school already uses is never second-guessed.
 */
function formBandGrade(form: string): Grade {
  if (form === '1' || form === '2') return 'C';
  if (form === '3' || form === '4') return 'B';
  return 'A';
}

/**
 * The name a newly created relay takes: its sibling's, with the division changed —
 * `Boys 4x100M Relay - Form 1` becomes `Girls 4x100M Relay - Form 1` — so the pair
 * reads as one set whatever convention the school named its relays by.
 *
 * `undefined` when the sibling's name does not begin with a division, which means
 * the school named that relay itself; the server then names the new one its own way
 * and the office can rename it. A name the school chose is never rewritten.
 */
function twinName(siblingName: string, sex: EventSex): string | undefined {
  const division = sex === 'MALE' ? 'Boys' : 'Girls';
  const leading = /^(Boys|Girls)\b/;
  return leading.test(siblingName) ? siblingName.replace(leading, division) : undefined;
}

/**
 * One cell of the programme's relay grid: a division of one group of this page's
 * axis. On the class page a cell is a **form** (`Form 3`) in one division, and the
 * relay it stands for is scoped to that form; on the house page it is a **grade**
 * (`C Grade`) in one division, and the relay it stands for belongs to that grade.
 * The two axes are the same grid with a different row header, which is why both
 * pages share one creator.
 */
interface RelayCell {
  key: string;
  label: string;
  sex: EventSex;
  /** The grade a house cell is created in — absent on a class cell. */
  grade?: Grade;
}

/**
 * The message the server sent, or our own wording when there is none.
 */
function errorText(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback;
}

/** Turns a school-side event name into something safe for a filename. */
function slug(value: string): string {
  const cleaned = value.replace(/[^\w.-]+/g, '-').replace(/^-+|-+$/g, '');
  return cleaned || 'relay-event';
}

/**
 * The message the server sent for one attempt, **verbatim and untranslated** — a
 * 403 for a role an endpoint refuses, a marking sheet that could not be written, or
 * a deletion the server refused. It is never replaced with a generic "failed", and
 * it is shown on the card the attempt belonged to, under what the attempt was.
 */
interface EventFailure {
  error: string;
  /** The heading of the alert: what the attempt was. */
  what: MessageKey;
}

/** How a board could not be read, when it could not be. */
interface BoardFailure {
  error: string;
}

/**
 * What a relay is short of, in the school's terms: how many teams it has, and the
 * teams that have not a runner on every leg. See `shortfallOf`.
 */
interface RelayShortfall {
  teams: number;
  shortTeams: Array<{ label: string; runners: number; needed: number }>;
}

/**
 * One group of this page's relays: a form (`Form 3`) on the class page, or a grade
 * (`C Grade`) on the house one, so the programme reads as the school thinks of it.
 */
interface EventGroup {
  key: string;
  label: string;
  events: EventDTO[];
}

/**
 * **One distance's grid on one page**, with what is missing from it and the names
 * already standing in it.
 *
 * A grid is one event type over the page's own axis: the 4x100M class relays, the
 * 4x400M class relays, or the grade house relays — which is what lets a page hold two
 * of them. The class page does: the school runs a **4x100M and a 4x400M for every form
 * in both divisions**, twenty-four relays, and one grid each is what says so and makes
 * them. A cell is the grid's own type, so the 4x100M grid never counts a 4x400M relay
 * as done — the failure that made the grid report a distance the programme did not
 * have.
 */
interface RelayGrid {
  /** The event type this grid is about, e.g. `RELAY_4X100M`. */
  type: string;
  /** The heading, naming the distance: `4x100M Relay — class relays`. */
  title: string;
  /** One row per group of this page's axis, and how the row is written. */
  rows: Array<{ key: string; label: string }>;
  /** The cells of this grid that are not on the programme yet. */
  missing: RelayCell[];
  /** How many cells this grid holds in all — twelve class relays, or six house ones. */
  cells: number;
}

/**
 * **One family of relay events, and the one click that makes its teams.**
 *
 * The programme holds two kinds of relay, and each has its own page:
 *
 * <ul>
 *   <li>{@code family="FORM"} — the <strong>form class</strong> relays, at
 *       {@code /admin/relay-events/form}: one team per <strong>class</strong>
 *       ({@code 5A}, {@code 5B}) of the form the relay is scoped to, Forms 1 to 6,
 *       with a filter on each form;</li>
 *   <li>{@code family="HOUSE"} — the <strong>grade house</strong> relays, at
 *       {@code /admin/relay-events/grade}: one team per <strong>grade x house</strong>
 *       ({@code C Grade Yellow}), Grades A to C, with a filter on each grade.</li>
 * </ul>
 *
 * Those are the two values of the event's relayTeamKind. A page lists the relays of
 * its own family <em>and</em> any relay that is still undivided — a relay with no
 * kind has not been assigned to a family yet, and a page that hid it would hide the
 * board of an event the school is still running. The list is grouped by the page's
 * own axis (form, or grade) and the filter narrows it to one of them.
 *
 * ## The programme each page should hold, and the press that makes it
 *
 * Above the list, each page shows **one grid per distance the school runs at its own
 * level** — and the school runs both distances at both levels, so **each page holds
 * two**: the 4x100M and the 4x400M. "A relay on each form class for the girls and the
 * boys" means both distances for every one of the six forms in both divisions —
 * twenty-four class relays, twelve in each grid — and the house programme is the same
 * two distances over Grades A to C and both divisions: twelve house relays, six in
 * each grid.
 *
 * A cell that is already on the programme names its relay; a cell that is missing is
 * offered, and one press creates every missing relay of that grid and derives its
 * teams. A cell is matched on the grid's <strong>own type</strong> and the page's
 * <em>own</em> axis, and nothing else: a class cell is the relay of that type scoped
 * to that form, whatever grade it is stored under, and a house cell is the relay of
 * that grade and that distance. A relay made for a cell takes the grid's own type, so
 * pressing the 4x400M grid's button can never make a 4x100M — and pressing either
 * button twice creates nothing the second time.
 *
 * A FORM relay may also be **scoped to a form**. A form is not a grade: a relay
 * scoped to Form 1 takes whoever is in Form 1 whatever grade they are, so its
 * teams are that form's classes across every grade — `1A`, `1B`, `1C`, `1D`. Left
 * unscoped (no form), a FORM relay keeps the older rule and takes one team per
 * class of **the event's own grade**. The scope is a field of the event, set where
 * the event itself is edited — and a cell of a grid is created already carrying the
 * form it stands for.
 *
 * ## Each grid carries its own distance, and that is what makes it safe
 *
 * A grid matches a cell on **its own type** and the page's axis, so a grid has to
 * carry a real distance to be able to see the relays standing in its cells. A grid
 * whose type is not a distance matches nothing, and every cell of it then reads as
 * missing: a press makes a **second** relay for each one already there. That is the
 * fault that left the live house page holding three relays for one grade x division
 * cell — and why the house page, like the class page, now names its two distances
 * outright rather than inferring one.
 *
 * The two pages together are the index the per-event board at
 * /admin/events/[id]/relay never had: that board shows the teams of **one** event,
 * so seeing which of the programme's relays is still undivided meant opening
 * twelve pages.
 *
 * ## A card says what the event holds, and whether it is ready
 *
 * The teams are made by the **grids above the list**: one press creates every relay
 * a grid is missing and derives its teams, which is the whole of how the programme
 * is built now. The card itself therefore carries **no rule button and no form
 * picker** — what it carries is the event: its board's counts, the title this page
 * is about (the **form** on the class page, the **grade** on the house one), a
 * warning line when the relay is **not ready**, and — for an administrator — the one
 * press that **deletes the relay itself**, which is what clears a relay the
 * programme holds twice and what the grid then offers to make again.
 *
 * ## Not ready, said in the school's terms
 *
 * A relay is ready when it holds **at least two teams and every team has a runner
 * on every leg** — the same verdict the server puts on the event list as
 * `relayReady`, read here from the board the page already fetches for every relay,
 * so the card can name *which* team is short rather than only that something is.
 * The line names how many teams the relay has and which of them is missing
 * runners, and a card without it is one that can be run and marked.
 *
 * ## One component, both roles
 *
 * The teacher endpoint family admits ADMIN and TEACHER alike and runs the same
 * services, so the role only picks which family is called (see api.relayBase). What
 * changes an event is **not** shared: creating a relay is hasAnyRole('ADMIN',
 * 'MANAGER'), so a teacher is not offered a grid's button at all; deleting a relay
 * is hasRole('ADMIN'); and the marking sheet PDF of an event is ADMIN, MANAGER or
 * HELPER, so a teacher may not print one either. A teacher is therefore **told that
 * plainly, up front** — the same treatment the per-event board gives the kind it
 * holds back — rather than being offered a control whose only outcome is a 403. What
 * a teacher *can* do from here is open any relay's board and place their own
 * classes' students.
 *
 * ## An event with no teams is the normal starting state
 *
 * A relay begins undivided and holding no teams. That is a starting point, not a
 * fault: no relay can be run without two teams, so its card says so in the
 * readiness line and offers its board, and never renders the event as a failure or
 * an empty table.
 */
export default function RelayEventsList({ family }: { family: RelayFamily }) {
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
  /** The last failure per event, keyed by event id. */
  const [failures, setFailures] = useState<Record<number, EventFailure>>({});
  /** A sheet saved for an event, keyed by event id. */
  const [saved, setSaved] = useState<Record<number, string>>({});
  /** The name of the relay the last "delete this relay" press removed. */
  const [deleted, setDeleted] = useState<string | null>(null);
  /** What the last "create the missing class relays" run produced. */
  const [built, setBuilt] = useState<{ names: string[]; failed: string[] } | null>(null);

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
      // A relay is its own event category now, and its own family of types
      // (`RELAY_4X100M` / `RELAY_4X400M`). Either reading alone would do; asking
      // both is what guarantees no relay is ever left off this page — the category
      // is what the event itself now holds, and the type is the older spelling of
      // it that every relay response still carries.
      setEvents(all.filter(event => event.category === 'RELAY' || isRelayEventType(event.type)));
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
   * Reads every relay's board once, to answer the questions this page exists to
   * answer: **which relays are undivided, which already hold teams, and which are
   * not ready to be run?**
   *
   * `GET /api/events` carries the kind on the event itself, so the division is
   * free. The **team count is not** — no list response carries it — so it costs
   * one request per relay. That is twelve requests for the live programme, made
   * **once**, in parallel, only for the relays that were just listed, and never
   * again per render or per keystroke. It buys the answers this page would
   * otherwise be unable to give, the readiness line among them: `teams[]` and the
   * runners on them are what say whether a relay is short.
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

  /**
   * What form a class relay is **scoped to**, or `''` when it takes one team per
   * class of its own grade.
   *
   * Read from the board and the event alike, and for the same reason `kindOf` is:
   * the board is re-read after every write, while the event in the list still
   * carries the scope it was loaded with. Without that, re-scoping a relay would
   * keep measuring it against the form it no longer holds — and would ask the
   * server to set the same form again on every press.
   */
  const formOf = (event: EventDTO): string => {
    const board = boardOf(event.id);
    return (board ? board.form : event.form) ?? '';
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
    setDeleted(null);
    try {
      const filename = await api.downloadEventSheets(
        event.id,
        `${slug(event.name)}-marking-sheets.pdf`
      );
      setSaved(previous => ({ ...previous, [event.id]: filename }));
    } catch (err) {
      const failure: EventFailure = {
        error: errorText(err, t('relayEvents.printFailed')),
        what: 'relayEvents.printFailed',
      };
      setFailures(previous => ({ ...previous, [event.id]: failure }));
    } finally {
      setBusy(null);
    }
  };

  /**
   * **Deletes one relay** — the event itself, with its teams and the runners named
   * on them, and its entries — after a confirmation that names the relay and how
   * many teams go with it.
   *
   * It is the one press that clears a relay the programme holds more than once: a
   * house cell whose grid made a second relay for it, or a relay made twice by hand,
   * is otherwise indistinguishable from the real one on this list, and nothing else
   * on either page removes a relay. It is offered on **every** relay of both pages,
   * so the office need not hunt the duplicate down on `/admin/events`.
   *
   * The card of a deleted relay goes with it, so the report of what was deleted is
   * the page's own notice rather than a line on a card that is no longer there. The
   * grid above recomputes its cells from what is left, which is what makes a relay
   * deleted by mistake offerable again.
   *
   * **ADMIN only** — {@code DELETE /api/admin/events/{id}} is `hasRole('ADMIN')`, so
   * a teacher is not offered the button at all — and nothing is deleted without the
   * confirmation, which names the relay because the reader pressed a card among
   * twelve.
   */
  const deleteRelay = async (event: EventDTO) => {
    /*
     * The board is read for the count the confirmation names, and **only** for it:
     * the boards arrive after the list does, so a press made in the first second
     * would otherwise be told the relay holds nothing when it holds four teams of
     * named runners. With no board to count, the confirmation says what goes without
     * putting a number on it.
     */
    const board = boardOf(event.id);
    const confirmation = board
      ? t('relayEvents.deleteRelayConfirm', { name: event.name, count: board.teams.length })
      : t('relayEvents.deleteRelayConfirmUnknown', { name: event.name });
    if (!confirm(confirmation)) return;
    setBusy(`delete-${event.id}`);
    setError(null);
    setDeleted(null);
    setSaved(previous => {
      const next = { ...previous };
      delete next[event.id];
      return next;
    });
    setFailures(previous => {
      const next = { ...previous };
      delete next[event.id];
      return next;
    });
    try {
      await api.deleteEvent(event.id);
      /*
       * The relay is gone from the programme: it leaves the list rather than being
       * re-read from the server, and the grid above stops counting it in the cell it
       * stood in — which is the whole point of deleting a duplicate.
       */
      setEvents(previous => previous.filter(candidate => candidate.id !== event.id));
      setDeleted(event.name);
    } catch (err) {
      const failure: EventFailure = {
        error: errorText(err, t('adminEvents.deleteFailed')),
        what: 'adminEvents.deleteFailed',
      };
      setFailures(previous => ({ ...previous, [event.id]: failure }));
    } finally {
      setBusy(null);
    }
  };

  /** True on the class relay page; false on the grade house one. */
  const classRelays = family === 'FORM';

  /** The rule this page makes, and the kind an event has to hold to be one of its own. */
  const familyKind: RelayTeamKind = family;

  /**
   * The relay a cell already has, if it has one — the grid's own distance, in the
   * page's own scope. Read from every event, not from the filtered list: the grids
   * are about the programme, not about what the filter happens to be showing.
   *
   * **Matched on the grid's type and this page's own axis, and nothing else.** A class
   * cell is the relay of that distance scoped to that form, whatever grade it is stored
   * under — a form is not a grade — and a house cell is the relay of that grade. A
   * 4x400M relay therefore never answers for the 4x100M grid, which is what makes each
   * page's two grids say the truth about two different distances — and what a house grid
   * of no particular distance could not do at all, reading every cell as missing.
   */
  const relayInCell = (type: string, cell: RelayCell): EventDTO | undefined =>
    events.find(event => {
      if (event.type !== type || kindOf(event) !== familyKind || event.sex !== cell.sex) {
        return false;
      }
      return classRelays ? formOf(event) === cell.key : event.grade === cell.grade;
    });

  /**
   * The grids this page holds, one per distance the school runs at this level:
   * **two on the class page** — the 4x100M and the 4x400M for every one of the six
   * forms in both divisions, twenty-four relays — and **two on the house page**, the
   * same two distances over Grades A to C and both divisions, twelve relays. Each
   * carries the cells that are missing from it, which is the whole of what its button
   * makes: nothing is created that already exists, so pressing a button twice creates
   * nothing the second time.
   *
   * `rowKeys` is the groups down the left of every grid here: the forms, or the grades.
   */
  const rowKeys = classRelays ? RELAY_FORMS : [...GRADES];
  const grids: RelayGrid[] = RELAY_TYPES.map(type => {
    const cells: RelayCell[] = [];
    rowKeys.forEach(key => RELAY_DIVISIONS.forEach(sex => {
      cells.push({
        key,
        label: classRelays ? t('relayEvents.formN', { form: key }) : label('grade', key),
        sex,
        grade: classRelays ? undefined : (key as Grade),
      });
    }));
    // The distance names the grid, read off a relay of **that very type** — the
    // server's own label — so a reader of either grid knows which race they are
    // looking at without the page spelling 4x100M and 4x400M out a second time. A
    // programme holding no relay of this distance yet has no label to read, and the
    // grid then names its own type rather than borrowing the other distance's label,
    // which would put the wrong race over the table.
    const named = events.find(event => event.type === type);
    return {
      type,
      title: t(classRelays ? 'relayEvents.gridTitle' : 'relayEvents.gridTitleHouse', {
        type: named ? named.typeLabel : type,
      }),
      rows: rowKeys.map(key => ({
        key,
        label: classRelays ? t('relayEvents.formN', { form: key }) : label('grade', key),
      })),
      missing: isAdmin ? cells.filter(cell => !relayInCell(type, cell)) : [],
      cells: cells.length,
    };
  });

  /**
   * **Creates every missing relay of one grid, ready to fill.** For each cell: the
   * event itself — the grid's own distance, under this page's rule, scoped to the
   * cell's form or belonging to its grade — and then its derive, so it arrives
   * holding its teams (1A, 1B, … on the class page, `C Grade Yellow`, … on the
   * house one) and a student can be added to a team or removed again on its own
   * board.
   *
   * **Each cell is made from itself.** The distance is the grid's, the grade is the
   * cell's own on the house page — Grade A's relay is the A grade relay — and the form
   * is the cell's own on the class page; a new relay copies nothing from another
   * distance or another grade. Only a cell with no relay of its distance anywhere to
   * learn the name convention from takes the server's own name, which is what the
   * panel says rather than leaving it for somebody to notice.
   *
   * One at a time, and each failure is reported with the server's own words: a
   * refusal for one relay must not stop the others being made, and the panel says
   * which.
   */
  const createMissingRelays = async (grid: RelayGrid) => {
    setBusy('create-missing');
    setError(null);
    setBuilt(null);
    const names: string[] = [];
    const failed: string[] = [];
    for (const cell of grid.missing) {
      /*
       * The same distance as this grid, in this cell: the other division of a house
       * cell, or — on a class cell, where the scope is the form rather than the grade —
       * any relay of that form at that distance. Nothing is taken from another distance
       * or another grade.
       */
      const siblings = events.filter(event => {
        if (kindOf(event) !== familyKind || event.type !== grid.type) return false;
        return classRelays ? formOf(event) === cell.key : event.grade === cell.grade;
      });
      const sibling = siblings.find(event => event.sex !== cell.sex) ?? siblings[0];
      // Whatever the new relay stands beside on the day, in the place and at the size
      // of: its own cell's relay when it has one, otherwise any relay at all — so a new
      // event lands on the sport day, not on today's date.
      const beside = sibling
        ?? events.find(event => kindOf(event) === familyKind)
        ?? events.find(event => event.category === 'RELAY');
      /*
       * The grid's own distance, which is now always one of the school's two: a grid
       * is built per distance on both pages, so a relay made for a cell can never be
       * made at the other distance — a 4x400M cell makes a 4x400M.
       */
      const type = grid.type;
      /*
       * A class relay's name is its sibling's with the division changed — the pair
       * reads as one set whatever convention the school named its relays by. A house
       * relay takes the server's own name (`Boys 4x100M Relay · C Grade`), which is
       * already the school's way of naming one.
       */
      const name = classRelays && sibling ? twinName(sibling.name, cell.sex) : undefined;
      // The cell's own grade: a house cell is that grade by definition, and a class
      // cell keeps the grade the school already files that form's relay under.
      const grade = sibling?.grade ?? cell.grade ?? formBandGrade(cell.key);
      let created: EventDTO | null = null;
      try {
        created = await api.createEvent({
          // Absent means the server names it, which is what a name we could not
          // derive from a sibling should do.
          name,
          type,
          sex: cell.sex,
          grade,
          form: classRelays ? cell.key : '',
          relayTeamKind: familyKind,
          // The same day, place and size as the relay it stands beside.
          eventDate: beside?.eventDate,
          location: beside?.location,
          groupSize: beside?.groupSize,
          maxParticipants: beside?.maxParticipants,
          enabled: true,
        });
        await api.deriveRelayTeams(created.id, false, role);
        names.push(created.name);
      } catch (err) {
        /*
         * Two different failures, said as two different things. An event that was
         * made but not divided EXISTS — it is on the page below with its own rule
         * button — so reporting it as "not created" would send the reader looking for
         * something that is already there.
         */
        failed.push(t(created
          ? 'relayEvents.createMissingDivideFailed'
          : 'relayEvents.createMissingOneFailed', {
          name: created?.name ?? name ?? cell.label,
          reason: errorText(err, t('relayEvents.makeFailed')),
        }));
      }
    }
    // Re-read the programme: the new relays are events like any other, and the
    // boards that were read for the old list say nothing about them.
    await load();
    setBuilt({ names, failed });
    setBusy(null);
  };

  /**
   * The filter, as the value it narrows on: `''` lists every relay of this family,
   * a form (`1`..`6`) or a grade (`A`..`C`) lists one group of them, and `none` is
   * the class relays that carry no form at all — the older rule, one team per class
   * of the event's own grade.
   */
  const [scope, setScope] = useState<string>('');

  /**
   * The relays this page lists: **its own family, plus any relay still undivided**.
   *
   * A relay with no kind has not been assigned to a family yet, and it is listed on
   * both pages so that neither hides an event the school still holds: its card
   * carries its board and its readiness wherever it is read. Its kind is set on the
   * event's own form. A relay of the other family is not listed here at all — it
   * belongs to the other page, where its card says the same thing.
   */
  const listed = events.filter(event => {
    const kind = kindOf(event);
    return kind === family || kind === '';
  });

  /** What this page's filter narrows on: the form scope, or the event's grade. */
  const scopeValueOf = (event: EventDTO): string =>
    classRelays ? formOf(event) : event.grade ?? '';

  const relayEvents = listed.filter(event => {
    if (scope === '') return true;
    if (classRelays && scope === 'none') return scopeValueOf(event) === '';
    return scopeValueOf(event) === scope;
  });

  /** True when a class relay on this page carries no form — the `none` option. */
  const anyUnscoped = classRelays && listed.some(event => scopeValueOf(event) === '');

  /**
   * The events grouped by this page's own axis — form (1 to 6, then the unscoped
   * ones) on the class page, grade (A to C) on the house one — so a reader sees the
   * shape of the programme rather than one flat list. A group with nothing in it is
   * not drawn; the filter is what asks for one form or one grade on its own.
   */
  const groups: EventGroup[] = [];
  const groupOrder = classRelays ? ['1', '2', '3', '4', '5', '6', ''] : [...GRADES];
  groupOrder.forEach(key => {
    const rows = relayEvents.filter(event => scopeValueOf(event) === key);
    if (rows.length === 0) return;
    groups.push({
      key: key === '' ? 'no-form' : key,
      label: classRelays
        ? key === ''
          ? t('relayEvents.groupNoForm')
          : t('relayEvents.formN', { form: key })
        : label('grade', key),
      events: rows,
    });
  });

  /**
   * What the last press on one event produced: the marking sheet it saved, or the
   * server's own wording of why it was refused. A print clears the deletion notice,
   * so this is the one line the card carries and it is always about the press the
   * reader just made. A **deleted** relay has no card left to carry a line, so its
   * report is the page's own notice (see `deleted`).
   */
  const outcomeFor = (event: EventDTO): ReactNode => {
    const failure = failures[event.id];
    const downloaded = saved[event.id];

    if (failure) {
      return (
        <div className="alert alert-error">
          <strong>{t(failure.what)}</strong>
          <p>{failure.error}</p>
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

    return null;
  };

  /**
   * **The card's title: the axis this page is about**, standing where the relay's
   * own name puts its scope.
   *
   * A relay's stored name carries the scope it was made for, and the live names
   * come in both shapes: `Boys 4x400M Relay - Form 3` for a seed relay, `Girls
   * 4x400M Relay · C Grade` for one the school named after the relay was re-scoped
   * to a form. The grade is **decorative on the form page** — a Form 1 relay takes
   * `1A` to `1D` whatever grade those students are in, so a form card reading `C
   * Grade` names the one thing that does not decide who runs.
   *
   * So the class page shows the **form** the relay is scoped to, and the scope the
   * name already carried is *replaced* rather than stacked: `… · C Grade` becomes
   * `… · Form 1`, and a name that already says `Form 3` stays a single `Form 3` —
   * never `Form 3 · Form 3`. A relay with no form keeps its own name, because
   * there the grade really is its scope.
   *
   * The house page keeps the name exactly as the school wrote it: there the grade
   * **is** the point, and the name already says it.
   */
  const titleFor = (event: EventDTO): string => {
    if (!classRelays) return event.name;
    const form = formOf(event);
    // A relay scoped to no form keeps the older rule and is scoped by its own
    // grade, so its name is the honest axis — exactly as on the grade page.
    if (!form) return event.name;
    // A name that already says this form, wherever it says it, is the school's own
    // line: `… - Form 3` is kept as it stands, and `Form 3` never becomes
    // `Form 3 · Form 3`.
    if (new RegExp(`\\bForm\\s*${form}\\b`, 'i').test(event.name)) return event.name;
    const scope = NAME_SCOPE_AT_END.exec(event.name);
    const base = (scope ? event.name.slice(0, scope.index) : event.name).trim();
    return base ? `${base} · ${t('relayEvents.formN', { form })}` : event.name;
  };

  /**
   * What a relay is **short of**, as the school counts it, or `null` when it is
   * ready to be run and marked.
   *
   * Ready means exactly two things, and both are read from the board this page
   * already fetches for every relay: **at least two teams** — one team is not a
   * relay, and a single team cannot be raced against itself — and **every team
   * holding a runner for every leg**. The per-team verdict is the server's own
   * `complete` (true once each leg has a runner, reserves not counted); where a
   * response carries none, the members are counted against the team's `legCount`.
   * Nothing here needed a new API field.
   */
  const shortfallOf = (board: RelayEventTeamsDTO): RelayShortfall | null => {
    const legs = board.legsPerTeam ?? 0;
    const shortTeams = board.teams
      .filter(team => {
        if (team.complete !== undefined) return !team.complete;
        const running = team.members.filter(member => !member.reserve).length;
        return running < (team.legCount ?? legs);
      })
      .map(team => ({
        label: team.label || String(team.id),
        runners: team.members.filter(member => !member.reserve).length,
        needed: team.legCount ?? legs,
      }));
    if (board.teams.length >= TEAMS_NEEDED && shortTeams.length === 0) return null;
    return { teams: board.teams.length, shortTeams };
  };

  /**
   * **A relay that is not ready, said in the school's terms** — how many teams it
   * has, and which of them is short of its runners — or nothing at all on a relay
   * that can be run, so a card without this line is one that is ready.
   *
   * It is a warning and nothing more: a relay below two teams is the normal state
   * of one that has just been made, and the runner who is missing is placed on the
   * event's own board, which the card links to. What is short of its runners is
   * named the way the per-event board names it (`relay.shortOfLegs`), so the two
   * pages read the same.
   */
  const readinessFor = (event: EventDTO): ReactNode => {
    const board = boardOf(event.id);
    if (!board) return null;
    const shortfall = shortfallOf(board);
    if (!shortfall) return null;
    return (
      <div className="alert alert-warning mt-2">
        <strong>{t('relayEvents.notReadyTitle')}</strong>
        <p>{t('relayEvents.notReadyTeams', { count: shortfall.teams, needed: TEAMS_NEEDED })}</p>
        {shortfall.shortTeams.length > 0 && (
          <ul className="mt-1">
            {shortfall.shortTeams.map(team => (
              <li key={team.label}>
                {team.label}: {t('relay.shortOfLegs', {
                  missing: Math.max(1, team.needed - team.runners),
                })}
              </li>
            ))}
          </ul>
        )}
      </div>
    );
  };

  /**
   * Each event's own controls: its board, where the runners of its teams are placed
   * or moved; for an administrator, the deletion of the relay itself, its marking
   * sheets and the print run.
   *
   * The making of an event's teams used to be offered here too: a form picker and
   * one button per rule, which set the event's kind and then derived its teams.
   * That block is **gone** — the grids above the list are what make the programme
   * now, one press for every relay of a distance — and what is left on the card is
   * the event, the way into it, and the one press that **deletes the relay** when the
   * programme holds it twice.
   *
   * **Delete this relay** is offered on every relay of both pages: a relay with no
   * teams is as deletable as one with four, because the duplicate a grid made carries
   * no teams at all, and nothing else on either page removes a relay.
   */
  const linksFor = (event: EventDTO) => (
    <div className="pill-actions mt-3">
      <Link href={`/admin/events/${event.id}/relay`} className="btn btn-sm btn-secondary">
        {t('relay.openBoard')}
      </Link>
      {isAdmin && (
        <button
          type="button"
          className="btn btn-sm btn-danger"
          disabled={busy !== null}
          onClick={() => deleteRelay(event)}
        >
          {busy === `delete-${event.id}` ? t('common.processing') : t('relayEvents.deleteRelay')}
        </button>
      )}
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
  );

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
          {/*
            What a class relay is scoped to, said out loud. The grade badge above
            is the event's own grade, which no longer decides who may run once a
            form is set — a Form 1 relay's 1A to 1D teams can be of any grade — so
            the scope is named here rather than left to whichever axis the reader
            happens to be on.
          */}
          {kind === 'FORM' && (
            <div>
              <strong>{t('relay.form')}:</strong>{' '}
              {formOf(event)
                ? t('relayEvents.formN', { form: formOf(event) })
                : t('relayEvents.anyForm')}
            </div>
          )}
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

  /**
   * One event's card, assembled from the pieces above.
   *
   * The title is this page's own axis — the form on the class page, the grade on
   * the house one (see `titleFor`) — and the readiness line stands with the facts,
   * so a relay short of teams says so where its counts are.
   */
  const eventCard = (event: EventDTO) => {
    const card = (
      <div key={event.id} className="card">
        <div className="flex justify-between items-center">
          <h3>{titleFor(event)}</h3>
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
        {readinessFor(event)}
        {linksFor(event)}
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
        <h1 className="page-title">
          {t(classRelays ? 'relayEvents.formTitle' : 'relayEvents.houseTitle')}
        </h1>
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
      <p className="muted">
        {t(classRelays ? 'relayEvents.formSubtitle' : 'relayEvents.houseSubtitle')}
      </p>

      {/* The two families are two pages, so each says which one it is and links to
          the other — the same pair of tabs the results page uses. */}
      <div className="grid-toolbar">
        <Link
          href="/admin/relay-events/form"
          className={`btn btn-sm ${classRelays ? 'btn-primary' : 'btn-secondary'}`}
        >
          {t('nav.relayFormEvents')}
        </Link>
        <Link
          href="/admin/relay-events/grade"
          className={`btn btn-sm ${classRelays ? 'btn-secondary' : 'btn-primary'}`}
        >
          {t('nav.relayHouseEvents')}
        </Link>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {/* The one report that cannot live on a card: the relay a press deleted is no
          longer on the page, so what was deleted is said where the page says things. */}
      {deleted && (
        <div className="alert alert-success">{t('adminEvents.deletedNotice', { name: deleted })}</div>
      )}

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

      {/*
        The relays the programme should hold, one grid per distance the school runs at
        this level. **Both pages hold two** — the 4x100M and the 4x400M: on the class
        page over Forms 1 to 6 × boys and girls, so a form class has a relay of both
        distances in both divisions (twenty-four relays), and on the house page over
        Grades A to C × boys and girls (twelve). Each grid makes the relays of its own
        distance under this page's own rule, so the school's whole programme is one
        press away on either page. ADMIN only, because creating an event is
        hasAnyRole('ADMIN','MANAGER') and a teacher cannot press it anyway.
      */}
      {isAdmin && grids.map(grid => (
        <div className="card" key={grid.type}>
          <div className="flex justify-between items-center">
            <h2>{grid.title}</h2>
            <div className="pill-actions">
              <span className="badge badge-info">
                {t(classRelays ? 'relayEvents.createMissingCount' : 'relayEvents.createMissingCountHouse', {
                  have: grid.cells - grid.missing.length,
                  wanted: grid.cells,
                })}
              </span>
            </div>
          </div>
          <p className="muted mt-2">
            {t(classRelays ? 'relayEvents.createMissingHint' : 'relayEvents.createMissingHouseHint')}
          </p>

          <div className="table-wrap mt-2">
            <table>
              <thead>
                <tr>
                  <th>{classRelays ? t('relay.form') : t('marks.grade')}</th>
                  {RELAY_DIVISIONS.map(sex => (
                    <th key={sex}>{label('sex', sex)}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {grid.rows.map(row => (
                  <tr key={row.key}>
                    <td>{row.label}</td>
                    {RELAY_DIVISIONS.map(sex => {
                      const there = relayInCell(grid.type, {
                        key: row.key,
                        label: row.label,
                        sex,
                        grade: classRelays ? undefined : (row.key as Grade),
                      });
                      return (
                        <td key={sex}>
                          {there ? (
                            /*
                             * The relay's title, not its stored name: on the class page
                             * the stored names of several form relays still say the grade
                             * they were filed under (`… · B Grade` on a Form 3 relay), and
                             * a grid of class relays must not read like a grid of grade
                             * ones. `titleFor` is the same line the card's own heading
                             * shows, so the grid and the card name a relay identically.
                             */
                            <span className="badge badge-success">{titleFor(there)}</span>
                          ) : (
                            <span className="badge badge-warning">
                              {t('relayEvents.createMissingCell')}
                            </span>
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <p className="muted mt-2">
            {t(classRelays ? 'relayEvents.createMissingRule' : 'relayEvents.createMissingHouseRule')}
          </p>

          {grid.missing.length === 0 ? (
            <p className="muted mt-2">{t('relayEvents.createMissingNone')}</p>
          ) : (
            <div className="pill-actions mt-3">
              <button
                type="button"
                className="btn btn-primary"
                disabled={busy !== null}
                onClick={() => createMissingRelays(grid)}
              >
                {busy === 'create-missing'
                  ? t('common.processing')
                  : t('relayEvents.createMissingButton', { count: grid.missing.length })}
              </button>
            </div>
          )}
        </div>
      ))}

      {/* What the last press produced, said once rather than under either grid: the
          report names the relays themselves, so it belongs to the page. */}
      {built && (
        <div className="alert alert-success">
          <strong>{t('relayEvents.createMissingDone', { count: built.names.length })}</strong>
          {built.names.length > 0 && (
            <ul className="mt-1">
              {built.names.map(name => <li key={name}>{name}</li>)}
            </ul>
          )}
          {/* Where the students go, said where the relays were just made. */}
          <p className="muted mt-1">{t('relayEvents.createMissingNext')}</p>
        </div>
      )}
      {built && built.failed.length > 0 && (
        <div className="alert alert-error">
          <strong>{t('relayEvents.createMissingFailed')}</strong>
          <ul className="mt-1">
            {built.failed.map(line => <li key={line}>{line}</li>)}
          </ul>
        </div>
      )}

      <div className="card">
        <div className="flex justify-between items-center">
          <h2>{t('relayEvents.listTitle')}</h2>
          <div className="pill-actions">
            {/* The filter on this family's own axis: which form, or which grade. */}
            <label className="muted" htmlFor="relay-events-scope">
              {t(classRelays ? 'relayEvents.filterForm' : 'relayEvents.filterGrade')}
              <select
                id="relay-events-scope"
                className="ml-1"
                value={scope}
                onChange={changed => setScope(changed.target.value)}
              >
                <option value="">
                  {t(classRelays ? 'relayEvents.allForms' : 'relayEvents.allGrades')}
                </option>
                {classRelays
                  ? ['1', '2', '3', '4', '5', '6'].map(form => (
                      <option key={form} value={form}>
                        {t('relayEvents.formN', { form })}
                      </option>
                    ))
                  : GRADES.map(grade => (
                      <option key={grade} value={grade}>
                        {label('grade', grade)}
                      </option>
                    ))}
                {anyUnscoped && (
                  <option value="none">{t('relayEvents.groupNoForm')}</option>
                )}
              </select>
            </label>
            <span className="badge badge-info">
              {t('relayEvents.eventCount', { count: relayEvents.length })}
            </span>
            {boardsLoading && <span className="muted">{t('relayEvents.readingBoards')}</span>}
          </div>
        </div>
        <p className="muted mt-2">{t('relayEvents.listHint')}</p>
      </div>

      {listed.length === 0 && (
        <div className="card">
          {/* Nothing of this family on the programme at all, which is different from
              a filter that matches nothing — and a relay that is still undivided is
              listed here, so it is never the second case by accident. */}
          <p className="muted">
            {t(classRelays ? 'relayEvents.noFormRelays' : 'relayEvents.noHouseRelays')}
          </p>
        </div>
      )}

      {listed.length > 0 && relayEvents.length === 0 && (
        <div className="card">
          <p className="muted">{t('relayEvents.filterEmpty')}</p>
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
