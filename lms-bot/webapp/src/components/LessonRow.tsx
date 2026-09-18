import { motion } from 'framer-motion';
import { Flag, MapPin, NotebookText, UserRound } from 'lucide-react';
import { useI18n, type Key } from '../lib/i18n';
import type { Lesson } from '../lib/types';
import { listItem } from './ui';

/** Пара в ТУИТ идёт 80 минут — по этому считаем «сейчас идёт». */
export const PAIR_MS = 80 * 60 * 1000;

const KIND: Record<Lesson['kind'], { label: Key; tone: string }> = {
  lecture: { label: 'lecture', tone: 'accent' },
  practice: { label: 'practice', tone: 'violet' },
  lab: { label: 'laboratory', tone: 'warning' },
};

export function LessonRow({ l, index, now = Date.now() }: { l: Lesson; index: number; now?: number }) {
  const { t } = useI18n();
  const kind = KIND[l.kind] ?? KIND.lecture;
  const live = now >= l.ts && now < l.ts + PAIR_MS;
  const done = now >= l.ts + PAIR_MS;
  const end = new Date(l.ts + PAIR_MS);
  const endLabel = `${String(end.getHours()).padStart(2, '0')}:${String(end.getMinutes()).padStart(2, '0')}`;

  return (
    <motion.div
      className={'lesson' + (live ? ' now' : done ? ' done' : '')}
      variants={listItem}
      initial="hidden"
      animate="show"
      custom={index}
    >
      <div className="lesson-time tabular">
        {l.time}
        <small>{endLabel}</small>
      </div>
      <div className={'lesson-bar ' + (l.kind === 'lecture' ? '' : 'practice')} />
      <div className="row-main">
        <div className="row-title">{l.subject}</div>
        <div className="meta" style={{ marginTop: 6 }}>
          <span className={'badge ' + kind.tone} style={{ height: 22 }}>{t(kind.label)}</span>
          {live && <span className="badge success" style={{ height: 22 }}>{t('now')}</span>}
          {l.room && (
            <span>
              <MapPin size={13} />
              {l.room}
            </span>
          )}
          {l.teacher && (
            <span>
              <UserRound size={13} />
              {l.teacher}
            </span>
          )}
        </div>
        {l.topic && (
          <div className="row-sub" style={{ marginTop: 6, display: 'flex', gap: 6 }}>
            <NotebookText size={13} style={{ marginTop: 2 }} />
            <span className="clamp-2">{l.topic}</span>
          </div>
        )}
        {l.last && (
          <div className="row-sub" style={{ marginTop: 6, display: 'flex', gap: 6, color: 'var(--warning)', fontWeight: 550 }}>
            <Flag size={13} style={{ marginTop: 2 }} />
            {t('last_lesson')}
          </div>
        )}
      </div>
    </motion.div>
  );
}
