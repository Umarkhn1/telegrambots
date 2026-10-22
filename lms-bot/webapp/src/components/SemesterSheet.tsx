import { Check, Layers } from 'lucide-react';
import { useApp } from '../lib/app';
import { useI18n } from '../lib/i18n';
import { haptic } from '../lib/tg';
import { Sheet } from './Sheet';
import { Tile } from './ui';

export function SemesterSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { semesters, semesterId, setSemesterId } = useApp();
  const { t } = useI18n();
  return (
    <Sheet open={open} onClose={onClose} title={t('choose_semester')}>
      <div className="list">
        {semesters.map((s) => {
          const active = s.id === semesterId;
          return (
            <button
              key={s.id}
              className="row with-icon pressable"
              onClick={() => {
                haptic('selection');
                setSemesterId(s.id);
                onClose();
              }}
            >
              <Tile icon={Layers} tone={active ? '' : 'neutral'} size={18} />
              <div className="row-main">
                <div className="row-title">{s.name}</div>
              </div>
              {active && <Check size={20} color="var(--accent)" strokeWidth={2.4} />}
            </button>
          );
        })}
      </div>
    </Sheet>
  );
}
