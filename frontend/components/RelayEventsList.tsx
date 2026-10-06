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
  RelayTeamDerivationDTO,
  RelayTeamKind,
  Role,
} from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';

/** The two families of relay, one page each: class teams, or grade x house teams. */
export type RelayFamily = RelayTeamKind;

/** The six forms the school's class relays are run by, in order. */
const RELAY_FORMS: ReadonlyArray<string> = ['1', '2', '3', '4', '5', '6'];

/** The two divisions a class relay is run in: a boys relay and a girls relay. */
const RELAY_DIVISIONS: ReadonlyArray<EventSex> = ['MALE', 'FEMALE'];

/** What a form that has no relay of its own starts as. */
const DEFAULT_CLASS_RELAY_TYPE = 'RELAY_4X100M';

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
 * kind has not been assigned to a family yet, and whichever page's rule is pressed
 * is the family it joins, so hiding it from both would hide the only control that
 * can divide it. The list is grouped by the page's own axis (form, or grade) and the
 * filter narrows it to one of them.
 *
 * A FORM relay may also be **scoped to a form**. A form is not a grade: a relay
 * scoped to Form 1 takes whoever is in Form 1 whatever grade they are, so its
 * teams are that form's classes across every grade — `1A`, `1B`, `1C`, `1D`. Left
 * unscoped (no form), a FORM relay keeps the older rule and takes one team per
 * class of **the event's own grade**. The form is chosen with the picker on the
 * card and sent with the kind.
 *
 * The two pages together are the index the per-event board at
 * /admin/events/[id]/relay never had: that board shows the teams of **one** event,
 * so seeing which of the programme's relays is still undivided meant opening
 * twelve pages.
 *
 * ## Setting the kind, then deriving — two calls, in that order
 *
 * Deriving reads the kind **the event already holds**: the derive endpoint takes
 * no kind at all. Calling it on an event whose kind is not the one the button
 * names would therefore make the wrong teams and report success. The kind is a
 * field of the event, set by PUT on the event itself — and the **form** a class
 * relay is scoped to is a field of that same call, because a form only ever means
 * something to a FORM kind. So "make the teams by grade and house" is **two calls:
 * set the kind, then derive**, and the derive is only sent once the kind — and,
 * for a class relay, the form — is settled. If the first call is refused the
 * derive is **not** sent at all — there is nothing to derive into, and sending it
 * would either write the wrong teams or fail a second time for a reason that hides
 * the first.
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
 * two calls. Nothing is cleared without that confirmation. Both rules stay on both
 * pages for that reason: the second button is how an event of one family is moved
 * to the other, and the page it lands on is named by the outcome it reports.
 *
 * ## One component, both roles
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
 * own classes' students.
 *
 * ## An event with no teams is the normal starting state
 *
 * A relay begins undivided and holding no teams. That is a starting point, not a
 * fault, so it is described as one — with the form picker and the two rule buttons
 * right beside it, which is where the reader sets the scope the teams are then made
 * under — and never rendered as a failure or an empty table.
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
  /** The last outcome per event, keyed by event id. */
  const [outcomes, setOutcomes] = useState<Record<number, EventOutcome>>({});
  /**
   * The form chosen for each event, before the button is pressed.
   *
   * A form relay is not a grade relay: `1` yields the classes of Form 1 across
   * every grade (1A, 1B, 1C, 1D), so the school picks the form here rather than
   * relying on the event's own grade.
   */
  const [forms, setForms] = useState<Record<number, string>>({});
  /** The last refusal per event, keyed by event id. */
  const [failures, setFailures] = useState<Record<number, EventFailure>>({});
  /** A sheet saved for an event, keyed by event id. */
  const [saved, setSaved] = useState<Record<number, string>>({});
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
   * The whole of one button: **set the kind — and, for a class relay, the form it
   * is scoped to — unless the event already holds it, then derive.**
   *
   * The order is the point. Deriving reads the scope the event holds, its kind and
   * its form together, so the derive is only sent once that scope is settled — and
   * if setting it is refused, the derive is never sent. That refusal is kept as the
   * event's failure, with the server's own wording untouched.
   */
  const makeTeams = useCallback(
    async (event: EventDTO, kind: RelayTeamKind, form?: string) => {
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
        /*
         * A form relay carries the form the school chose. It is sent whenever the
         * form differs, not only when the kind does — otherwise changing a Form 1
         * relay to Form 2 would refine nothing and quietly leave the old classes.
         * A house relay clears it: a form means nothing to a house team, and the
         * server refuses one on a non-FORM relay.
         */
        const choosesForm = kind === 'FORM';
        const formChanged = choosesForm && (form ?? '') !== formOf(event);
        if (kindOf(event) !== kind || formChanged) {
          /*
           * Step one. This is the call the server refuses with a 409 while the
           * event already has teams, and that refusal is the reader's answer: it
           * names the event and the count and says what to do. It is shown as it
           * stands and the derive below is not attempted.
           */
          await api.updateEvent(event.id, choosesForm
            ? { relayTeamKind: kind, form }
            : { relayTeamKind: kind, form: '' });
        }
        /*
         * Step two, reached only when the scope now stands. Deriving is additive,
         * so re-scoping a form relay would leave the classes of the form it just
         * left behind as empty teams — and one short team holds the whole relay
         * back from being marked. Pruning drops exactly those and only those: a
         * team somebody runs in is never dropped, and the server reports how many
         * it kept for that reason.
         */
        const derived = await api.deriveRelayTeams(event.id, formChanged, role);
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
    // `kindOf` and `formOf` read the boards, which the list below covers.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [boards, role, t]
  );

  /**
   * The way out of the refusal: remove this event's teams, then make them by the
   * rule that was refused. **Two deliberate steps, never one** — the teams hold
   * real selections, so this asks for confirmation and is offered only to an
   * administrator, who is the only role the endpoint admits.
   *
   * The form the reader picked is carried straight through, so the retry makes
   * exactly the teams the refused attempt was making. Letting it fall back to the
   * event's stored scope would quietly make a class relay by **grade** instead,
   * which is the one thing a class relay could already do — and the whole reason
   * the picker is here.
   */
  const clearAndMake = async (event: EventDTO, kind: RelayTeamKind, form?: string) => {
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
      await makeTeams(event, kind, form);
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

  /** True on the class relay page; false on the grade house one. */
  const classRelays = family === 'FORM';

  /**
   * The class relay a form and a division already have, if they have one. Read from
   * every event, not from the filtered list: the grid below is about the programme,
   * not about what the filter happens to be showing.
   */
  const classRelayOf = (form: string, sex: EventSex): EventDTO | undefined =>
    events.find(event => kindOf(event) === 'FORM' && formOf(event) === form
      && event.sex === sex);

  /**
   * The class relays the programme has not got yet: Forms 1 to 6, each in both
   * divisions, less the ones that are already there. The school runs twelve, and
   * this is the whole of what "create each from Form 1 to Form 6, boys and girls"
   * means — nothing is created that already exists, so pressing the button twice
   * creates nothing the second time.
   */
  const missingRelays: Array<{ form: string; sex: EventSex }> = [];
  if (classRelays && isAdmin) {
    RELAY_FORMS.forEach(form => RELAY_DIVISIONS.forEach(sex => {
      if (!classRelayOf(form, sex)) missingRelays.push({ form, sex });
    }));
  }

  /**
   * **Creates every missing class relay, ready to fill.** For each one: the event
   * itself — the class rule, scoped to its form, and named as its sibling is named —
   * and then the same derive the per-event button runs, so it arrives holding its
   * class teams (1A, 1B, …) and a student can be added to a team or removed again on
   * its own board.
   *
   * Two things are copied from the form's existing relay rather than invented: the
   * event type and the grade. A form's boys relay and girls relay are the same race,
   * so Form 1's Girls relay is the 4x100M its Boys relay is, in the grade the school
   * already keeps that form's relay in. Only a form with no relay at all falls back
   * to the 4x100M and the grade its age band runs, and the fallback is named on the
   * panel rather than left for somebody to notice.
   *
   * One at a time, and each failure is reported with the server's own words: a
   * refusal for one relay must not stop the other five being made, and the panel
   * says which.
   */
  const createMissingRelays = async () => {
    setBusy('create-missing');
    setError(null);
    setBuilt(null);
    const names: string[] = [];
    const failed: string[] = [];
    for (const cell of missingRelays) {
      // The other division of the same form is the twin to copy: the same race, the
      // same grade, and a name one word away.
      const sibling = classRelayOf(cell.form, cell.sex === 'MALE' ? 'FEMALE' : 'MALE')
        ?? events.find(event => kindOf(event) === 'FORM' && formOf(event) === cell.form);
      // Whatever the new relay stands beside on the day, in the place and at the size
      // of: its own sibling when it has one, otherwise a class relay, otherwise any
      // relay at all — so a new event lands on the sport day, not on today's date.
      const beside = sibling
        ?? events.find(event => kindOf(event) === 'FORM')
        ?? events.find(event => event.category === 'RELAY');
      const type = sibling?.type ?? DEFAULT_CLASS_RELAY_TYPE;
      const name = sibling ? twinName(sibling.name, cell.sex) : undefined;
      let created: EventDTO | null = null;
      try {
        created = await api.createEvent({
          // Absent means the server names it, which is what a name we could not
          // derive from a sibling should do.
          name,
          type,
          sex: cell.sex,
          grade: sibling?.grade ?? formBandGrade(cell.form),
          form: cell.form,
          relayTeamKind: 'FORM',
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
          name: created?.name ?? name ?? t('relayEvents.formN', { form: cell.form }),
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
   * A relay with no kind has not been assigned to a family yet, and the rule pressed
   * on its card is what assigns it — so it is listed on both pages, which is also
   * the only place it can be divided from. A relay of the other family is not listed
   * here at all: it belongs to the other page, and the second button on its card
   * there is how it is moved back.
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

  /**
   * The form picker and the two rule buttons, the way out of a refusal, and each
   * event's own links.
   */
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
          {isAdmin && (
            <label className="muted" htmlFor={`relay-form-${event.id}`}>
              {t('relay.kindForm')}
              <select
                id={`relay-form-${event.id}`}
                className="ml-1"
                value={forms[event.id] ?? formOf(event)}
                disabled={busy !== null}
                onChange={changed => setForms(previous => ({
                  ...previous, [event.id]: changed.target.value,
                }))}
              >
                {/* Empty keeps the older rule: one team per class of this event's grade. */}
                <option value="">{t('relayEvents.anyForm')}</option>
                {['1', '2', '3', '4', '5', '6'].map(form => (
                  <option key={form} value={form}>{t('relayEvents.formN', { form })}</option>
                ))}
              </select>
            </label>
          )}
          {/* This page's own rule is the primary button; the other family's is the
              secondary one, which is how an event is moved to the other page. */}
          <button
            type="button"
            className={`btn ${classRelays ? 'btn-primary' : 'btn-secondary'}`}
            disabled={busy !== null || !isAdmin}
            title={isAdmin ? undefined : t('relayEvents.teacherLimits')}
            onClick={() => makeTeams(event, 'FORM', forms[event.id] ?? formOf(event))}
          >
            {formLabel}
          </button>
          <button
            type="button"
            className={`btn ${classRelays ? 'btn-secondary' : 'btn-primary'}`}
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
              onClick={() => clearAndMake(
                event,
                failure.kind === 'HOUSE' ? 'HOUSE' : 'FORM',
                forms[event.id] ?? formOf(event)
              )}
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
          {/*
            What a class relay is scoped to, said out loud. The grade badge above
            is the event's own grade, which no longer decides who may run once a
            form is set — a Form 1 relay's 1A to 1D teams can be of any grade — so
            the scope is named here rather than left to the picker an administrator
            happens to be looking at.
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
        The twelve class relays the programme should hold, as a form x division grid,
        and the one press that makes the ones that are missing. Only the class relay
        page has it: the grid is its own axis, and creating a class relay is that
        page's job. ADMIN only, because creating an event is hasAnyRole('ADMIN',
        'MANAGER') and a teacher cannot press it anyway.
      */}
      {classRelays && isAdmin && (
        <div className="card">
          <div className="flex justify-between items-center">
            <h2>{t('relayEvents.createMissingTitle')}</h2>
            <div className="pill-actions">
              <span className="badge badge-info">
                {t('relayEvents.createMissingCount', {
                  have: RELAY_FORMS.length * RELAY_DIVISIONS.length - missingRelays.length,
                  wanted: RELAY_FORMS.length * RELAY_DIVISIONS.length,
                })}
              </span>
            </div>
          </div>
          <p className="muted mt-2">{t('relayEvents.createMissingHint')}</p>

          <div className="table-wrap mt-2">
            <table>
              <thead>
                <tr>
                  <th>{t('relay.form')}</th>
                  {RELAY_DIVISIONS.map(sex => (
                    <th key={sex}>{label('sex', sex)}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {RELAY_FORMS.map(form => (
                  <tr key={form}>
                    <td>{t('relayEvents.formN', { form })}</td>
                    {RELAY_DIVISIONS.map(sex => {
                      const there = classRelayOf(form, sex);
                      return (
                        <td key={sex}>
                          {there ? (
                            <span className="badge badge-success">{there.name}</span>
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

          <p className="muted mt-2">{t('relayEvents.createMissingRule')}</p>

          {missingRelays.length === 0 ? (
            <p className="muted mt-2">{t('relayEvents.createMissingNone')}</p>
          ) : (
            <div className="pill-actions mt-3">
              <button
                type="button"
                className="btn btn-primary"
                disabled={busy !== null}
                onClick={createMissingRelays}
              >
                {busy === 'create-missing'
                  ? t('common.processing')
                  : t('relayEvents.createMissingButton', { count: missingRelays.length })}
              </button>
            </div>
          )}

          {built && (
            <div className="alert alert-success mt-2">
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
            <div className="alert alert-error mt-2">
              <strong>{t('relayEvents.createMissingFailed')}</strong>
              <ul className="mt-1">
                {built.failed.map(line => <li key={line}>{line}</li>)}
              </ul>
            </div>
          )}
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
