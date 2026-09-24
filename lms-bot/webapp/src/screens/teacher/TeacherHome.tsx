import { motion } from 'framer-motion';
import {
  BookOpen,
  CalendarCheck,
  ChevronRight,
  ClipboardCheck,
  FileWarning,
  FolderOpen,
  Hourglass,
  Trophy,
  UsersRound,
  type LucideIcon,
} from 'lucide-react';
import { useMemo, useState } from 'react';
import { LessonRow, PAIR_MS } from '../../components/LessonRow';
import { SemesterSheet } from '../../components/SemesterSheet';
import { Empty, ErrorState, ListSkeleton, listItem, PageHead, Screen, Section, SemesterChip, Skeleton, StreamChip, Tile } from '../../components/ui';
import { api } from '../../lib/api';
import { useApp } from '../../lib/app';
import { addDays, initials, isoDate } from '../../lib/format';
import { dayMonth, useI18n, WEEKDAYS } from '../../lib/i18n';
import { useQuery } from '../../lib/query';
import { useTT } from '../../lib/ti18n';
import { haptic } from '../../lib/tg';
import type { GradingItem, TCourse } from '../../lib/types';

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

/** Сроки, по которым сейчас есть работа: приём ещё идёт или идёт проверка. */
export function splitGrading(items: GradingItem[], now = Date.now()) {
  const awaiting = items.filter((g) => g.studentTs <= now && (g.teacherTs == null || g.teacherTs > now));
  const accepting = items.filter((g) => g.studentTs > now);
  return { awaiting, accepting };
}

export function TeacherHome() {
  const { me, semesterId, setTab, push } = useApp();
  const { t, lang } = useI18n();
  const tt = useTT();
  const sem = semesterId ?? 0;

  const schedule = useQuery(sem ? `sched:${sem}` : null, (f) => api.schedule(sem, f));
  const courses = useQuery(sem ? `tcourses:${sem}` : null, (f) => api.t.courses(sem, f));
  const grading = useQuery('tgrading', (f) => api.t.grading(f));
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

  const { awaiting } = splitGrading(grading.data ?? []);
  const toGrade = awaiting.reduce((s, g) => s + g.pending, 0);
  const students = courses.data?.reduce((s, c) => s + c.students, 0);
  const name = me.user.firstName || '';

  const openItem = (g: GradingItem) => {
    const c = courses.data?.find((x) => x.id === g.courseId);
    haptic('light');
    push({ name: 'tcourse', course: c ?? fallbackCourse(g), column: g.column >= 0 ? g.column : undefined });
  };

  return (
    <Screen onRefresh={() => Promise.all([schedule.refresh(), courses.refresh(), grading.refresh()])}>
      <PageHead
        title={
          <>
            {t(greetingKey(now.getHours()))}
            {name && <span style={{ color: 'var(--text-2)', fontWeight: 600 }}>,<br />{name}</span>}
          </>
        }
        sub={`${WEEKDAYS[lang][(now.getDay() + 6) % 7]}, ${dayMonth(now, lang)} · ${tt('role_teacher')}`}
        right={
          <button className="avatar" onClick={() => setTab('profile')} aria-label="profile" style={{ width: 48, height: 48 }}>
            {photo.data?.dataUrl ? <img src={photo.data.dataUrl} alt="" /> : me.user.photoUrl ? <img src={me.user.photoUrl} alt="" /> : initials(name)}
          </button>
        }
      />

      <div className="stat-grid">
        <Stat icon={BookOpen} tone="" value={courses.data ? String(courses.data.length) : '—'} label={tt('stat_streams')} loading={courses.loading} onClick={() => setTab('courses')} />
        <Stat icon={UsersRound} tone="violet" value={students != null ? String(students) : '—'} label={tt('stat_students')} loading={courses.loading} onClick={() => setTab('courses')} />
        <Stat icon={Hourglass} tone={toGrade ? 'warning' : 'success'} value={grading.data ? String(toGrade) : '—'} label={tt('stat_pending')} loading={grading.loading} onClick={() => setTab('grading')} />
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
              <LessonRow key={l.ts + l.subject + l.stream} l={l} index={i} />
            ))}
          </div>
        ) : (
          <div className="card">
            <Empty icon={CalendarCheck} tone="success" title={day.over ? t('classes_over') : t('no_classes_today')} sub={t('no_classes_sub')} />
          </div>
        )}
      </Section>

      <Section
        title={tt('awaiting')}
        action={
          <button className="link-btn" onClick={() => setTab('grading')}>
            {tt('nav_grading')} <ChevronRight size={16} />
          </button>
        }
      >
        {grading.loading ? (
          <ListSkeleton rows={2} tall />
        ) : grading.error ? (
          <ErrorState error={grading.error} onRetry={grading.refresh} />
        ) : awaiting.length ? (
          <div className="list">
            {awaiting.slice(0, 4).map((g, i) => <GradingRow key={g.courseId + g.activity} g={g} index={i} onClick={() => openItem(g)} />)}
          </div>
        ) : (
          <div className="card">
            <Empty icon={ClipboardCheck} tone="success" title={tt('no_grading')} sub={tt('no_grading_sub')} />
          </div>
        )}
      </Section>

      <Section title={tt('sections')}>
        <div className="list">
          <NavRow icon={FolderOpen} tone="" title={tt('materials')} onClick={() => push({ name: 'materials' })} />
          <NavRow icon={FileWarning} tone="danger" title={tt('appeals')} onClick={() => push({ name: 'appeals' })} appeals />
          <NavRow icon={Trophy} tone="warning" title={tt('finals')} onClick={() => push({ name: 'finals' })} />
          {me.tutor && <NavRow icon={UsersRound} tone="violet" title={tt('tutor')} sub={tt('my_groups')} onClick={() => push({ name: 'tutor' })} />}
        </div>
      </Section>
    </Screen>
  );
}

/** Название раздела «Исправление НБ» берём со страницы LMS, пока она не загружена — подпись приложения. */
function NavRow({ icon, tone, title, sub, onClick, appeals }: { icon: LucideIcon; tone: string; title: string; sub?: string; onClick: () => void; appeals?: boolean }) {
  const q = useQuery(appeals ? 'tappeals' : null, (f) => api.t.appeals(f));
  const label = appeals ? q.data?.page.title || title : title;
  return (
    <button className="row with-icon pressable" onClick={() => { haptic('light'); onClick(); }}>
      <Tile icon={icon} tone={tone} size={18} />
      <div className="row-main">
        <div className="row-title">{label}</div>
        {sub && <div className="row-sub">{sub}</div>}
      </div>
      <ChevronRight size={18} className="chev" />
    </button>
  );
}

export function fallbackCourse(g: GradingItem): TCourse {
  return { id: g.courseId, subject: g.subject, type: '', students: 0, rejected: 0, stream: g.stream };
}

export function GradingRow({ g, index, onClick }: { g: GradingItem; index: number; onClick: () => void }) {
  const tt = useTT();
  const accepting = g.studentTs > Date.now();
  return (
    <motion.button className="row with-icon pressable" style={{ alignItems: 'flex-start' }} variants={listItem} initial="hidden" animate="show" custom={index} onClick={onClick}>
      <span className={'tile ' + (accepting ? 'accent' : g.pending ? 'warning' : 'success')}>
        {accepting ? <BookOpen size={18} /> : <Hourglass size={18} />}
      </span>
      <div className="row-main">
        <div className="row-title">{g.activity} <span style={{ color: 'var(--text-2)', fontWeight: 500 }}>· {g.subject}</span></div>
        <div className="meta" style={{ marginTop: 6 }}>
          {g.stream && <StreamChip stream={g.stream} />}
          {accepting ? (
            <span className="tabular">{tt('student_deadline')}: {g.studentDeadline}</span>
          ) : g.teacherDeadline ? (
            <span className="tabular">{tt('teacher_deadline')}: {g.teacherDeadline}</span>
          ) : null}
        </div>
        <div className="meta" style={{ marginTop: 6 }}>
          <span className="badge accent" style={{ height: 22 }}>{tt('submitted')}: {g.submitted}</span>
          {!accepting && <span className={'badge ' + (g.pending ? 'warning' : 'success')} style={{ height: 22 }}>{tt('pending')}: {g.pending}</span>}
          {g.graded > 0 && <span className="badge success" style={{ height: 22 }}>{tt('graded')}: {g.graded}</span>}
        </div>
      </div>
      <ChevronRight size={18} className="chev" style={{ alignSelf: 'center' }} />
    </motion.button>
  );
}

/* ─── Мои потоки ─── */

const TONES = ['', 'violet', 'success', 'warning'];

export function TeacherCourses() {
  const { semesterId, push } = useApp();
  const tt = useTT();
  const [semOpen, setSemOpen] = useState(false);
  const sem = semesterId ?? 0;
  const q = useQuery(sem ? `tcourses:${sem}` : null, (f) => api.t.courses(sem, f));

  const groups = useMemo(() => {
    const m = new Map<string, TCourse[]>();
    for (const c of q.data ?? []) m.set(c.subject, [...(m.get(c.subject) ?? []), c]);
    return [...m.entries()];
  }, [q.data]);

  return (
    <Screen onRefresh={q.refresh}>
      <PageHead title={tt('my_streams')} sub={q.data ? tt('streams_n', { n: q.data.length }) : undefined} />
      <div style={{ marginBottom: 14 }}>
        <SemesterChip onClick={() => setSemOpen(true)} />
      </div>
      {q.loading ? (
        <ListSkeleton rows={6} tall />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={q.refresh} />
      ) : !q.data?.length ? (
        <div className="card"><Empty icon={BookOpen} title={tt('no_streams')} /></div>
      ) : (
        groups.map(([subject, list], gi) => (
          <Section key={subject} title={subject} first={gi === 0}>
            <div className="list">
              {list.map((c, i) => (
                <motion.button
                  key={c.id}
                  className="row with-icon pressable"
                  variants={listItem}
                  initial="hidden"
                  animate="show"
                  custom={i}
                  onClick={() => {
                    haptic('light');
                    push({ name: 'tcourse', course: c });
                  }}
                >
                  <span className={'tile ' + TONES[gi % TONES.length]}><BookOpen size={18} /></span>
                  <div className="row-main">
                    <div className="row-title">{c.stream ?? c.type}</div>
                    <div className="meta" style={{ marginTop: 5 }}>
                      <span>{c.type}</span>
                      <span><UsersRound size={13} /> {c.students}</span>
                      {c.rejected > 0 && <span className="badge warning" style={{ height: 22 }}>✉ {c.rejected}</span>}
                    </div>
                  </div>
                  <ChevronRight size={18} className="chev" />
                </motion.button>
              ))}
            </div>
          </Section>
        ))
      )}
      <SemesterSheet open={semOpen} onClose={() => setSemOpen(false)} />
    </Screen>
  );
}

/* ─── Проверка работ ─── */

export function Grading() {
  const { push, semesterId } = useApp();
  const tt = useTT();
  const q = useQuery('tgrading', (f) => api.t.grading(f));
  const sem = semesterId ?? 0;
  const courses = useQuery(sem ? `tcourses:${sem}` : null, (f) => api.t.courses(sem, f));
  const { awaiting, accepting } = splitGrading(q.data ?? []);

  const open = (g: GradingItem) => {
    haptic('light');
    const c = courses.data?.find((x) => x.id === g.courseId);
    push({ name: 'tcourse', course: c ?? fallbackCourse(g), column: g.column >= 0 ? g.column : undefined });
  };

  return (
    <Screen onRefresh={q.refresh}>
      <PageHead title={tt('nav_grading')} />
      {q.loading ? (
        <ListSkeleton rows={4} tall />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={q.refresh} />
      ) : !awaiting.length && !accepting.length ? (
        <div className="card"><Empty icon={ClipboardCheck} tone="success" title={tt('no_grading')} sub={tt('no_grading_sub')} /></div>
      ) : (
        <>
          {awaiting.length > 0 && (
            <Section title={tt('awaiting')} first>
              <div className="list">{awaiting.map((g, i) => <GradingRow key={g.courseId + g.activity} g={g} index={i} onClick={() => open(g)} />)}</div>
            </Section>
          )}
          {accepting.length > 0 && (
            <Section title={tt('accepting')} first={!awaiting.length}>
              <div className="list">{accepting.map((g, i) => <GradingRow key={g.courseId + g.activity} g={g} index={i} onClick={() => open(g)} />)}</div>
            </Section>
          )}
        </>
      )}
    </Screen>
  );
}
