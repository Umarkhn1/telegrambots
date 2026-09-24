import { motion } from 'framer-motion';
import { BookOpen, CalendarDays, ChevronLeft, ChevronRight, GraduationCap, TriangleAlert, UsersRound } from 'lucide-react';
import { useMemo, useState, type ReactNode } from 'react';
import { Sheet } from '../../components/Sheet';
import { Empty, ErrorState, ListSkeleton, listItem, Screen, Section, Segmented, StreamChip } from '../../components/ui';
import { api } from '../../lib/api';
import { useApp } from '../../lib/app';
import { gpa, roman } from '../../lib/format';
import { useI18n, WEEKDAYS } from '../../lib/i18n';
import { useQuery } from '../../lib/query';
import { useTT } from '../../lib/ti18n';
import { haptic } from '../../lib/tg';
import type { TGroup } from '../../lib/types';

function Stacked({ title, sub, onBack, onRefresh, children }: { title: ReactNode; sub?: ReactNode; onBack: () => void; onRefresh?: () => Promise<unknown>; children: ReactNode }) {
  const { t } = useI18n();
  return (
    <Screen stacked onRefresh={onRefresh}>
      <div className="back-head">
        <button className="icon-btn" onClick={onBack} aria-label={t('back')}>
          <ChevronLeft size={22} />
        </button>
      </div>
      <h1 className="page-title" style={{ fontSize: 25 }}>{title}</h1>
      {sub && <div className="page-sub" style={{ marginTop: 6 }}>{sub}</div>}
      <div style={{ marginTop: 18 }}>{children}</div>
    </Screen>
  );
}

const nbTone = (n: number) => (n >= 5 ? 'danger' : n >= 3 ? 'warning' : n > 0 ? 'accent' : 'success');

export function TutorGroups({ onBack }: { onBack: () => void }) {
  const { push } = useApp();
  const tt = useTT();
  const q = useQuery('tgroups', (f) => api.t.groups(f));
  return (
    <Stacked title={tt('my_groups')} sub={tt('tutor')} onBack={onBack} onRefresh={q.refresh}>
      {q.loading ? (
        <ListSkeleton rows={3} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={q.refresh} />
      ) : !q.data?.length ? (
        <div className="card"><Empty icon={UsersRound} title={tt('no_groups')} /></div>
      ) : (
        <div className="list">
          {q.data.map((g, i) => (
            <motion.button key={g.id} className="row with-icon pressable" variants={listItem} initial="hidden" animate="show" custom={i}
              onClick={() => { haptic('light'); push({ name: 'tgroup', group: g }); }}>
              <span className="tile violet"><UsersRound size={18} /></span>
              <div className="row-main">
                <div className="row-title">{g.name}</div>
                <div className="row-sub">{g.speciality}</div>
              </div>
              <ChevronRight size={18} className="chev" />
            </motion.button>
          ))}
        </div>
      )}
    </Stacked>
  );
}

export function TutorGroup({ group, onBack }: { group: TGroup; onBack: () => void }) {
  const { push } = useApp();
  const tt = useTT();
  const q = useQuery(`tgroup:${group.id}`, (f) => api.t.group(group.id, f));
  return (
    <Stacked title={group.name} sub={group.speciality} onBack={onBack} onRefresh={q.refresh}>
      {q.loading ? (
        <ListSkeleton rows={8} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={q.refresh} />
      ) : !q.data?.length ? (
        <div className="card"><Empty icon={UsersRound} title="—" /></div>
      ) : (
        <div className="list">
          {q.data.map((s, i) => (
            <motion.button key={s.id} className="row with-icon pressable" variants={listItem} initial="hidden" animate="show" custom={i}
              onClick={() => { haptic('light'); push({ name: 'tstudent', id: s.id, title: s.fio }); }}>
              <span className={'tile tabular ' + nbTone(s.attendance)} style={{ fontWeight: 700, fontSize: 14 }}>{s.attendance}</span>
              <div className="row-main">
                <div className="row-title" style={{ fontSize: 15 }}>{s.fio}</div>
                <div className="row-sub">{tt('nb')}: {s.attendance}</div>
              </div>
              <ChevronRight size={18} className="chev" />
            </motion.button>
          ))}
        </div>
      )}
    </Stacked>
  );
}

type STab = 'courses' | 'plan' | 'schedule';

export function TutorStudentScreen({ id, name, onBack }: { id: number; name: string; onBack: () => void }) {
  const tt = useTT();
  const [tab, setTab] = useState<STab>('courses');
  const info = useQuery(`tstudent:${id}`, () => api.t.student(id), 10 * 60_000);
  const [sem, setSem] = useState<string | null>(null);
  const [semOpen, setSemOpen] = useState(false);
  const semester = sem ?? info.data?.currentSemester ?? null;
  const semName = info.data?.semesters.find((s) => s.id === semester)?.text;

  return (
    <Stacked title={name} sub={info.data?.title} onBack={onBack} onRefresh={info.refresh}>
      <Segmented<STab>
        value={tab}
        onChange={setTab}
        options={[
          { value: 'courses', label: tt('subjects_tab') },
          { value: 'plan', label: tt('plan_tab') },
          { value: 'schedule', label: tt('schedule_tab') },
        ]}
      />
      {tab !== 'plan' && info.data && info.data.semesters.length > 0 && (
        <button className="semester-chip" style={{ marginTop: 12 }} onClick={() => setSemOpen(true)}>
          <span className="ellipsis">{semName}</span>
          <ChevronRight size={15} strokeWidth={2.4} style={{ transform: 'rotate(90deg)' }} />
        </button>
      )}
      <div style={{ marginTop: 14 }}>
        {info.loading ? (
          <ListSkeleton rows={4} />
        ) : info.error ? (
          <ErrorState error={info.error} onRetry={info.refresh} />
        ) : !info.data || !semester ? null : tab === 'courses' ? (
          <StudentCourses id={id} user={info.data.userId} sem={semester} />
        ) : tab === 'plan' ? (
          <StudentPlan id={id} />
        ) : (
          <StudentSchedule id={id} user={info.data.userId} sem={semester} />
        )}
      </div>
      <Sheet open={semOpen} onClose={() => setSemOpen(false)} title={tt('semester')}>
        <div className="list">
          {(info.data?.semesters ?? []).map((s) => (
            <button key={s.id} className="row pressable" onClick={() => { haptic('selection'); setSem(s.id); setSemOpen(false); }}>
              <div className="row-main"><div className="row-title" style={{ fontWeight: s.id === semester ? 700 : 500 }}>{s.text}</div></div>
            </button>
          ))}
        </div>
      </Sheet>
    </Stacked>
  );
}

function StudentCourses({ id, user, sem }: { id: number; user: number | null; sem: string }) {
  const tt = useTT();
  const { t } = useI18n();
  const q = useQuery(user ? `tsc:${user}:${sem}` : null, (f) => api.t.studentCourses(id, user!, sem, f));
  if (!user) return <div className="card"><Empty icon={BookOpen} title="—" /></div>;
  if (q.loading) return <ListSkeleton rows={5} tall />;
  if (q.error) return <ErrorState error={q.error} onRetry={q.refresh} />;
  if (!q.data?.length) return <div className="card"><Empty icon={BookOpen} title={t('no_courses')} /></div>;
  return (
    <div className="list">
      {q.data.map((c, i) => (
        <motion.div key={c.id} className="row with-icon" style={{ alignItems: 'flex-start' }} variants={listItem} initial="hidden" animate="show" custom={i}>
          <span className={'tile tabular ' + nbTone(c.attendance)} style={{ fontWeight: 700, fontSize: 14 }}>{c.attendance}</span>
          <div className="row-main">
            <div className="row-title">{c.subject}</div>
            {c.teachers.map((x) => (
              <div className="teacher-line" key={x[0] + x[1]} style={{ marginTop: 5 }}>
                {x[0] && <StreamChip stream={x[0]} />}
                <span className="row-sub" style={{ marginTop: 0 }}>{x[1]}</span>
              </div>
            ))}
            <div className="meta" style={{ marginTop: 6 }}>
              <span className={'badge ' + nbTone(c.attendance)} style={{ height: 22 }}>{tt('nb')}: {c.attendance}</span>
              {c.failed && <span className="badge warning" style={{ height: 22 }}><TriangleAlert size={12} /> {t('failed')}</span>}
            </div>
          </div>
        </motion.div>
      ))}
    </div>
  );
}

function StudentPlan({ id }: { id: number }) {
  const { t } = useI18n();
  const tt = useTT();
  const q = useQuery(`tsp:${id}`, (f) => api.t.studentPlan(id, f));
  const bySem = useMemo(() => {
    const m = new Map<number, NonNullable<typeof q.data>>();
    for (const s of q.data ?? []) m.set(s.semester, [...(m.get(s.semester) ?? []), s]);
    return [...m.entries()].sort((a, b) => a[0] - b[0]);
  }, [q.data]);
  if (q.loading) return <ListSkeleton rows={6} />;
  if (q.error) return <ErrorState error={q.error} onRetry={q.refresh} />;
  if (!q.data?.length) return <div className="card"><Empty icon={GraduationCap} title="—" /></div>;
  const g = gpa(q.data, false);
  return (
    <>
      {g.value != null && (
        <div className="card" style={{ marginBottom: 12 }}>
          <div className="stat-label" style={{ marginTop: 0 }}>{t('stat_gpa')}</div>
          <div className="stat-value tabular" style={{ fontSize: 28 }}>{g.value.toFixed(2)}</div>
        </div>
      )}
      {bySem.map(([n, list], i) => (
        <Section key={n} title={`${roman(n)} · ${tt('semester')}`} first={i === 0}>
          <div className="list">
            {list.map((s) => (
              <div className="row" key={s.name}>
                <div className="row-main">
                  <div className="row-title" style={{ fontSize: 14.5 }}>{s.name}</div>
                  <div className="row-sub">{s.credits} {t('credits_short')}</div>
                </div>
                <span className={'badge ' + (s.grade == null ? 'neutral' : s.grade >= 4 ? 'success' : s.grade === 3 ? 'warning' : 'danger')}>{s.grade ?? '—'}</span>
              </div>
            ))}
          </div>
        </Section>
      ))}
    </>
  );
}

function StudentSchedule({ id, user, sem }: { id: number; user: number | null; sem: string }) {
  const { lang, t } = useI18n();
  const q = useQuery(`tss:${id}:${sem}`, () => api.t.studentSchedule(id, user, sem), 10 * 60_000);
  if (q.loading) return <ListSkeleton rows={5} />;
  if (q.error) return <ErrorState error={q.error} onRetry={q.refresh} />;
  const events = q.data?.events ?? [];
  if (!events.length) return <div className="card"><Empty icon={CalendarDays} title={t('no_classes_today')} /></div>;
  const meta = q.data!.meta;
  const byDay = new Map<number, typeof events>();
  for (const e of events) {
    const d = new Date(e.start.replace(' ', 'T'));
    if (Number.isNaN(d.getTime())) continue;
    const k = (d.getDay() + 6) % 7;
    byDay.set(k, [...(byDay.get(k) ?? []), e]);
  }
  return (
    <>
      {[...byDay.entries()].sort((a, b) => a[0] - b[0]).map(([k, list], i) => (
        <Section key={k} title={WEEKDAYS[lang][k]} first={i === 0}>
          <div className="list">
            {list.sort((a, b) => a.start.localeCompare(b.start)).map((e, j) => {
              const [room, ...rest] = e.title.split('\n');
              const week = e.type === 2 ? meta.oddWeek : e.type === 3 ? meta.evenWeek : null;
              return (
                <div className="row" key={j}>
                  <div className="lesson-time tabular" style={{ minWidth: 52 }}>{e.start.slice(11, 16)}</div>
                  <div className="row-main">
                    <div className="row-title" style={{ fontSize: 14.5 }}>{rest.join(' ').replace(/^\/|\/$/g, '') || room}</div>
                    <div className="meta" style={{ marginTop: 4 }}>
                      {rest.length > 0 && <span>{room}</span>}
                      {week && <span className="badge violet" style={{ height: 22 }}>{week}</span>}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </Section>
      ))}
    </>
  );
}
