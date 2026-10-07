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
  isRelayEvent,
  RelayEventTeamsDTO,
  RelayTeamDTO,
  RelayTeamKind,
  Role,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';
import type { MessageKey } from '@/lib/i18n';
import RelayMarkEntry from '@/components/RelayMarkEntry';

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
 * What a relay is short of, in the school's terms: how many teams are **in the
 * race** — the ones somebody has been named in — and the teams in it that have not
 * a runner on every leg. See `shortfallOf`.
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
 *       {@code /admin/relay-events/form}: one team per <strong>class that entered the
 *       relay</strong> ({@code 5A}, {@code 5B}), across the form the relay is scoped
 *       to, Forms 1 to 6, with a filter on each form;</li>
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
 * teams are **the classes its entrants are in**, across every grade — `1A`, `1B`,
 * `1C` when students from those three entered, and no team for a class nobody
 * entered from. Left
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
 * A relay is ready when it holds **at least two teams in the race and every one of
 * them has a runner on every leg** — the same verdict the server puts on the event
 * list as `relayReady`, read here from the board the page already fetches for every
 * relay, so the card can name *which* team is short rather than only that something
 * is. A team nobody has been named in is **not** in the race and does not hold the
 * relay back — the school's rule is two teams to four — while a team with even one
 * runner in it is judged. The line names how many teams are in the race and which of
 * them is missing runners, and a card without it is one that can be run and marked.
 *
 * ## One component, four roles, each offered exactly what the endpoints allow
 *
 * Three different endpoint families meet on this page, and each control is
 * offered only to the roles its own endpoint admits:
 *
 * <ul>
 *   <li>the <strong>relay boards</strong> — every card's counts, teams and
 *       readiness — come from {@code /api/admin/events/{id}/relay-teams} (ADMIN)
 *       and its {@code /api/teacher/**} twin (ADMIN or TEACHER), so the boards are
 *       read for an <strong>administrator and a teacher</strong> only. A manager
 *       or an input helper is shown the programme without them — the relays, their
 *       names and the way into each one — rather than a page of failed requests;</li>
 *   <li>a relay's <strong>marking sheets</strong> are ADMIN, MANAGER or HELPER
 *       ({@code EventGroupController}), so an administrator, a manager and a
 *       helper each get the card's own print button and the one press that prints the
 *       whole list in a single file, and a <strong>teacher</strong>
 *       is told plainly that printing is not theirs;</li>
 *   <li>a relay's <strong>marks</strong> are ADMIN, MANAGER or HELPER too
 *       ({@code GET}/{@code POST /api/events/{id}/marks}; there is no
 *       {@code /api/admin/events/{id}/marks}), so those three are offered the card's
 *       mark-entry panel and a <strong>teacher</strong> — who may build a relay and
 *       place its runners but may not key its times — is told so on the card;</li>
 *   <li>creating the programme's relays (the grids above) and
 *       <strong>deleting</strong> one are {@code hasAnyRole('ADMIN','MANAGER')} and
 *       {@code hasRole('ADMIN')}, so both are an administrator's.</li>
 * </ul>
 *
 * What every role can do from here is open a relay's board, which is where its
 * teams are filled. A relay's **marks are keyed in here**, on the relay's own card:
 * a relay is marked by team, one time for the four runners together, and that grid
 * (`RelayMarkEntry`) is opened by the card's *Key this relay's times* button rather
 * than living on the event's own board — one panel at a time, because the page lists
 * up to twenty-four relays and each has its own teams and its own save.
 *
 * ## A relay's sheets are printed from its own card, or from the whole list at once
 *
 * A relay is not on the print page at all any more and not in the whole-programme
 * print run: its paper is one line per team rather than one per athlete, and it
 * belongs here. Each card therefore carries the download of
 * {@code GET /api/events/{id}/sheets.pdf} for its own relay — which prints a
 * relay that has teams and no heats, and **refuses a relay with nothing to print
 * with the reason**, naming the team that is short. That refusal is shown exactly
 * as the server wrote it.
 *
 * And above the list stands **one press for the whole list**:
 * {@code GET /api/relay-events/sheets.pdf?eventIds=…} composes every relay this page
 * is showing into a single PDF — the relays the filter lets through, so the file is
 * the list on screen — and names, on the screen, every relay it had to leave out and
 * why, so a file short of a relay is never a surprise. A relay that cannot print is
 * therefore refused by the server with its own reason rather than dropped quietly.
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
  /**
   * The relay whose mark entry is open on its card, if any. **One at a time**, and it
   * is the same component the relay's own board used to carry: a grid of up to
   * twenty-four relays, each with its own teams and its own save, would be a wall of
   * inputs nobody could read, so the page offers the grid for the relay being worked on
   * and leaves the others as cards.
   */
  const [marking, setMarking] = useState<number | null>(null);
  /**
   * The file the combined relay print run saved, or `null` until it is pressed. The
   * relays it had to leave out are computed from the list at render time rather than
   * held here, so they are on screen before the press as well as after it.
   */
  const [printRunFile, setPrintRunFile] = useState<string | null>(null);

  const isAdmin = user?.role === 'ADMIN';
  /**
   * Who may read the relay boards: exactly the roles the relay-team endpoints
   * admit (`/api/admin/**` is ADMIN, `/api/teacher/**` is ADMIN or TEACHER). The
   * boards are **only fetched for them** — one request per relay is a real cost,
   * and a manager or a helper asking for them would only be refused twelve times
   * over.
   */
  const canReadBoard = isAdmin || user?.role === 'TEACHER';
  /**
   * Who may download a relay's marking sheets: exactly the roles
   * `GET /api/events/{id}/sheets.pdf` admits — ADMIN, MANAGER or HELPER.
   */
  const canPrintSheets =
    isAdmin || user?.role === 'MANAGER' || user?.role === 'HELPER';
  /**
   * Whether this reader has anything to do on this page: the board is an
   * administrator's or a teacher's, and a manager or an input helper is here for
   * the relay's own sheets and its teams' times, which their endpoints do admit.
   */
  const canWork = canReadBoard || canPrintSheets;
  /**
   * **Who may key a relay's times** — exactly the roles
   * `GET/POST /api/events/{id}/marks` admits: ADMIN, MANAGER and HELPER. A teacher
   * may build a relay and place its runners but may not record its marks, so the
   * grid is not offered to them and the reason is on the card instead. Nothing is
   * narrowed here and nothing widened; there is no `/api/admin/events/{id}/marks`.
   */
  const canKeyMarks = isAdmin || user?.role === 'MANAGER' || user?.role === 'HELPER';
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
      // A relay is read from both places the server says so, in the one helper
      // that owns that test (`isRelayEvent`): the category the event itself holds
      // now, and the type family every relay response still carries.
      setEvents(all.filter(isRelayEvent));
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
   *
   * **And no board is asked for at all by a role that may not read one** (see
   * `canReadBoard`). A manager and an input helper reach this page for the relay's
   * own sheets and its teams' times, and neither may open its board: twelve
   * refusals would tell them nothing twelve times over, so their cards carry no
   * counts instead and say so in one line.
   */
  const loadBoards = useCallback(
    async (list: EventDTO[]) => {
      // Only for a caller the board endpoints admit: everyone else is shown the
      // programme without the counts rather than a column of 403s.
      if (!canReadBoard || list.length === 0) {
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
    [role, canReadBoard, t]
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
   * **Prints one relay's marking sheets**: the file the server renders for this
   * event alone, `GET /api/events/{id}/sheets.pdf` — the one place a relay's paper
   * is printed from now. Its lines are the event's teams, one per team, so a relay
   * with teams and no heats prints perfectly well; a relay with nothing to print
   * is **refused with the reason**, and the reason names the team that is short
   * (or that the relay has too few teams in the race). That sentence is set on the
   * card exactly as the server wrote it — never replaced with a generic "print
   * failed" — because the reader has to know *which team* to go and fill.
   *
   * **ADMIN, MANAGER or HELPER** — the same three the endpoint admits. A teacher is
   * refused by it, so the control is not offered to them and the reason is on
   * screen instead.
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
   * **Prints every relay this page is showing, in one file** — one press, one PDF,
   * through `GET /api/relay-events/sheets.pdf?eventIds=…`.
   *
   * ## What it covers
   *
   * The **relays listed below**, as this page's own filter is showing them — not the
   * whole programme: the class page prints the class relays it lists and the house page
   * the house ones, so the file matches the count on the screen. The request names them,
   * so the run cannot quietly widen.
   *
   * ## A relay that cannot print is named, never dropped in silence
   *
   * The relays the event list reports as **not ready** (`relayReady === false`) are left
   * out of the request and named under the button with the server's own
   * `readinessReason` — the same verdict the readiness gate would refuse the run with,
   * read from the list the page already holds. The server is still the gate: if a relay
   * it is sent has become unprintable since the list was read, the whole request is
   * refused with every such relay's reason, shown here as it stands.
   *
   * **ADMIN, MANAGER or HELPER** — the three the endpoint admits. A teacher is not
   * offered the control; the note beside it says so.
   */
  const printAllSheets = async () => {
    const printable = relayEvents.filter(event => event.relayReady !== false);
    setBusy('print-all');
    setError(null);
    setDeleted(null);
    setPrintRunFile(null);
    if (printable.length === 0) {
      // Nothing to ask for: the warning under the button already names which relays
      // are missing what, so no request that could only be refused is sent.
      setBusy(null);
      return;
    }
    try {
      const filename = await api.downloadRelaySheets(
        printable.map(event => event.id),
        'sportday-relay-sheets.pdf'
      );
      setPrintRunFile(filename);
    } catch (err) {
      // The server's own sentence, which names every relay that has no sheet to print.
      setError(errorText(err, t('relayEvents.printAllFailed')));
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
   * **The relays the combined print run covers, split by whether they can print.**
   *
   * The list is `relayEvents` — this page's family, as the filter is showing it — read
   * against the server's own readiness verdict on the event list (`relayReady`, absent
   * meaning ready), which is the same fact the server's gate judges. The split is shown
   * on screen rather than discovered in the file: every relay left out is named with the
   * server's `readinessReason`, so a run that is short of a relay says why.
   */
  const printableRelays = relayEvents.filter(event => event.relayReady !== false);
  const skippedRelays = relayEvents
    .filter(event => event.relayReady === false)
    .map(event => ({
      id: event.id,
      name: titleFor(event),
      reason: event.readinessReason ?? t('relayEvents.notReadyUnknown'),
    }));

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
  /*
   * Declared as a function, not a `const` arrow, on purpose: this is called while the
   * page is still building its lists — the skipped-relay note needs each relay's title —
   * and a `const` would not be initialised yet at that point. A function declaration is
   * hoisted, so the call above works. Everything it reads (`t`, `formOf`, `classRelays`)
   * is already initialised by the time it runs.
   */
  function titleFor(event: EventDTO): string {
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
  }

  /**
   * What a relay is **short of**, as the school counts it, or `null` when it is
   * ready to be run and marked.
   *
   * Ready means exactly two things, and both are read from the board this page
   * already fetches for every relay: **at least two teams in the race** — one team
   * is not a relay, and a single team cannot be raced against itself — and **every
   * team in the race holding a runner for every leg**. A team nobody has been named
   * in is *not* in the race: the school's rule is two teams to four, an empty team
   * is one it may still fill, and it must not hold a race of two back — which is
   * also how the server reads it (`RelayReadiness`), and how the mark grid and the
   * marking sheet have always read it, since neither lists a team with no runners
   * in it. A team with even one runner *is* in the race and is judged below, so a
   * half-filled team still holds the relay back. The per-team verdict is the
   * server's own `complete` (true once each leg has a runner, reserves not counted);
   * where a response carries none, the members are counted against the team's
   * `legCount`. Nothing here needed a new API field.
   */
  const shortfallOf = (board: RelayEventTeamsDTO): RelayShortfall | null => {
    const legs = board.legsPerTeam ?? 0;
    const runningIn = (team: RelayTeamDTO) =>
      team.members.filter(member => !member.reserve).length;
    const inTheRace = board.teams.filter(team => runningIn(team) > 0);
    const shortTeams = inTheRace
      .filter(team => {
        if (team.complete !== undefined) return !team.complete;
        return runningIn(team) < (team.legCount ?? legs);
      })
      .map(team => ({
        label: team.label || String(team.id),
        runners: runningIn(team),
        needed: team.legCount ?? legs,
      }));
    if (inTheRace.length >= TEAMS_NEEDED && shortTeams.length === 0) return null;
    return { teams: inTheRace.length, shortTeams };
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
   * Each event's own controls: the way into **its own board** — where its teams
   * are filled and its teams' times are keyed in — the download of **its own
   * marking sheets**, and, for an administrator, the one press that deletes the
   * relay itself.
   *
   * There is **no link to the print page here any more**: a relay is not printed
   * from there (it is not on it, and it is not in its whole-programme run either),
   * and its paper is the card's own button above. The whole print run of the
   * individual events is still what the marking sheets are printed from for
   * everything that is not a relay, and the navigation carries it.
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
      {canPrintSheets ? (
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
      {/*
        Where a relay's times are keyed in: on this card, in the panel the button
        opens. It is offered to exactly the three roles the mark-entry endpoints
        admit — ADMIN, MANAGER and HELPER — and a teacher, who may build a relay but
        may not key its marks, is told so rather than shown a control whose only
        outcome would be a 403.
      */}
      {canKeyMarks ? (
        <button
          type="button"
          className="btn btn-sm btn-primary"
          disabled={busy !== null}
          onClick={() => setMarking(open => (open === event.id ? null : event.id))}
        >
          {marking === event.id ? t('relayMark.hide') : t('relayMark.keyTimes')}
        </button>
      ) : (
        <span className="muted">{t('relayMark.teacherLimit')}</span>
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
    // Nothing is being read at all for a role that may not read a board, so it is
    // "unknown" rather than a spinner that will never stop.
    if (!canReadBoard) teamCountText = t('relayEvents.countUnknown');
    else if (boardFailure) teamCountText = t('relayEvents.countUnknown');
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
        {/*
          A manager and an input helper are not shown counts, because the team
          board is not theirs to read. Said in one line rather than left as five
          blanks: what they are here for — the relay's sheets and its teams' times
          — is on the card and behind its board link.
        */}
        {!canReadBoard && (
          <p className="muted mt-2">{t('relayEvents.boardNotYours')}</p>
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
   *
   * **A relay's times are keyed in on its own card**, in the panel the card's
   * *Key this relay's times* button opens. The grid is the relay's marks as they have
   * always been read — one line per team, one time for the four runners together
   * (`RelayMarkEntry`, `GET`/`POST /api/events/{id}/marks`) — and it is offered here,
   * where the relays are listed, rather than on the event's own board. Only one is
   * open at a time: a page listing twenty-four relays, each with its own teams and its
   * own save, would otherwise be a wall of inputs.
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
        {canKeyMarks && marking === event.id && (
          <div className="mt-3">
            <RelayMarkEntry eventId={event.id} />
          </div>
        )}
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
          {/*
            No link to the print page: a relay is printed from its own card below,
            and it is neither listed on that page nor part of its whole-programme
            run. The navigation still carries the print run for everything else.
          */}
          {user?.role === 'TEACHER' ? (
            <Link href="/teacher" className="btn btn-secondary">
              {t('relay.backToTeacher')}
            </Link>
          ) : (
            <Link href="/admin" className="btn btn-secondary">
              {t('common.backToAdmin')}
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
        {!isAdmin && <p className="muted mt-2">{t('relayEvents.teacherLimits')}</p>}      </div>

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

        {/*
          **One press for every relay this page is showing.** The run covers the list
          below — the relays of this family that the filter lets through — and the
          request names them, so the file is the list on screen and not a programme that
          quietly differs from it. A relay with no markable sheet yet is left out and
          named with its reason underneath, so a file that is short of a relay is never a
          surprise; the cards' own buttons stay, for one relay at a time.
        */}
        {canPrintSheets ? (
          <>
            <div className="pill-actions mt-3">
              <button
                type="button"
                className="btn btn-primary"
                disabled={busy !== null || printableRelays.length === 0}
                onClick={printAllSheets}
              >
                {busy === 'print-all'
                  ? t('common.downloading')
                  : t('relayEvents.printAllSheets', { count: printableRelays.length })}
              </button>
              <span className="muted">
                {t('relayEvents.printAllScope', { count: relayEvents.length })}
              </span>
            </div>
            {printRunFile && (
              <div className="alert alert-success mt-2">
                {t('relayEvents.printAllDone', {
                  filename: printRunFile,
                  count: printableRelays.length,
                })}
              </div>
            )}
            {skippedRelays.length > 0 && (
              <div className="alert alert-warning mt-2">
                <strong>
                  {printableRelays.length === 0
                    ? t('relayEvents.printAllNone')
                    : t('relayEvents.printAllSkipped')}
                </strong>
                <ul className="mt-1">
                  {skippedRelays.map(relay => (
                    <li key={relay.id}>
                      {relay.name} — {relay.reason}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </>
        ) : (
          <p className="muted mt-2">{t('relayEvents.teacherPrintLimit')}</p>
        )}
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
