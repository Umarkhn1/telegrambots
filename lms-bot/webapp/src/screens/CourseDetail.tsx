import { AnimatePresence, motion } from 'framer-motion';
import { CalendarX, ChevronLeft, ChevronRight, CircleCheck, FolderOpen, ListChecks, MapPin, NotebookText, Paperclip, UserRound, Users } from 'lucide-react';
import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { ActivityRow } from '../components/ActivityRow';
import { ActivitySheet } from '../components/ActivitySheet';
import { FileRow } from '../components/FileRow';
import { KIND } from '../components/LessonRow';
import { Sheet } from '../components/Sheet';
import { Chips, Empty, StreamChip, ErrorState, ListSkeleton, listItem, Screen, Section, Segmented, Skeleton } from '../components/ui';
import { api } from '../lib/api';
import { useApp } from '../lib/app';
import { addDays, isoDate, num, parseISODate, planDateIso, tabKind, tabLabel } from '../lib/format';
import { dayMonth, useI18n } from '../lib/i18n';
import { useQuery } from '../lib/query';
import type { Activity, CalendarEntry, CalendarTab, Course, Lesson } from '../lib/types';

type Tab = 'tasks' | 'attendance' | 'topics';
type Filter = 'all' | 'todo' | 'done' | 'missed';

const hasDate = (tab: CalendarTab, iso: string) => tab.entries.some((e) => planDateIso(e.date) === iso);

/** Вкладка плана с нужным днём: сначала того же вида занятий, что и пара. */
function tabFor(tabs: CalendarTab[], iso: string, kind?: Lesson['kind']) {
  return (
    tabs.find((x) => tabKind(x.key) === kind && hasDate(x, iso)) ??
    tabs.find((x) => hasDate(x, iso)) ??
    tabs.find((x) => tabKind(x.key) === kind) ??
    tabs[0]
  );
}

export function CourseDetail({ course, lesson, onBack }: { course: Course; lesson?: Lesson; onBack: () => void }) {
  const { t } = useI18n();
  // С пары студент приходит к плану: там её тема и файлы.
  const [tab, setTab] = useState<Tab>(lesson ? 'topics' : 'tasks');
  const acts = useQuery(`act:${course.id}`, (f) => api.activities(course.id, f));

  const refresh = () => acts.refresh();

  return (
    <Screen stacked onRefresh={refresh}>
      <div className="back-head">
        <button className="icon-btn" onClick={onBack} aria-label={t('back')}>
          <ChevronLeft size={22} />
        </button>
      </div>
      <h1 className="page-title" style={{ fontSize: 25 }}>{course.subject}</h1>
      {course.teachers.length > 0 && (
        <div className="page-sub" style={{ marginTop: 6 }}>
          {course.teachers.map((x) => (
            <div className="teacher-line" key={x.name + x.stream}>
              {x.stream && <StreamChip stream={x.stream} />}
              <span>{x.name}</span>
            </div>
          ))}
        </div>
      )}

      {lesson && <LessonCard course={course} lesson={lesson} />}

      <Summary data={acts.data} loading={acts.loading} />

      <Segmented<Tab>
        value={tab}
        onChange={setTab}
        style={{ marginTop: 18 }}
        options={[
          { value: 'tasks', label: t('tab_tasks') },
          { value: 'attendance', label: t('tab_attendance') },
          { value: 'topics', label: t('tab_topics') },
        ]}
      />

      <AnimatePresence mode="wait" initial={false}>
        <motion.div
          key={tab}
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -6 }}
          transition={{ duration: 0.18 }}
          style={{ marginTop: 14 }}
        >
          {tab === 'tasks' && <Tasks course={course} q={acts} />}
          {tab === 'attendance' && <Attendance course={course} />}
          {tab === 'topics' && <Topics course={course} lesson={lesson} />}
        </motion.div>
      </AnimatePresence>
    </Screen>
  );
}

/** Занятие, с которого пришёл студент: тема, преподаватель, группа, аудитория и файлы этой темы из плана. */
function LessonCard({ course, lesson }: { course: Course; lesson: Lesson }) {
  const { t, lang } = useI18n();
  const plan = useQuery(`cal:${course.id}`, (f) => api.calendar(course.id, f));
  const profile = useQuery('profile', (f) => api.profile(f));
  const tabs = plan.data ?? [];
  const entry = tabs.length ? tabFor(tabs, lesson.date, lesson.kind).entries.find((e) => planDateIso(e.date) === lesson.date) : undefined;
  const now = new Date();
  const day =
    lesson.date === isoDate(now) ? t('today')
    : lesson.date === isoDate(addDays(now, 1)) ? t('tomorrow')
    : dayMonth(parseISODate(lesson.date), lang);
  const kind = KIND[lesson.kind] ?? KIND.lecture;
  const facts = [
    { icon: NotebookText, label: t('topic'), value: lesson.topic || entry?.topic },
    { icon: UserRound, label: t('teacher'), value: lesson.teacher },
    { icon: Users, label: t('group'), value: lesson.stream || profile.data?.group },
    { icon: MapPin, label: t('lesson_room'), value: lesson.room },
  ].filter((f) => f.value);

  return (
    <div className="card lesson-card" style={{ marginTop: 18 }}>
      <div className="meta">
        <span className="row-title tabular">{day}, {lesson.time}</span>
        <span className={'badge ' + kind.tone} style={{ height: 22 }}>{t(kind.label)}</span>
      </div>
      {facts.map((f) => (
        <div className="lesson-fact" key={f.label}>
          <f.icon size={16} />
          <div className="row-main">
            <div className="row-sub" style={{ marginTop: 0 }}>{f.label}</div>
            <div className="row-title wrap" style={{ fontWeight: 550, fontSize: 14.5 }}>{f.value}</div>
          </div>
        </div>
      ))}
      <div className="section-title" style={{ margin: '16px 0 8px' }}>{t('materials')}</div>
      {plan.loading ? (
        <Skeleton h={44} r={12} />
      ) : entry?.files.length ? (
        <div className="list" style={{ boxShadow: 'none', background: 'var(--surface-2)' }}>
          {entry.files.map((f, i) => (
            <FileRow key={f.url + i} name={f.name} url={f.url} type={f.type} />
          ))}
        </div>
      ) : (
        <div className="row-sub">{t('no_files')}</div>
      )}
    </div>
  );
}

function Summary({ data, loading }: { data?: { earned: string | null; maxScore: string | null; progress: string | null; grade: string | null }; loading: boolean }) {
  const { t } = useI18n();
  const pct = num(data?.progress);
  return (
    <div className="card" style={{ marginTop: 18 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12 }}>
        {[
          { label: t('score'), value: data ? `${data.earned ?? '—'}/${data.maxScore ?? '—'}` : '' },
          { label: t('progress'), value: data?.progress ?? '—' },
          { label: t('grade'), value: data?.grade ?? '—' },
        ].map((x) => (
          <div key={x.label}>
            <div className="stat-label" style={{ marginTop: 0 }}>{x.label}</div>
            {loading ? <Skeleton h={22} w="70%" style={{ marginTop: 6 }} /> : <div className="stat-value tabular" style={{ fontSize: 21, marginTop: 4 }}>{x.value || '—'}</div>}
          </div>
        ))}
      </div>
      <div className="progress" style={{ marginTop: 14 }}>
        <motion.div initial={{ width: 0 }} animate={{ width: `${Math.min(100, Math.max(0, pct ?? 0))}%` }} transition={{ duration: 0.8, ease: [0.2, 0.8, 0.2, 1] }} />
      </div>
    </div>
  );
}

function Tasks({ course, q }: { course: Course; q: ReturnType<typeof useQuery<{ activities: Activity[] }>> }) {
  const { t } = useI18n();
  const [filter, setFilter] = useState<Filter>('all');
  const [open, setOpen] = useState<Activity | null>(null);
  const list = q.data?.activities ?? [];

  const sorted = useMemo(() => {
    // Открытые — по близости дедлайна, остальные — от свежих к старым.
    const rank = (a: Activity) => (a.status === 'open' ? 0 : 1);
    return [...list].sort((a, b) => rank(a) - rank(b) || (rank(a) === 0 ? (a.deadlineTs ?? 0) - (b.deadlineTs ?? 0) : (b.deadlineTs ?? 0) - (a.deadlineTs ?? 0)));
  }, [list]);

  const pick = (f: Filter) =>
    sorted.filter((a) =>
      f === 'all' ? true : f === 'todo' ? a.status === 'open' : f === 'done' ? a.status === 'uploaded' || a.status === 'graded' : a.status === 'missed',
    );
  const shown = pick(filter);

  if (q.loading) return <ListSkeleton rows={4} tall />;
  if (q.error) return <ErrorState error={q.error} onRetry={q.refresh} />;
  if (!list.length) return <div className="card"><Empty icon={ListChecks} title={t('no_tasks')} /></div>;

  return (
    <>
      <Chips<Filter>
        value={filter}
        onChange={setFilter}
        options={[
          { value: 'all', label: t('filter_all'), count: list.length },
          { value: 'todo', label: t('filter_todo'), count: pick('todo').length },
          { value: 'done', label: t('filter_done'), count: pick('done').length },
          { value: 'missed', label: t('filter_missed'), count: pick('missed').length },
        ]}
      />
      <div style={{ marginTop: 12 }}>
        {shown.length ? (
          <div className="list">
            {shown.map((a, i) => (
              <ActivityRow key={a.index} a={a} index={i} onClick={() => setOpen(a)} />
            ))}
          </div>
        ) : (
          <div className="card"><Empty icon={CircleCheck} tone="success" title={t('no_tasks_filter')} /></div>
        )}
      </div>
      <ActivitySheet activity={open} courseId={course.id} course={course.subject} onClose={() => setOpen(null)} />
    </>
  );
}

function Attendance({ course }: { course: Course }) {
  const { t } = useI18n();
  const { semesterId } = useApp();
  const sem = semesterId ?? 0;
  const q = useQuery(`att:${course.id}:${sem}`, (f) => api.attendance(course.id, sem, course.subject, f));

  if (q.loading) return <ListSkeleton rows={3} />;
  if (q.error) return <ErrorState error={q.error} onRetry={q.refresh} />;
  const items = q.data?.items ?? [];
  if (!items.length) {
    return <div className="card"><Empty icon={CircleCheck} tone="success" title={t('all_attended')} sub={t('all_attended_sub')} /></div>;
  }
  return (
    <>
      <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 12 }}>
        <span className="tile danger" style={{ width: 46, height: 46, borderRadius: 14, fontSize: 20, fontWeight: 750 }}>{items.length}</span>
        <div>
          <div className="row-title">{t('missed_total')}</div>
          <div className="row-sub">
            {t('excused')}: {items.filter((x) => x.excused).length} · {t('unexcused')}: {items.filter((x) => !x.excused).length}
          </div>
        </div>
      </div>
      <div className="list">
        {items.map((r, i) => (
          <motion.div key={r.date + i} className="row with-icon" variants={listItem} initial="hidden" animate="show" custom={i} style={{ alignItems: 'flex-start' }}>
            <span className={'tile ' + (r.excused ? 'warning' : 'danger')}>
              <CalendarX size={18} />
            </span>
            <div className="row-main">
              <div className="row-title tabular">{r.date}</div>
              <div className="meta" style={{ marginTop: 5 }}>
                <span className={'badge ' + (r.lecture ? 'accent' : 'violet')} style={{ height: 22 }}>{r.type}</span>
                <span className={'badge ' + (r.excused ? 'warning' : 'danger')} style={{ height: 22 }}>{r.excused ? t('excused') : t('unexcused')}</span>
              </div>
              {r.topic && <div className="row-sub wrap" style={{ marginTop: 6 }}>{r.topic}</div>}
            </div>
          </motion.div>
        ))}
      </div>
    </>
  );
}

function Topics({ course, lesson }: { course: Course; lesson?: Lesson }) {
  const { t, lang } = useI18n();
  const q = useQuery(`cal:${course.id}`, (f) => api.calendar(course.id, f));
  const [key, setKey] = useState<string | null>(null);
  const [entry, setEntry] = useState<CalendarEntry | null>(null);
  const lastEntry = useRef<CalendarEntry | null>(null);
  const todayEl = useRef<HTMLElement | null>(null);
  if (entry) lastEntry.current = entry;
  const shownEntry = lastEntry.current;

  const tabs = q.data ?? [];
  const today = isoDate(new Date());
  const active = tabs.find((x) => x.key === key) ?? (tabs.length ? tabFor(tabs, lesson?.date ?? today, lesson?.kind) : undefined);
  const dates = active?.entries.map((e) => planDateIso(e.date)) ?? [];
  // Сегодня занятия нет — черта «Сегодня» встаёт там, где сегодняшний день был бы в плане.
  const next = dates.findIndex((d) => d != null && d > today);
  const lineAt = dates.includes(today) || !dates.some(Boolean) ? -1 : next >= 0 ? next : dates.length;
  const todayLine = (
    <div className="plan-today-line" ref={(el) => { todayEl.current = el; }}>
      {t('today')}, {dayMonth(new Date(), lang)}
    </div>
  );

  useEffect(() => {
    // С пары студент пришёл к её карточке наверху экрана: план не прокручиваем.
    if (!lesson) todayEl.current?.scrollIntoView({ block: 'center' });
  }, [active?.key, lesson]);

  if (q.loading) return <ListSkeleton rows={5} />;
  if (q.error) return <ErrorState error={q.error} onRetry={q.refresh} />;
  if (!active) return <div className="card"><Empty icon={NotebookText} title={t('no_topics')} /></div>;

  return (
    <>
      {tabs.length > 1 && (
        <Segmented<string>
          value={active.key}
          onChange={setKey}
          style={{ marginBottom: 12 }}
          options={tabs.map((x) => ({ value: x.key, label: tabLabel(x.key, t) }))}
        />
      )}
      <Section first>
        <div className="list">
          {active.entries.map((e, i) => {
            const isToday = dates[i] === today;
            return (
              <Fragment key={active.key + e.number + i}>
                {i === lineAt && todayLine}
                <motion.button
                  className={'row with-icon pressable' + (isToday ? ' plan-today' : '')}
                  ref={isToday ? (el: HTMLButtonElement | null) => { todayEl.current = el; } : undefined}
                  style={{ alignItems: 'flex-start' }}
                  variants={listItem}
                  initial="hidden"
                  animate="show"
                  custom={i}
                  onClick={() => setEntry(e)}
                >
                  <span className="tile neutral tabular" style={{ fontWeight: 700, fontSize: 14 }}>{e.number}</span>
                  <div className="row-main">
                    <div className="row-title wrap">{e.topic}</div>
                    <div className="meta" style={{ marginTop: 5 }}>
                      {isToday && <span className="badge accent" style={{ height: 22 }}>{t('today')}</span>}
                      {e.date && <span className="tabular">{e.date}</span>}
                      {e.files.length > 0 && (
                        <span>
                          <Paperclip size={13} /> {t('files', { n: e.files.length })}
                        </span>
                      )}
                    </div>
                  </div>
                  <ChevronRight size={18} className="chev" style={{ alignSelf: 'center' }} />
                </motion.button>
              </Fragment>
            );
          })}
          {lineAt === dates.length && todayLine}
        </div>
      </Section>

      <Sheet
        open={!!entry}
        onClose={() => setEntry(null)}
        title={shownEntry ? `${shownEntry.number}. ${shownEntry.topic}` : ''}
        subtitle={shownEntry?.date}
      >
        {shownEntry && (
          <>
            <div className="section-title" style={{ margin: '4px 4px 10px' }}>{t('materials')}</div>
            {shownEntry.files.length ? (
              <div className="list">
                {shownEntry.files.map((f, i) => (
                  <FileRow key={f.url + i} name={f.name} url={f.url} type={f.type} />
                ))}
              </div>
            ) : (
              <div className="card"><Empty icon={FolderOpen} title={t('no_files')} /></div>
            )}
          </>
        )}
      </Sheet>
    </>
  );
}
