'use client';

import { Fragment, useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, RecordDTO, RecordSource } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';
import { formatDate } from '@/lib/format';

type Grouping = 'category' | 'event';

const GRADE_ORDER = ['A', 'B', 'C'];
const SEX_ORDER = ['FEMALE', 'MALE'];

/** The units a school record is measured in: seconds on the track, metres in the field. */
const UNIT_SUGGESTIONS = ['s', 'M'];

function sortRecords(records: RecordDTO[]): RecordDTO[] {
  return [...records].sort((a, b) => {
    if (a.eventTypeLabel !== b.eventTypeLabel) {
      return a.eventTypeLabel.localeCompare(b.eventTypeLabel);
    }
    const sexDiff = SEX_ORDER.indexOf(a.sex) - SEX_ORDER.indexOf(b.sex);
    if (sexDiff !== 0) return sexDiff;
    const aGrade = GRADE_ORDER.indexOf(a.grade);
    const bGrade = GRADE_ORDER.indexOf(b.grade);
    if (aGrade !== bGrade) return (aGrade < 0 ? 99 : aGrade) - (bGrade < 0 ? 99 : bGrade);
    return 0;
  });
}

function groupRecords(records: RecordDTO[], grouping: Grouping): Array<[string, RecordDTO[]]> {
  const groups = new Map<string, RecordDTO[]>();
  sortRecords(records).forEach(record => {
    const key =
      grouping === 'category' ? record.category : `${record.eventTypeLabel}|${record.eventType}`;
    const bucket = groups.get(key);
    if (bucket) {
      bucket.push(record);
    } else {
      groups.set(key, [record]);
    }
  });
  return Array.from(groups.entries());
}

const SOURCE_BADGE: Record<RecordSource, string> = {
  RESULT: 'badge badge-success',
  BASELINE: 'badge badge-warning',
  NONE: 'badge badge-danger',
};

/** What an administrator is typing into the baseline form of one row. */
interface BaselineForm {
  mark: string;
  unit: string;
  holderName: string;
  achievedOn: string;
}

const EMPTY_FORM: BaselineForm = { mark: '', unit: '', holderName: '', achievedOn: '' };

/** Written the way a programme writes it: metres in the field, seconds on the track. */
function defaultUnit(record: RecordDTO): string {
  return record.category === 'FIELD' ? 'M' : 's';
}

export default function RecordsPage() {
  const { user, isLoading } = useAuth();
  const { t, label } = useI18n();
  const router = useRouter();
  const [records, setRecords] = useState<RecordDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [grouping, setGrouping] = useState<Grouping>('category');
  const [rebuilding, setRebuilding] = useState(false);
  const [seeding, setSeeding] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  // Inline editing of the hand-entered mark (ADMIN only).
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<BaselineForm>(EMPTY_FORM);
  const [savingId, setSavingId] = useState<number | null>(null);
  const [clearingId, setClearingId] = useState<number | null>(null);

  const isAdmin = user?.role === 'ADMIN';

  const load = useCallback(async () => {
    const fresh = await api.getRecords();
    setRecords(fresh);
  }, []);

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    load()
      .catch((err: unknown) =>
        setError(err instanceof Error ? err.message : t('records.loadFailed'))
      )
      .finally(() => setLoading(false));
  }, [user, isLoading, router, load, t]);

  const refresh = async () => {
    setLoading(true);
    try {
      await load();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('records.loadFailed'));
    } finally {
      setLoading(false);
    }
  };

  const handleRebuild = async () => {
    if (!confirm(t('records.rebuildConfirm'))) return;
    setMessage('');
    setError('');
    setRebuilding(true);
    try {
      const result = await api.recomputeRecords();
      await load();
      setMessage(
        t('records.rebuilt', { count: result.records, rebuilt: result.recordsRebuilt })
      );
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('records.rebuildFailed'));
    } finally {
      setRebuilding(false);
    }
  };

  const handleSeed = async () => {
    setMessage('');
    setError('');
    setSeeding(true);
    try {
      const result = await api.seedRecords();
      await load();
      setMessage(
        result.recordsCreated > 0
          ? t('records.seeded', { created: result.recordsCreated, count: result.records })
          : t('records.seedNone', { count: result.records })
      );
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('records.seedFailed'));
    } finally {
      setSeeding(false);
    }
  };

  const startEdit = (record: RecordDTO) => {
    setMessage('');
    setError('');
    setEditingId(record.id);
    setForm({
      mark: record.manualMark !== undefined && record.manualMark !== null
        ? String(record.manualMark)
        : '',
      unit: record.manualUnit || record.unit || defaultUnit(record),
      holderName: record.manualHolderName || '',
      achievedOn: (record.manualAchievedOn || '').slice(0, 10),
    });
  };

  const cancelEdit = () => {
    setEditingId(null);
    setForm(EMPTY_FORM);
  };

  const updateForm = (key: keyof BaselineForm, value: string) => {
    setForm(prev => ({ ...prev, [key]: value }));
  };

  const handleSaveBaseline = async (record: RecordDTO) => {
    const raw = form.mark.trim();
    const mark = Number(raw);
    if (!raw || Number.isNaN(mark)) {
      setError(t('records.baselineMarkRequired'));
      return;
    }
    setMessage('');
    setError('');
    setSavingId(record.id);
    try {
      // Every field is sent: the server replaces the whole typed-in mark, so an
      // omitted holder name would be dropped rather than kept.
      await api.setRecordBaseline(record.id, {
        mark,
        unit: form.unit.trim() || null,
        holderName: form.holderName.trim() || null,
        achievedOn: form.achievedOn || null,
      });
      await load();
      setMessage(t('records.baselineSaved'));
      cancelEdit();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('records.baselineSaveFailed'));
    } finally {
      setSavingId(null);
    }
  };

  const handleClearBaseline = async (record: RecordDTO) => {
    const division = label('sex', record.sex) || record.sexLabel;
    const grade = label('grade.short', record.grade) || record.gradeLabel;
    if (
      !confirm(
        t('records.clearBaselineConfirm', {
          event: record.eventTypeLabel,
          division,
          grade,
        })
      )
    ) {
      return;
    }
    setMessage('');
    setError('');
    setClearingId(record.id);
    try {
      await api.clearRecordBaseline(record.id);
      await load();
      setMessage(t('records.baselineCleared'));
      if (editingId === record.id) cancelEdit();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('records.baselineClearFailed'));
    } finally {
      setClearingId(null);
    }
  };

  if (isLoading || !user || loading) {
    return <div>{t('common.loading')}</div>;
  }

  const groups = groupRecords(records, grouping);
  const columnCount = isAdmin ? 10 : 9;

  const sourceText = (source: RecordSource): string =>
    source === 'RESULT'
      ? t('records.sourceResult')
      : source === 'BASELINE'
        ? t('records.sourceBaseline')
        : t('records.sourceNone');

  return (
    <div>
      <h1 className="page-title">{t('records.title')}</h1>
      <p className="muted">{t('records.subtitle')}</p>

      {message && <div className="alert alert-success">{message}</div>}
      {error && <div className="alert alert-error">{error}</div>}

      <div className="card">
        <div className="grid-toolbar">
          <div className="field">
            <label>{t('records.groupBy')}</label>
            <select value={grouping} onChange={e => setGrouping(e.target.value as Grouping)}>
              <option value="category">{t('records.byCategory')}</option>
              <option value="event">{t('records.byEvent')}</option>
            </select>
          </div>
          <div className="field">
            <button type="button" className="btn btn-secondary" onClick={refresh}>
              {t('common.refresh')}
            </button>
          </div>
          {isAdmin && (
            <div className="field">
              <button
                type="button"
                className="btn btn-secondary"
                onClick={handleSeed}
                disabled={seeding}
              >
                {seeding ? t('records.seeding') : t('records.seed')}
              </button>
            </div>
          )}
          {isAdmin && (
            <div className="field">
              <button
                type="button"
                className="btn btn-primary"
                onClick={handleRebuild}
                disabled={rebuilding}
              >
                {rebuilding ? t('records.rebuilding') : t('records.rebuild')}
              </button>
            </div>
          )}
        </div>
        {isAdmin && <p className="muted mt-2">{t('records.rebuildKeepsBaseline')}</p>}
        <p className="muted">{t('records.count', { count: records.length })}</p>
      </div>

      {records.length === 0 ? (
        <div className="empty mt-2">
          <p>{t('records.empty')}</p>
          <p>{t('records.emptyHint')}</p>
        </div>
      ) : (
        groups.map(([key, rows]) => (
          <div className="card mt-2" key={key}>
            <h2>
              {grouping === 'category'
                ? label('category', key)
                : rows[0].eventTypeLabel}
              <span className="badge badge-info" style={{ marginLeft: '0.5rem' }}>
                {t('records.count', { count: rows.length })}
              </span>
            </h2>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{t('records.colEvent')}</th>
                    <th>{t('records.colDivision')}</th>
                    <th>{t('records.colGrade')}</th>
                    <th>{t('records.colSource')}</th>
                    <th>{t('records.colMark')}</th>
                    <th>{t('records.colTypedIn')}</th>
                    <th>{t('records.colHolder')}</th>
                    <th>{t('records.colAchieved')}</th>
                    <th>{t('records.colPrevious')}</th>
                    {isAdmin && <th>{t('common.actions')}</th>}
                  </tr>
                </thead>
                <tbody>
                  {rows.map(record => {
                    const hasBaseline =
                      record.manualMark !== undefined && record.manualMark !== null;
                    const editing = editingId === record.id;
                    const busy = savingId === record.id || clearingId === record.id;
                    return (
                      <Fragment key={record.id}>
                        <tr>
                          <td>
                            <strong>{record.eventTypeLabel}</strong>
                            <span className="badge badge-info" style={{ marginLeft: '0.4rem' }}>
                              {label('category', record.category)}
                            </span>
                          </td>
                          <td>{label('sex', record.sex) || record.sexLabel}</td>
                          <td>{label('grade.short', record.grade) || record.gradeLabel}</td>
                          <td>
                            <span className={SOURCE_BADGE[record.source]}>
                              {sourceText(record.source)}
                            </span>
                            <div className="muted">
                              {record.source === 'RESULT'
                                ? t('records.resultHeld')
                                : record.source === 'BASELINE'
                                  ? t('records.baselineHeld')
                                  : ''}
                            </div>
                          </td>
                          <td>
                            {record.source === 'NONE' ||
                            record.mark === undefined ||
                            record.mark === null ? (
                              <span className="muted">{t('records.noMark')}</span>
                            ) : (
                              <>
                                <strong>{record.mark}</strong>{' '}
                                {record.unit ? label('unit', record.unit) : ''}
                              </>
                            )}
                          </td>
                          <td>
                            {hasBaseline ? (
                              <>
                                <strong>{record.manualMark}</strong>{' '}
                                {record.manualUnit ? label('unit', record.manualUnit) : ''}
                                <div className="muted">
                                  {record.manualHolderName || '-'} ·{' '}
                                  {formatDate(record.manualAchievedOn)}
                                </div>
                                {record.source === 'RESULT' && (
                                  <div className="blocked-note">
                                    {t('records.baselineBeaten', {
                                      mark: record.manualMark ?? '-',
                                      unit: record.manualUnit
                                        ? label('unit', record.manualUnit)
                                        : '',
                                    })}
                                  </div>
                                )}
                              </>
                            ) : (
                              <span className="muted">{t('records.noTypedIn')}</span>
                            )}
                          </td>
                          <td>
                            {record.holderName ? (
                              <>
                                {record.holderName}
                                <span className="muted" style={{ marginLeft: '0.4rem' }}>
                                  {record.holderStudentRef || ''}
                                </span>
                              </>
                            ) : (
                              '-'
                            )}
                          </td>
                          <td>{record.achievedOn ? formatDate(record.achievedOn) : '-'}</td>
                          <td>
                            {record.hasPrevious ? (
                              t('records.previousLine', {
                                name: record.previousHolderName || '-',
                                mark: record.previousMark ?? '-',
                                unit: record.unit ? label('unit', record.unit) : '',
                                date: formatDate(record.previousAchievedOn),
                              })
                            ) : (
                              <span className="badge badge-success">
                                {t('records.firstRecord')}
                              </span>
                            )}
                          </td>
                          {isAdmin && (
                            <td>
                              <div className="pill-actions">
                                <button
                                  type="button"
                                  className="btn btn-sm btn-secondary"
                                  disabled={busy}
                                  title={t('records.editBaseline')}
                                  onClick={() =>
                                    editing ? cancelEdit() : startEdit(record)
                                  }
                                >
                                  {editing ? t('common.cancel') : t('common.edit')}
                                </button>
                                {hasBaseline && (
                                  <button
                                    type="button"
                                    className="btn btn-sm btn-danger"
                                    disabled={busy}
                                    onClick={() => handleClearBaseline(record)}
                                  >
                                    {clearingId === record.id
                                      ? t('common.processing')
                                      : t('records.clearBaseline')}
                                  </button>
                                )}
                              </div>
                            </td>
                          )}
                        </tr>
                        {isAdmin && editing && (
                          <tr>
                            <td colSpan={columnCount}>
                              <div className="toolbar">
                                <div className="form-group" style={{ minWidth: '130px' }}>
                                  <label>{t('records.fieldMark')} *</label>
                                  <input
                                    type="number"
                                    step="0.001"
                                    value={form.mark}
                                    onChange={e => updateForm('mark', e.target.value)}
                                  />
                                </div>
                                <div className="form-group" style={{ minWidth: '150px' }}>
                                  <label>{t('records.fieldUnit')}</label>
                                  <input
                                    type="text"
                                    list="record-units"
                                    value={form.unit}
                                    placeholder={t('results.unitPlaceholder')}
                                    onChange={e => updateForm('unit', e.target.value)}
                                  />
                                </div>
                                <div className="form-group" style={{ minWidth: '220px' }}>
                                  <label>{t('records.colHolder')}</label>
                                  <input
                                    type="text"
                                    value={form.holderName}
                                    placeholder={t('records.holderPlaceholder')}
                                    onChange={e => updateForm('holderName', e.target.value)}
                                  />
                                </div>
                                <div className="form-group" style={{ minWidth: '160px' }}>
                                  <label>{t('records.colAchieved')}</label>
                                  <input
                                    type="date"
                                    value={form.achievedOn}
                                    onChange={e => updateForm('achievedOn', e.target.value)}
                                  />
                                </div>
                                <div className="pill-actions">
                                  <button
                                    type="button"
                                    className="btn btn-primary"
                                    disabled={savingId === record.id}
                                    onClick={() => handleSaveBaseline(record)}
                                  >
                                    {savingId === record.id
                                      ? t('common.saving')
                                      : t('records.saveBaseline')}
                                  </button>
                                  <button
                                    type="button"
                                    className="btn btn-secondary"
                                    onClick={cancelEdit}
                                  >
                                    {t('common.cancel')}
                                  </button>
                                </div>
                              </div>
                              <div className="muted mt-2">{t('records.baselineNote')}</div>
                            </td>
                          </tr>
                        )}
                      </Fragment>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        ))
      )}

      <datalist id="record-units">
        {UNIT_SUGGESTIONS.map(unit => (
          <option key={unit} value={unit}>
            {label('unit', unit)}
          </option>
        ))}
      </datalist>
    </div>
  );
}
