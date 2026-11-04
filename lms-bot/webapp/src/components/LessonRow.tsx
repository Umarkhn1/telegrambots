import { motion } from 'framer-motion';
import { ChevronRight, ClipboardCheck, Flag, MapPin, NotebookText, UserRound } from 'lucide-react';
import { api } from '../lib/api';
import { useApp } from '../lib/app';
import { useI18n, type Key } from '../lib/i18n';
import { useQuery } from '../lib/query';
import { haptic } from '../lib/tg';
import { useTT } from '../lib/ti18n';
import type { Course, Lesson } from '../lib/types';
import { listItem, StreamChip } from './ui';

/** Пара в ТУИТ идёт 80 минут — по этому считаем «сейчас идёт». */
export const PAIR_MS = 80 * 60 * 1000;

export const KIND: Record<Lesson['kind'], { label: Key; tone: string }> = {
  lecture: { label: 'lecture', tone: 'accent' },
  practice: { label: 'practice', tone: 'violet' },
  lab: { label: 'laboratory', tone: 'warning' },
};

/** Тап по паре у студента: открыть предмет с этим занятием. undefined — предмет пары неизвестен. */
export function useOpenLesson() {
  const { semesterId, push } = useApp();
  const sem = semesterId ?? 0;
  const courses = useQuery(sem ? `courses:${sem}` : null, (f) => api.courses(sem, f));
  return (l: Lesson) => {
    const id = l.courseId;
    if (id == null) return undefined;
    const course: Course = courses.data?.find((c) => c.id === id) ?? {
      id,
      subject: l.subject,
      attendance: 0,
      failed: false,
      teachers: l.teacher ? [{ name: l.teacher, stream: l.stream ?? '' }] : [],
    };
    return () => {
      haptic('light');
      push({ name: 'course', course, lesson: l });
    };
  };
}

/** onClick — что делает тап по паре; rollCall — преподавателю: тап открывает перекличку. */
export function LessonRow({ l, index, now = Date.now(), onClick, rollCall }: { l: Lesson; index: number; now?: number; onClick?: () => void; rollCall?: boolean }) {
  const { t } = useI18n();
  const tt = useTT();
  const kind = KIND[l.kind] ?? KIND.lecture;
  const live = now >= l.ts && now < l.ts + PAIR_MS;
  const done = now >= l.ts + PAIR_MS;
  const end = new Date(l.ts + PAIR_MS);
  const endLabel = `${String(end.getHours()).padStart(2, '0')}:${String(end.getMinutes()).padStart(2, '0')}`;

  return (
    <motion.div
      className={'lesson' + (live ? ' now' : done ? ' done' : '') + (onClick ? ' pressable' : '')}
      {...(onClick ? { role: 'button', tabIndex: 0, onClick, onKeyDown: (e: React.KeyboardEvent) => e.key === 'Enter' && onClick() } : {})}
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
          {l.stream && <StreamChip stream={l.stream} />}
          {live && <span className="badge success" style={{ height: 22 }}>{t('now')}</span>}
          {onClick && rollCall && (
            <span className="badge accent" style={{ height: 22 }}>
              <ClipboardCheck size={12} /> {tt('roll_call')}
            </span>
          )}
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
            <span className="wrap">{l.topic}</span>
          </div>
        )}
        {l.last && (
          <div className="row-sub" style={{ marginTop: 6, display: 'flex', gap: 6, color: 'var(--warning)', fontWeight: 550 }}>
            <Flag size={13} style={{ marginTop: 2 }} />
            {t('last_lesson')}
          </div>
        )}
      </div>
      {onClick && !rollCall && <ChevronRight size={18} className="chev" style={{ alignSelf: 'center', flexShrink: 0 }} />}
    </motion.div>
  );
}
