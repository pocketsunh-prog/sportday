'use client';

import { LANGUAGES, useI18n } from '@/lib/i18n';

/**
 * EN / CH toggle. Switching re-renders every label because the whole tree below
 * <LanguageProvider> reads from the same context, and the choice is remembered
 * in localStorage.
 */
export function LanguageSwitch() {
  const { lang, setLang, t } = useI18n();

  return (
    <span className="lang-switch" role="group" aria-label={t('nav.language')}>
      {LANGUAGES.map(option => (
        <button
          key={option.code}
          type="button"
          className={`lang-option${lang === option.code ? ' lang-option-active' : ''}`}
          aria-pressed={lang === option.code}
          title={option.label}
          onClick={() => setLang(option.code)}
        >
          {option.short}
        </button>
      ))}
    </span>
  );
}
