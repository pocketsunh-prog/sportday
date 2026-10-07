'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  api,
  BulkMarkResultDTO,
  MarkEntryInput,
  MarkOutcome,
  MarkRowDTO,
  MarkSheetDTO,
} from '@/lib/api';
import {
  canonicalStopwatchTime,
  formatStopwatchTime,
  parseStopwatchTime,
} from '@/lib/stopwatch';
import { useI18n } from '@/lib/i18n';

/**
 * **A relay's marks, where the relay is: one time per team.**
 *
 * The line is the team, exactly as it is on the relay's marking sheet: the team's
 * name, and one stopwatch time written for its four runners together. That is the
 * shape the server already answers a relay with — `GET /api/events/{id}/marks`
 * returns one row per team, carrying `teamId`, `teamLabel`, the time already
 * recorded, the standard when the event has one and the server's own
 * `belowStandard` verdict — so this component **reads that endpoint and saves
 * through it** rather than inventing a second one. A relay has no heats and no
 * final, so the stage is always the heats (`HEAT`).
 *
 * ## Why it is here and not on the marking grid
 *
 * The marking grid at `/admin/marks` is about individual athletes: its rows are
 * athletes, its columns are the athlete's heat, lane, grade and class, and its
 * filters are the event's heats and grades. A relay has none of those — its lines
 * are teams and its one number is the team's — so a relay is **not offered there
 * at all** any more, and is keyed in here instead, on the relay's own board,
 * beside the teams and runners it belongs to.
 *
 * ## What a line holds, and what it refuses
 *
 * One box per team, in the school's own shape (`M.SS.mmm`, e.g. `1.04.123`), the
 * standard drawn under it when the event carries one, and the server's
 * below-standard badge on a saved mark. A remark and the ABS / DQ choices sit
 * beside it exactly as they do on the marking grid, so nothing a helper could
 * record before has been lost by moving the grid here. A time that is not a time
 * is named before anything is sent, and the server refuses the same text with the
 * same grammar. An empty box on a team that already holds a time clears it.
 *
 * **Every refusal is shown as the server wrote it.** A relay that is not ready to
 * be marked — fewer than two teams in the race, or a team in the race short of its
 * runners — is refused by the endpoint with the reason, and that reason names
 * *which team* is short, so it is put on screen as it stands rather than being
 * replaced with a generic failure.
 */
export default function RelayMarkEntry({
  eventId,
  onSaved,
}: {
  /** The relay whose teams are marked. */
  eventId: number;
  /** Called after a save, so the board around this grid can re-read its teams. */
  onSaved?: () => void;
}) {
  const { t, label } = useI18n();

  const [sheet, setSheet] = useState<MarkSheetDTO | null>(null);
  const [drafts, setDrafts] = useState<Record<number, RelayDraft>>({});
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [result, setResult] = useState<BulkMarkResultDTO | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  /** Ignores a stale response when the event changes mid-flight. */
  const requestRef = useRef(0);

  useEffect(() => {
    const requestId = requestRef.current + 1;
    requestRef.current = requestId;
    setLoading(true);
    setError(null);
    setResult(null);
    /*
     * The heats stage is the relay's whole grid: a relay is run straight to a
     * finish and has no drawn final, so there is no stage to choose and the sheet
     * is asked for the one way there is.
     */
    api
      .getMarkSheet(eventId, { stage: 'HEAT' })
      .then(data => {
        if (requestRef.current !== requestId) return;
        setSheet(data);
        setDrafts({});
      })
      .catch(err => {
        if (requestRef.current !== requestId) return;
        setSheet(null);
        setDrafts({});
        // The server's own sentence: it names the relay and, when a team is short,
        // which team — which is the one thing a generic message cannot say.
        setError(errorText(err, t('relayMark.loadFailed')));
      })
      .finally(() => {
        if (requestRef.current === requestId) setLoading(false);
      });
  }, [eventId, reloadToken, t]);

  /** The rows this sheet is drawn from, in the server's own order. */
  const rows = useMemo(() => sheet?.rows ?? [], [sheet]);

  /** True when every line is a team's — the only shape a ready relay answers with. */
  const teamLines = useMemo(
    () => rows.length > 0 && rows.every(row => row.teamId !== undefined && row.teamId !== null),
    [rows]
  );

  /**
   * Whether the box is the school's stopwatch shape (`M.SS.mmm`) rather than a
   * bare count of seconds. Read from the sheet, which reads it from the server's
   * own rule — both relays are timed this way.
   */
  const timeInMinutes = !!sheet?.timeInMinutes;

  const updateDraft = (row: MarkRowDTO, patch: Partial<RelayDraft>) => {
    const key = draftKey(row);
    setDrafts(previous => ({
      ...previous,
      [key]: { ...(previous[key] ?? draftFrom(row)), ...patch },
    }));
    setNotice(null);
  };

  /**
   * Types into a team's time. It is one value in one box, so emptying it leaves
   * the team with nothing typed — which is what clears the mark — and typing a
   * time takes the line back off ABS / DQ, exactly as on the individual grid.
   */
  const updateTime = (row: MarkRowDTO, value: string) => {
    const key = draftKey(row);
    setDrafts(previous => ({
      ...previous,
      [key]: { ...(previous[key] ?? draftFrom(row)), time: value, outcome: '' },
    }));
    setNotice(null);
  };

  /**
   * Records ABS or DQ in place of a time, or puts the line back to a time. The
   * outcome is the whole record for that team: there is no number to keep and the
   * box is emptied, and the Clear tick is dropped because "remove the record" and
   * "record an absence" are two different answers for the same team.
   */
  const updateOutcome = (row: MarkRowDTO, next: '' | MarkOutcome) => {
    const key = draftKey(row);
    setDrafts(previous => {
      const draft = previous[key] ?? draftFrom(row);
      if (next === '') return { ...previous, [key]: { ...draft, outcome: '' } };
      return {
        ...previous,
        [key]: { ...draft, outcome: next, time: '', clear: false },
      };
    });
    setNotice(null);
  };

  const dirty = useMemo(
    () =>
      rows.some(row => {
        const draft = drafts[draftKey(row)];
        if (!draft) return false;
        if (draft.clear) return true;
        if (draft.outcome !== serverOutcome(row)) return true;
        if (draft.notes !== (row.notes ?? '')) return true;
        // The same time written differently — `48.123` for a stored `0.48.123` — is
        // not a change, so both sides are compared in the one shape. A box that is
        // not a time at all is something somebody typed, so it counts as a change.
        const typed = canonicalStopwatchTime(draft.time);
        return typed === null ? draft.time.trim() !== '' : typed !== serverTime(row);
      }),
    [rows, drafts]
  );

  /**
   * Only the lines somebody actually touched are sent back, and a line is named by
   * its **team**: the payload carries the team's id as well as the runner the line
   * hangs off, because the one time belongs to the team and the server stores it
   * against the team. Three cases would otherwise lose data silently and are
   * handled explicitly — a value that is not a time is reported rather than
   * dropped, a remark with no record is reported, and emptying a box that held a
   * time means "remove it".
   */
  const buildRows = (): { rows: MarkEntryInput[]; problems: string[] } => {
    const payload: MarkEntryInput[] = [];
    const problems: string[] = [];

    rows.forEach(row => {
      const draft = drafts[draftKey(row)];
      if (!draft) return;
      const entry: MarkEntryInput = {
        userId: row.userId,
        ...(row.teamId !== undefined && row.teamId !== null ? { teamId: row.teamId } : {}),
      };
      const who = teamName(row);

      if (draft.clear) {
        payload.push({ ...entry, mark: null, clear: true });
        return;
      }

      const notes = draft.notes.trim();

      if (draft.outcome !== '') {
        payload.push({ ...entry, outcome: draft.outcome, mark: null, notes: notes || null });
        return;
      }

      const typed = draft.time.trim();
      if (typed === '') {
        if (hasServerValue(row)) payload.push({ ...entry, mark: null, clear: true });
        else if (notes) problems.push(t('marks.remarkNeedsRecord', { who }));
        return;
      }

      const time = parseStopwatchTime(typed);
      if (!time.ok) {
        problems.push(
          time.problem === 'seconds'
            ? t('marks.timeSecondsLimit', { who })
            : t('marks.timeBadShape', { who, value: typed })
        );
        return;
      }

      payload.push({ ...entry, outcome: 'RESULT', time: typed, notes: notes || null });
    });

    return { rows: payload, problems };
  };

  const handleSave = async () => {
    const { rows: payload, problems } = buildRows();
    if (problems.length > 0) {
      setNotice(null);
      setError(`${t('marks.fixBeforeSaving')} ${problems.join('; ')}`);
      return;
    }
    if (payload.length === 0) {
      setNotice(t('marks.nothingToSave'));
      return;
    }
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      // A relay is run straight to a finish, so its whole grid is the heats stage.
      const saved = await api.saveMarks(eventId, payload, 'HEAT');
      setResult(saved);
      setDrafts({});
      setNotice(
        t('marks.saveSummary', {
          saved: saved.saved,
          cleared: saved.cleared,
          skipped: saved.skipped,
          failed: saved.failed,
        })
      );
      setReloadToken(token => token + 1);
      onSaved?.();
    } catch (err) {
      // The server's own wording, including a relay that is not ready and which
      // team it is short of.
      setError(errorText(err, t('marks.saveFailed')));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="card">
      <div className="flex justify-between items-center">
        <h2>{t('relayMark.title')}</h2>
        <div className="pill-actions">
          {sheet && (
            <span className="badge badge-info">
              {t('marks.marked', { marked: sheet.markedCount, total: sheet.totalAthletes })}
            </span>
          )}
          {dirty && <span className="badge badge-warning">{t('marks.unsaved')}</span>}
          {loading && <span className="muted">{t('marks.loadingSheet')}</span>}
        </div>
      </div>
      <p className="muted mt-2">{t('relayMark.hint')}</p>

      {error && <div className="alert alert-error mt-2">{error}</div>}
      {notice && <div className="alert alert-success mt-2">{notice}</div>}

      {result && result.errors.length > 0 && (
        <div className="alert alert-error mt-2">
          <strong>{t('marks.saveErrors')}</strong>
          <ul className="mt-1">
            {result.errors.map(problem => (
              <li key={`${problem.userId ?? 'row'}-${problem.message}`}>{problem.message}</li>
            ))}
          </ul>
        </div>
      )}

      {!loading && rows.length === 0 && !error && (
        <p className="muted mt-2">{t('relayMark.noTeams')}</p>
      )}

      {rows.length > 0 && !teamLines && (
        /*
         * The endpoint is answering with athlete lines, which a relay only does
         * for an event that is not one. Rather than drawing a grid whose lines it
         * cannot name, say what was read: this is the endpoint's own shape, and a
         * reader who sees it knows the event is not the relay they expected.
         */
        <p className="muted mt-2">{t('relayMark.notTeamLines')}</p>
      )}

      {teamLines && (
        <>
          <div className="table-wrap mt-2">
            <table className="marks-table">
              <thead>
                <tr>
                  <th>{t('relay.team')}</th>
                  {/* A relay is timed on a stopwatch, so the box is the school's own
                      shape — `M.SS.mmm` — and the heading says which. */}
                  <th className="col-mark">
                    {t('marks.record')} ({timeInMinutes ? t('marks.timeFormat') : label('unit', sheet?.defaultUnit)})
                  </th>
                  <th className="col-remark">{t('marks.remark')}</th>
                  <th className="col-narrow">{t('marks.clearMark')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map(row => {
                  const draft = drafts[draftKey(row)] ?? draftFrom(row);
                  return (
                    <tr key={draftKey(row)} className={draft.clear ? 'cell-cleared' : undefined}>
                      <td>{teamName(row)}</td>
                      <td>
                        <input
                          type="text"
                          inputMode="decimal"
                          placeholder={t('marks.timePlaceholder')}
                          aria-label={`${t('marks.record')} ${teamName(row)}`}
                          value={draft.time}
                          disabled={draft.outcome !== ''}
                          onChange={event => updateTime(row, event.target.value)}
                        />
                        {/*
                          The standard the server holds for this event, drawn under
                          the box, with its own verdict once a mark is recorded —
                          so nothing is compared here and the grid and the
                          leaderboard cannot come to disagree.
                        */}
                        {row.standardLabel && (
                          <span
                            className={row.belowStandard ? 'badge badge-warning' : 'muted'}
                            style={{ display: 'block', marginTop: '0.25rem' }}
                          >
                            {row.belowStandard
                              ? t('marks.belowStandard', { standard: row.standardLabel })
                              : t('marks.standard', { standard: row.standardLabel })}
                          </span>
                        )}
                        {/* ABS / DQ sit beside the box, as on the individual grid. */}
                        <select
                          value={draft.outcome}
                          aria-label={`${t('marks.outcome')} ${teamName(row)}`}
                          onChange={event =>
                            updateOutcome(row, event.target.value as '' | MarkOutcome)
                          }
                        >
                          <option value="">{t('marks.outcomeResult')}</option>
                          <option value="ABS">{t('marks.outcomeAbs')}</option>
                          <option value="DQ">{t('marks.outcomeDq')}</option>
                        </select>
                      </td>
                      <td>
                        <input
                          type="text"
                          aria-label={`${t('marks.remark')} ${teamName(row)}`}
                          value={draft.notes}
                          onChange={event => updateDraft(row, { notes: event.target.value })}
                        />
                      </td>
                      <td>
                        <input
                          type="checkbox"
                          aria-label={`${t('marks.clearMark')} ${teamName(row)}`}
                          checked={draft.clear}
                          onChange={event => updateDraft(row, { clear: event.target.checked })}
                        />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          <div className="pill-actions mt-3">
            <button
              type="button"
              className="btn btn-primary"
              disabled={saving}
              onClick={handleSave}
            >
              {saving ? t('common.saving') : t('marks.saveAll')}
            </button>
            <button
              type="button"
              className="btn btn-secondary"
              disabled={loading}
              onClick={() => setReloadToken(token => token + 1)}
            >
              {loading ? t('common.loading') : t('common.refresh')}
            </button>
            <span className="muted">{t('marks.blankSkipped')}</span>
          </div>
        </>
      )}
    </div>
  );
}

/**
 * What somebody has typed for one team. Times stay strings while editing, so a
 * partial `1.0` is never clobbered.
 */
interface RelayDraft {
  /** The stopwatch time as typed: `1.04.123`, `0.48.123`, `48.123`. */
  time: string;
  notes: string;
  clear: boolean;
  /** `''` leaves the line a time; `ABS` / `DQ` stands in place of one. */
  outcome: '' | MarkOutcome;
}

function errorText(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback;
}

/**
 * The key a line's draft is held under: its **team**, or the runner the line
 * hangs off for a line with no team on it. The team is the line's identity — it is
 * what the sheet prints and what the mark belongs to — so two drafts can never
 * collide over one runner.
 */
function draftKey(row: MarkRowDTO): number {
  return row.teamId ?? row.userId;
}

/** The team's name as the school writes it, or the anchor runner's id. */
function teamName(row: MarkRowDTO): string {
  return row.teamLabel && row.teamLabel.trim() !== '' ? row.teamLabel : `#${row.userId}`;
}

/**
 * The stopwatch time a row arrived with, exactly as the box should show it: the
 * text the server wrote (`row.time`, from the same grammar that parses it back),
 * or the parts of a mark a server that does not carry the text yet would leave —
 * and blank on a team with no time at all.
 */
function serverTime(row: MarkRowDTO): string {
  if (row.time !== undefined && row.time !== null) return row.time;
  if (row.mark === null || row.mark === undefined) return '';
  return formatStopwatchTime(row.mark);
}

/** The outcome a row arrived with, in the same shape as a draft. */
function serverOutcome(row: MarkRowDTO): '' | MarkOutcome {
  return row.outcome === 'ABS' || row.outcome === 'DQ' ? row.outcome : '';
}

/** True when the server already holds something for this team. */
function hasServerValue(row: MarkRowDTO): boolean {
  if (serverTime(row) !== '') return true;
  return row.outcome === 'ABS' || row.outcome === 'DQ';
}

/** What the box and the remark cell hold for a team before anybody types. */
function draftFrom(row: MarkRowDTO): RelayDraft {
  return {
    time: serverTime(row),
    notes: row.notes ?? '',
    clear: false,
    outcome: serverOutcome(row),
  };
}
