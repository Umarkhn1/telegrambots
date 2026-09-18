import { Check } from 'lucide-react';
import { LANG_NAMES, useI18n } from '../lib/i18n';
import type { Lang } from '../lib/types';
import { Sheet } from './Sheet';

const CODES: Record<Lang, string> = { ru: 'RU', uz_lat: "O'Z", uz_cyr: 'ЎЗ', en: 'EN' };

export function LanguageSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { lang, setLang, t } = useI18n();
  return (
    <Sheet open={open} onClose={onClose} title={t('language')}>
      <div className="list">
        {(Object.keys(LANG_NAMES) as Lang[]).map((l) => (
          <button
            key={l}
            className="row with-icon pressable"
            onClick={() => {
              setLang(l);
              onClose();
            }}
          >
            <span className={'tile ' + (l === lang ? '' : 'neutral')} style={{ fontSize: 12.5, fontWeight: 700 }}>
              {CODES[l]}
            </span>
            <div className="row-main">
              <div className="row-title">{LANG_NAMES[l]}</div>
            </div>
            {l === lang && <Check size={20} color="var(--accent)" strokeWidth={2.4} />}
          </button>
        ))}
      </div>
    </Sheet>
  );
}
