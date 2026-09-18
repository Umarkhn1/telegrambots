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
      <span className={'tile ' + st.tone} style={{ marginTop: 1 }}>
        <st.icon size={19} />
      </span>
      <div className="row-main">
        {course && <div className="row-sub ellipsis" style={{ marginTop: 0, marginBottom: 2, fontWeight: 550 }}>{course}</div>}
        <div className="row-title clamp-2">{a.task}</div>
        <div className="meta" style={{ marginTop: 7 }}>
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
