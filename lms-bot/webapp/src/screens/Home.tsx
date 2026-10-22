import { motion } from 'framer-motion';
import { CalendarCheck, ChevronRight, ListChecks, TrendingUp, UserX, type LucideIcon } from 'lucide-react';
import { useMemo, useState } from 'react';
import { ActivityRow } from '../components/ActivityRow';
import { ActivitySheet } from '../components/ActivitySheet';
import { LessonRow, PAIR_MS } from '../components/LessonRow';
import { Empty, ListSkeleton, PageHead, Screen, Section, Skeleton } from '../components/ui';
import { api } from '../lib/api';
import { useApp } from '../lib/app';
import { addDays, gpa, initials, isoDate } from '../lib/format';
import { dayMonth, useI18n, WEEKDAYS } from '../lib/i18n';
import { useQuery } from '../lib/query';
import type { DeadlineItem } from '../lib/types';

function greetingKey(h: number) {
  if (h < 5) return 'greet_night' as const;
  if (h < 12) return 'greet_morning' as const;
  if (h < 18) return 'greet_day' as const;
  return 'greet_evening' as const;
}

function Stat({ icon: Icon, tone, value, label, onClick, loading }: { icon: LucideIcon; tone: string; value: string; label: string; onClick: () => void; loading: boolean }) {
  return (
    <button className="stat card pressable" onClick={onClick}>
      <span className={'tile ' + tone} style={{ width: 32, height: 32, borderRadius: 10 }}>
        <Icon size={17} />
      </span>
      {loading ? <Skeleton h={24} w="60%" style={{ marginTop: 12 }} /> : <div className="stat-value tabular">{value}</div>}
      <div className="stat-label">{label}</div>
    </button>
  );
}

export function Home() {
  const { me, semesterId, setTab, push } = useApp();
  const { t, lang } = useI18n();
  const [openAct, setOpenAct] = useState<DeadlineItem | null>(null);
  const sem = semesterId ?? 0;

  const schedule = useQuery(sem ? `sched:${sem}` : null, (f) => api.schedule(sem, f));
  const deadlines = useQuery(sem ? `dl:${sem}` : null, (f) => api.deadlines(sem, f));
  const courses = useQuery(sem ? `courses:${sem}` : null, (f) => api.courses(sem, f));
  const plan = useQuery('plan', (f) => api.studyPlan(f));
  const photo = useQuery('photo', () => api.photo(), 30 * 60_000);

  const now = new Date();
  const today = isoDate(now);
  const tomorrow = isoDate(addDays(now, 1));

  const day = useMemo(() => {
    const lessons = schedule.data?.lessons ?? [];
    const todays = lessons.filter((l) => l.date === today);
    if (todays.some((l) => l.ts + PAIR_MS > Date.now())) return { key: 'classes_today' as const, list: todays };
    const tomorrows = lessons.filter((l) => l.date === tomorrow);
    if (tomorrows.length) return { key: 'classes_tomorrow' as const, list: tomorrows, over: todays.length > 0 };
    return { key: 'classes_today' as const, list: [] as typeof lessons, over: todays.length > 0 };
  }, [schedule.data, today, tomorrow]);

  const pending = (deadlines.data ?? []).filter((d) => d.status === 'open');
  const gpaValue = plan.data ? gpa(plan.data, false).value : null;
  const nb = courses.data?.reduce((s, c) => s + c.attendance, 0);

  const refresh = () => Promise.all([schedule.refresh(), deadlines.refresh(), courses.refresh(), plan.refresh()]);
  const name = me.user.firstName || '';

  return (
    <Screen onRefresh={refresh}>
      <PageHead
        title={
          <>
            {t(greetingKey(now.getHours()))}
            {name && <span style={{ color: 'var(--text-2)', fontWeight: 600 }}>,<br />{name}</span>}
          </>
        }
        sub={`${WEEKDAYS[lang][(now.getDay() + 6) % 7]}, ${dayMonth(now, lang)}`}
        right={
          <button className="avatar" onClick={() => setTab('profile')} aria-label="profile" style={{ width: 48, height: 48 }}>
            {photo.data?.dataUrl ? <img src={photo.data.dataUrl} alt="" /> : me.user.photoUrl ? <img src={me.user.photoUrl} alt="" /> : initials(name)}
          </button>
        }
      />

      <div className="stat-grid">
        <Stat icon={TrendingUp} tone="" value={gpaValue != null ? gpaValue.toFixed(2) : '—'} label={t('stat_gpa')} loading={plan.loading} onClick={() => setTab('grades')} />
        <Stat icon={UserX} tone={nb ? 'danger' : 'success'} value={nb != null ? String(nb) : '—'} label={t('stat_nb')} loading={courses.loading} onClick={() => setTab('courses')} />
        <Stat icon={ListChecks} tone={pending.length ? 'warning' : 'success'} value={deadlines.data ? String(pending.length) : '—'} label={t('deadlines_title')} loading={deadlines.loading} onClick={() => push({ name: 'deadlines' })} />
      </div>

      <Section
        title={t(day.key)}
        action={
          <button className="link-btn" onClick={() => setTab('schedule')}>
            {t('schedule_title')} <ChevronRight size={16} />
          </button>
        }
      >
        {schedule.loading ? (
          <ListSkeleton rows={3} />
        ) : day.list.length ? (
          <div className="list">
            {day.list.map((l, i) => (
              <LessonRow key={l.ts + l.subject} l={l} index={i} />
            ))}
          </div>
        ) : (
          <div className="card">
            <Empty icon={CalendarCheck} tone="success" title={day.over ? t('classes_over') : t('no_classes_today')} sub={t('no_classes_sub')} />
          </div>
        )}
      </Section>

      <Section
        title={t('upcoming_deadlines')}
        action={
          pending.length > 0 && (
            <button className="link-btn" onClick={() => push({ name: 'deadlines' })}>
              {t('see_all')} <ChevronRight size={16} />
            </button>
          )
        }
      >
        {deadlines.loading ? (
          <ListSkeleton rows={3} tall />
        ) : pending.length ? (
          <motion.div className="list" layout>
            {pending.slice(0, 4).map((d, i) => (
              <ActivityRow key={d.courseId + ':' + d.index} a={d} course={d.course} index={i} onClick={() => setOpenAct(d)} />
            ))}
          </motion.div>
        ) : (
          <div className="card">
            <Empty icon={ListChecks} tone="success" title={t('no_deadlines')} sub={t('no_deadlines_sub')} />
          </div>
        )}
      </Section>

      <ActivitySheet activity={openAct} courseId={openAct?.courseId ?? 0} course={openAct?.course} onClose={() => setOpenAct(null)} />
    </Screen>
  );
}
