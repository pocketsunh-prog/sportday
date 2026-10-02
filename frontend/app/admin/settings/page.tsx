'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, SettingsDTO, SettingsUpdate } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useI18n, MessageKey } from '@/lib/i18n';
import { formatDateTime } from '@/lib/format';

/**
 * The settings this page edits: the numeric ones. `SettingsDTO` also carries the
 * school's own details (name, address, principal, sheet title), which are edited
 * on `/admin/sport-day`, so they are deliberately excluded here — narrowing the
 * key type this way keeps `patch[field] = value` well typed while the school
 * fields are present on the DTO.
 */
type NumericSettingKey = {
  [K in keyof SettingsUpdate]-?: NonNullable<SettingsUpdate[K]> extends number ? K : never;
}[keyof SettingsUpdate];

const NUMBER_FIELDS: NumericSettingKey[] = [
  'trackMaxEntries',
  'fieldMaxEntries',
  'pointsFirst',
  'pointsSecond',
  'pointsThird',
  'pointsTopPlace',
  'pointsTop',
  'relayPointsFirst',
  'relayPointsSecond',
  'relayPointsThird',
  'relayPointsTop',
];

const FIELD_LABELS: Partial<Record<keyof SettingsUpdate, MessageKey>> = {
  trackMaxEntries: 'settings.trackMaxEntries',
  fieldMaxEntries: 'settings.fieldMaxEntries',
  pointsFirst: 'settings.pointsFirst',
  pointsSecond: 'settings.pointsSecond',
  pointsThird: 'settings.pointsThird',
  pointsTopPlace: 'settings.pointsTopPlace',
  pointsTop: 'settings.pointsTop',
  relayPointsFirst: 'settings.pointsFirst',
  relayPointsSecond: 'settings.pointsSecond',
  relayPointsThird: 'settings.pointsThird',
  relayPointsTop: 'settings.pointsTop',
};

type FormState = Record<string, string>;

function toForm(settings: SettingsDTO): FormState {
  const form: FormState = {};
  NUMBER_FIELDS.forEach(field => {
    form[field] = String(settings[field] ?? '');
  });
  return form;
}

export default function AdminSettingsPage() {
  const { user, isLoading } = useAuth();
  const { t } = useI18n();
  const router = useRouter();
  const [settings, setSettings] = useState<SettingsDTO | null>(null);
  const [form, setForm] = useState<FormState>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    if (isLoading) return;
    if (!user) {
      router.push('/login');
      return;
    }
    if (user.role !== 'ADMIN') {
      router.push('/');
      return;
    }
    api
      .getSettings()
      .then(data => {
        setSettings(data);
        setForm(toForm(data));
      })
      .catch((err: unknown) => setError(err instanceof Error ? err.message : t('settings.loadFailed')))
      .finally(() => setLoading(false));
  }, [user, isLoading, router, t]);

  const setValue = (field: string, value: string) => {
    setForm(prev => ({ ...prev, [field]: value }));
  };

  /** The whole scale as one readable sentence. */
  const scaleSentence = (data: SettingsDTO): string => {
    const base = t('settings.scale', {
      first: data.pointsFirst,
      second: data.pointsSecond,
      third: data.pointsThird,
      topPlace: data.pointsTopPlace,
      top: data.pointsTop,
      rfirst: data.relayPointsFirst,
      rsecond: data.relayPointsSecond,
      rthird: data.relayPointsThird,
    });
    return `${base} · ${t('settings.scaleTopPlace', {
      topPlace: data.pointsTopPlace,
      top: data.pointsTop,
      rtop: data.relayPointsTop,
    })}`;
  };

  const collect = (): SettingsUpdate | null => {
    const patch: SettingsUpdate = {};
    for (const field of NUMBER_FIELDS) {
      const raw = (form[field] ?? '').trim();
      const value = Number(raw);
      if (raw === '' || !Number.isFinite(value)) {
        setError(t('settings.unexpectedError'));
        return null;
      }
      patch[field] = value;
    }
    if ((patch.trackMaxEntries ?? 0) < 1 || (patch.fieldMaxEntries ?? 0) < 1) {
      setError(t('settings.minOne'));
      return null;
    }
    return patch;
  };

  const handleSave = async () => {
    setMessage('');
    setError('');
    const patch = collect();
    if (!patch) return;
    setSaving(true);
    try {
      const updated = await api.updateSettings(patch);
      setSettings(updated);
      setForm(toForm(updated));
      setMessage(t('settings.saved'));
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('settings.saveFailed'));
    } finally {
      setSaving(false);
    }
  };

  const handleReset = async () => {
    if (!confirm(t('settings.resetConfirm'))) return;
    setMessage('');
    setError('');
    setResetting(true);
    try {
      const restored = await api.resetSettings();
      setSettings(restored);
      setForm(toForm(restored));
      setMessage(t('settings.resetDone'));
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t('settings.resetFailed'));
    } finally {
      setResetting(false);
    }
  };

  if (isLoading || !user || loading) {
    return <div>{t('common.loading')}</div>;
  }

  const numberInput = (field: NumericSettingKey, min = 0) => (
    <div className="form-group">
      <label>{t(FIELD_LABELS[field] as MessageKey)}</label>
      <input
        type="number"
        min={min}
        step={1}
        value={form[field] ?? ''}
        onChange={e => setValue(field, e.target.value)}
      />
    </div>
  );

  return (
    <div>
      <h1 className="page-title">{t('settings.title')}</h1>
      <p className="muted">{t('settings.subtitle')}</p>

      {message && <div className="alert alert-success">{message}</div>}
      {error && <div className="alert alert-error">{error}</div>}

      {settings && (
        <div className="card">
          <h2>{t('settings.currentScale')}</h2>
          <p style={{ fontSize: '1.05rem' }}>{scaleSentence(settings)}</p>
          {settings.updatedAt && (
            <p className="muted">
              {t('settings.updatedAt', { date: formatDateTime(settings.updatedAt) })}
            </p>
          )}
        </div>
      )}

      <div className="card mt-2">
        <h2>{t('settings.entryLimits')}</h2>
        <p className="muted">{t('settings.entryLimitsHint')}</p>
        {numberInput('trackMaxEntries', 1)}
        {numberInput('fieldMaxEntries', 1)}
      </div>

      <div className="card mt-2">
        <h2>{t('settings.points')}</h2>
        <p className="muted">{t('settings.pointsHint')}</p>

        <h3>{t('settings.individual')}</h3>
        {numberInput('pointsFirst')}
        {numberInput('pointsSecond')}
        {numberInput('pointsThird')}
        {numberInput('pointsTopPlace')}
        {numberInput('pointsTop')}

        <h3 className="mt-2">{t('settings.relay')}</h3>
        {numberInput('relayPointsFirst')}
        {numberInput('relayPointsSecond')}
        {numberInput('relayPointsThird')}
        {numberInput('relayPointsTop')}
      </div>

      <div className="card mt-2">
        <button
          type="button"
          className="btn btn-primary"
          onClick={handleSave}
          disabled={saving || resetting}
        >
          {saving ? t('common.saving') : t('settings.save')}
        </button>
        <button
          type="button"
          className="btn btn-secondary"
          style={{ marginLeft: '0.5rem' }}
          onClick={handleReset}
          disabled={saving || resetting}
        >
          {resetting ? t('settings.resetting') : t('settings.reset')}
        </button>
      </div>
    </div>
  );
}
