import { motion } from 'framer-motion';
import { Clock3, Trophy } from 'lucide-react';
import { deadlineLabel, duration, urgency } from '../lib/format';
import { useI18n } from '../lib/i18n';
import type { Activity } from '../lib/types';
import { STATUS, scoreText } from './ActivitySheet';
import { listItem } from './ui';

/** Строка задания: статус, название, срок с обратным отсчётом и баллы. */
export function ActivityRow({ a, course, index, onClick }: { a: Activity; course?: string; index: number; onClick: () => void }) {
  const { t, lang } = useI18n();
  const st = STATUS[a.status];
  const now = Date.now();
  const u = a.status === 'open' ? urgency(a.deadlineTs, now) : 'neutral';
  const submitted = a.status === 'uploaded' || a.status === 'graded';
  // Несданные различаются срочностью: меньше суток — красный, до трёх дней — жёлтый, дальше — синий.
  const tone = a.status === 'open' && u !== 'neutral' ? u : st.tone;
  const chip = submitted ? 'success' : a.status === 'missed' ? 'danger' : a.status === 'open' ? 'warning' : 'neutral';

  return (
    <motion.button
      className="row with-icon pressable"
      style={{ alignItems: 'flex-start', paddingTop: 13, paddingBottom: 13 }}
      onClick={onClick}
      variants={listItem}
      initial="hidden"
      animate="show"
      custom={index}
    >
      <span className={'tile ' + tone} style={{ marginTop: 1 }}>
        <st.icon size={19} />
      </span>
      <div className="row-main">
        {course && <div className="row-sub wrap" style={{ marginTop: 0, marginBottom: 2, fontWeight: 550 }}>{course}</div>}
        <div className="row-title wrap">{a.task}</div>
        <div className="meta" style={{ marginTop: 7 }}>
          <span className={'badge ' + chip} style={{ height: 22 }}>
            {submitted ? t('submitted_yes') : a.status === 'unknown' ? t(st.label) : t('submitted_no')}
          </span>
          {a.deadlineTs && (
            <span className="tabular">
              <Clock3 size={13} />
              {deadlineLabel(a.deadlineTs, lang)}
            </span>
          )}
          <span className="tabular">
            <Trophy size={13} />
            {scoreText(a)}
          </span>
          {a.status === 'open' && a.deadlineTs && a.deadlineTs > now && (
            <span className={'badge ' + u} style={{ height: 22 }}>{duration(a.deadlineTs - now, t)}</span>
          )}
        </div>
      </div>
    </motion.button>
  );
}
