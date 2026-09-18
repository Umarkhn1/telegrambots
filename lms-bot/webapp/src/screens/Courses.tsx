import { motion } from 'framer-motion';
import { BookOpen, ChevronRight, TriangleAlert } from 'lucide-react';
import { useState } from 'react';
import { SemesterSheet } from '../components/SemesterSheet';
import { Empty, ErrorState, ListSkeleton, listItem, PageHead, Screen, SemesterChip } from '../components/ui';
import { api } from '../lib/api';
import { useApp } from '../lib/app';
import { useI18n } from '../lib/i18n';
import { prefetch, useQuery } from '../lib/query';
import { haptic } from '../lib/tg';

/** Цвет плитки предмета — стабильный по id, чтобы список не «мигал» при обновлении. */
const TONES = ['', 'violet', 'success', 'warning'];

export function Courses() {
  const { semesterId, push } = useApp();
  const { t } = useI18n();
  const [semOpen, setSemOpen] = useState(false);
  const sem = semesterId ?? 0;
  const q = useQuery(sem ? `courses:${sem}` : null, (f) => api.courses(sem, f));

  return (
    <Screen onRefresh={q.refresh}>
      <PageHead
        title={t('courses_title')}
        sub={q.data ? t('subjects', { n: q.data.length }) : undefined}
      />
      <div style={{ marginBottom: 14 }}>
        <SemesterChip onClick={() => setSemOpen(true)} />
      </div>

      {q.loading ? (
        <ListSkeleton rows={6} tall />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={q.refresh} />
      ) : !q.data?.length ? (
        <div className="card">
          <Empty icon={BookOpen} title={t('no_courses')} />
        </div>
      ) : (
        <div className="list">
          {q.data.map((c, i) => (
            <motion.button
              key={c.id}
              className="row with-icon pressable"
              style={{ alignItems: 'flex-start', paddingTop: 14, paddingBottom: 14 }}
              variants={listItem}
              initial="hidden"
              animate="show"
              custom={i}
              onPointerDown={() => prefetch(`act:${c.id}`, (f) => api.activities(c.id, f))}
              onClick={() => {
                haptic('light');
                push({ name: 'course', course: c });
              }}
            >
              <span className={'tile ' + TONES[c.id % TONES.length]} style={{ marginTop: 1 }}>
                <BookOpen size={18} />
              </span>
              <div className="row-main">
                <div className="row-title">{c.subject}</div>
                {c.teachers.length > 0 && (
                  <div className="row-sub ellipsis">{c.teachers.map((x) => x.name).join(', ')}</div>
                )}
                <div className="meta" style={{ marginTop: 8 }}>
                  {c.attendance > 0 ? (
                    <span className="badge danger" style={{ height: 22 }}>
                      {t('nb')}: {c.attendance}
                    </span>
                  ) : (
                    <span className="badge success" style={{ height: 22 }}>{t('no_absences')}</span>
                  )}
                  {c.failed && (
                    <span className="badge warning" style={{ height: 22 }}>
                      <TriangleAlert size={12} /> {t('failed')}
                    </span>
                  )}
                </div>
              </div>
              <ChevronRight size={18} className="chev" style={{ alignSelf: 'center' }} />
            </motion.button>
          ))}
        </div>
      )}
      <SemesterSheet open={semOpen} onClose={() => setSemOpen(false)} />
    </Screen>
  );
}
