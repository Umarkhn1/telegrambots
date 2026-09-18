import { AnimatePresence, motion } from 'framer-motion';
import { Award, CalendarClock, ChevronDown, Clock3, DoorOpen, GraduationCap, Hash } from 'lucide-react';
import { useMemo, useState } from 'react';
import { SemesterSheet } from '../components/SemesterSheet';
import { Empty, ErrorState, ListSkeleton, listItem, PageHead, Screen, SemesterChip, Segmented, Skeleton } from '../components/ui';
import { api } from '../lib/api';
import { useApp } from '../lib/app';
import { currentPlanSemester, gpa, gpaComment, num } from '../lib/format';
import { useI18n } from '../lib/i18n';
import { useQuery } from '../lib/query';
import { haptic } from '../lib/tg';
import type { StudyPlanSubject } from '../lib/types';

type View = 'plan' | 'exams';

export function Grades() {
  const { t } = useI18n();
  const [view, setView] = useState<View>('plan');
  const { semesterId } = useApp();
  const sem = semesterId ?? 0;
  const plan = useQuery('plan', (f) => api.studyPlan(f));
  const finals = useQuery(sem ? `finals:${sem}` : null, (f) => api.finals(sem, f));

  return (
    <Screen onRefresh={() => (view === 'plan' ? plan.refresh() : finals.refresh())}>
      <PageHead title={t('grades_title')} />
      <Segmented<View>
        value={view}
        onChange={setView}
        options={[
          { value: 'plan', label: t('tab_plan') },
          { value: 'exams', label: t('tab_exams') },
        ]}
      />
      <AnimatePresence mode="wait" initial={false}>
        <motion.div
          key={view}
          initial={{ opacity: 0, x: view === 'plan' ? -16 : 16 }}
          animate={{ opacity: 1, x: 0 }}
          exit={{ opacity: 0, x: view === 'plan' ? 16 : -16 }}
          transition={{ duration: 0.2 }}
          style={{ marginTop: 16 }}
        >
          {view === 'plan' ? <Plan q={plan} /> : <Exams q={finals} />}
        </motion.div>
      </AnimatePresence>
    </Screen>
  );
}

function gradeClass(g: number | null) {
  return g == null ? 'g0' : g >= 5 ? 'g5' : g === 4 ? 'g4' : g === 3 ? 'g3' : 'g2';
}

function Plan({ q }: { q: ReturnType<typeof useQuery<StudyPlanSubject[]>> }) {
  const { t } = useI18n();
  const [withZero, setWithZero] = useState(false);
  const subjects = q.data ?? [];
  const current = useMemo(() => currentPlanSemester(subjects), [subjects]);
  // null — пользователь ещё ничего не раскрывал: открыт текущий семестр.
  const [open, setOpen] = useState<Set<number> | null>(null);
  const res = gpa(subjects, withZero);

  const bySem = useMemo(() => {
    const m = new Map<number, StudyPlanSubject[]>();
    for (const s of subjects) m.set(s.semester, [...(m.get(s.semester) ?? []), s]);
    return [...m.entries()].sort((a, b) => a[0] - b[0]);
  }, [subjects]);

  if (q.loading) return <><Skeleton h={150} r={18} /><div style={{ marginTop: 14 }}><ListSkeleton rows={5} /></div></>;
  if (q.error) return <ErrorState error={q.error} onRetry={q.refresh} />;
  if (!subjects.length) return <div className="card"><Empty icon={GraduationCap} title={t('no_plan')} /></div>;

  const expandedSet = open ?? new Set([current]);
  const isOpen = (s: number) => expandedSet.has(s);
  const toggle = (s: number) => {
    haptic('selection');
    const next = new Set(expandedSet);
    if (next.has(s)) next.delete(s);
    else next.add(s);
    setOpen(next);
  };

  return (
    <>
      <div className="card">
        <div className="gpa-hero">
          <div>
            <div className="stat-label" style={{ marginTop: 0 }}>GPA</div>
            <motion.div key={String(res.value)} className="gpa-value tabular" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }}>
              {res.value != null ? res.value.toFixed(2) : '—'}
            </motion.div>
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="row-title">{res.value != null ? gpaComment(res.value, t) : t('no_grades')}</div>
            <div className="row-sub">{t('gpa_credits', { n: res.credits })}</div>
          </div>
        </div>
        <div className="progress" style={{ marginTop: 16 }}>
          <motion.div animate={{ width: `${((res.value ?? 0) / 5) * 100}%` }} transition={{ duration: 0.7, ease: [0.2, 0.8, 0.2, 1] }} />
        </div>
        <Segmented<'graded' | 'zero'>
          value={withZero ? 'zero' : 'graded'}
          onChange={(v) => setWithZero(v === 'zero')}
          style={{ marginTop: 16 }}
          options={[
            { value: 'graded', label: t('gpa_mode_graded') },
            { value: 'zero', label: t('gpa_mode_zero') },
          ]}
        />
        {withZero && <div className="row-sub" style={{ marginTop: 10 }}>{t('gpa_hint_zero')}</div>}
      </div>

      <div className="stack" style={{ marginTop: 14 }}>
        {bySem.map(([s, list]) => {
          const semGpa = gpa(list, false).value;
          const credits = list.reduce((a, x) => a + x.credits, 0);
          const expanded = isOpen(s);
          return (
            <div className="list" key={s}>
              <button className="row pressable" onClick={() => toggle(s)} style={{ minHeight: 60 }}>
                <span className={'tile ' + (s === current ? '' : 'neutral')} style={{ fontWeight: 700, fontSize: 14 }}>{s}</span>
                <div className="row-main">
                  <div className="row-title">
                    {t('semester_n', { n: s })}
                    {s === current && <span className="badge accent" style={{ height: 20, marginLeft: 8, fontSize: 11 }}>{t('current')}</span>}
                  </div>
                  <div className="row-sub tabular">
                    {credits} {t('credits_short')} {semGpa != null && `· GPA ${semGpa.toFixed(2)}`}
                  </div>
                </div>
                <motion.span animate={{ rotate: expanded ? 180 : 0 }} className="chev" style={{ display: 'grid' }}>
                  <ChevronDown size={19} />
                </motion.span>
              </button>
              <AnimatePresence initial={false}>
                {expanded && (
                  <motion.div
                    initial={{ height: 0, opacity: 0 }}
                    animate={{ height: 'auto', opacity: 1 }}
                    exit={{ height: 0, opacity: 0 }}
                    transition={{ duration: 0.25, ease: [0.2, 0.8, 0.2, 1] }}
                    style={{ overflow: 'hidden', borderTop: '1px solid var(--line)' }}
                  >
                    {list.map((x, i) => (
                      <div className="row" key={x.name + i} style={{ minHeight: 50 }}>
                        <div className="row-main">
                          <div className="row-title" style={{ fontWeight: 500, fontSize: 14.5 }}>{x.name}</div>
                          <div className="row-sub tabular">{x.credits} {t('credits_short')}</div>
                        </div>
                        <span className={'grade tabular ' + gradeClass(x.grade)}>{x.grade ?? '—'}</span>
                      </div>
                    ))}
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          );
        })}
      </div>
    </>
  );
}

function examTone(g: string) {
  const v = num(g);
  if (v == null) return 'neutral';
  if (v >= 86) return 'success';
  if (v >= 71) return 'accent';
  if (v >= 56) return 'warning';
  return 'danger';
}

function Exams({ q }: { q: ReturnType<typeof useQuery<import('../lib/types').FinalExam[]>> }) {
  const { t } = useI18n();
  const [semOpen, setSemOpen] = useState(false);
  const list = q.data ?? [];

  return (
    <>
      <div style={{ marginBottom: 14 }}>
        <SemesterChip onClick={() => setSemOpen(true)} />
      </div>
      {q.loading ? (
        <ListSkeleton rows={4} tall />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={q.refresh} />
      ) : !list.length ? (
        <div className="card"><Empty icon={Award} title={t('no_exams')} sub={t('no_exams_sub')} /></div>
      ) : (
        <div className="stack">
          {list.map((e, i) => {
            const tone = examTone(e.grade);
            return (
              <motion.div className="card" key={e.subject + i} variants={listItem} initial="hidden" animate="show" custom={i}>
                <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                  <div className="row-main">
                    <div className="row-title">{e.subject}</div>
                    <div className="meta" style={{ marginTop: 8 }}>
                      {e.date && <span className="tabular"><CalendarClock size={13} /> {e.date}</span>}
                      {e.from && <span className="tabular"><Clock3 size={13} /> {e.from}</span>}
                      {e.room && <span><DoorOpen size={13} /> {e.room}</span>}
                      {e.stream && <span><Hash size={13} /> {e.stream}</span>}
                    </div>
                  </div>
                  <div style={{ textAlign: 'center' }}>
                    <div className={'tile ' + tone} style={{ width: 52, height: 52, borderRadius: 16, fontSize: 18, fontWeight: 750 }}>
                      {num(e.grade) ?? '—'}
                    </div>
                    <div className="row-sub" style={{ fontSize: 11.5 }}>{t('points')}</div>
                  </div>
                </div>
              </motion.div>
            );
          })}
        </div>
      )}
      <SemesterSheet open={semOpen} onClose={() => setSemOpen(false)} />
    </>
  );
}
