import { AnimatePresence, motion, type PanInfo } from 'framer-motion';
import { CalendarDays, CalendarOff, ChevronLeft, ChevronRight, LocateFixed } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { LessonRow } from '../components/LessonRow';
import { SemesterSheet } from '../components/SemesterSheet';
import { Empty, ErrorState, ListSkeleton, PageHead, Screen, SemesterChip, Skeleton } from '../components/ui';
import { api } from '../lib/api';
import { useApp } from '../lib/app';
import { addDays, isoDate, parseISODate } from '../lib/format';
import { dayMonth, useI18n, WEEKDAYS, WEEKDAYS_SHORT } from '../lib/i18n';
import { useQuery } from '../lib/query';
import { haptic } from '../lib/tg';
import type { Lesson } from '../lib/types';

const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

export function Schedule() {
  const { semesterId } = useApp();
  const { t, lang } = useI18n();
  const [semOpen, setSemOpen] = useState(false);
  const sem = semesterId ?? 0;
  const q = useQuery(sem ? `sched:${sem}` : null, (f) => api.schedule(sem, f));

  const weeks = q.data?.weeks ?? 0;
  const start = q.data?.start ? parseISODate(q.data.start) : null;
  const today = isoDate(new Date());

  const [week, setWeek] = useState<number | null>(null);
  const [day, setDay] = useState<string | null>(null);
  const [dir, setDir] = useState(0);

  // При загрузке и смене семестра — текущая неделя и сегодняшний день.
  useEffect(() => {
    if (!q.data?.weeks) return;
    setWeek(q.data.currentWeek ?? 0);
    setDay(null);
  }, [q.data]);

  const byDate = useMemo(() => {
    const m = new Map<string, Lesson[]>();
    for (const l of q.data?.lessons ?? []) {
      const list = m.get(l.date) ?? [];
      list.push(l);
      m.set(l.date, list);
    }
    return m;
  }, [q.data]);

  const w = week ?? q.data?.currentWeek ?? 0;
  const monday = start ? addDays(start, w * 7) : null;
  const days = monday ? Array.from({ length: 7 }, (_, i) => isoDate(addDays(monday, i))) : [];
  const firstWithLessons = days.find((d) => byDate.has(d));
  const selected = day && days.includes(day) ? day : days.includes(today) ? today : firstWithLessons ?? days[0];
  const lessons = selected ? byDate.get(selected) ?? [] : [];

  const goWeek = (next: number) => {
    if (next < 0 || next >= weeks) return;
    haptic('selection');
    setDir(next > w ? 1 : -1);
    setWeek(next);
    setDay(null);
  };

  const goDay = (delta: number) => {
    if (!selected) return;
    const idx = days.indexOf(selected) + delta;
    if (idx < 0) {
      if (w > 0) {
        setDir(-1);
        setWeek(w - 1);
        setDay(isoDate(addDays(parseISODate(selected), -1)));
        haptic('selection');
      }
      return;
    }
    if (idx > 6) {
      if (w < weeks - 1) {
        setDir(1);
        setWeek(w + 1);
        setDay(isoDate(addDays(parseISODate(selected), 1)));
        haptic('selection');
      }
      return;
    }
    haptic('selection');
    setDir(delta);
    setDay(days[idx]);
  };

  const onSwipe = (_: unknown, info: PanInfo) => {
    if (Math.abs(info.offset.x) < 60 || Math.abs(info.offset.y) > Math.abs(info.offset.x)) return;
    goDay(info.offset.x < 0 ? 1 : -1);
  };

  const weekRange = monday ? `${dayMonth(monday, lang, true)} – ${dayMonth(addDays(monday, 6), lang, true)}` : '';
  const currentWeek = q.data?.currentWeek ?? 0;
  const selDate = selected ? parseISODate(selected) : null;

  return (
    <Screen onRefresh={q.refresh}>
      <PageHead title={t('schedule_title')} />
      <div style={{ marginBottom: 14 }}>
        <SemesterChip onClick={() => setSemOpen(true)} />
      </div>

      {q.loading ? (
        <>
          <Skeleton h={86} r={18} />
          <div style={{ marginTop: 14 }}><ListSkeleton rows={3} tall /></div>
        </>
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={q.refresh} />
      ) : !weeks ? (
        <div className="card"><Empty icon={CalendarDays} title={t('no_schedule')} /></div>
      ) : (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <button className="icon-btn" onClick={() => goWeek(w - 1)} disabled={w <= 0} aria-label="prev">
              <ChevronLeft size={20} />
            </button>
            <div style={{ flex: 1, textAlign: 'center', minWidth: 0 }}>
              <div className="row-title tabular">{weekRange}</div>
              <div className="row-sub" style={{ marginTop: 1 }}>{t('week_n', { n: w + 1, total: weeks })}</div>
            </div>
            <button className="icon-btn" onClick={() => goWeek(w + 1)} disabled={w >= weeks - 1} aria-label="next">
              <ChevronRight size={20} />
            </button>
          </div>

          <AnimatePresence mode="popLayout" initial={false} custom={dir}>
            <motion.div
              key={w}
              custom={dir}
              initial={{ opacity: 0, x: dir * 40 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: dir * -40 }}
              transition={{ duration: 0.22, ease: [0.2, 0.8, 0.2, 1] }}
              className="week-strip"
            >
              {days.map((d, i) => {
                const date = parseISODate(d);
                const active = d === selected;
                return (
                  <button
                    key={d}
                    className={'day-cell' + (active ? ' active' : '') + (d === today ? ' today' : '')}
                    onClick={() => {
                      if (d === selected) return;
                      haptic('selection');
                      setDir(days.indexOf(d) > days.indexOf(selected ?? d) ? 1 : -1);
                      setDay(d);
                    }}
                  >
                    {active && <motion.span layoutId="day-pill" className="day-pill" transition={{ type: 'spring', stiffness: 500, damping: 38 }} />}
                    <span className="dow">{WEEKDAYS_SHORT[lang][i]}</span>
                    <span className="num tabular">{date.getDate()}</span>
                    <span className={'dot' + (byDate.has(d) ? '' : ' hidden')} />
                  </button>
                );
              })}
            </motion.div>
          </AnimatePresence>

          {w !== currentWeek && (
            <div style={{ display: 'flex', justifyContent: 'center', marginTop: 12 }}>
              <button
                className="chip"
                onClick={() => {
                  haptic('selection');
                  setDir(currentWeek > w ? 1 : -1);
                  setWeek(currentWeek);
                  setDay(today);
                }}
              >
                <span>
                  <LocateFixed size={15} /> {t('this_week')}
                </span>
              </button>
            </div>
          )}

          <div className="section-head" style={{ marginTop: 20 }}>
            <span className="section-title">
              {selDate && capitalize(`${WEEKDAYS[lang][(selDate.getDay() + 6) % 7]}, ${dayMonth(selDate, lang)}`)}
            </span>
            {lessons.length > 0 && <span className="section-title">{t('pairs', { n: lessons.length })}</span>}
          </div>

          <motion.div drag="x" dragConstraints={{ left: 0, right: 0 }} dragElastic={0.15} dragDirectionLock onDragEnd={onSwipe} style={{ touchAction: 'pan-y' }}>
            <AnimatePresence mode="wait" initial={false} custom={dir}>
              <motion.div
                key={selected}
                initial={{ opacity: 0, x: dir * 24 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: dir * -24 }}
                transition={{ duration: 0.18 }}
              >
                {lessons.length ? (
                  <div className="list">
                    {lessons.map((l, i) => (
                      <LessonRow key={l.ts + l.subject} l={l} index={i} />
                    ))}
                  </div>
                ) : (
                  <div className="card"><Empty icon={CalendarOff} title={t('no_classes_day')} /></div>
                )}
              </motion.div>
            </AnimatePresence>
          </motion.div>

          {q.data?.end && (
            <div className="divider-note">{t('semester_until', { date: dayMonth(parseISODate(q.data.end), lang) })}</div>
          )}
        </>
      )}
      <SemesterSheet open={semOpen} onClose={() => setSemOpen(false)} />
    </Screen>
  );
}
