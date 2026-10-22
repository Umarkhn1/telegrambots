import { ChevronLeft, ListChecks } from 'lucide-react';
import { useState } from 'react';
import { ActivityRow } from '../components/ActivityRow';
import { ActivitySheet } from '../components/ActivitySheet';
import { Empty, ErrorState, ListSkeleton, Screen, Section, Segmented } from '../components/ui';
import { api } from '../lib/api';
import { useApp } from '../lib/app';
import { useI18n, type Key } from '../lib/i18n';
import { useQuery } from '../lib/query';
import type { DeadlineItem } from '../lib/types';

const DAY = 24 * 3600e3;

export function Deadlines({ onBack }: { onBack: () => void }) {
  const { semesterId } = useApp();
  const { t } = useI18n();
  const [mode, setMode] = useState<'open' | 'done' | 'all'>('open');
  const [open, setOpen] = useState<DeadlineItem | null>(null);
  const sem = semesterId ?? 0;
  const q = useQuery(sem ? `dl:${sem}` : null, (f) => api.deadlines(sem, f));

  const now = Date.now();
  const submitted = (d: DeadlineItem) => d.status === 'uploaded' || d.status === 'graded';
  const all = q.data ?? [];
  const items = mode === 'all' ? all : all.filter((d) => (mode === 'done') === submitted(d));
  // Несданные — по срочности (просроченные отдельно), сданные — своей группой.
  const notDone = items.filter((d) => !submitted(d));
  const left = (d: DeadlineItem) => (d.deadlineTs == null ? Infinity : d.deadlineTs - now);
  const groups: { key: Key; list: DeadlineItem[] }[] = [
    { key: 'group_overdue', list: notDone.filter((d) => left(d) < 0) },
    { key: 'group_24h', list: notDone.filter((d) => left(d) >= 0 && left(d) < DAY) },
    { key: 'group_3d', list: notDone.filter((d) => left(d) >= DAY && left(d) < 3 * DAY) },
    { key: 'group_later', list: notDone.filter((d) => left(d) >= 3 * DAY) },
    { key: 'group_done', list: items.filter(submitted) },
  ];

  let n = 0;
  return (
    <Screen stacked onRefresh={q.refresh}>
      <div className="back-head">
        <button className="icon-btn" onClick={onBack} aria-label={t('back')}>
          <ChevronLeft size={22} />
        </button>
      </div>
      <h1 className="page-title">{t('deadlines_title')}</h1>
      <Segmented
        value={mode}
        onChange={setMode}
        style={{ marginTop: 16 }}
        options={[
          { value: 'open', label: t('only_pending') },
          { value: 'done', label: t('only_done') },
          { value: 'all', label: t('all_items') },
        ]}
      />

      <div style={{ marginTop: 6 }}>
        {q.loading ? (
          <Section><ListSkeleton rows={5} tall /></Section>
        ) : q.error ? (
          <Section><ErrorState error={q.error} onRetry={q.refresh} /></Section>
        ) : !items.length ? (
          <Section><div className="card"><Empty icon={ListChecks} tone="success" title={t('no_deadlines')} sub={t('no_deadlines_sub')} /></div></Section>
        ) : (
          groups
            .filter((g) => g.list.length)
            .map((g) => (
              <Section key={g.key} title={`${t(g.key)} · ${g.list.length}`}>
                <div className="list">
                  {g.list.map((d) => (
                    <ActivityRow key={d.courseId + ':' + d.index} a={d} course={d.course} index={n++} onClick={() => setOpen(d)} />
                  ))}
                </div>
              </Section>
            ))
        )}
      </div>
      <ActivitySheet activity={open} courseId={open?.courseId ?? 0} course={open?.course} onClose={() => setOpen(null)} />
    </Screen>
  );
}
